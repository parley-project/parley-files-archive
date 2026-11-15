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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Objects;
import java.util.zip.ZipException;

import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveStreamReader;

/**
 * Reads a zip archive front to back from a plain stream, by its local headers, for a source that
 * can't seek.
 * <p>
 * A deflated entry marks its own end, so it is read reliably even when its sizes come after it
 * (a data descriptor). A stored entry with a data descriptor has no size to go by, so it can't
 * be read this way: {@link #nextEntry()} throws a ZipException for it. What the central
 * directory alone holds (Unix modes, comments) isn't known, and an archive that was appended to,
 * or has bytes in front of it, can read differently than {@link ZipReader} reads it.
 * Encrypted entries and methods other than stored and deflated fail when they are reached.
 */
public final class ZipStreamReader implements ArchiveStreamReader {

	private final PushbackInputStream in;
	private ZipArchiveEntry current;
	private EntryStreams.CheckedInputStream currentStream;
	private EntryStreams.InflatingInputStream currentInflater;
	/** the current entry's compressed bytes, as they are read (stored entries) */
	private CountingInputStream currentRaw;
	private boolean currentHasDescriptor;
	private boolean currentZip64;
	private boolean ended;
	private boolean first = true;

	/** @param in the archive; closed when the reader is */
	public ZipStreamReader(InputStream in) {
		this.in = new PushbackInputStream(Objects.requireNonNull(in, "in"), EntryStreams.BUFFER_SIZE);
	}

	@Override
	public ArchiveEntry nextEntry() throws IOException {
		if( ended ) {
			return null;
		}
		if( current != null ) {
			finishCurrent();
		}
		byte[] h = new byte[LOCAL_HEADER_SIZE];
		int got = readUpTo(h, 0, 4);
		if( got == 0 ) {
			ended = true;
			return null;
		}
		if( got < 4 ) {
			throw new EOFException("The archive ends inside a header");
		}
		int sig = sig(h, 0);
		if( first && sig == DATA_DESCRIPTOR_SIG ) {
			// the "spanned archive" marker some writers put at the start
			first = false;
			return nextEntry();
		}
		first = false;
		if( sig == CENTRAL_HEADER_SIG || sig == EOCD_SIG || sig == ZIP64_EOCD_SIG ) {
			ended = true;
			return null;
		}
		if( sig != LOCAL_HEADER_SIG ) {
			throw new ZipException("Not a zip local header (signature " + Integer.toHexString(sig) + ")");
		}
		readFully(h, 4, LOCAL_HEADER_SIZE - 4);
		int flags = u16(h, 6);
		int method = u16(h, 8);
		long dos = u32(h, 10);
		long crc = u32(h, 14);
		long csize = u32(h, 18);
		long size = u32(h, 22);
		int nameLen = u16(h, 26);
		int extraLen = u16(h, 28);
		byte[] rawName = new byte[nameLen];
		readFully(rawName, 0, nameLen);
		byte[] extra = new byte[extraLen];
		readFully(extra, 0, extraLen);
		boolean utf8 = (flags & FLAG_UTF8) != 0;
		boolean descriptor = (flags & FLAG_DATA_DESCRIPTOR) != 0;
		ExtraFields x = ExtraFields.parse(extra, 0, extraLen, size == ZIP64_MAGIC, csize == ZIP64_MAGIC, false, utf8 ? null : rawName);
		if( size == ZIP64_MAGIC && x.size >= 0 ) {
			size = x.size;
		}
		if( csize == ZIP64_MAGIC && x.compressedSize >= 0 ) {
			csize = x.compressedSize;
		}
		String n = x.unicodePath != null ? x.unicodePath : ZipConstants.decode(rawName, 0, nameLen, utf8);
		String path = ArchiveEntry.normalize(n);
		boolean dir = n.endsWith("/");
		ZipArchiveEntry.Builder b = ZipArchiveEntry.builder(path.isEmpty() ? "." : path).directory(dir)
				.lastModified(x.modified != ArchiveEntry.UNKNOWN ? x.modified : dosToMillis(dos));
		b.rawName = dir ? n.substring(0, n.length() - 1) : n;
		b.method = method;
		b.flags = flags;
		b.versionMadeBy = 0;
		b.crc = descriptor ? ArchiveEntry.UNKNOWN : crc;
		b.size = descriptor ? ArchiveEntry.UNKNOWN : size;
		b.compressedSize = descriptor ? ArchiveEntry.UNKNOWN : csize;
		b.lastAccessTime = x.accessed;
		b.creationTime = x.created;
		current = b.build();
		currentHasDescriptor = descriptor;
		currentZip64 = x.hasZip64;
		currentInflater = null;
		currentRaw = null;
		if( !dir ) {
			EntryStreams.checkReadable(current);
			if( method == ZipArchiveEntry.STORED && descriptor ) {
				throw new ZipException(path + " is stored with its size after its data, so it can't be read as a stream: "
						+ "read the archive with random access (ZipReader)");
			}
		}
		InputStream data;
		if( dir && !descriptor && csize == 0 ) {
			data = EntryStreams.empty();
		} else if( method == ZipArchiveEntry.STORED ) {
			currentRaw = new CountingInputStream(new Bounded(in, csize));
			data = currentRaw;
		} else {
			currentRaw = new CountingInputStream(descriptor ? new Unclosable(in) : new Bounded(in, csize));
			currentInflater = new EntryStreams.InflatingInputStream(currentRaw, EntryStreams.BUFFER_SIZE);
			data = currentInflater;
		}
		currentStream = new EntryStreams.CheckedInputStream(data, path, current.getSize(), current.getCrc());
		if( path.isEmpty() ) {
			// "/" or "./": the top folder; skip it
			return nextEntry();
		}
		return current;
	}

