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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import us.bringardner.parley.files.archive.compress.Compression;
import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveReader;
import us.bringardner.parley.files.archive.format.ReopeningSeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSourceInputStream;
import us.bringardner.parley.files.archive.format.SeekableSources;

/**
 * Reads a tar archive, plain or gzipped, with random access.
 * <p>
 * A tar has no index, so opening one reads every header ({@link TarScanner}):
 * <ul>
 * <li>a plain tar is read at each header's position, skipping the data between them: one small
 * read per entry, whatever the entries' sizes;</li>
 * <li>a gzipped tar can't be entered in the middle, so it is decompressed from its start to the
 * end, once.</li>
 * </ul>
 * An entry is then read on its own: from a plain tar in place; from a gzipped one by
 * decompressing from the start to the entry (random access to such an entry holds it in memory
 * when it is small; see {@link ReopeningSeekableSource}).
 * <p>
 * A hard link's bytes are its target's; a symbolic link's bytes are its target's name (as in a
 * zip archive); a device or fifo has none. A sparse file is listed, but reading it fails.
 */
public final class TarReader implements ArchiveReader {

	private final ArchiveFormat format;
	private final SeekableSource source;
	private final Compression compression;
	private final List<TarArchiveEntry> entries;
	private final Map<String, TarArchiveEntry> byPath;

	private TarReader(ArchiveFormat format, SeekableSource source, Compression compression, List<TarArchiveEntry> entries) {
		this.format = format;
		this.source = source;
		this.compression = compression;
		this.entries = Collections.unmodifiableList(entries);
		Map<String, TarArchiveEntry> map = new HashMap<>(entries.size() * 2);
		for(TarArchiveEntry e : entries) {
			map.put(e.getPath(), e);   // the last wins, as tar extracts them
		}
		this.byPath = map;
	}

	/**
	 * Reads the archive's headers.
	 *
	 * @param source a tar archive, plain or gzipped; it stays the caller's to close
	 * @throws IOException if it isn't a tar archive, or is damaged
	 */
	public static TarReader open(SeekableSource source) throws IOException {
		return open(TarFormat.INSTANCE, source);
	}

	static TarReader open(ArchiveFormat format, SeekableSource source) throws IOException {
		Objects.requireNonNull(source, "source");
		Compression c = compressionOf(source);
		List<TarArchiveEntry> entries = new ArrayList<>();
		if( c == null ) {
			TarScanner s = new TarScanner((position, b, off, len) -> {
				int cnt = source.read(position, b, off, len);
				return cnt < 0 ? 0 : cnt;
			});
			for(TarArchiveEntry e; (e = s.next()) != null;) {
				entries.add(e);
			}
		} else {
			try (InputStream in = c.decompress(new SeekableSourceInputStream(source, 0, source.length(), 64 * 1024))) {
				ForwardInput f = new ForwardInput(in);
				TarScanner s = new TarScanner(f);
				for(TarArchiveEntry e; (e = s.next()) != null;) {
					entries.add(e);
				}
			}
		}
		return new TarReader(format, source, c, withLinkSizes(entries));
	}

	/** hard links get their target's size (the last entry of that path before the link) */
	private static List<TarArchiveEntry> withLinkSizes(List<TarArchiveEntry> entries) {
		Map<String, TarArchiveEntry> seen = new HashMap<>();
		List<TarArchiveEntry> ret = new ArrayList<>(entries.size());
		for(TarArchiveEntry e : entries) {
			if( e.isHardLink() && e.getLinkName() != null ) {
				TarArchiveEntry t = seen.get(ArchiveEntry.normalize(e.getLinkName()));
				if( t != null && t.getSize() >= 0 ) {
					e = e.withTargetSize(t.getSize());
				}
			}
			seen.put(e.getPath(), e);
			ret.add(e);
		}
		return ret;
	}

	/** @return the compression source's first bytes say, or null */
	static Compression compressionOf(SeekableSource source) throws IOException {
		byte[] head = new byte[4];
		int got = 0;
		while( got < head.length ) {
			int cnt = source.read(got, head, got, head.length - got);
			if( cnt < 0 ) {
				break;
			}
			got += cnt;
		}
		return Compression.detect(head, got);
	}

	/** An InputStream as forward-only positioned reads: it skips to each position asked. */
	static final class ForwardInput implements TarScanner.Input {
		private final InputStream in;
		long pos;

		ForwardInput(InputStream in) {
			this.in = in;
		}

		@Override
		public int read(long position, byte[] b, int off, int len) throws IOException {
			if( position < pos ) {
				throw new IllegalStateException("A tar stream can't go back to " + position + " from " + pos);
			}
			while( pos < position ) {
				long skipped = in.skip(position - pos);
				if( skipped <= 0 ) {
					if( in.read() < 0 ) {
						return 0;
					}
					skipped = 1;
				}
				pos += skipped;
			}
			int done = 0;
			while( done < len ) {
				int cnt = in.read(b, off + done, len - done);
				if( cnt < 0 ) {
					break;
				}
				done += cnt;
			}
			pos += done;
			return done;
		}
	}

