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

import us.bringardner.parley.files.DelegatingFileSource;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.archive.format.ArchiveFormat;

/**
 * An archive file shown as a folder, in place, in the file system it is in: foo.zip on an SFTP
 * server is a folder whose files are what the archive holds, at /remote/dir/foo.zip/inner/x.
 * <p>
 * It wraps the archive file (any FileSource that can be read at a position; see
 * {@link FileSourceSources}) and is it in every way but one: it is always a folder
 * ({@link #isDirectory()} true, {@link #isFile()} false), whose files ({@link #listFiles()},
 * {@link #list()}, {@link #getChild(String)}) are {@link ArchiveEntryFileSource}s read from the
 * archive's index. Its bytes, size, times, permissions, and delete and rename are the archive
 * file's. Code that wants the archive as a file uses the file itself, or a plain
 * {@link DelegatingFileSource}.
 * <p>
 * The index is read when the folder is first listed or entered, and again after
 * {@link #refresh()} or when the archive's length changes. Nothing is held open in between (see
 * {@link ArchiveHandle}). What is inside is read only.
 * <p>
 * The files inside report the archive file's factory, as the archive does; a path inside the
 * archive means nothing to that factory ({@code createFileSource("/remote/dir/foo.zip/inner/x")}
 * doesn't find it). To use an archive as a file system with paths of its own, use
 * {@link ArchiveFileSourceFactory}.
 */
public class ArchiveFileSource extends DelegatingFileSource {

	private static final long serialVersionUID = 1L;

	private final transient ArchiveHandle handle;
	private final transient ArchiveMount mount = new Mount();

	/** @param archive the archive file; its format is found from its content */
	public ArchiveFileSource(FileSource archive) {
		this(archive, null);
	}

	/**
	 * @param archive the archive file
	 * @param format its format, or null to find it from the content
	 */
	public ArchiveFileSource(FileSource archive, ArchiveFormat format) {
		super(archive);
		this.handle = new ArchiveHandle(archive, format);
	}

	/** The archive's files are named from this one's path. */
	private final class Mount implements ArchiveMount {
		@Override
		public FileSourceFactory factory() {
			return getFileSourceFactory();
		}

		@Override
		public String absolutePath(String entryPath) {
			String base = getAbsolutePath();
			if( entryPath.isEmpty() ) {
				return base;
			}
			char sep = separator();
			String inner = sep == '/' ? entryPath : entryPath.replace('/', sep);
			return base.endsWith(String.valueOf(sep)) ? base + inner : base + sep + inner;
		}

		@Override
		public FileSource top() {
			return ArchiveFileSource.this;
		}

		@Override
		public FileSource parentOfTop() throws IOException {
			return getParentFile();
		}

		@Override
		public FileSource resolveAbsolute(String path) throws IOException {
			return getFileSourceFactory().createFileSource(path);
		}

		@Override
		public char separator() {
			FileSourceFactory f = getFileSourceFactory();
			return f == null ? '/' : f.getSeparatorChar();
		}
	}

	/** @return the archive's top folder as an entry view (its files' parent is this) */
	private ArchiveEntryFileSource top() {
		return new ArchiveEntryFileSource(handle, mount, "");
	}

	/** @return the archive file */
	public FileSource getArchiveFile() {
		return real();
	}

	/**
	 * @return the archive's format, reading the index if it wasn't
	 * @throws IOException if it isn't an archive of a known format, or can't be read
	 */
	public ArchiveFormat getArchiveFormat() throws IOException {
		return handle.load().reader.getFormat();
	}

	/** @return the archive's comment, or null (reading the index if it wasn't) */
	public String getArchiveComment() throws IOException {
		return handle.load().reader.getComment();
	}

	/** @return true while the archive file is there and is a file */
	@Override
	public boolean isDirectory() throws IOException {
		return real().isFile();
	}

	@Override
	public boolean isFile() {
		return false;
	}

	/**
	 * @return what the archive's top folder holds
	 * @throws IOException if the archive can't be read, or isn't an archive of a known format
	 */
	@Override
	public FileSource[] listFiles() throws IOException {
		return top().listFiles();
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		return top().listFiles(progress);
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		return top().listFiles(filter);
	}

	@Override
	public String[] list() throws IOException {
		return top().list();
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		return top().list(filter);
	}

	/**
	 * @param path a path inside the archive (inner/x.txt; .. can't go above the archive), or an
	 *        absolute path of the archive file's factory
	 */
	@Override
	public FileSource getChild(String path) throws IOException {
		return top().getChild(path);
	}

	/** Reads the archive file's attributes and the archive's index again when next asked. */
	@Override
	public void refresh() throws IOException {
		handle.refresh();
		super.refresh();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof ArchiveFileSource && super.equals(obj);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}
}
