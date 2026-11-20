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
package us.bringardner.parley.files.archive;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.archive.format.SeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSources;

/**
 * A FileSource's bytes as a {@link SeekableSource}: what an archive is read from.
 * <p>
 * {@link FileSource#getSeekableInputStream()} is used where the file system has it. Where it
 * throws UnsupportedOperationException, or an IOException for a file that is there (parley-files-vfs
 * for a scheme without random access), the bytes are read with
 * {@link FileSource#getInputStream(long)}: a stream from the position wanted, kept open while
 * reads go forward, and opened again when one goes back. Either way the last
 * {@value #BLOCK_SIZE} bytes read are kept, so an archive's small headers cost one read each block
 * rather than one each over a remote protocol.
 */
public final class FileSourceSources {

	/** the block kept for small reads */
	public static final int BLOCK_SIZE = 64 * 1024;

	/** how far forward a positioned stream reads to get somewhere, rather than opening a new one */
	static final long MAX_SKIP = 256 * 1024;

	private FileSourceSources() {
	}

	/**
	 * @return file's bytes, open until the source is closed
	 * @throws IOException if it can't be opened
	 */
	public static SeekableSource open(FileSource file) throws IOException {
		Objects.requireNonNull(file, "file");
		SeekableSource raw;
		ISeekableInputStream in;
		try {
			in = file.getSeekableInputStream();
		} catch (UnsupportedOperationException e) {
			in = null;
		} catch (IOException e) {
			// some file systems have random access only for some files (parley-files-vfs, by scheme):
			// read with positioned streams, unless the file isn't there to read at all
			if( !file.exists() ) {
				throw e;
			}
			in = null;
		}
		raw = in != null ? SeekableSources.of(in) : new PositionedStreams(file);
		return SeekableSources.buffered(raw, BLOCK_SIZE);
	}

	/** Reads with getInputStream(position). */
	static final class PositionedStreams implements SeekableSource {
		private final FileSource file;
		private long length = -1;
		private InputStream in;
		private long inPos;
		private boolean closed;

		PositionedStreams(FileSource file) {
			this.file = file;
		}

		@Override
		public synchronized long length() throws IOException {
			if( length < 0 ) {
				length = file.contentLength();
			}
			return length;
		}

		@Override
		public synchronized int read(long position, byte[] b, int off, int len) throws IOException {
			Objects.checkFromIndexSize(off, len, b.length);
			if( closed ) {
				throw new IOException("Closed");
			}
			if( position >= length() ) {
				return -1;
			}
			if( len == 0 ) {
				return 0;
			}
			if( in == null || position < inPos || position - inPos > MAX_SKIP ) {
				closeStream();
				in = file.getInputStream(position);
				inPos = position;
			}
			while( inPos < position ) {
				long skipped = in.skip(position - inPos);
				if( skipped <= 0 ) {
					if( in.read() < 0 ) {
						return -1;
					}
					skipped = 1;
				}
				inPos += skipped;
			}
			int cnt = in.read(b, off, len);
			if( cnt > 0 ) {
				inPos += cnt;
			}
			return cnt;
		}

		private void closeStream() throws IOException {
			if( in != null ) {
				InputStream c = in;
				in = null;
				c.close();
			}
		}

		@Override
		public synchronized void close() throws IOException {
			closed = true;
			closeStream();
		}
	}
}
