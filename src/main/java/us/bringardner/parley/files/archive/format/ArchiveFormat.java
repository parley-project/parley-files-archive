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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * An archive format (zip, tar). Each is a service ({@code META-INF/services}), found by
 * {@link ArchiveFormats}.
 */
public interface ArchiveFormat {

	/** @return the format's name, lower case ("zip") */
	String getName();

	/** @return the file name extensions its archives usually have, lower case, without the dot */
	List<String> getExtensions();

	/**
	 * @return true if source holds an archive of this format, by its content (magic bytes, an
	 *         end record). Reads only a little.
	 * @throws IOException if source can't be read
	 */
	boolean probe(SeekableSource source) throws IOException;

	/**
	 * Opens an archive for random access, reading its index.
	 *
	 * @param source the archive's bytes; it stays the caller's to close
	 * @throws IOException if it isn't an archive of this format, or is damaged
	 */
	ArchiveReader openReader(SeekableSource source) throws IOException;

	/**
	 * Opens an archive to read front to back.
	 *
	 * @param in the archive's bytes; closed when the reader is
	 */
	ArchiveStreamReader openStreamReader(InputStream in) throws IOException;

	/**
	 * Starts writing an archive.
	 *
	 * @param out where it goes; closed when the writer is
	 */
	ArchiveWriter openWriter(OutputStream out) throws IOException;
}
