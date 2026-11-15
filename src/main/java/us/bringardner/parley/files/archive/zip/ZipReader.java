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
package us.bringardner.parley.files.archive.zip;

import static us.bringardner.parley.files.archive.zip.ZipConstants.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipException;

import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveReader;
import us.bringardner.parley.files.archive.format.SeekableSource;

/**
 * Reads a zip archive with random access (APPNOTE.TXT), from the end: the end of central
 * directory record (and the ZIP64 one), then the whole central directory in one read. That takes
 * two or three reads, whatever the archive's size; each entry then takes one or two more.
 * <p>
 * The central directory is the source of truth: entries written as a stream (sizes in a data
 * descriptor after the data) are read correctly, and so is an archive with bytes in front of it
 * (a self-extracting program), whose offsets are all moved by the same amount.
 * <p>
 * Supported: stored and deflated entries, ZIP64, UTF-8 and code page 437 names, the Info-ZIP
 * Unicode path, extended and NTFS times. Not supported: encryption, other methods (an entry that
 * uses one is listed, and fails with a ZipException naming its method when it is read), and
 * archives split over several files.
 */
public final class ZipReader implements ArchiveReader {

	/** how much of the end is read first when looking for the end record */
	static final int FIRST_TAIL_READ = 4096;

	private final ArchiveFormat format;
	private final SeekableSource source;
	private final List<ZipArchiveEntry> entries;
	private final Map<String, ZipArchiveEntry> byPath;
	private final String comment;
	private final long length;
	private volatile boolean closed;

	private ZipReader(ArchiveFormat format, SeekableSource source, long length, List<ZipArchiveEntry> entries, String comment) {
		this.format = format;
		this.source = source;
		this.length = length;
		this.entries = Collections.unmodifiableList(entries);
		Map<String, ZipArchiveEntry> map = new HashMap<>(entries.size() * 2);
		for(ZipArchiveEntry e : entries) {
			map.put(e.getPath(), e);   // the last one wins
		}
		this.byPath = map;
		this.comment = comment;
	}

	/**
	 * Reads the archive's index.
	 *
	 * @param source the archive; it stays the caller's to close
	 * @throws ZipException if it isn't a zip archive or is damaged
	 * @throws IOException if it can't be read
	 */
	public static ZipReader open(SeekableSource source) throws IOException {
		return open(ZipFormat.INSTANCE, source);
	}

	static ZipReader open(ArchiveFormat format, SeekableSource source) throws IOException {
		Objects.requireNonNull(source, "source");
		long len = source.length();
		End end = findEnd(source, len);
		if( end == null ) {
			throw new ZipException("Not a zip archive: there is no end of central directory record");
		}
		if( end.cdSize > Integer.MAX_VALUE - 16 ) {
			throw new ZipException("The central directory is too large to read: " + end.cdSize + " bytes");
		}
		long cdStart = end.cdOffset + end.prefix;
		byte[] cd = source.readFully(cdStart, (int) end.cdSize);
		List<ZipArchiveEntry> entries = new ArrayList<>((int) Math.min(end.entryCount, 1 << 20));
		int p = 0;
		long headers = 0;
		while( p + CENTRAL_HEADER_SIZE <= cd.length && sig(cd, p) == CENTRAL_HEADER_SIG ) {
			p = readCentralHeader(cd, p, end.prefix, len, entries);
			headers++;
		}
		if( headers != end.entryCount ) {
			throw new ZipException("The central directory has " + headers + " entries, not the "
					+ end.entryCount + " its end record says");
		}
		return new ZipReader(format, source, len, entries, end.comment);
	}

	/** What the end records say. */
	static final class End {
		long entryCount;
		long cdSize;
		long cdOffset;
		/** bytes in front of the archive: added to every offset it holds */
		long prefix;
		String comment;
	}

