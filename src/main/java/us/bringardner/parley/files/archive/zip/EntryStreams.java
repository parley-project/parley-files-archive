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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.SeekableSource;

/** The streams that read an entry's bytes. */
final class EntryStreams {

	static final int BUFFER_SIZE = 64 * 1024;

	private EntryStreams() {
	}

	/**
	 * Raw DEFLATE (RFC 1951) from in. When the deflated data ends, the bytes read from in past its
	 * end can be pushed back ({@link #pushBackUnused(PushbackInputStream)}).
	 */
	static final class InflatingInputStream extends InputStream {
		private final InputStream in;
		private final Inflater inflater = new Inflater(true);
		private final byte[] buf;
		private int bufLen;
		private boolean dummyGiven;
		private boolean closed;
		private final byte[] one = new byte[1];

		InflatingInputStream(InputStream in, int bufferSize) {
			this.in = in;
			this.buf = new byte[bufferSize];
		}

		@Override
		public int read() throws IOException {
			return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			Objects.checkFromIndexSize(off, len, b.length);
			if( closed ) {
				throw new IOException("Stream closed");
			}
			if( len == 0 ) {
				return 0;
			}
			try {
				while( true ) {
					if( inflater.finished() ) {
						return -1;
					}
					int cnt = inflater.inflate(b, off, len);
					if( cnt > 0 ) {
						return cnt;
					}
					if( inflater.needsDictionary() ) {
						throw new ZipException("Deflated data that needs a preset dictionary");
					}
					if( inflater.finished() ) {
						return -1;
					}
					if( inflater.needsInput() ) {
						bufLen = in.read(buf, 0, buf.length);
						if( bufLen < 0 ) {
							bufLen = 0;
							if( dummyGiven ) {
								throw new EOFException("The deflated data ends early");
							}
							// zlib can need one byte more than the deflated data has (as the JDK's ZipFile gives it)
							dummyGiven = true;
							buf[0] = 0;
							inflater.setInput(buf, 0, 1);
						} else {
							inflater.setInput(buf, 0, bufLen);
						}
					}
				}
			} catch (DataFormatException e) {
				ZipException z = new ZipException("Damaged deflated data: " + e.getMessage());
				z.initCause(e);
				throw z;
			}
		}

		/** @return how many compressed bytes were taken (what the inflater used) */
		long compressedCount() {
			return inflater.getBytesRead();
		}

		/** After the end: puts the bytes read past the deflated data back into in. */
		void pushBackUnused(PushbackInputStream into) throws IOException {
			int rem = inflater.getRemaining();
			if( rem > 0 && !dummyGiven ) {
				into.unread(buf, bufLen - rem, rem);
			}
		}

		@Override
		public void close() {
			if( !closed ) {
				closed = true;
				inflater.end();
			}
		}
	}

	/**
	 * An entry's uncompressed bytes, checked at the end: the size and the CRC-32 must be what the
	 * archive says (when it says). Closing it closes the stream underneath.
	 */
	static final class CheckedInputStream extends InputStream {
		private final InputStream in;
		private final String name;
		private final long size;
		private final long crcExpected;
		private final CRC32 crc = new CRC32();
		private long count;
		private boolean checked;
		private boolean closed;

		/**
		 * @param size the size the archive says, or ArchiveEntry.UNKNOWN
		 * @param crc the CRC the archive says, or ArchiveEntry.UNKNOWN
		 */
		CheckedInputStream(InputStream in, String name, long size, long crc) {
			this.in = in;
			this.name = name;
			this.size = size;
			this.crcExpected = crc;
		}

		private final byte[] one = new byte[1];

		@Override
		public int read() throws IOException {
			return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if( closed ) {
				throw new IOException("Stream closed");
			}
			int cnt = in.read(b, off, len);
			if( cnt > 0 ) {
				crc.update(b, off, cnt);
				count += cnt;
				if( size >= 0 && count > size ) {
					throw new ZipException(name + " has more bytes than its size, " + size);
				}
			} else if( cnt < 0 ) {
				check();
			}
			return cnt;
		}

		private void check() throws ZipException {
			if( checked ) {
				return;
			}
			checked = true;
			if( size >= 0 && count != size ) {
				throw new ZipException(name + " has " + count + " bytes, not its size, " + size);
			}
			if( crcExpected >= 0 && crc.getValue() != crcExpected ) {
				throw new ZipException(name + " is damaged: its CRC-32 is " + Long.toHexString(crc.getValue())
						+ ", not " + Long.toHexString(crcExpected));
			}
		}

		long count() {
			return count;
		}

		long crc() {
			return crc.getValue();
		}

		@Override
		public long skip(long n) throws IOException {
			// read, so the CRC still covers every byte
			byte[] skip = new byte[(int) Math.min(8192, Math.max(1, n))];
			long done = 0;
			while( done < n ) {
				int cnt = read(skip, 0, (int) Math.min(skip.length, n - done));
				if( cnt < 0 ) {
					break;
				}
				done += cnt;
			}
			return done;
		}

		@Override
		public void close() throws IOException {
			if( !closed ) {
				closed = true;
				in.close();
			}
		}
	}

	/** @return a message naming what can't be read: a method or encryption this reader doesn't do */
	static void checkReadable(ZipArchiveEntry e) throws ZipException {
		if( e.isEncrypted() ) {
			throw new ZipException(e.getPath() + " is encrypted, which isn't supported");
		}
		int m = e.getMethod();
		if( m != ZipArchiveEntry.STORED && m != ZipArchiveEntry.DEFLATED ) {
			throw new ZipException(e.getPath() + " is compressed with method " + m + " (" + methodName(m)
					+ "), which isn't supported: only stored (0) and deflated (8) are");
		}
	}

	static String methodName(int m) {
		switch (m) {
		case 1: return "shrunk";
		case 6: return "imploded";
		case 9: return "Deflate64";
		case 12: return "bzip2";
		case 14: return "LZMA";
		case 93: return "Zstandard";
		case 95: return "XZ";
		case 98: return "PPMd";
		case 99: return "AES encrypted";
		default: return "unknown";
		}
	}

	/** an entry with nothing to read (a folder) */
	static InputStream empty() {
		return InputStream.nullInputStream();
	}

	static boolean known(long v) {
		return v != ArchiveEntry.UNKNOWN;
	}
}
