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
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.files.archive.format.SeekableSource;

/** Read-only random access ("r" mode) to an archive entry. Closing it closes the archive. */
final class ArchiveRandomAccessIoController implements IRandomAccessIoController {

	private final FileSource file;
	private final SeekableSource source;
	private final byte[] one = new byte[1];

	ArchiveRandomAccessIoController(FileSource file, SeekableSource source) {
		this.file = file;
		this.source = source;
	}

	@Override
	public synchronized int read(long position) throws IOException {
		return source.read(position, one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
	}

	@Override
	public int read(long position, byte[] b, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, b.length);
		return len == 0 ? 0 : source.read(position, b, off, len);
	}

	@Override
	public void write(long position, byte value) throws IOException {
		throw ArchiveEntryFileSource.readOnly(file);
	}

	@Override
	public long length() throws IOException {
		return source.length();
	}

	@Override
	public void setLength(long newLength) throws IOException {
		throw ArchiveEntryFileSource.readOnly(file);
	}

	@Override
	public void save() {
		// nothing is written
	}

	@Override
	public FileSource getFile() {
		return file;
	}

	/** it opened the entry, which fails for a missing file or a folder */
	@Override
	public boolean checksAccessOnOpen() {
		return true;
	}

	@Override
	public void close() throws IOException {
		source.close();
	}
}
