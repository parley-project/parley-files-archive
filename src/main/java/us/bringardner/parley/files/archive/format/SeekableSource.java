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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;

/**
 * Bytes that can be read at any position: what an archive reader needs for random access.
 * <p>
 * Reads are positional (there is no file pointer), so several entries of one archive can be
 * read at the same time from one source. Implementations must allow that: a source that has a
 * file pointer underneath synchronizes its seek and read.
 * <p>
 * {@link SeekableSources} makes one from a byte array, a local file or a parley-files
 * {@link us.bringardner.parley.files.ISeekableInputStream}.
 */
public interface SeekableSource extends Closeable {

	/**
	 * @return the number of bytes
	 * @throws IOException if it can't be found
	 */
	long length() throws IOException;

	/**
	 * Reads up to len bytes at position into b[off..].
	 *
	 * @param position where to start, 0 or more
	 * @return the number of bytes read (at least 1 when len is more than 0), or -1 if position is
	 *         at or past the end
	 * @throws IOException if they can't be read
	 */
	int read(long position, byte[] b, int off, int len) throws IOException;

	/**
	 * Reads exactly len bytes at position into b[off..].
	 *
	 * @throws EOFException if the source ends first
	 * @throws IOException if they can't be read
	 */
	default void readFully(long position, byte[] b, int off, int len) throws IOException {
		int done = 0;
		while( done < len ) {
			int cnt = read(position + done, b, off + done, len - done);
			if( cnt < 0 ) {
				throw new EOFException("Wanted " + len + " bytes at " + position + ", the source ends after " + done);
			}
			done += cnt;
		}
	}

	/**
	 * @return len bytes read at position
	 * @throws EOFException if the source ends first
	 */
	default byte[] readFully(long position, int len) throws IOException {
		byte[] ret = new byte[len];
		readFully(position, ret, 0, len);
		return ret;
	}
}
