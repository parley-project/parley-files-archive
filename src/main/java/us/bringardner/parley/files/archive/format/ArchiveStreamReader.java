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
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an archive front to back from a plain stream, for a source that can't seek. Entries come
 * in the order they are stored, each readable only until the next is asked for.
 * <p>
 * What a streaming reader finds can differ from what the archive's own index says (an archive
 * that was appended to, or one with a program in front of it), and some entries can't be read
 * at all this way (see the format's class). Prefer {@link ArchiveReader} where the source can
 * seek. Closing the reader closes its stream.
 */
public interface ArchiveStreamReader extends Closeable {

	/**
	 * Moves to the next entry; what was left of the one before is skipped.
	 *
	 * @return the next entry, or null at the end of the entries
	 * @throws IOException if it can't be read
	 */
	ArchiveEntry nextEntry() throws IOException;

	/**
	 * @return the current entry's bytes, uncompressed, which end with the entry. The checksum and
	 *         size are checked at the end. Closing it doesn't close the reader.
	 * @throws IllegalStateException before the first {@link #nextEntry()} or after the last
	 */
	InputStream getInputStream();
}
