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
package us.bringardner.parley.files.archive;

import java.io.IOException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * Where an archive's files are seen from: an {@link ArchiveFileSource} (the archive as a folder
 * of the file system it is in) or an {@link ArchiveFileSourceFactory} (the archive as a file
 * system of its own). It names them (their paths) and says what is above the archive's top.
 */
interface ArchiveMount {

	/** @return the factory the archive's files report */
	FileSourceFactory factory();

	/** @return the path of the file at entryPath ("" for the archive's top) */
	String absolutePath(String entryPath);

	/** @return the archive's top folder */
	FileSource top();

	/** @return the folder the archive's top is in, or null if it is a root */
	FileSource parentOfTop() throws IOException;

	/** @return the file at path, a path of {@link #factory()} (as getChild("/a/b") is asked) */
	FileSource resolveAbsolute(String path) throws IOException;

	/** @return the separator {@link #absolutePath(String)} puts between names */
	char separator();
}
