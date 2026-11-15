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
package us.bringardner.parley.files.archive.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import us.bringardner.parley.files.ISeekableInputStream;

/** Ways to make a {@link SeekableSource}. */
public final class SeekableSources {

	private SeekableSources() {
	}

	/** @return the bytes of data (not copied); closing it does nothing */
	public static SeekableSource of(byte[] data) {
		Objects.requireNonNull(data, "data");
		return new SeekableSource() {
			@Override
			public long length() {
				return data.length;
			}

			@Override
			public int read(long position, byte[] b, int off, int len) {
				Objects.checkFromIndexSize(off, len, b.length);
				if( position >= data.length ) {
					return -1;
				}
				int cnt = (int) Math.min(len, data.length - position);
				System.arraycopy(data, (int) position, b, off, cnt);
				return cnt;
			}

			@Override
			public void close() {
			}
		};
	}

	/**
	 * @return a local file, open for reading until the source is closed
	 * @throws IOException if it can't be opened
	 */
	public static SeekableSource of(Path file) throws IOException {
		FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
		return new SeekableSource() {
			@Override
			public long length() throws IOException {
				return channel.size();
			}

			@Override
			public int read(long position, byte[] b, int off, int len) throws IOException {
				Objects.checkFromIndexSize(off, len, b.length);
				if( len == 0 ) {
					return 0;
				}
				// positional: FileChannel allows reads at different positions at once
				int cnt = channel.read(ByteBuffer.wrap(b, off, len), position);
				return cnt == 0 ? -1 : cnt;
			}

			@Override
			public void close() throws IOException {
				channel.close();
			}
		};
	}

	/**
	 * @param in a parley-files seekable stream; closed when the source is
	 * @return in as a source: each read is a seek and a read, done together under in's lock, so
	 *         reads at different positions can be made at once. Its length is asked once.
	 */
	public static SeekableSource of(ISeekableInputStream in) {
		Objects.requireNonNull(in, "in");
		return new SeekableSource() {
			/** asked once: over a remote protocol each ask can be a round trip */
			private long length = -1;

			@Override
			public long length() throws IOException {
				synchronized (in) {
					if( length < 0 ) {
						length = in.length();
					}
					return length;
				}
			}

			@Override
			public int read(long position, byte[] b, int off, int len) throws IOException {
				Objects.checkFromIndexSize(off, len, b.length);
				if( len == 0 ) {
					return 0;
				}
				synchronized (in) {
					if( position >= length() ) {
						return -1;
					}
					if( in.getFilePointer() != position ) {
						in.seek(position);
					}
					return in.read(b, off, len);
				}
			}

			@Override
			public void close() throws IOException {
				in.close();
			}
		};
	}

	/**
	 * @return length bytes of source starting at start, as a source of their own; closing it does
	 *         not close source
	 */
	public static SeekableSource range(SeekableSource source, long start, long length) {
		Objects.requireNonNull(source, "source");
		if( start < 0 || length < 0 ) {
			throw new IllegalArgumentException("start=" + start + " length=" + length);
		}
		return new SeekableSource() {
			@Override
			public long length() {
				return length;
			}

			@Override
			public int read(long position, byte[] b, int off, int len) throws IOException {
				Objects.checkFromIndexSize(off, len, b.length);
				if( position >= length ) {
					return -1;
				}
				if( len == 0 ) {
					return 0;
				}
				int want = (int) Math.min(len, length - position);
				return source.read(start + position, b, off, want);
			}

			@Override
			public void close() {
			}
		};
	}

	/**
	 * A source that keeps the last block it read, for a source where each read costs a round
	 * trip (a remote file): an archive's small headers then cost one read per block rather than
	 * one each. Reads of a block or more go straight to source.
	 *
	 * @param blockSize the size of the block kept, at least 512
	 * @return source with a one-block cache; closing it closes source
	 */
	public static SeekableSource buffered(SeekableSource source, int blockSize) {
		Objects.requireNonNull(source, "source");
		if( blockSize < 512 ) {
			throw new IllegalArgumentException("blockSize=" + blockSize);
		}
		return new SeekableSource() {
			private final byte[] block = new byte[blockSize];
			/** where block starts, -1: nothing in it */
			private long blockStart = -1;
			private int blockLen;

			@Override
			public long length() throws IOException {
				return source.length();
			}

			@Override
			public synchronized int read(long position, byte[] b, int off, int len) throws IOException {
				Objects.checkFromIndexSize(off, len, b.length);
				if( len == 0 ) {
					return 0;
				}
				if( blockStart >= 0 && position >= blockStart && position < blockStart + blockLen ) {
					int at = (int) (position - blockStart);
					int cnt = Math.min(len, blockLen - at);
					System.arraycopy(block, at, b, off, cnt);
					return cnt;
				}
				if( len >= blockSize ) {
					return source.read(position, b, off, len);
				}
				int got = 0;
				while( got < blockSize ) {
					int cnt = source.read(position + got, block, got, blockSize - got);
					if( cnt < 0 ) {
						break;
					}
					got += cnt;
				}
				if( got == 0 ) {
					blockStart = -1;
					return -1;
				}
				blockStart = position;
				blockLen = got;
				int cnt = Math.min(len, got);
				System.arraycopy(block, 0, b, off, cnt);
				return cnt;
			}

			@Override
			public void close() throws IOException {
				source.close();
			}
		};
	}
}
