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
import java.util.Objects;

import us.bringardner.parley.files.archive.format.SeekableSource;

/**
 * Random access to a deflated entry's uncompressed bytes. DEFLATE can't be entered in the
 * middle, so:
 * <ul>
 * <li>an entry of up to {@link #getMemoryLimit()} bytes is inflated into memory on the first read,
 * and read from there (a nested archive's index is read from its end, then its start);</li>
 * <li>a larger one is inflated as reads move forward, and from its start again whenever a read
 * goes backwards.</li>
 * </ul>
 * The limit is the system property {@value #MEMORY_LIMIT_PROPERTY} (bytes), default
 * {@value #DEFAULT_MEMORY_LIMIT}.
 */
final class InflatingSeekableSource implements SeekableSource {

	public static final String MEMORY_LIMIT_PROPERTY = "parley.files.archive.inflateInMemoryLimit";
	public static final long DEFAULT_MEMORY_LIMIT = 16L * 1024 * 1024;

	/** Opens the entry's inflated bytes from their start. */
	interface Opener {
		InputStream open() throws IOException;
	}

	private final Opener opener;
	private final long size;
	private byte[] all;
	private InputStream current;
	private long currentPos;
	private boolean closed;

	InflatingSeekableSource(Opener opener, long size) {
		this.opener = Objects.requireNonNull(opener, "opener");
		this.size = size;
	}

	static long getMemoryLimit() {
		String v = System.getProperty(MEMORY_LIMIT_PROPERTY);
		if( v != null ) {
			try {
				return Long.parseLong(v.trim());
			} catch (NumberFormatException e) {
				// the default
			}
		}
		return DEFAULT_MEMORY_LIMIT;
	}

	@Override
	public long length() {
		return size;
	}

	@Override
	public synchronized int read(long position, byte[] b, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, b.length);
		if( closed ) {
			throw new IOException("Closed");
		}
		if( position >= size ) {
			return -1;
		}
		if( len == 0 ) {
			return 0;
		}
		if( all == null && size <= getMemoryLimit() ) {
			all = new byte[(int) size];
			try (InputStream in = opener.open()) {
				int done = 0;
				while( done < size ) {
					int cnt = in.read(all, done, (int) size - done);
					if( cnt < 0 ) {
						all = null;
						throw new EOFException("The entry inflates to " + done + " bytes, not its size, " + size);
					}
					done += cnt;
				}
			}
		}
		if( all != null ) {
			int cnt = (int) Math.min(len, size - position);
			System.arraycopy(all, (int) position, b, off, cnt);
			return cnt;
		}
		if( current == null || position < currentPos ) {
			closeCurrent();
			current = opener.open();
			currentPos = 0;
		}
		while( currentPos < position ) {
			long skipped = current.skip(position - currentPos);
			if( skipped <= 0 ) {
				if( current.read() < 0 ) {
					return -1;
				}
				skipped = 1;
			}
			currentPos += skipped;
		}
		int cnt = current.read(b, off, (int) Math.min(len, size - position));
		if( cnt > 0 ) {
			currentPos += cnt;
		}
		return cnt;
	}

	private void closeCurrent() throws IOException {
		if( current != null ) {
			InputStream c = current;
			current = null;
			c.close();
		}
	}

	@Override
	public synchronized void close() throws IOException {
		closed = true;
		all = null;
		closeCurrent();
	}
}
