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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipException;

import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveWriter;

/**
 * Writes a zip archive as a stream (APPNOTE.TXT), so all it needs is an OutputStream.
 * <ul>
 * <li><b>Deflated entries</b> (the default) are written with their sizes and CRC-32 in a data
 * descriptor after the data (flag bit 3), so their size needn't be known first.</li>
 * <li><b>Stored entries</b> need their size and CRC-32 up front
 * ({@link ZipArchiveEntry.Builder#sizeAndCrc(long, long)}), which go in the local header; they
 * are checked when the entry ends. A stored entry without them is refused: nothing is
 * buffered.</li>
 * <li><b>ZIP64</b> is used only where it is needed, as the JDK does: an entry whose size is known
 * to be 4 GB or more gets a ZIP64 field in its local header; one that passes 4 GB as it is
 * written gets 8-byte sizes in its data descriptor; the central directory and end records use
 * ZIP64 for whatever doesn't fit in 32 bits (sizes, offsets, more than 65,535 entries). A small
 * archive has no ZIP64 at all.</li>
 * <li><b>Names</b> are UTF-8, with flag bit 11 set when they aren't plain ASCII. Times are the DOS
 * time and an extended timestamp (0x5455, UTC seconds). Entries are marked as made on Unix,
 * with the mode given (default 0644, folders 0755).</li>
 * </ul>
 * A path may be written once; a folder's path ends with {@code /} in the archive.
 */
public final class ZipWriter implements ArchiveWriter {

	private final CountingOutputStream out;
	private final List<Written> written = new ArrayList<>();
	private final Set<String> paths = new HashSet<>();
	private int level = Deflater.DEFAULT_COMPRESSION;
	private String comment;
	private EntryOutputStream open;
	private boolean finished;
	private boolean closed;

	/** @param out where the archive goes; closed when the writer is */
	public ZipWriter(OutputStream out) {
		this.out = new CountingOutputStream(Objects.requireNonNull(out, "out"));
	}

	/** @param level the DEFLATE level for entries started after this, 0 to 9 or -1 (the default) */
	public void setLevel(int level) {
		if( level < -1 || level > 9 ) {
			throw new IllegalArgumentException("level=" + level);
		}
		this.level = level;
	}

	/** @param comment the archive's comment, at most 65,535 bytes as UTF-8, or null */
	public void setComment(String comment) {
		if( comment != null && comment.getBytes(StandardCharsets.UTF_8).length > 0xFFFF ) {
			throw new IllegalArgumentException("The archive comment is longer than 65,535 bytes");
		}
		this.comment = comment;
	}

	/** What the central directory needs of an entry written. */
	private static final class Written {
		byte[] name;
		boolean utf8;
		int flags;
		int method;
		long dos;
		long mtimeSeconds;
		boolean hasUt;
		long crc;
		long csize;
		long size;
		long offset;
		long external;
		int madeBy;
		byte[] comment;
	}

