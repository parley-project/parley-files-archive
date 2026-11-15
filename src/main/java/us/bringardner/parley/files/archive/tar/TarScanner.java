/**
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 * ~version~
 */
package us.bringardner.parley.files.archive.tar;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import us.bringardner.parley.files.archive.format.ArchiveEntry;

/**
 * Reads a tar archive's headers, one entry after another, from any {@link Input}: positioned
 * reads of a seekable source (skipping each entry's data) or a stream read front to back.
 * <p>
 * It reads every dialect: v7, POSIX ustar (with its name prefix), GNU (long names and link
 * names, 'L' and 'K'; base-256 numbers; its own atime and ctime) and pax (extended headers 'x'
 * and global headers 'g', with path, linkpath, size, mtime, atime, ctime, uid, gid, uname and
 * gname). A header's checksum must be right (unsigned or, as some old tars wrote it, signed).
 * The archive ends at two zero blocks, or where the bytes end after a header's data.
 */
final class TarScanner {

	static final int BLOCK = 512;
	/** the most an extended header ('x', 'g', 'L', 'K') may hold, so a damaged one can't eat the heap */
	static final int MAX_EXTENDED = 16 * 1024 * 1024;

	/** Where the archive's (uncompressed) bytes come from: reads only ever move forward. */
	interface Input {
		/**
		 * Reads up to len bytes at position (never less than the position of the read before).
		 *
		 * @return how many were read: len, or fewer at the end
		 */
		int read(long position, byte[] b, int off, int len) throws IOException;
	}

	private final Input in;
	/** where the next header is */
	private long pos;
	private boolean ended;
	private final Map<String, String> global = new HashMap<>();

	TarScanner(Input in) {
		this.in = in;
	}

	/** @return where the archive's end was found (after the last entry's data) */
	long position() {
		return pos;
	}

