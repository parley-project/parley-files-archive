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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.archive.compress.Compression;
import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveFormats;
import us.bringardner.parley.files.archive.format.ReopeningSeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSources;

/**
 * The tar reader, stream reader and writer: archives made by GNU tar 1.35 (gnu, posix, ustar and v7
 * formats) and Python's tarfile (pax and gnu), in src/test/resources/tar, and round trips.
 */
public class TarFormatTest {

	static final String LONG_DIR = "d".repeat(120);
	static final String LONG_FILE = LONG_DIR + "/" + "f".repeat(110) + ".txt";

	static byte[] resource(String name) throws IOException {
		try (InputStream in = TarFormatTest.class.getResourceAsStream("/tar/" + name)) {
			assertNotNull(in, name);
			return in.readAllBytes();
		}
	}

	static String text(InputStream in) throws IOException {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** what GNU tar put in the fixtures (from a folder with a long name, a link, a hard link, UTF-8) */
	static void checkGnuTarFixture(TarReader r, boolean longNames, boolean v7) throws IOException {
		assertEquals("hello\n", text(r.openStream(r.getEntry("hard"))));
		TarArchiveEntry a = r.getEntry("a.txt");
		assertTrue(a.isHardLink(), "GNU tar wrote a.txt as a link to hard");
		assertEquals(6, a.getSize(), "a hard link has its target's size");
		assertEquals("hello\n", text(r.openStream(a)));
		TarArchiveEntry link = r.getEntry("link");
		assertTrue(link.isSymbolicLink());
		assertEquals("a.txt", link.getLinkName());
		assertEquals("a.txt", text(r.openStream(link)));
		assertTrue(r.getEntry("empty").isDirectory());
		assertEquals("café\n", text(r.openStream(r.getEntry("café.txt"))));
		if( longNames ) {
			assertEquals("deep\n", text(r.openStream(r.getEntry(LONG_FILE))));
			assertTrue(r.getEntry(LONG_DIR).isDirectory());
		} else {
			assertNull(r.getEntry(LONG_FILE));
		}
		assertEquals(0644, r.getEntry("hard").getUnixMode());
		assertEquals(v7 ? null : "root", r.getEntry("hard").getUserName(), "v7 has no user names");
	}

	@Test
	public void readsGnuTarsFormats() throws IOException {
		for(String f : new String[] { "gnu.tar", "posix.tar", "ustar.tar", "v7.tar", "posix.tar.gz" }) {
			try (SeekableSource src = SeekableSources.of(resource(f)); TarReader r = TarReader.open(src)) {
				checkGnuTarFixture(r, f.startsWith("gnu") || f.startsWith("posix"), f.startsWith("v7"));
				assertEquals(f.endsWith(".gz") ? Compression.GZIP : null, r.getCompression(), f);
			}
		}
		// pax keeps the fraction of a second
		try (SeekableSource src = SeekableSources.of(resource("posix.tar")); TarReader r = TarReader.open(src)) {
			assertEquals(678, r.getEntry("hard").getLastModified() % 1000);
		}
	}

	@Test
	public void readsPythonsPaxAndGnu() throws IOException {
		for(String f : new String[] { "py-pax.tar", "py-gnu.tar" }) {
			try (SeekableSource src = SeekableSources.of(resource(f)); TarReader r = TarReader.open(src)) {
				TarArchiveEntry e = r.getEntry("big-uid.txt");
				assertEquals("uid", text(r.openStream(e)));
				assertEquals(9_999_999, e.getUid(), f);
				if( f.equals("py-pax.tar") ) {
					assertEquals("a-very-long-user-name-more-than-32-chars", e.getUserName());
					assertEquals(1_700_000_000_250L, e.getLastModified());
				}
			}
		}
	}

	@Test
	public void streamReaderReadsTheFixtures() throws IOException {
		for(String f : new String[] { "gnu.tar", "posix.tar", "posix.tar.gz" }) {
			Map<String, String> got = new LinkedHashMap<>();
			try (TarStreamReader r = new TarStreamReader(new ByteArrayInputStream(resource(f)))) {
				for(TarArchiveEntry e; (e = r.nextEntry()) != null;) {
					got.put(e.getPath(), e.isRegularFile() ? text(r.getInputStream()) : null);
				}
			}
			assertEquals("hello\n", got.get("hard"), f);
			assertEquals("deep\n", got.get(LONG_FILE), f);
			assertTrue(got.containsKey("a.txt"));
		}
	}

	public static byte[] write(boolean gzip, Map<String, byte[]> files) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		OutputStream out = gzip ? Compression.GZIP.compress(bout) : bout;
		try (TarWriter w = new TarWriter(out)) {
			w.putEntry(TarArchiveEntry.builder("top/").lastModified(1_600_000_000_000L).build()).close();
			for(Map.Entry<String, byte[]> e : files.entrySet()) {
				try (OutputStream o = w.putEntry(TarArchiveEntry.builder(e.getKey()).size(e.getValue().length)
						.lastModified(1_600_000_000_123L).unixMode(0750).ids(12_345_678, 7).names("tony", "staff").build())) {
					o.write(e.getValue());
				}
			}
			w.putEntry(TarArchiveEntry.builder("top/sym").symbolicLink("../" + "t".repeat(150)).build()).close();
			w.putEntry(TarArchiveEntry.builder("top/hard").hardLink("top/a.txt").build()).close();
		}
		return bout.toByteArray();
	}