	/** reads what is left of the current entry, and its data descriptor */
	private void finishCurrent() throws IOException {
		byte[] skip = new byte[8192];
		while( currentStream.read(skip, 0, skip.length) >= 0 ) {
			// the CheckedInputStream checks what the local header said, if it said
		}
		long csize = currentInflater != null ? currentInflater.compressedCount() : currentRaw == null ? 0 : currentRaw.count;
		if( currentInflater != null ) {
			if( currentHasDescriptor ) {
				// the inflater read ahead of the deflated data's end: the descriptor is in what it read
				currentInflater.pushBackUnused(in);
			} else {
				// the local header's compressed size is where the entry ends, whatever the data does
				while( currentRaw.read(skip, 0, skip.length) >= 0 ) {
					// skipped
				}
			}
			currentInflater.close();
		}
		if( currentHasDescriptor ) {
			long size = currentStream.count();
			byte[] d = new byte[24];
			readFully(d, 0, 4);
			int at = 0;
			if( sig(d, 0) == DATA_DESCRIPTOR_SIG ) {
				readFully(d, 4, 4);
				at = 4;
			}
			long crc = u32(d, at);
			// 8-byte sizes when the entry is ZIP64 (as the JDK reads them: a local ZIP64 field, or sizes that need it)
			boolean wide = currentZip64 || csize >= ZIP64_MAGIC || size >= ZIP64_MAGIC;
			byte[] s = new byte[wide ? 16 : 8];
			readFully(s, 0, s.length);
			long dCsize = wide ? u64(s, 0) : u32(s, 0);
			long dSize = wide ? u64(s, 8) : u32(s, 4);
			if( crc != currentStream.crc() ) {
				throw new ZipException(current.getPath() + " is damaged: its CRC-32 is " + Long.toHexString(currentStream.crc())
						+ ", not " + Long.toHexString(crc));
			}
			if( dSize != size || dCsize != csize ) {
				throw new ZipException(current.getPath() + "'s data descriptor says " + dSize + " bytes (" + dCsize
						+ " compressed), not " + size + " (" + csize + ")");
			}
		}
		current = null;
		currentStream = null;
	}

	@Override
	public InputStream getInputStream() {
		if( current == null ) {
			throw new IllegalStateException("No entry: call nextEntry() first");
		}
		EntryStreams.CheckedInputStream s = currentStream;
		// closing what the caller has doesn't end the entry or the reader
		return new InputStream() {
			@Override
			public int read() throws IOException {
				return s.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return s.read(b, off, len);
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void close() throws IOException {
		ended = true;
		if( currentInflater != null ) {
			currentInflater.close();
		}
		in.close();
	}

	private int readUpTo(byte[] b, int off, int len) throws IOException {
		int done = 0;
		while( done < len ) {
			int cnt = in.read(b, off + done, len - done);
			if( cnt < 0 ) {
				break;
			}
			done += cnt;
		}
		return done;
	}

	private void readFully(byte[] b, int off, int len) throws IOException {
		if( readUpTo(b, off, len) < len ) {
			throw new EOFException("The archive ends inside " + (current == null ? "a header" : current.getPath()));
		}
	}

	/** n bytes of in, then the end; closing it doesn't close in */
	private static final class Bounded extends InputStream {
		private final InputStream in;
		private long left;

		Bounded(InputStream in, long n) {
			this.in = in;
			this.left = n;
		}

		@Override
		public int read() throws IOException {
			if( left <= 0 ) {
				return -1;
			}
			int v = in.read();
			if( v < 0 ) {
				throw new EOFException("The archive ends inside an entry");
			}
			left--;
			return v;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if( left <= 0 ) {
				return -1;
			}
			int cnt = in.read(b, off, (int) Math.min(len, left));
			if( cnt < 0 ) {
				throw new EOFException("The archive ends inside an entry");
			}
			left -= cnt;
			return cnt;
		}

		@Override
		public void close() {
		}
	}

	private static final class Unclosable extends InputStream {
		private final InputStream in;

		Unclosable(InputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			return in.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return in.read(b, off, len);
		}

		@Override
		public void close() {
		}
	}

	private static final class CountingInputStream extends InputStream {
		private final InputStream in;
		long count;

		CountingInputStream(InputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			int v = in.read();
			if( v >= 0 ) {
				count++;
			}
			return v;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int cnt = in.read(b, off, len);
			if( cnt > 0 ) {
				count += cnt;
			}
			return cnt;
		}

		@Override
		public void close() {
		}
	}
}