	/**
	 * Finds the end of central directory record: searched backwards from the end, in the last
	 * 4 KB first, then in the most it can be (22 bytes and a comment of 65,535).
	 *
	 * @return the end records' values, or null if there is no end record
	 */
	static End findEnd(SeekableSource source, long len) throws IOException {
		if( len < EOCD_SIZE ) {
			return null;
		}
		int want = (int) Math.min(len, FIRST_TAIL_READ);
		byte[] tail = source.readFully(len - want, want);
		int at = searchEnd(tail);
		if( at < 0 && want < len && want < MAX_EOCD_SEARCH ) {
			want = (int) Math.min(len, MAX_EOCD_SEARCH);
			tail = source.readFully(len - want, want);
			at = searchEnd(tail);
		}
		if( at < 0 ) {
			return null;
		}
		long eocdPos = len - want + at;
		End end = new End();
		int disk = u16(tail, at + 4);
		int cdDisk = u16(tail, at + 6);
		int diskEntries = u16(tail, at + 8);
		end.entryCount = u16(tail, at + 10);
		end.cdSize = u32(tail, at + 12);
		end.cdOffset = u32(tail, at + 16);
		int commentLen = u16(tail, at + 20);
		end.comment = commentLen == 0 ? null : ZipConstants.decode(tail, at + 22, commentLen, false);
		// where the central directory ends: the ZIP64 end record if there is one, else this record
		long cdEnd = eocdPos;
		boolean zip64 = false;
		if( eocdPos >= ZIP64_LOCATOR_SIZE ) {
			byte[] loc = source.readFully(eocdPos - ZIP64_LOCATOR_SIZE, ZIP64_LOCATOR_SIZE);
			if( sig(loc, 0) == ZIP64_LOCATOR_SIG ) {
				// The ZIP64 end record is usually just before the locator (it has no extensible data):
				// look there first, which also works when bytes in front move every offset. Else
				// where the locator says, which doesn't count such bytes.
				long[] candidates = { eocdPos - ZIP64_LOCATOR_SIZE - ZIP64_EOCD_SIZE, u64(loc, 8) };
				for(long pos : candidates) {
					if( pos < 0 || pos + ZIP64_EOCD_SIZE > eocdPos ) {
						continue;
					}
					byte[] z = source.readFully(pos, ZIP64_EOCD_SIZE);
					if( sig(z, 0) == ZIP64_EOCD_SIG ) {
						zip64 = true;
						disk = (int) u32(z, 16);
						cdDisk = (int) u32(z, 20);
						end.entryCount = u64(z, 32);
						end.cdSize = u64(z, 40);
						end.cdOffset = u64(z, 48);
						cdEnd = pos;
						break;
					}
				}
			}
		}
		if( !zip64 && (end.entryCount == ZIP64_MAGIC_16 || end.cdSize == ZIP64_MAGIC || end.cdOffset == ZIP64_MAGIC) ) {
			throw new ZipException("The end record says the archive is ZIP64, but there is no ZIP64 end record");
		}
		if( disk != 0 || cdDisk != 0 || (!zip64 && diskEntries != end.entryCount) ) {
			throw new ZipException("The archive is split over several files, which isn't supported");
		}
		end.prefix = cdEnd - end.cdSize - end.cdOffset;
		if( end.prefix < 0 || end.cdOffset + end.prefix + end.cdSize > len ) {
			throw new ZipException("The end record's central directory (offset " + end.cdOffset + ", "
					+ end.cdSize + " bytes) isn't in the archive");
		}
		return end;
	}

	/** @return where an end record starts in tail, searched backwards, or -1. Its comment must reach the end exactly. */
	private static int searchEnd(byte[] tail) {
		for(int at = tail.length - EOCD_SIZE; at >= 0; at--) {
			if( tail[at] == 0x50 && tail[at + 1] == 0x4b && sig(tail, at) == EOCD_SIG ) {
				int commentLen = u16(tail, at + 20);
				if( at + EOCD_SIZE + commentLen == tail.length ) {
					return at;
				}
			}
		}
		// some writers leave junk after the comment: take the last record whose comment fits
		for(int at = tail.length - EOCD_SIZE; at >= 0; at--) {
			if( tail[at] == 0x50 && tail[at + 1] == 0x4b && sig(tail, at) == EOCD_SIG ) {
				int commentLen = u16(tail, at + 20);
				if( at + EOCD_SIZE + commentLen <= tail.length ) {
					return at;
				}
			}
		}
		return -1;
	}

	/** @return where the next header starts */
	private static int readCentralHeader(byte[] cd, int p, long prefix, long len, List<ZipArchiveEntry> out) throws IOException {
		int madeBy = u16(cd, p + 4);
		int flags = u16(cd, p + 8);
		int method = u16(cd, p + 10);
		long dos = u32(cd, p + 12);
		long crc = u32(cd, p + 16);
		long csize = u32(cd, p + 20);
		long size = u32(cd, p + 24);
		int nameLen = u16(cd, p + 28);
		int extraLen = u16(cd, p + 30);
		int commentLen = u16(cd, p + 32);
		long external = u32(cd, p + 38);
		long offset = u32(cd, p + 42);
		int name = p + CENTRAL_HEADER_SIZE;
		int extra = name + nameLen;
		int comment = extra + extraLen;
		int next = comment + commentLen;
		if( next > cd.length ) {
			throw new ZipException("A central directory header runs past the central directory");
		}
		boolean utf8 = (flags & FLAG_UTF8) != 0;
		byte[] rawName = java.util.Arrays.copyOfRange(cd, name, name + nameLen);
		ExtraFields x = ExtraFields.parse(cd, extra, extraLen, size == ZIP64_MAGIC, csize == ZIP64_MAGIC,
				offset == ZIP64_MAGIC, utf8 ? null : rawName);
		if( size == ZIP64_MAGIC ) {
			size = require(x.size, "size");
		}
		if( csize == ZIP64_MAGIC ) {
			csize = require(x.compressedSize, "compressed size");
		}
		if( offset == ZIP64_MAGIC ) {
			offset = require(x.localHeaderOffset, "local header offset");
		}
		String n = x.unicodePath != null ? x.unicodePath : ZipConstants.decode(rawName, 0, nameLen, utf8);
		long mode = (madeBy >> 8) == HOST_UNIX ? (external >> 16) & 0xFFFF : 0;
		// a folder: its name ends with /, or its Unix mode says so, or (no Unix mode) its DOS attribute does
		boolean dir = n.endsWith("/") || (mode & S_IFMT) == S_IFDIR || (mode == 0 && (external & DOS_DIRECTORY) != 0);
		String path = ArchiveEntry.normalize(n);
		if( path.isEmpty() ) {
			// "/" or "./": the top folder, which is always there
			return next;
		}
		long headerAt = offset + prefix;
		if( headerAt + LOCAL_HEADER_SIZE > len ) {
			throw new ZipException(path + "'s local header (offset " + offset + ") isn't in the archive");
		}
		ZipArchiveEntry.Builder b = ZipArchiveEntry.builder(path).directory(dir)
				.lastModified(x.modified != ArchiveEntry.UNKNOWN ? x.modified : dosToMillis(dos));
		b.rawName = n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
		b.size = size;
		b.compressedSize = csize;
		b.crc = crc;
		b.method = method;
		b.flags = flags;
		b.versionMadeBy = madeBy;
		b.externalAttributes = external;
		b.comment = commentLen == 0 ? null : ZipConstants.decode(cd, comment, commentLen, utf8);
		b.lastAccessTime = x.accessed;
		b.creationTime = x.created;
		b.localHeaderOffset = headerAt;
		out.add(b.build());
		return next;
	}