	public static Map<String, byte[]> files() {
		Map<String, byte[]> m = new LinkedHashMap<>();
		byte[] noise = new byte[100_000];
		new Random(7).nextBytes(noise);
		m.put("top/a.txt", "alpha\n".getBytes(StandardCharsets.UTF_8));
		m.put("top/empty.txt", new byte[0]);
		m.put("top/" + "x".repeat(140) + "/" + "y".repeat(120) + ".bin", noise);   // can't be split: pax path
		m.put("top/" + "p".repeat(150) + "/short.txt", "split\n".getBytes(StandardCharsets.UTF_8));   // prefix and name
		m.put("top/café 日本.txt", "utf8\n".getBytes(StandardCharsets.UTF_8));
		return m;
	}

	@Test
	public void roundTrip() throws IOException {
		Map<String, byte[]> m = files();
		for(boolean gz : new boolean[] { false, true }) {
			byte[] tar = write(gz, m);
			if( !gz ) {
				assertEquals(0, tar.length % TarWriter.RECORD);
			}
			try (SeekableSource src = SeekableSources.of(tar); TarReader r = TarReader.open(src)) {
				for(Map.Entry<String, byte[]> e : m.entrySet()) {
					TarArchiveEntry t = r.getEntry(e.getKey());
					assertNotNull(t, e.getKey());
					assertArrayEquals(e.getValue(), r.openStream(t).readAllBytes(), e.getKey());
					assertEquals(1_600_000_000_123L, t.getLastModified(), "pax mtime keeps milliseconds");
					assertEquals(12_345_678, t.getUid(), "pax uid");
					assertEquals(7, t.getGid());
					assertEquals(0750, t.getUnixMode());
					assertEquals("tony", t.getUserName());
				}
				assertTrue(r.getEntry("top").isDirectory());
				assertEquals("../" + "t".repeat(150), r.getEntry("top/sym").getLinkName(), "pax linkpath");
				assertEquals("alpha\n", text(r.openStream(r.getEntry("top/hard"))));
				// random access
				String big = "top/" + "x".repeat(140) + "/" + "y".repeat(120) + ".bin";
				byte[] noise = m.get(big);
				for(String limit : new String[] { null, "0" }) {
					if( limit != null ) {
						System.setProperty(ReopeningSeekableSource.MEMORY_LIMIT_PROPERTY, limit);
					}
					try (SeekableSource e = r.openSource(r.getEntry(big), src)) {
						for(long pos : new long[] { 90_000, 3, 50_000 }) {
							byte[] got = new byte[16];
							e.readFully(pos, got, 0, got.length);
							assertArrayEquals(Arrays.copyOfRange(noise, (int) pos, (int) pos + 16), got);
						}
					} finally {
						System.clearProperty(ReopeningSeekableSource.MEMORY_LIMIT_PROPERTY);
					}
				}
			}
			try (TarStreamReader r = new TarStreamReader(new ByteArrayInputStream(tar))) {
				List<String> names = new ArrayList<>();
				for(TarArchiveEntry e; (e = r.nextEntry()) != null;) {
					names.add(e.getPath());
					if( m.containsKey(e.getPath()) && e.getPath().endsWith(".txt") ) {
						assertArrayEquals(m.get(e.getPath()), r.getInputStream().readAllBytes());
					}
				}
				assertEquals(m.size() + 3, names.size(), names.toString());
			}
		}
	}

