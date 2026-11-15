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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveWriter;

/**
 * Writes a POSIX pax tar archive as a stream: each entry is a ustar header, its data and padding to
 * 512 bytes; the archive ends with two zero blocks, padded to a 10,240-byte record, as GNU tar and
 * bsdtar write it.
 * <p>
 * A pax extended header goes before an entry only when something doesn't fit a ustar header: a
 * name that can't be split into its 155-byte prefix and 100-byte name, or isn't plain ASCII; a
 * link target over 100 bytes or not ASCII; a size of 8 GB or more; a time before 1970, past 2242,
 * or with milliseconds; an id over 2,097,151; a user or group name over 32 bytes or not ASCII.
 * Such a field's ustar value is 0 (or empty), and the pax record holds the real one.
 * <p>
 * Each file's size must be given before its data ({@link TarArchiveEntry.Builder#size(long)}), and
 * exactly that many bytes written: nothing is buffered. To gzip the archive, give the writer a
 * {@link us.bringardner.parley.files.archive.compress.Compression#compress(OutputStream)} stream.
 */
public final class TarWriter implements ArchiveWriter {

	static final int RECORD = 10240;
	private static final long MAX_OCTAL_11 = 077777777777L;
	private static final long MAX_OCTAL_7 = 07777777L;

	private final OutputStream out;
	private long count;
	private EntryStream open;
	private boolean finished;
	private boolean closed;
	private int paxCount;

	/** @param out where the archive goes; closed when the writer is */
	public TarWriter(OutputStream out) {
		this.out = Objects.requireNonNull(out, "out");
	}

	/**
	 * @param entry a {@link TarArchiveEntry}, or another format's entry (a file needs its size; its
	 *        path, time and mode are kept)
	 * @return where a file's bytes go: exactly its size; closing it ends the entry. A folder's or
	 *         link's takes no bytes.
	 * @throws IOException if a file has no size, or the archive is finished
	 */
	@Override
	public OutputStream putEntry(ArchiveEntry entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		checkWritable();
		closeEntry();
		TarArchiveEntry t = entry instanceof TarArchiveEntry ? (TarArchiveEntry) entry : convert(entry);
		if( t.getPath().isEmpty() ) {
			throw new IOException("An entry needs a path (the archive's top folder isn't an entry)");
		}
		boolean hasData = t.isRegularFile();
		long size = hasData ? t.storedSize : 0;
		if( hasData && size < 0 ) {
			throw new IOException(t.getPath() + " has no size: a tar entry's size goes before its data");
		}
		writeHeader(t, size);
		open = new EntryStream(t.getPath(), size);
		return open;
	}

	private static TarArchiveEntry convert(ArchiveEntry e) {
		TarArchiveEntry.Builder b = TarArchiveEntry.builder(e.getPath()).lastModified(e.getLastModified()).unixMode(e.getUnixMode());
		if( e.isDirectory() ) {
			b.directory();
		} else if( e.getSize() >= 0 ) {
			b.size(e.getSize());
		}
		return b.build();
	}