	private static long require(long v, String what) throws ZipException {
		if( v < 0 ) {
			throw new ZipException("A header says its " + what + " is in a ZIP64 extra field, which isn't there");
		}
		return v;
	}

	@Override
	public ArchiveFormat getFormat() {
		return format;
	}

	@Override
	public List<ZipArchiveEntry> getEntries() {
		return entries;
	}

	@Override
	public ZipArchiveEntry getEntry(String path) {
		return byPath.get(ArchiveEntry.normalize(path));
	}

	@Override
	public String getComment() {
		return comment;
	}

	@Override
	public SeekableSource getSource() {
		return source;
	}

	/** @return the archive's length when it was opened */
	public long length() {
		return length;
	}

	/** @return where the entry's data starts: after its local header, whose name and extra lengths can differ from the central ones */
	private long dataStart(ZipArchiveEntry e, SeekableSource src) throws IOException {
		if( e.localHeaderOffset < 0 ) {
			throw new IllegalArgumentException(e.getPath() + " isn't an entry of this archive");
		}
		byte[] h = src.readFully(e.localHeaderOffset, LOCAL_HEADER_SIZE);
		if( sig(h, 0) != LOCAL_HEADER_SIG ) {
			throw new ZipException(e.getPath() + "'s local header isn't where the central directory says");
		}
		return e.localHeaderOffset + LOCAL_HEADER_SIZE + u16(h, 26) + u16(h, 28);
	}

	private ZipArchiveEntry mine(ArchiveEntry entry) {
		if( !(entry instanceof ZipArchiveEntry) || ((ZipArchiveEntry) entry).localHeaderOffset < 0 ) {
			throw new IllegalArgumentException(entry + " isn't an entry this reader read");
		}
		return (ZipArchiveEntry) entry;
	}

	private void checkOpen() throws IOException {
		if( closed ) {
			throw new IOException("The zip reader is closed");
		}
	}

	@Override
	public InputStream openStream(ArchiveEntry entry, SeekableSource src) throws IOException {
		checkOpen();
		ZipArchiveEntry e = mine(entry);
		if( e.isDirectory() ) {
			return EntryStreams.empty();
		}
		EntryStreams.checkReadable(e);
		long start = dataStart(e, src);
		InputStream raw = new EntryStreams.SourceInputStream(src, start, e.getCompressedSize(), EntryStreams.BUFFER_SIZE);
		InputStream data = e.getMethod() == ZipArchiveEntry.STORED ? raw
				: new EntryStreams.InflatingInputStream(raw, (int) Math.max(512, Math.min(EntryStreams.BUFFER_SIZE, e.getCompressedSize() + 1)));
		return new EntryStreams.CheckedInputStream(data, e.getPath(), e.getSize(), e.getCrc());
	}

	@Override
	public SeekableSource openSource(ArchiveEntry entry, SeekableSource src) throws IOException {
		checkOpen();
		ZipArchiveEntry e = mine(entry);
		if( e.isDirectory() ) {
			return us.bringardner.parley.files.archive.format.SeekableSources.of(new byte[0]);
		}
		EntryStreams.checkReadable(e);
		long start = dataStart(e, src);
		if( e.getMethod() == ZipArchiveEntry.STORED ) {
			return us.bringardner.parley.files.archive.format.SeekableSources.range(src, start, e.getSize());
		}
		return new InflatingSeekableSource(() -> new EntryStreams.InflatingInputStream(
				new EntryStreams.SourceInputStream(src, start, e.getCompressedSize(), EntryStreams.BUFFER_SIZE),
				(int) Math.max(512, Math.min(EntryStreams.BUFFER_SIZE, e.getCompressedSize() + 1))), e.getSize());
	}

	@Override
	public void close() {
		closed = true;
	}

	@Override
	public String toString() {
		return "ZipReader[" + entries.size() + " entries]";
	}
}