	@Test
	public void sizesAreRequiredAndKept() throws IOException {
		try (TarWriter w = new TarWriter(new ByteArrayOutputStream())) {
			IOException e = assertThrows(IOException.class, () -> w.putEntry(TarArchiveEntry.builder("no-size").build()));
			assertTrue(e.getMessage().contains("size"), e.getMessage());
			OutputStream o = w.putEntry(TarArchiveEntry.builder("three").size(3).build());
			assertThrows(IOException.class, () -> o.write(new byte[4]));
			o.write(new byte[2]);
			assertThrows(IOException.class, o::close, "fewer bytes than its size");
		}
	}

	@Test
	public void detected() throws IOException {
		byte[] plain = write(false, files());
		byte[] gz = write(true, files());
		for(byte[] b : List.of(plain, gz)) {
			try (SeekableSource src = SeekableSources.of(b)) {
				ArchiveFormat f = ArchiveFormats.detect(src, null);
				assertNotNull(f);
				assertEquals("tar", f.getName());
			}
		}
		assertEquals("tar", ArchiveFormats.forFileName("x.tar.gz").getName());
		assertEquals("tar", ArchiveFormats.forFileName("x.tgz").getName());
		try (SeekableSource src = SeekableSources.of(new byte[2048])) {
			assertEquals("tar", ArchiveFormats.detect(src, null).getName(), "an empty tar");
		}
		try (SeekableSource src = SeekableSources.of("not a tar at all, just some words".repeat(40).getBytes(StandardCharsets.UTF_8))) {
			assertNull(ArchiveFormats.detect(src, "x.tar"));
		}
	}

	@Test
	public void damagedHeader() throws IOException {
		byte[] tar = write(false, files());
		tar[600] ^= 0x55;   // in the second header
		try (SeekableSource src = SeekableSources.of(tar)) {
			IOException e = assertThrows(IOException.class, () -> TarReader.open(src));
			assertTrue(e.getMessage().contains("checksum"), e.getMessage());
		}
	}

	@Test
	public void unsafeNamesStayInside() throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (TarWriter w = new TarWriter(bout)) {
			// a TarArchiveEntry normalizes its path; an archive from elsewhere may hold anything,
			// so write the raw name through a pax path
			w.putEntry(TarArchiveEntry.builder("x").size(1).build()).write('x');
		}
		byte[] tar = bout.toByteArray();
		// rewrite the name field to ../../etc/passwd and fix the checksum
		byte[] h = Arrays.copyOf(tar, 512);
		Arrays.fill(h, 0, 100, (byte) 0);
		byte[] n = "../../etc/passwd".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(n, 0, h, 0, n.length);
		Arrays.fill(h, 148, 156, (byte) ' ');
		long sum = 0;
		for(byte b : h) {
			sum += b & 0xFF;
		}
		byte[] cs = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(cs, 0, h, 148, 8);
		System.arraycopy(h, 0, tar, 0, 512);
		try (SeekableSource src = SeekableSources.of(tar); TarReader r = TarReader.open(src)) {
			TarArchiveEntry e = r.getEntry("etc/passwd");
			assertNotNull(e, r.getEntries().toString());
			assertEquals("../../etc/passwd", e.getRawName());
			assertEquals(ArchiveEntry.normalize(e.getRawName()), e.getPath());
		}
	}
}