	private void writeHeader(TarArchiveEntry e, long size) throws IOException {
		Map<String, String> pax = new LinkedHashMap<>();
		byte[] h = new byte[TarScanner.BLOCK];
		String name = e.isDirectory() ? e.getPath() + "/" : e.getPath();
		if( !putName(h, name) ) {
			pax.put("path", name);
		}
		long mode = e.getUnixMode() >= 0 ? e.getUnixMode() : e.isDirectory() ? 0755 : e.isSymbolicLink() ? 0777 : 0644;
		octal(h, 100, 8, mode & 07777);
		putId(h, 108, e.getUid(), "uid", pax);
		putId(h, 116, e.getGid(), "gid", pax);
		if( size > MAX_OCTAL_11 ) {
			pax.put("size", Long.toString(size));
			octal(h, 124, 12, 0);
		} else {
			octal(h, 124, 12, size);
		}
		// (now, when not given, in whole seconds: a fraction of it isn't worth a pax header)
		long mtime = e.getLastModified() == ArchiveEntry.UNKNOWN ? System.currentTimeMillis() / 1000 * 1000 : e.getLastModified();
		long seconds = Math.floorDiv(mtime, 1000);
		if( seconds < 0 || seconds > MAX_OCTAL_11 || mtime % 1000 != 0 ) {
			pax.put("mtime", BigDecimal.valueOf(mtime).movePointLeft(3).stripTrailingZeros().toPlainString());
		}
		octal(h, 136, 12, Math.max(0, Math.min(seconds, MAX_OCTAL_11)));
		h[156] = (byte) e.getType();
		String link = e.getLinkName();
		if( link != null ) {
			if( !fits(link, 100) ) {
				pax.put("linkpath", link);
			} else {
				put(h, 157, link);
			}
		}
		put(h, 257, "ustar");   // with its NUL: POSIX
		h[263] = '0';
		h[264] = '0';
		putName(h, 265, 32, e.getUserName(), "uname", pax);
		putName(h, 297, 32, e.getGroupName(), "gname", pax);
		octal(h, 329, 8, 0);
		octal(h, 337, 8, 0);
		if( !pax.isEmpty() ) {
			writePax(name, pax, Math.max(0, Math.min(seconds, MAX_OCTAL_11)));
		}
		checksum(h);
		write(h, 0, h.length);
	}

	/** the name in the name field, or split at a / into prefix and name; false if it can't be */
	private static boolean putName(byte[] h, String name) {
		if( !ascii(name) ) {
			put(h, 0, truncated(name, 100));
			return false;
		}
		if( name.length() <= 100 ) {
			put(h, 0, name);
			return true;
		}
		for(int i = Math.min(name.length() - 1, 155); i > 0; i--) {
			if( name.charAt(i) == '/' && name.length() - i - 1 <= 100 && name.length() - i - 1 > 0 ) {
				put(h, 345, name.substring(0, i));
				put(h, 0, name.substring(i + 1));
				return true;
			}
		}
		put(h, 0, truncated(name, 100));
		return false;
	}

	private static void putId(byte[] h, int off, long id, String key, Map<String, String> pax) {
		long v = id < 0 ? 0 : id;
		if( v > MAX_OCTAL_7 ) {
			pax.put(key, Long.toString(v));
			v = 0;
		}
		octal(h, off, 8, v);
	}

	private static void putName(byte[] h, int off, int len, String value, String key, Map<String, String> pax) {
		if( value == null ) {
			return;
		}
		if( fits(value, len) ) {
			put(h, off, value);
		} else {
			pax.put(key, value);
		}
	}

	/** plain ASCII, and at most len bytes (a field holds len bytes, NUL-ended when shorter) */
	private static boolean fits(String s, int len) {
		return ascii(s) && s.length() <= len;
	}