	/**
	 * @return the next entry (its header and data offsets set), or null at the end
	 * @throws IOException if a header is damaged, or the bytes end inside one
	 */
	TarArchiveEntry next() throws IOException {
		if( ended ) {
			return null;
		}
		Map<String, String> pax = null;
		String longName = null;
		String longLink = null;
		byte[] h = new byte[BLOCK];
		int zeros = 0;
		while( true ) {
			int got = readFully(pos, h, BLOCK);
			if( got == 0 ) {
				ended = true;
				return null;
			}
			if( got < BLOCK ) {
				throw new EOFException("The tar archive ends inside a header, at offset " + pos);
			}
			if( isZero(h) ) {
				pos += BLOCK;
				if( ++zeros >= 2 ) {
					ended = true;
					return null;
				}
				continue;
			}
			zeros = 0;
			if( !checksumOk(h) ) {
				throw new IOException("Not a tar header at offset " + pos + " (its checksum is wrong)");
			}
			long headerAt = pos;
			char type = h[156] == 0 ? TarArchiveEntry.REGULAR : (char) (h[156] & 0xFF);
			long size = number(h, 124, 12);
			long dataAt = pos + BLOCK;
			if( type == 'x' || type == 'g' || type == 'L' || type == 'K' ) {
				byte[] data = readData(dataAt, size, type);
				pos = dataAt + padded(size);
				if( type == 'x' ) {
					pax = parsePax(data, pax == null ? new HashMap<>() : pax);
				} else if( type == 'g' ) {
					parsePax(data, global);
				} else if( type == 'L' ) {
					longName = cstring(data, 0, data.length);
				} else {
					longLink = cstring(data, 0, data.length);
				}
				continue;
			}
			if( type == 'V' ) {
				// a volume label: not a file
				pos = dataAt + padded(size);
				continue;
			}
			if( type == 'M' ) {
				throw new IOException("The tar archive continues from another volume, which isn't supported");
			}
			String magic = ascii(h, 257, 6);
			boolean posix = magic.equals("ustar");
			boolean gnu = magic.equals("ustar ") && h[263] == ' ';
			String name = cstring(h, 0, 100);
			if( posix ) {
				String prefix = cstring(h, 345, 155);
				if( !prefix.isEmpty() ) {
					name = prefix + "/" + name;
				}
			}
			if( longName != null ) {
				name = longName;
			}
			String link = longLink != null ? longLink : cstring(h, 157, 100);
			long mode = number(h, 100, 8);
			long uid = number(h, 108, 8);
			long gid = number(h, 116, 8);
			long mtime = number(h, 136, 12) * 1000;
			long atime = ArchiveEntry.UNKNOWN;
			long ctime = ArchiveEntry.UNKNOWN;
			if( gnu ) {
				long a = number(h, 345, 12);
				long c = number(h, 357, 12);
				atime = a > 0 ? a * 1000 : ArchiveEntry.UNKNOWN;
				ctime = c > 0 ? c * 1000 : ArchiveEntry.UNKNOWN;
			}
			String uname = posix || gnu ? cstring(h, 265, 32) : "";
			String gname = posix || gnu ? cstring(h, 297, 32) : "";
			boolean sparse = type == TarArchiveEntry.GNU_SPARSE;
			Map<String, String> all = new HashMap<>(global);
			if( pax != null ) {
				all.putAll(pax);
			}
			for(Map.Entry<String, String> e : all.entrySet()) {
				String v = e.getValue();
				switch (e.getKey()) {
				case "path": name = v; break;
				case "linkpath": link = v; break;
				case "size": size = paxLong(v, size); break;
				case "mtime": mtime = paxTime(v, mtime); break;
				case "atime": atime = paxTime(v, atime); break;
				case "ctime": ctime = paxTime(v, ctime); break;
				case "uid": uid = paxLong(v, uid); break;
				case "gid": gid = paxLong(v, gid); break;
				case "uname": uname = v; break;
				case "gname": gname = v; break;
				default:
					if( e.getKey().startsWith("GNU.sparse.") ) {
						sparse = true;
					}
					break;
				}
			}
			pos = dataAt + padded(size);
			boolean dir = type == TarArchiveEntry.DIRECTORY
					|| ((type == TarArchiveEntry.REGULAR || type == TarArchiveEntry.CONTIGUOUS) && name.endsWith("/") && size == 0);
			String path = ArchiveEntry.normalize(name);
			if( path.isEmpty() ) {
				// "./" or "/": the top folder, which is always there
				pax = null;
				longName = longLink = null;
				continue;
			}
			TarArchiveEntry.Builder b = TarArchiveEntry.builder(path).type(dir ? TarArchiveEntry.DIRECTORY : type)
					.lastModified(mtime).unixMode(mode).ids(uid, gid)
					.names(uname.isEmpty() ? null : uname, gname.isEmpty() ? null : gname);
			b.rawName = name;
			b.size = dir ? 0 : size;
			b.linkName = link.isEmpty() ? null : link;
			b.lastAccessTime = atime;
			b.creationTime = ctime;
			b.sparse = sparse;
			b.headerOffset = headerAt;
			b.dataOffset = dataAt;
			return b.build();
		}
	}

	private int readFully(long at, byte[] b, int len) throws IOException {
		int done = 0;
		while( done < len ) {
			int cnt = in.read(at + done, b, done, len - done);
			if( cnt <= 0 ) {
				break;
			}
			done += cnt;
		}
		return done;
	}

	private byte[] readData(long at, long size, char type) throws IOException {
		if( size > MAX_EXTENDED ) {
			throw new IOException("A tar extended header ('" + type + "') of " + size + " bytes is too large");
		}
		byte[] data = new byte[(int) size];
		if( readFully(at, data, data.length) < data.length ) {
			throw new EOFException("The tar archive ends inside an extended header");
		}
		return data;
	}

	static long padded(long size) {
		return (size + BLOCK - 1) / BLOCK * BLOCK;
	}

	private static boolean isZero(byte[] h) {
		for(byte x : h) {
			if( x != 0 ) {
				return false;
			}
		}
		return true;
	}

