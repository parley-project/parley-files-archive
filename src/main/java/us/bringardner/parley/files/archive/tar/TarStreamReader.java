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
package us.bringardner.parley.files.archive.tar;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import us.bringardner.parley.files.archive.compress.Compression;
import us.bringardner.parley.files.archive.format.ArchiveStreamReader;

/**
 * Reads a tar archive front to back from a plain stream; a gzipped one is recognized by its
 * first bytes and decompressed. Every dialect {@link TarScanner} reads.
 * <p>
 * An entry's stream ({@link #getInputStream()}) holds a regular file's bytes, a symbolic link's
 * target's name, and nothing for anything else (a hard link's bytes are earlier in the stream,
 * gone by now). A sparse file's stream fails.
 */
public final class TarStreamReader implements ArchiveStreamReader {

	private final InputStream in;
	private final TarReader.ForwardInput input;
	private final TarScanner scanner;
	private TarArchiveEntry current;
	private InputStream currentStream;

	/** @param in the archive, plain or gzipped; closed when the reader is */
	public TarStreamReader(InputStream in) throws IOException {
		this.in = Compression.decompressIfCompressed(Objects.requireNonNull(in, "in"));
		this.input = new TarReader.ForwardInput(this.in);
		this.scanner = new TarScanner(input);
	}

	@Override
	public TarArchiveEntry nextEntry() throws IOException {
		current = scanner.next();
		currentStream = null;
		return current;
	}

	@Override
	public InputStream getInputStream() {
		if( current == null ) {
			throw new IllegalStateException("No entry: call nextEntry() first");
		}
		if( currentStream == null ) {
			currentStream = open(current);
		}
		return currentStream;
	}

	private InputStream open(TarArchiveEntry e) {
		if( e.isSymbolicLink() ) {
			return new ByteArrayInputStream(e.getLinkName().getBytes(StandardCharsets.UTF_8));
		}
		if( e.isDirectory() || !e.isRegularFile() ) {
			return InputStream.nullInputStream();
		}
		if( e.isSparse() ) {
			return new InputStream() {
				@Override
				public int read() throws IOException {
					throw new IOException(e.getPath() + " is a sparse file, which isn't supported");
				}
			};
		}
		long start = e.dataOffset;
		long end = start + e.storedSize;
		// reads through the scanner's input, so the next header is found after whatever was read
		return new InputStream() {
			private final byte[] one = new byte[1];

			@Override
			public int read() throws IOException {
				return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if( current != e ) {
					throw new IOException("The entry " + e.getPath() + " has been passed");
				}
				long at = Math.max(input.pos, start);
				if( at >= end ) {
					return -1;
				}
				if( len == 0 ) {
					return 0;
				}
				int cnt = input.read(at, b, off, (int) Math.min(len, end - at));
				if( cnt <= 0 ) {
					throw new EOFException("The tar archive ends inside " + e.getPath());
				}
				return cnt;
			}

			@Override
			public void close() {
				// the reader's stream stays open; what is left is skipped by the next nextEntry()
			}
		};
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
