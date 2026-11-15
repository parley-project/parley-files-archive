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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveFormats;
import us.bringardner.parley.files.archive.format.ArchiveReader;
import us.bringardner.parley.files.archive.format.SeekableSource;

/**
 * One archive, as its FileSources share it: the archive file (the backing), and its index, read
 * when it is first needed.
 * <p>
 * Nothing is held open between uses. Opening reads the index and closes the archive; reading an
 * entry opens the archive and closes it when the entry's stream is closed. So an archive on a
 * remote file system costs no connection while it is only being listed, and nothing leaks when a
 * caller forgets an ArchiveFileSource.
 * <p>
 * When an entry is opened, the archive's length is checked against the length the index was read
 * at. If it changed, the index is read again first. {@link #refresh()} drops the index.
 */
final class ArchiveHandle {

	/** The index and the reader that read it (its source is closed: entries are read from a fresh one). */
	static final class Loaded {
		final ArchiveReader reader;
		final ArchiveIndex index;
		final long length;

		Loaded(ArchiveReader reader, ArchiveIndex index, long length) {
			this.reader = reader;
			this.index = index;
			this.length = length;
		}
	}

	private final FileSource backing;
	/** null: found from the content */
	private final ArchiveFormat format;
	private volatile Loaded loaded;

	ArchiveHandle(FileSource backing, ArchiveFormat format) {
		this.backing = Objects.requireNonNull(backing, "backing");
		this.format = format;
	}

	FileSource backing() {
		return backing;
	}

	/**
	 * @return the index, read now if it wasn't
	 * @throws FileNotFoundException if the archive isn't there
	 * @throws IOException if it isn't an archive of a known format, or can't be read
	 */
	Loaded load() throws IOException {
		Loaded l = loaded;
		if( l == null ) {
			synchronized (this) {
				l = loaded;
				if( l == null ) {
					loaded = l = read();
				}
			}
		}
		return l;
	}

	private Loaded read() throws IOException {
		if( !backing.exists() ) {
			throw new FileNotFoundException(backing.getAbsolutePath() + " doesn't exist");
		}
		if( backing.isDirectory() ) {
			throw new IOException(backing.getAbsolutePath() + " is a folder, not an archive");
		}
		try (SeekableSource src = FileSourceSources.open(backing)) {
			ArchiveFormat f = format != null ? format : ArchiveFormats.detect(src, backing.getName());
			if( f == null ) {
				throw new IOException(backing.getAbsolutePath() + " isn't an archive of a known format (" + names() + ")");
			}
			// the reader keeps the entries. Its own source is closed here: entries are read from a
			// new one each time (openStream(entry, source)), so the reader itself holds nothing open.
			ArchiveReader r = f.openReader(src);
			return new Loaded(r, new ArchiveIndex(r.getEntries()), src.length());
		}
	}

	private static String names() {
		StringBuilder b = new StringBuilder();
		for(ArchiveFormat f : ArchiveFormats.all()) {
			if( b.length() > 0 ) {
				b.append(", ");
			}
			b.append(f.getName());
		}
		return b.toString();
	}

	/** Drops the index: it is read again when next needed. */
	void refresh() {
		loaded = null;
	}

	/** @return the node at path, or null (the index is read if it wasn't) */
	ArchiveIndex.Node node(String path) throws IOException {
		return load().index.get(path);
	}

	/**
	 * Opens the archive for one entry: with the archive's current length, so a changed archive is
	 * read again before the entry is found.
	 *
	 * @return the open source and the entry, as the index read from this archive has it
	 */
	private Opened open(String path) throws IOException {
		SeekableSource src = FileSourceSources.open(backing);
		try {
			Loaded l = load();
			if( src.length() != l.length ) {
				synchronized (this) {
					if( loaded == l ) {
						loaded = null;
					}
				}
				l = load();
			}
			ArchiveIndex.Node n = l.index.get(path);
			if( n == null ) {
				throw new FileNotFoundException(path + " isn't in " + backing.getAbsolutePath());
			}
			if( n.directory ) {
				throw new FileNotFoundException(path + " is a folder in " + backing.getAbsolutePath());
			}
			return new Opened(src, l.reader, n.entry);
		} catch (IOException | RuntimeException e) {
			src.close();
			throw e;
		}
	}

	private static final class Opened {
		final SeekableSource source;
		final ArchiveReader reader;
		final ArchiveEntry entry;

		Opened(SeekableSource source, ArchiveReader reader, ArchiveEntry entry) {
			this.source = source;
			this.reader = reader;
			this.entry = entry;
		}
	}

	/**
	 * @return the entry's bytes; closing the stream closes the archive
	 * @throws FileNotFoundException if path isn't a file in the archive
	 */
	InputStream openStream(String path) throws IOException {
		Opened o = open(path);
		try {
			InputStream in = o.reader.openStream(o.entry, o.source);
			return new java.io.FilterInputStream(in) {
				private boolean closed;

				@Override
				public void close() throws IOException {
					if( closed ) {
						return;
					}
					closed = true;
					try {
						super.close();
					} finally {
						o.source.close();
					}
				}
			};
		} catch (IOException | RuntimeException e) {
			o.source.close();
			throw e;
		}
	}

	/**
	 * @return the entry's bytes with random access; closing it closes the archive
	 * @throws FileNotFoundException if path isn't a file in the archive
	 */
	SeekableSource openSource(String path) throws IOException {
		Opened o = open(path);
		try {
			SeekableSource s = o.reader.openSource(o.entry, o.source);
			return new SeekableSource() {
				private boolean closed;

				@Override
				public long length() throws IOException {
					return s.length();
				}

				@Override
				public int read(long position, byte[] b, int off, int len) throws IOException {
					return s.read(position, b, off, len);
				}

				@Override
				public synchronized void close() throws IOException {
					if( closed ) {
						return;
					}
					closed = true;
					try {
						s.close();
					} finally {
						o.source.close();
					}
				}
			};
		} catch (IOException | RuntimeException e) {
			o.source.close();
			throw e;
		}
	}
}