	/** the checksum counts the checksum field as spaces; old tars summed signed bytes */
	static boolean checksumOk(byte[] h) {
		long stored;
		try {
			stored = number(h, 148, 8);
		} catch (IOException e) {
			return false;
		}
		long unsigned = 0;
		long signed = 0;
		for(int i = 0; i < BLOCK; i++) {
			int v = i >= 148 && i < 156 ? ' ' : h[i];
			unsigned += v & 0xFF;
			signed += i >= 148 && i < 156 ? ' ' : h[i];
		}
		return stored == unsigned || stored == signed;
	}

	/** an octal number (spaces and NULs around it), or GNU's base 256 (high bit set) */
	static long number(byte[] h, int off, int len) throws IOException {
		if( (h[off] & 0x80) != 0 ) {
			// two's complement, big-endian, in the bits after the marker bit
			long v = h[off] & 0x7F;
			if( (v & 0x40) != 0 ) {
				v -= 0x80;   // negative: extend the sign
			}
			for(int i = 1; i < len; i++) {
				if( v > (Long.MAX_VALUE >> 8) || v < (Long.MIN_VALUE >> 8) ) {
					throw new IOException("A tar number is too large");
				}
				v = (v << 8) | (h[off + i] & 0xFF);
			}
			return v;
		}
		long v = 0;
		boolean seen = false;
		for(int i = off; i < off + len; i++) {
			int c = h[i] & 0xFF;
			if( c == 0 || (c == ' ' && seen) ) {
				break;
			}
			if( c == ' ' ) {
				continue;
			}
			if( c < '0' || c > '7' ) {
				throw new IOException("A tar header has a field that isn't an octal number");
			}
			seen = true;
			v = (v << 3) + (c - '0');
		}
		return v;
	}

	private static String ascii(byte[] h, int off, int len) {
		int end = off;
		while( end < off + len && h[end] != 0 ) {
			end++;
		}
		return new String(h, off, end - off, StandardCharsets.ISO_8859_1);
	}

	/** a NUL-ended name: UTF-8 when it is valid UTF-8, else ISO-8859-1 (what old tars wrote) */
	static String cstring(byte[] h, int off, int len) {
		int end = off;
		while( end < off + len && h[end] != 0 ) {
			end++;
		}
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(h, off, end - off)).toString();
		} catch (CharacterCodingException e) {
			return new String(h, off, end - off, StandardCharsets.ISO_8859_1);
		}
	}

	/** pax records: "LENGTH KEY=VALUE\n", LENGTH counting the whole record; values are UTF-8 */
	static Map<String, String> parsePax(byte[] data, Map<String, String> into) throws IOException {
		int p = 0;
		while( p < data.length ) {
			if( data[p] == 0 ) {
				break;
			}
			int sp = p;
			while( sp < data.length && data[sp] != ' ' ) {
				sp++;
			}
			int len;
			try {
				len = Integer.parseInt(new String(data, p, sp - p, StandardCharsets.US_ASCII));
			} catch (NumberFormatException e) {
				throw new IOException("A tar pax header is damaged");
			}
			if( len <= 0 || p + len > data.length || data[p + len - 1] != '\n' ) {
				throw new IOException("A tar pax header is damaged");
			}
			String rec = new String(data, sp + 1, p + len - 1 - (sp + 1), StandardCharsets.UTF_8);
			int eq = rec.indexOf('=');
			if( eq > 0 ) {
				String key = rec.substring(0, eq);
				String value = rec.substring(eq + 1);
				if( value.isEmpty() ) {
					// an empty value removes the key (a global one, for this entry)
					into.remove(key);
				} else {
					into.put(key, value);
				}
			}
			p += len;
		}
		return into;
	}

	private static long paxLong(String v, long otherwise) {
		try {
			return Long.parseLong(v.trim());
		} catch (NumberFormatException e) {
			return otherwise;
		}
	}

	/** seconds, with a fraction: as milliseconds */
	private static long paxTime(String v, long otherwise) {
		try {
			return new BigDecimal(v.trim()).movePointRight(3).longValue();
		} catch (NumberFormatException | ArithmeticException e) {
			return otherwise;
		}
	}
}