	@Override
	public OutputStream putEntry(ArchiveEntry entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		checkWritable();
		closeEntry();
		ZipArchiveEntry z = entry instanceof ZipArchiveEntry ? (ZipArchiveEntry) entry : ZipArchiveEntry.builder(entry).build();
		String path = z.getPath();
		if( path.isEmpty() ) {
			throw new ZipException("An entry needs a path (the archive's top folder isn't an entry)");
		}
		boolean dir = z.isDirectory();
		String name = dir ? path + "/" : path;
		if( !paths.add(name) ) {
			throw new ZipException("Duplicate entry: " + name);
		}
		Written w = new Written();
		w.name = name.getBytes(StandardCharsets.UTF_8);
		if( w.name.length > 0xFFFF ) {
			throw new ZipException("The entry name is longer than 65,535 bytes: " + path);
		}
		w.utf8 = needsUtf8(name) || needsUtf8(z.getComment());
		w.comment = z.getComment() == null ? null : z.getComment().getBytes(StandardCharsets.UTF_8);
		if( w.comment != null && w.comment.length > 0xFFFF ) {
			throw new ZipException("The comment of " + path + " is longer than 65,535 bytes");
		}
		long mtime = z.getLastModified() == ArchiveEntry.UNKNOWN ? System.currentTimeMillis() : z.getLastModified();
		w.dos = millisToDos(mtime);
		w.mtimeSeconds = Math.floorDiv(mtime, 1000);
		w.hasUt = w.mtimeSeconds >= Integer.MIN_VALUE && w.mtimeSeconds <= Integer.MAX_VALUE;
		w.external = z.getExternalAttributes();
		w.madeBy = z.getVersionMadeBy();
		if( w.external == 0 ) {
			long mode = dir ? S_IFDIR | 0755 : S_IFREG | 0644;
			w.external = mode << 16 | (dir ? DOS_DIRECTORY : 0);
			w.madeBy = HOST_UNIX << 8;
		}
		w.method = dir ? ZipArchiveEntry.STORED : z.getMethod();
		w.offset = out.count;
		boolean known = false;
		if( dir ) {
			w.size = w.csize = 0;
			w.crc = 0;
			known = true;
		} else if( w.method == ZipArchiveEntry.STORED ) {
			if( z.getSize() < 0 || z.getCrc() < 0 ) {
				throw new ZipException(path + " is to be stored, but its size and CRC-32 weren't given (ZipArchiveEntry.Builder.sizeAndCrc): "
						+ "a stored entry's size goes before its data");
			}
			w.size = w.csize = z.getSize();
			w.crc = z.getCrc();
			known = true;
		}
		w.flags = (w.utf8 ? FLAG_UTF8 : 0) | (known ? 0 : FLAG_DATA_DESCRIPTOR);
		// ZIP64 in the local header only when a known size needs it
		boolean localZip64 = known && w.size >= ZIP64_MAGIC;
		writeLocalHeader(w, localZip64);
		written.add(w);
		open = new EntryOutputStream(w, known, path);
		return open;
	}

	private void writeLocalHeader(Written w, boolean zip64) throws IOException {
		byte[] extra = extra(w, zip64, false, false, false);
		byte[] h = new byte[LOCAL_HEADER_SIZE];
		put32(h, 0, LOCAL_HEADER_SIG);
		put16(h, 4, zip64 ? VERSION_ZIP64 : w.method == ZipArchiveEntry.STORED ? VERSION_STORED : VERSION_DEFLATE);
		put16(h, 6, w.flags);
		put16(h, 8, w.method);
		put32(h, 10, w.dos);
		boolean descriptor = (w.flags & FLAG_DATA_DESCRIPTOR) != 0;
		put32(h, 14, descriptor ? 0 : w.crc);
		put32(h, 18, descriptor ? 0 : zip64 ? ZIP64_MAGIC : w.csize);
		put32(h, 22, descriptor ? 0 : zip64 ? ZIP64_MAGIC : w.size);
		put16(h, 26, w.name.length);
		put16(h, 28, extra.length);
		out.write(h);
		out.write(w.name);
		out.write(extra);
	}

