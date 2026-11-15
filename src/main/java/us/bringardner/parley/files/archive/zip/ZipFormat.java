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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveReader;
import us.bringardner.parley.files.archive.format.ArchiveStreamReader;
import us.bringardner.parley.files.archive.format.ArchiveWriter;
import us.bringardner.parley.files.archive.format.SeekableSource;

/**
 * The zip format (PKWARE APPNOTE.TXT): {@link ZipReader}, {@link ZipStreamReader} and
 * {@link ZipWriter}. Jar, war, ear and the Office and OpenDocument files are zip archives too.
 */
public final class ZipFormat implements ArchiveFormat {

	public static final String NAME = "zip";

	/** the one the ServiceLoader doesn't make (the reader's default) */
	static final ZipFormat INSTANCE = new ZipFormat();

	private static final List<String> EXTENSIONS = List.of("zip", "jar", "war", "ear", "apk", "aar",
			"docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "nupkg", "vsix", "whl", "xpi", "kmz");

	/** for ServiceLoader */
	public ZipFormat() {
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
	 * A zip archive starts with a local header (or the empty archive's end record, or the
	 * spanned-archive marker), or, when something is in front of it, has an end record at its end.
	 */
	@Override
	public boolean probe(SeekableSource source) throws IOException {
		long len = source.length();
		if( len < ZipConstants.EOCD_SIZE ) {
			return false;
		}
		byte[] head = source.readFully(0, 4);
		int sig = ZipConstants.sig(head, 0);
		if( sig == ZipConstants.LOCAL_HEADER_SIG || sig == ZipConstants.EOCD_SIG || sig == ZipConstants.DATA_DESCRIPTOR_SIG ) {
			return true;
		}
		try {
			return ZipReader.findEnd(source, len) != null;
		} catch (java.util.zip.ZipException e) {
			// an end record that doesn't add up: not one we can read
			return false;
		}
	}

	@Override
	public ArchiveReader openReader(SeekableSource source) throws IOException {
		return ZipReader.open(this, source);
	}

	@Override
	public ArchiveStreamReader openStreamReader(InputStream in) throws IOException {
		return new ZipStreamReader(in);
	}

	@Override
	public ArchiveWriter openWriter(OutputStream out) throws IOException {
		return new ZipWriter(out);
	}

	@Override
	public String toString() {
		return NAME;
	}
}