	@Override
	public ArchiveFormat getFormat() {
		return format;
	}

	/** @return how the archive is compressed, or null for a plain tar */
	public Compression getCompression() {
		return compression;
	}

	@Override
	public List<TarArchiveEntry> getEntries() {
		return entries;
	}

	@Override
	public TarArchiveEntry getEntry(String path) {
		return byPath.get(ArchiveEntry.normalize(path));
	}

	/** tar has no archive comment */
	@Override
	public String getComment() {
		return null;
	}

	@Override
	public SeekableSource getSource() {
		return source;
	}

	private TarArchiveEntry mine(ArchiveEntry entry) {
		if( !(entry instanceof TarArchiveEntry) || ((TarArchiveEntry) entry).dataOffset < 0 ) {
			throw new IllegalArgumentException(entry + " isn't an entry this reader read");
		}
		return (TarArchiveEntry) entry;
	}

	/** @return the entry whose bytes are entry's: a hard link's target, else entry */
	private TarArchiveEntry data(TarArchiveEntry e) throws IOException {
		TarArchiveEntry t = e;
		for(int hops = 0; t.isHardLink(); hops++) {
			TarArchiveEntry target = t.getLinkName() == null ? null : byPath.get(ArchiveEntry.normalize(t.getLinkName()));
			if( target == null || hops > 32 ) {
				throw new FileNotFoundException(e.getPath() + " is a hard link to " + t.getLinkName() + ", which isn't in the archive");
			}
			t = target;
		}
		if( t.isSparse() ) {
			throw new IOException(e.getPath() + " is a sparse file, which isn't supported");
		}
		return t;
	}

	@Override
	public InputStream openStream(ArchiveEntry entry, SeekableSource src) throws IOException {
		TarArchiveEntry e = mine(entry);
		if( e.isDirectory() ) {
			return InputStream.nullInputStream();
		}
		if( e.isSymbolicLink() ) {
			return new ByteArrayInputStream(e.getLinkName().getBytes(StandardCharsets.UTF_8));
		}
		TarArchiveEntry d = data(e);
		if( !d.isRegularFile() ) {
			return InputStream.nullInputStream();
		}
		if( compression == null ) {
			return new SeekableSourceInputStream(src, d.dataOffset, d.storedSize, 64 * 1024);
		}
		return decompressedRange(src, d.dataOffset, d.storedSize);
	}

	/** the bytes at [start, start + length) of the decompressed archive */
	private InputStream decompressedRange(SeekableSource src, long start, long length) throws IOException {
		InputStream in = compression.decompress(new SeekableSourceInputStream(src, 0, src.length(), 64 * 1024));
		try {
			long left = start;
			while( left > 0 ) {
				long skipped = in.skip(left);
				if( skipped <= 0 ) {
					if( in.read() < 0 ) {
						throw new java.io.EOFException("The archive ends before its entry");
					}
					skipped = 1;
				}
				left -= skipped;
			}
		} catch (IOException | RuntimeException e) {
			in.close();
			throw e;
		}
		return new InputStream() {
			private long left = length;

			@Override
			public int read() throws IOException {
				if( left <= 0 ) {
					return -1;
				}
				int v = in.read();
				if( v < 0 ) {
					throw new java.io.EOFException("The archive ends inside an entry");
				}
				left--;
				return v;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if( left <= 0 ) {
					return -1;
				}
				int cnt = in.read(b, off, (int) Math.min(len, left));
				if( cnt < 0 ) {
					throw new java.io.EOFException("The archive ends inside an entry");
				}
				left -= cnt;
				return cnt;
			}

			@Override
			public void close() throws IOException {
				in.close();
			}
		};
	}

	@Override
	public SeekableSource openSource(ArchiveEntry entry, SeekableSource src) throws IOException {
		TarArchiveEntry e = mine(entry);
		if( e.isDirectory() || e.isSymbolicLink() ) {
			byte[] b = e.isSymbolicLink() ? e.getLinkName().getBytes(StandardCharsets.UTF_8) : new byte[0];
			return SeekableSources.of(b);
		}
		TarArchiveEntry d = data(e);
		if( !d.isRegularFile() ) {
			return SeekableSources.of(new byte[0]);
		}
		if( compression == null ) {
			return SeekableSources.range(src, d.dataOffset, d.storedSize);
		}
		return new ReopeningSeekableSource(() -> decompressedRange(src, d.dataOffset, d.storedSize), d.storedSize);
	}

	/** Nothing is held: the source stays the caller's. */
	@Override
	public void close() {
	}

	@Override
	public String toString() {
		return "TarReader[" + entries.size() + " entries" + (compression == null ? "" : ", " + compression) + "]";
	}
}
