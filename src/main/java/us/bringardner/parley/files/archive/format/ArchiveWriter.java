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
import java.io.OutputStream;

/**
 * Writes an archive as a stream: entries one after another, then the end (the index), so all
 * it needs is an OutputStream.
 */
public interface ArchiveWriter extends Closeable {

	/**
	 * Starts an entry. What was open is ended first.
	 *
	 * @param entry the entry; a format's own entry class says more (a zip entry's method). Its
	 *        size and checksum are worked out as it is written.
	 * @return where its bytes go; closing it ends the entry (it does not close the archive). A
	 *         folder's stream takes no bytes.
	 * @throws IOException if it can't be started (a path already written, the archive finished)
	 */
	OutputStream putEntry(ArchiveEntry entry) throws IOException;

	/**
	 * Ends the archive: ends the open entry and writes the index. The stream underneath stays open.
	 */
	void finish() throws IOException;

	/** Finishes the archive if it wasn't, and closes the stream underneath. */
	@Override
	void close() throws IOException;
}