	/**
	 * The extra fields: ZIP64 (in a local header, both sizes; in the central directory, each value
	 * that doesn't fit, in APPNOTE's order) and the extended timestamp.
	 */
	private static byte[] extra(Written w, boolean localZip64, boolean cSize, boolean cCsize, boolean cOffset) {
		ByteArrayOutputStream x = new ByteArrayOutputStream();
		int n = localZip64 ? 2 : (cSize ? 1 : 0) + (cCsize ? 1 : 0) + (cOffset ? 1 : 0);
		if( n > 0 ) {
			byte[] z = new byte[4 + 8 * n];
			put16(z, 0, EXTRA_ZIP64);
			put16(z, 2, 8 * n);
			int at = 4;
			if( localZip64 || cSize ) {
				put64(z, at, w.size);
				at += 8;
			}
			if( localZip64 || cCsize ) {
				put64(z, at, w.csize);
				at += 8;
			}
			if( cOffset ) {
				put64(z, at, w.offset);
			}
			x.writeBytes(z);
		}
		if( w.hasUt ) {
			byte[] t = new byte[9];
			put16(t, 0, EXTRA_EXT_TIME);
			put16(t, 2, 5);
			t[4] = 1;   // the modification time
			put32(t, 5, w.mtimeSeconds);
			x.writeBytes(t);
		}
		return x.toByteArray();
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
		long cdStart = out.count;
		for(Written w : written) {
			writeCentralHeader(w);
		}
		long cdSize = out.count - cdStart;
		long count = written.size();
		boolean zip64 = count >= ZIP64_MAGIC_16 || cdSize >= ZIP64_MAGIC || cdStart >= ZIP64_MAGIC;
		if( zip64 ) {
			long z64Pos = out.count;
			byte[] z = new byte[ZIP64_EOCD_SIZE];
			put32(z, 0, ZIP64_EOCD_SIG);
			put64(z, 4, ZIP64_EOCD_SIZE - 12);
			put16(z, 12, HOST_UNIX << 8 | VERSION_ZIP64);
			put16(z, 14, VERSION_ZIP64);
			put32(z, 16, 0);
			put32(z, 20, 0);
			put64(z, 24, count);
			put64(z, 32, count);
			put64(z, 40, cdSize);
			put64(z, 48, cdStart);
			out.write(z);
			byte[] loc = new byte[ZIP64_LOCATOR_SIZE];
			put32(loc, 0, ZIP64_LOCATOR_SIG);
			put32(loc, 4, 0);
			put64(loc, 8, z64Pos);
			put32(loc, 16, 1);
			out.write(loc);
		}
		byte[] c = comment == null ? new byte[0] : comment.getBytes(StandardCharsets.UTF_8);
		byte[] e = new byte[EOCD_SIZE];
		put32(e, 0, EOCD_SIG);
		int n16 = (int) Math.min(count, ZIP64_MAGIC_16);
		put16(e, 8, n16);
		put16(e, 10, n16);
		put32(e, 12, Math.min(cdSize, ZIP64_MAGIC));
		put32(e, 16, Math.min(cdStart, ZIP64_MAGIC));
		put16(e, 20, c.length);
		out.write(e);
		out.write(c);
		out.flush();
		finished = true;
	}

