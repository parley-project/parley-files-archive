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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * length bytes of a {@link SeekableSource} from start, as a stream, read in blocks. An end of the
 * source before length bytes is an EOFException. Closing it doesn't close the source.
 */
public final class SeekableSourceInputStream extends InputStream {
	private final SeekableSource source;
	private long position;
	private final long end;
	private final byte[] buf;
	private int bufPos;
	private int bufLen;
	private boolean closed;

/**
 * @param source where the bytes are
 * @param start where they start
 * @param length how many there are
 * @param bufferSize how much is read at a time
 */
public SeekableSourceInputStream(SeekableSource source, long start, long length, int bufferSize) {
		this.source = source;
		this.position = start;
		this.end = start + length;
		this.buf = new byte[(int) Math.max(1, Math.min(bufferSize, length))];
	}

	private boolean fill() throws IOException {
		if( closed ) {
			throw new IOException("Stream closed");
		}
		if( position >= end ) {
			return false;
		}
		int want = (int) Math.min(buf.length, end - position);
		int cnt = source.read(position, buf, 0, want);
		if( cnt < 0 ) {
			throw new EOFException("The archive ends " + (end - position) + " bytes before the entry does");
		}
		position += cnt;
		bufPos = 0;
		bufLen = cnt;
		return true;
	}

	@Override
	public int read() throws IOException {
		if( bufPos >= bufLen && !fill() ) {
			return -1;
		}
		return buf[bufPos++] & 0xFF;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, b.length);
		if( len == 0 ) {
			return 0;
		}
		if( bufPos >= bufLen ) {
			if( len >= buf.length && position < end && !closed ) {
				// straight into the caller's array
				int want = (int) Math.min(len, end - position);
				int cnt = source.read(position, b, off, want);
				if( cnt < 0 ) {
					throw new EOFException("The archive ends " + (end - position) + " bytes before the entry does");
				}
				position += cnt;
				return cnt;
			}
			if( !fill() ) {
				return -1;
			}
		}
		int cnt = Math.min(len, bufLen - bufPos);
		System.arraycopy(buf, bufPos, b, off, cnt);
		bufPos += cnt;
		return cnt;
	}

	@Override
	public long skip(long n) throws IOException {
		if( n <= 0 ) {
			return 0;
		}
		long inBuf = bufLen - bufPos;
		if( n <= inBuf ) {
			bufPos += (int) n;
			return n;
		}
		long more = Math.min(n - inBuf, end - position);
		bufPos = bufLen = 0;
		position += more;
		return inBuf + more;
	}

	@Override
	public int available() {
		return bufLen - bufPos;
	}

	@Override
	public void close() {
		closed = true;
	}
}
