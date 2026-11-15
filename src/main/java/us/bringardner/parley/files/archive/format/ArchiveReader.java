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
import java.util.List;

/**
 * Reads an archive with random access: the index (every entry) is read when it is opened, and
 * any entry can then be read on its own, without reading the ones before it.
 * <p>
 * A reader reads from the source it was opened on. It can also read an entry from another
 * source holding the same bytes ({@link #openStream(ArchiveEntry, SeekableSource)}), so a
 * caller can keep the index and open the archive again only while an entry is read, rather than
 * holding it open. Closing a reader doesn't close its source: whoever opened the source closes
 * it.
 */
public interface ArchiveReader extends Closeable {

	/** @return the format that opened it */
	ArchiveFormat getFormat();

	/**
	 * @return every entry, in the order the archive lists them. A path can appear more than once
	 *         (an archive that was appended to); {@link #getEntry(String)} answers with the last.
	 */
	List<? extends ArchiveEntry> getEntries();

	/**
	 * @param path a path in the archive (normalized as {@link ArchiveEntry#normalize(String)})
	 * @return the entry at path, or null. A folder the archive implies (a/b/c.txt implies a and
	 *         a/b) but doesn't list has no entry.
	 */
	ArchiveEntry getEntry(String path);

	/** @return the archive's comment, or null */
	String getComment();

	/**
	 * @return the entry's bytes, uncompressed, from the reader's own source. The stream checks
	 *         the entry's checksum and size when it reaches the end, and throws an IOException if
	 *         they are wrong.
	 * @throws IOException if the entry can't be read (a method or encryption this reader doesn't
	 *         support, a damaged header)
	 */
	default InputStream openStream(ArchiveEntry entry) throws IOException {
		return openStream(entry, getSource());
	}

	/**
	 * As {@link #openStream(ArchiveEntry)}, from source, which must hold the bytes the reader was
	 * opened on. Closing the stream doesn't close source.
	 */
	InputStream openStream(ArchiveEntry entry, SeekableSource source) throws IOException;

	/**
	 * @return the entry's bytes, uncompressed, with random access, from source (which must hold
	 *         the bytes the reader was opened on). A stored entry is read in place; a compressed
	 *         one is decompressed from its start whenever a read goes backwards. Closing it
	 *         doesn't close source. No checksum is checked.
	 * @throws IOException if the entry can't be read
	 */
	SeekableSource openSource(ArchiveEntry entry, SeekableSource source) throws IOException;

	/** @return the source the reader was opened on */
	SeekableSource getSource();

	/** Ends the reader: it no longer reads. Its source stays open. */
	@Override
	void close() throws IOException;
}