	private void writeCentralHeader(Written w) throws IOException {
		boolean bigSize = w.size >= ZIP64_MAGIC;
		boolean bigCsize = w.csize >= ZIP64_MAGIC;
		boolean bigOffset = w.offset >= ZIP64_MAGIC;
		boolean zip64 = bigSize || bigCsize || bigOffset;
		byte[] extra = extra(w, false, bigSize, bigCsize, bigOffset);
		int version = zip64 ? VERSION_ZIP64 : w.method == ZipArchiveEntry.STORED ? VERSION_STORED : VERSION_DEFLATE;
		byte[] h = new byte[CENTRAL_HEADER_SIZE];
		put32(h, 0, CENTRAL_HEADER_SIG);
		put16(h, 4, (w.madeBy & 0xFF00) | Math.max(version, w.madeBy & 0xFF));
		put16(h, 6, version);
		put16(h, 8, w.flags);
		put16(h, 10, w.method);
		put32(h, 12, w.dos);
		put32(h, 16, w.crc);
		put32(h, 20, bigCsize ? ZIP64_MAGIC : w.csize);
		put32(h, 24, bigSize ? ZIP64_MAGIC : w.size);
		put16(h, 28, w.name.length);
		put16(h, 30, extra.length);
		put16(h, 32, w.comment == null ? 0 : w.comment.length);
		put16(h, 34, 0);
		put16(h, 36, 0);
		put32(h, 38, w.external);
		put32(h, 42, bigOffset ? ZIP64_MAGIC : w.offset);
		out.write(h);
		out.write(w.name);
		out.write(extra);
		if( w.comment != null ) {
			out.write(w.comment);
		}
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
			if( open != null ) {
				open.end();
			}
			out.close();
		}
	}

	private void checkWritable() throws IOException {
		if( closed ) {
			throw new IOException("The zip writer is closed");
		}
		if( finished ) {
			throw new IOException("The archive is finished");
		}
	}

	/** One entry's bytes. Closing it ends the entry. */
	private final class EntryOutputStream extends OutputStream {
		private final Written w;
		private final boolean known;
		private final String path;
		private final CRC32 crc = new CRC32();
		private final Deflater deflater;
		private final byte[] buf;
		private long count;
		private long start;
		private boolean done;

		EntryOutputStream(Written w, boolean known, String path) {
			this.w = w;
			this.known = known;
			this.path = path;
			this.start = out.count;
			if( w.method == ZipArchiveEntry.DEFLATED ) {
				deflater = new Deflater(level, true);
				buf = new byte[EntryStreams.BUFFER_SIZE];
			} else {
				deflater = null;
				buf = null;
			}
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
			if( len == 0 ) {
				return;
			}
			if( pathIsDir() ) {
				throw new IOException(path + " is a folder: it has no bytes");
			}
			crc.update(b, off, len);
			count += len;
			if( deflater == null ) {
				if( count > w.size ) {
					throw new ZipException(path + " is stored with a size of " + w.size + ", and more was written");
				}
				out.write(b, off, len);
			} else {
				deflater.setInput(b, off, len);
				while( !deflater.needsInput() ) {
					drain(Deflater.NO_FLUSH);
				}
			}
		}

		private boolean pathIsDir() {
			return w.name.length > 0 && w.name[w.name.length - 1] == '/';
		}

		private void drain(int flush) throws IOException {
			int cnt = deflater.deflate(buf, 0, buf.length, flush);
			if( cnt > 0 ) {
				out.write(buf, 0, cnt);
			}
		}

		@Override
		public void flush() throws IOException {
			out.flush();
		}

		/** frees the deflater, whatever happened */
		void end() {
			done = true;
			if( deflater != null ) {
				deflater.end();
			}
			if( open == this ) {
				open = null;
			}
		}

		@Override
		public void close() throws IOException {
			if( done ) {
				return;
			}
			try {
				if( deflater != null ) {
					deflater.finish();
					while( !deflater.finished() ) {
						drain(Deflater.NO_FLUSH);
					}
				}
				long csize = out.count - start;
				if( known ) {
					if( count != w.size ) {
						throw new ZipException(path + " is stored with a size of " + w.size + ", but " + count + " bytes were written");
					}
					if( crc.getValue() != w.crc ) {
						throw new ZipException(path + " is stored with a CRC-32 of " + Long.toHexString(w.crc)
								+ ", but what was written has " + Long.toHexString(crc.getValue()));
					}
				} else {
					w.crc = crc.getValue();
					w.size = count;
					w.csize = csize;
					boolean wide = w.size >= ZIP64_MAGIC || w.csize >= ZIP64_MAGIC;
					byte[] d = new byte[wide ? 24 : 16];
					put32(d, 0, DATA_DESCRIPTOR_SIG);
					put32(d, 4, w.crc);
					if( wide ) {
						put64(d, 8, w.csize);
						put64(d, 16, w.size);
					} else {
						put32(d, 8, w.csize);
						put32(d, 12, w.size);
					}
					out.write(d);
				}
			} finally {
				end();
			}
		}
	}

	/** counts what goes through, for the offsets; closing it closes the stream underneath */
	private static final class CountingOutputStream extends OutputStream {
		private final OutputStream out;
		long count;

		CountingOutputStream(OutputStream out) {
			this.out = out;
		}

		@Override
		public void write(int b) throws IOException {
			out.write(b);
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			count += len;
		}

		@Override
		public void flush() throws IOException {
			out.flush();
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
