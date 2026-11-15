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

/** An archive entry's bytes as parley-files' seekable stream. Closing it closes the archive. */
final class ArchiveSeekableInputStream implements ISeekableInputStream {

	private final FileSource file;
	private final SeekableSource source;
	private long pointer;
	private boolean closed;

	ArchiveSeekableInputStream(FileSource file, SeekableSource source) {
		this.file = file;
		this.source = source;
	}

	private void checkOpen() throws IOException {
		if( closed ) {
			throw new IOException("Stream closed");
		}
	}

	@Override
	public synchronized long length() throws IOException {
		checkOpen();
		return source.length();
	}

	@Override
	public synchronized void seek(long pos) throws IOException {
		checkOpen();
		if( pos < 0 ) {
			throw new IOException("Negative seek offset: " + pos);
		}
		pointer = pos;
	}

	@Override
	public synchronized int read() throws IOException {
		byte[] one = new byte[1];
		return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
	}

	@Override
	public synchronized int read(byte[] data, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, data.length);
		checkOpen();
		if( len == 0 ) {
			return 0;
		}
		int cnt = source.read(pointer, data, off, len);
		if( cnt > 0 ) {
			pointer += cnt;
		}
		return cnt;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	@Override
	public synchronized long getFilePointer() throws IOException {
		checkOpen();
		return pointer;
	}

	@Override
	public FileSource getFile() {
		return file;
	}

	/** @return the bytes from the file pointer on, as a stream that moves it */
	@Override
	public InputStream getInputStream() {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				return ArchiveSeekableInputStream.this.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return ArchiveSeekableInputStream.this.read(b, off, len);
			}

			@Override
			public void close() throws IOException {
				ArchiveSeekableInputStream.this.close();
			}
		};
	}

	@Override
	public synchronized void close() throws IOException {
		if( !closed ) {
			closed = true;
			source.close();
		}
	}
}
