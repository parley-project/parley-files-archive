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

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.DelegatingFileSource;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.archive.zip.ZipArchiveEntry;
import us.bringardner.parley.files.archive.zip.ZipWriter;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** ArchiveFileSource, ArchiveEntryFileSource and ArchiveFileSourceFactory, over memory and local files. */
public class ArchiveFileSourceTest {

	@TempDir
	Path tmp;

	private MemoryFileSourceFactory memory;

	@BeforeEach
	public void setUp() throws IOException {
		memory = new MemoryFileSourceFactory();
		memory.connect();
	}

	@AfterEach
	public void tearDown() throws IOException {
		memory.disConnect();
	}

	static byte[] read(InputStream in) throws IOException {
		try (in) {
			return in.readAllBytes();
		}
	}

	static String text(FileSource f) throws IOException {
		return new String(read(f.getInputStream()), StandardCharsets.UTF_8);
	}

	/** writes the sample archive (deflated) to f, with Unix modes */
	static void writeSample(FileSource f, String readme) throws IOException {
		try (ZipWriter w = new ZipWriter(f.getOutputStream())) {
			for(Map.Entry<String, byte[]> e : us.bringardner.parley.files.archive.zip.ZipFormatTest.sample().entrySet()) {
				ZipArchiveEntry.Builder b = ZipArchiveEntry.builder(e.getKey()).lastModified(1_700_000_000_000L);
				if( e.getKey().equals("bin/noise.dat") ) {
					b.unixMode(0755);
				}
				try (OutputStream out = w.putEntry(b.build())) {
					if( e.getValue() != null ) {
						out.write(e.getKey().equals("readme.txt") ? readme.getBytes(StandardCharsets.UTF_8) : e.getValue());
					}
				}
			}
		}
	}

	private FileSource memoryArchive() throws IOException {
		FileSource dir = memory.createFileSource("/data");
		assertTrue(dir.mkdirs() || dir.isDirectory());
		FileSource zip = dir.getChild("foo.zip");
		writeSample(zip, "Hello, zip\n");
		return zip;
	}

	@Test
	public void archiveIsAFolderInPlace() throws IOException {
		FileSource zip = memoryArchive();
		ArchiveFileSource a = new ArchiveFileSource(zip);
		assertTrue(a.isDirectory());
		assertFalse(a.isFile());
		assertEquals("foo.zip", a.getName());
		assertEquals("/data/foo.zip", a.getAbsolutePath());
		assertEquals(zip.length(), a.length(), "the file face is the archive file's");
		assertEquals("zip", a.getArchiveFormat().getName());

		Set<String> top = new HashSet<>(Arrays.asList(a.list()));
		assertEquals(Set.of("readme.txt", "empty.txt", "docs", "bin", "deep", "unicode"), top);

		FileSource c = a.getChild("deep/a/b/c.txt");
		assertTrue(c.exists());
		assertTrue(c.isFile());
		assertEquals("/data/foo.zip/deep/a/b/c.txt", c.getAbsolutePath());
		assertEquals("implied folders", text(c));
		assertSame(memory, c.getFileSourceFactory(), "files inside report the archive file's factory");

		// implied folders, and the way back up
		FileSource b = c.getParentFile();
		assertTrue(b.isDirectory());
		assertEquals("/data/foo.zip/deep/a/b", b.getAbsolutePath());
		FileSource deep = b.getParentFile().getParentFile();
		assertEquals("deep", deep.getName());
		assertSame(a, deep.getParentFile(), "the top's files have the archive as their parent");
		assertEquals("/data", deep.getParentFile().getParentFile().getAbsolutePath());
		assertEquals("/data/foo.zip/deep", a.getChild("deep/a/../").getAbsolutePath());
		assertEquals("/data/foo.zip", c.getChild("../../../..").getAbsolutePath(), ".. stops at the archive's top");

		assertEquals(1_700_000_000_000L, a.getChild("readme.txt").lastModified());
		assertEquals(0, a.getChild("empty.txt").length());
		assertEquals("", text(a.getChild("empty.txt")));
		assertFalse(a.getChild("nope.txt").exists());
		assertThrows(FileNotFoundException.class, () -> a.getChild("nope.txt").getInputStream());
		assertThrows(FileNotFoundException.class, () -> a.getChild("docs").getInputStream());
		assertNull(a.getChild("readme.txt").listFiles());
		assertEquals(1, a.getChild("docs").listFiles().length);
	}