	private static boolean ascii(String s) {
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if( c >= 0x80 || c == 0 ) {
				return false;
			}
		}
		return true;
	}

	/** what a reader that doesn't know pax sees: the name's ASCII, cut to len bytes */
	private static String truncated(String s, int len) {
		StringBuilder b = new StringBuilder();
		for(int i = 0; i < s.length() && b.length() < len; i++) {
			char c = s.charAt(i);
			b.append(c < 0x80 && c != 0 ? c : '_');
		}
		return b.toString();
	}

	private static void put(byte[] h, int off, String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(b, 0, h, off, b.length);
	}

	/** len-1 octal digits, then NUL */
	private static void octal(byte[] h, int off, int len, long v) {
		String s = Long.toOctalString(v);
		StringBuilder b = new StringBuilder();
		for(int i = s.length(); i < len - 1; i++) {
			b.append('0');
		}
		put(h, off, b.append(s).toString());
		h[off + len - 1] = 0;
	}

	private static void checksum(byte[] h) {
		Arrays.fill(h, 148, 156, (byte) ' ');
		long sum = 0;
		for(byte b : h) {
			sum += b & 0xFF;
		}
		String s = Long.toOctalString(sum);
		StringBuilder b = new StringBuilder();
		for(int i = s.length(); i < 6; i++) {
			b.append('0');
		}
		put(h, 148, b.append(s).toString());
		h[154] = 0;
		h[155] = ' ';
	}

	/** an 'x' header and its records, before the entry it is for */
	private void writePax(String name, Map<String, String> pax, long seconds) throws IOException {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for(Map.Entry<String, String> e : pax.entrySet()) {
			data.writeBytes(record(e.getKey(), e.getValue()));
		}
		byte[] d = data.toByteArray();
		byte[] h = new byte[TarScanner.BLOCK];
		String base = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
		base = base.substring(base.lastIndexOf('/') + 1);
		put(h, 0, truncated("PaxHeaders." + (paxCount++) + "/" + base, 100));
		octal(h, 100, 8, 0644);
		octal(h, 108, 8, 0);
		octal(h, 116, 8, 0);
		octal(h, 124, 12, d.length);
		octal(h, 136, 12, seconds);
		h[156] = 'x';
		put(h, 257, "ustar");
		h[263] = '0';
		h[264] = '0';
		checksum(h);
		write(h, 0, h.length);
		write(d, 0, d.length);
		pad(d.length);
	}

	/** "LENGTH KEY=VALUE\n", LENGTH counting itself */
	static byte[] record(String key, String value) {
		int body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8).length;
		int len = body + 1;
		while( Integer.toString(len).length() + body != len ) {
			len = Integer.toString(len).length() + body;
		}
		return (len + " " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private void write(byte[] b, int off, int len) throws IOException {
		out.write(b, off, len);
		count += len;
	}

	private void pad(long size) throws IOException {
		int rest = (int) (size % TarScanner.BLOCK);
		if( rest != 0 ) {
			write(new byte[TarScanner.BLOCK - rest], 0, TarScanner.BLOCK - rest);
		}
	}

	/** Ends the open entry, if there is one. */
	public void closeEntry() throws IOException {
		if( open != null ) {
			open.close();
		}
	}

	@Override
	public void finish() throws IOException {
		if( finished ) {
			return;
		}
		checkWritable();
		closeEntry();
		byte[] zeros = new byte[2 * TarScanner.BLOCK];
		write(zeros, 0, zeros.length);
		long rest = count % RECORD;
		if( rest != 0 ) {
			byte[] fill = new byte[(int) (RECORD - rest)];
			write(fill, 0, fill.length);
		}
		out.flush();
		finished = true;
	}

	@Override
	public void close() throws IOException {
		if( closed ) {
			return;
		}
		try {
			if( !finished ) {
				finish();
			}
		} finally {
			closed = true;
			open = null;
			out.close();
		}
	}

	private void checkWritable() throws IOException {
		if( closed ) {
			throw new IOException("The tar writer is closed");
		}
		if( finished ) {
			throw new IOException("The archive is finished");
		}
	}

	/** One entry's bytes: exactly its size. Closing it pads the entry and ends it. */
	private final class EntryStream extends OutputStream {
		private final String path;
		private final long size;
		private long written;
		private boolean done;

		EntryStream(String path, long size) {
			this.path = path;
			this.size = size;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			Objects.checkFromIndexSize(off, len, b.length);
			if( done ) {
				throw new IOException("The entry " + path + " has ended");
			}
			if( written + len > size ) {
				throw new IOException(path + " has a size of " + size + " bytes, and more was written");
			}
			TarWriter.this.write(b, off, len);
			written += len;
		}

		@Override
		public void flush() throws IOException {
			out.flush();
		}

		@Override
		public void close() throws IOException {
			if( done ) {
				return;
			}
			done = true;
			if( open == this ) {
				open = null;
			}
			if( written != size ) {
				throw new IOException(path + " has a size of " + size + " bytes, but " + written + " were written");
			}
			pad(size);
		}
	}
}
