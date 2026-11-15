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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import us.bringardner.parley.files.archive.compress.Compression;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveReader;
import us.bringardner.parley.files.archive.format.ArchiveStreamReader;
import us.bringardner.parley.files.archive.format.ArchiveWriter;
import us.bringardner.parley.files.archive.format.SeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSourceInputStream;

/**
 * The tar format, plain or gzipped: {@link TarReader}, {@link TarStreamReader} and
 * {@link TarWriter} (which writes plain tar; give it a gzip stream for .tar.gz).
 */
public final class TarFormat implements ArchiveFormat {

	public static final String NAME = "tar";

	static final TarFormat INSTANCE = new TarFormat();

	private static final List<String> EXTENSIONS = List.of("tar", "tar.gz", "tgz");

	/** for ServiceLoader */
	public TarFormat() {
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public List<String> getExtensions() {
		return EXTENSIONS;
	}

	/**
	 * A tar archive starts with a header whose checksum is right (a gzipped one, once its first
	 * 512 bytes are decompressed), or with two zero blocks (an empty archive).
	 */
	@Override
	public boolean probe(SeekableSource source) throws IOException {
		long len = source.length();
		if( len < TarScanner.BLOCK ) {
			return false;
		}
		byte[] h = new byte[TarScanner.BLOCK];
		int got;
		Compression c = TarReader.compressionOf(source);
		if( c != null ) {
			try (InputStream in = c.decompress(new SeekableSourceInputStream(source, 0, len, 8192))) {
				got = in.readNBytes(h, 0, h.length);
			} catch (IOException e) {
				// damaged, or not gzip after all
				return false;
			}
		} else {
			got = 0;
			while( got < h.length ) {
				int cnt = source.read(got, h, got, h.length - got);
				if( cnt < 0 ) {
					break;
				}
				got += cnt;
			}
		}
		if( got < TarScanner.BLOCK ) {
			return false;
		}
		boolean zero = true;
		for(byte b : h) {
			if( b != 0 ) {
				zero = false;
				break;
			}
		}
		if( zero ) {
			return c == null && len % TarScanner.BLOCK == 0 && len >= 2 * TarScanner.BLOCK;
		}
		return TarScanner.checksumOk(h);
	}

	@Override
	public ArchiveReader openReader(SeekableSource source) throws IOException {
		return TarReader.open(this, source);
	}

	@Override
	public ArchiveStreamReader openStreamReader(InputStream in) throws IOException {
		return new TarStreamReader(in);
	}

	/** a plain tar; give it a gzip stream ({@link Compression#compress(OutputStream)}) for .tar.gz */
	@Override
	public ArchiveWriter openWriter(OutputStream out) throws IOException {
		return new TarWriter(out);
	}

	@Override
	public String toString() {
		return NAME;
	}
}