	@Test
	public void insideIsReadOnly() throws IOException {
		ArchiveFileSource a = new ArchiveFileSource(memoryArchive());
		FileSource f = a.getChild("readme.txt");
		assertFalse(f.canWrite());
		assertTrue(f.canRead());
		assertThrows(IOException.class, f::getOutputStream);
		assertThrows(IOException.class, () -> a.getChild("new.txt").createNewFile());
		assertFalse(f.createNewFile(), "it is there");
		assertFalse(f.delete());
		assertFalse(a.getChild("newdir").mkdir());
		assertFalse(f.renameTo(a.getChild("other.txt")));
		assertFalse(f.setLastModifiedTime(0));
		assertThrows(IOException.class, () -> f.getRandomAccessStream("rw"));
		assertTrue(f.exists(), "nothing changed");
		// modes from the archive
		FileSource noise = a.getChild("bin/noise.dat");
		assertTrue(noise.canOwnerExecute());
		assertFalse(f.canOwnerExecute());
		assertTrue(a.getChild("docs").canOwnerExecute());
	}

	@Test
	public void randomAccessInside() throws IOException {
		ArchiveFileSource a = new ArchiveFileSource(memoryArchive());
		byte[] noise = us.bringardner.parley.files.archive.zip.ZipFormatTest.sample().get("bin/noise.dat");
		FileSource f = a.getChild("bin/noise.dat");
		assertEquals(noise.length, f.length());
		ISeekableInputStream in = f.getSeekableInputStream();
		try {
			for(long pos : new long[] { 150_000, 5, 199_000, 77_777 }) {
				in.seek(pos);
				byte[] got = new byte[100];
				int n = 0;
				while( n < got.length ) {
					n += in.read(got, n, got.length - n);
				}
				assertArrayEquals(Arrays.copyOfRange(noise, (int) pos, (int) pos + 100), got, "at " + pos);
			}
		} finally {
			in.close();
		}
		try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
			r.seek(123_456);
			assertEquals(noise[123_456] & 0xFF, r.read());
			assertEquals(noise.length, r.length());
		}
		try (InputStream s = f.getInputStream(199_998)) {
			assertArrayEquals(Arrays.copyOfRange(noise, 199_998, 200_000), s.readAllBytes());
		}
	}

	@Test
	public void changedArchiveIsReadAgain() throws IOException {
		FileSource zip = memoryArchive();
		ArchiveFileSource a = new ArchiveFileSource(zip);
		assertEquals("Hello, zip\n", text(a.getChild("readme.txt")));
		writeSample(zip, "A longer greeting, so the archive's length changes\n");
		assertEquals("A longer greeting, so the archive's length changes\n", text(a.getChild("readme.txt")));
		writeSample(zip, "Same length here!!\n".substring(0, 11));
		a.refresh();
		assertEquals("Same length", text(a.getChild("readme.txt")));
	}

	@Test
	public void notAnArchive() throws IOException {
		FileSource f = memory.createFileSource("/plain.txt");
		try (OutputStream out = f.getOutputStream()) {
			out.write("just text, long enough to be looked at".getBytes(StandardCharsets.UTF_8));
		}
		ArchiveFileSource a = new ArchiveFileSource(f);
		IOException e = assertThrows(IOException.class, a::listFiles);
		assertTrue(e.getMessage().contains("isn't an archive"), e.getMessage());
		assertEquals("just text, long enough to be looked at", text(a), "its bytes are still the file's");
		ArchiveFileSource missing = new ArchiveFileSource(memory.createFileSource("/missing.zip"));
		assertFalse(missing.isDirectory());
		assertFalse(missing.exists());
	}

	@Test
	public void nestedArchive() throws IOException {
		FileSource inner = memory.createFileSource("/inner.jar");
		writeSample(inner, "inside the inner jar\n");
		FileSource outer = memory.createFileSource("/outer.zip");
		try (ZipWriter w = new ZipWriter(outer.getOutputStream()); InputStream in = inner.getInputStream()) {
			try (OutputStream out = w.putEntry(ZipArchiveEntry.builder("lib/inner.jar").build())) {
				in.transferTo(out);
			}
		}
		ArchiveFileSource o = new ArchiveFileSource(outer);
		ArchiveFileSource i = new ArchiveFileSource(o.getChild("lib/inner.jar"));
		assertTrue(i.isDirectory());
		assertEquals("inside the inner jar\n", text(i.getChild("readme.txt")));
		assertEquals("/outer.zip/lib/inner.jar/docs/guide.md", i.getChild("docs/guide.md").getAbsolutePath());
	}

	/** counts the archive file's seekable streams, to check every one is closed */
	static final class Counting extends DelegatingFileSource {
		private static final long serialVersionUID = 1L;
		final AtomicInteger opened = new AtomicInteger();
		final AtomicInteger closed = new AtomicInteger();
		/** 0: seekable; 1: UnsupportedOperationException; 2: IOException (as parley-files-vfs for some schemes) */
		final int seekable;

		Counting(FileSource real, int seekable) {
			super(real);
			this.seekable = seekable;
		}

		@Override
		public ISeekableInputStream getSeekableInputStream() throws IOException {
			if( seekable == 1 ) {
				throw new UnsupportedOperationException("not here");
			}
			if( seekable == 2 ) {
				throw new IOException("no random access here");
			}
			ISeekableInputStream in = super.getSeekableInputStream();
			opened.incrementAndGet();
			return (ISeekableInputStream) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { ISeekableInputStream.class }, (proxy, m, args) -> {
						if( m.getName().equals("close") ) {
							closed.incrementAndGet();
						}
						try {
							return m.invoke(in, args);
						} catch (java.lang.reflect.InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}

		@Override
		public InputStream getInputStream(long start) throws IOException {
			InputStream in = super.getInputStream(start);
			opened.incrementAndGet();
			return new java.io.FilterInputStream(in) {
				@Override
				public void close() throws IOException {
					closed.incrementAndGet();
					super.close();
				}
			};
		}
	}

	@Test
	public void nothingIsLeftOpen() throws IOException {
		for(int seekable = 0; seekable < 3; seekable++) {
			Counting zip = new Counting(memoryArchive(), seekable);
			ArchiveFileSource a = new ArchiveFileSource(zip);
			a.listFiles();
			assertEquals("Hello, zip\n", text(a.getChild("readme.txt")));
			// a stream read only in part, then closed
			try (InputStream in = a.getChild("bin/noise.dat").getInputStream()) {
				assertTrue(in.read() >= 0);
			}
			ISeekableInputStream s = a.getChild("docs/guide.md").getSeekableInputStream();
			s.seek(100);
			s.read();
			s.close();
			try (IRandomAccessStream r = a.getChild("bin/noise.dat").getRandomAccessStream("r")) {
				r.seek(5);
				r.read();
			}
			// an entry that isn't there opens and closes the archive too
			assertThrows(FileNotFoundException.class, () -> a.getChild("docs").getInputStream());
			assertTrue(zip.opened.get() > 0, "seekable=" + seekable);
			assertEquals(zip.opened.get(), zip.closed.get(), "seekable=" + seekable + ": every stream opened is closed");
		}
	}

	@Test
	public void factoryOverAFileSource() throws IOException {
		ArchiveFileSourceFactory f = new ArchiveFileSourceFactory(memoryArchive());
		try {
			assertTrue(f.connect());
			FileSource[] roots = f.listRoots();
			assertEquals(1, roots.length);
			assertEquals("/", roots[0].getAbsolutePath());
			assertTrue(roots[0].isDirectory());
			assertNull(roots[0].getParentFile());
			FileSource g = f.createFileSource("/docs/guide.md");
			assertEquals("/docs/guide.md", g.getAbsolutePath());
			assertSame(f, g.getFileSourceFactory());
			assertTrue(text(g).startsWith("# Guide"));
			assertEquals("/docs", g.getParent());
			assertEquals("/", g.getParentFile().getParent());
			f.setCurrentDirectory(f.createFileSource("/deep/a"));
			assertEquals("implied folders", text(f.createFileSource("b/c.txt")));
			assertEquals("/deep/a/b/c.txt", f.createFileSource("b/c.txt").getAbsolutePath());
			assertEquals("/readme.txt", f.createFileSource("../../readme.txt").getAbsolutePath());
			assertEquals("/readme.txt", f.createFileSource("b").getChild("/readme.txt").getAbsolutePath());

			// a URL opens the same factory (its session)
			URL url = g.toURL();
			FileSource back = FileSourceFactory.getFileSource(url);
			assertSame(f, back.getFileSourceFactory());
			assertEquals("/docs/guide.md", back.getAbsolutePath());
		} finally {
			f.disConnect();
		}
		assertFalse(f.isConnected());
	}

	@Test
	public void factoryFromSettings() throws IOException {
		Path local = tmp.resolve("local.zip");
		FileSource lf = new FileProxyFactory().createFileSource(local.toString());
		writeSample(lf, "local archive\n");
		assertTrue(Files.size(local) > 0);

		FileSourceFactory found = FileSourceFactory.getFileSourceFactory(ArchiveFileSourceFactory.FACTORY_ID);
		assertTrue(found instanceof ArchiveFileSourceFactory, "found as a service: " + found);

		ArchiveFileSourceFactory f = new ArchiveFileSourceFactory();
		Properties p = new Properties();
		p.setProperty(ArchiveFileSourceFactory.PROP_ARCHIVE, local.toString());
		f.setConnectionProperties(p);
		try {
			assertTrue(f.connect());
			assertEquals("local archive\n", text(f.createFileSource("/readme.txt")));
		} finally {
			f.disConnect();
		}

		// a URL naming the archive, from an ArchiveFileSource used on its own
		ArchiveFileSource a = new ArchiveFileSource(lf);
		URL url = a.getChild("deep/a/b/c.txt").toURL();
		assertTrue(url.toString().contains("archive="), url.toString());
		FileSource back = FileSourceFactory.getFileSource(url);
		try {
			assertTrue(back.getFileSourceFactory() instanceof ArchiveFileSourceFactory);
			assertEquals("/deep/a/b/c.txt", back.getAbsolutePath());
			assertEquals("implied folders", text(back));
		} finally {
			back.getFileSourceFactory().disConnect();
		}

		ArchiveFileSourceFactory bad = new ArchiveFileSourceFactory();
		Properties q = new Properties();
		q.setProperty(ArchiveFileSourceFactory.PROP_ARCHIVE, tmp.resolve("not-there.zip").toString());
		bad.setConnectionProperties(q);
		assertThrows(IOException.class, bad::connect);
		assertFalse(bad.isConnected());
	}

	@Test
	public void tarGzIsAFolderToo() throws IOException {
		FileSource tgz = memory.createFileSource("/pkg.tar.gz");
		try (OutputStream out = tgz.getOutputStream()) {
			out.write(us.bringardner.parley.files.archive.tar.TarFormatTest.write(true,
					us.bringardner.parley.files.archive.tar.TarFormatTest.files()));
		}
		ArchiveFileSource a = new ArchiveFileSource(tgz);
		assertEquals("tar", a.getArchiveFormat().getName());
		assertEquals(Set.of("top"), new HashSet<>(Arrays.asList(a.list())));
		assertEquals("alpha\n", text(a.getChild("top/a.txt")));
		assertEquals("alpha\n", text(a.getChild("top/hard")), "a hard link reads its target");
		assertEquals(0750, a.getChild("top/a.txt").canOwnerExecute() ? 0750 : 0, "the mode from the archive");
		ISeekableInputStream in = a.getChild("top/a.txt").getSeekableInputStream();
		try {
			in.seek(2);
			assertEquals('p', in.read());
		} finally {
			in.close();
		}
	}
}
