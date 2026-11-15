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
package us.bringardner.parley.files.archive.zip;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveFormats;
import us.bringardner.parley.files.archive.format.SeekableSource;
import us.bringardner.parley.files.archive.format.SeekableSources;

/** The zip reader, stream reader and writer, checked against the JDK's java.util.zip. */
public class ZipFormatTest {

	@TempDir
	Path tmp;

	/** what the tests put in archives: path to bytes (null for a folder) */
	public static Map<String, byte[]> sample() {
		Map<String, byte[]> m = new LinkedHashMap<>();
		Random r = new Random(42);
		byte[] noise = new byte[200_000];
		r.nextBytes(noise);
		m.put("readme.txt", "Hello, zip\n".getBytes(StandardCharsets.UTF_8));
		m.put("empty.txt", new byte[0]);
		m.put("docs/", null);
		m.put("docs/guide.md", "# Guide\n".repeat(5000).getBytes(StandardCharsets.UTF_8));
		m.put("bin/noise.dat", noise);
		m.put("deep/a/b/c.txt", "implied folders".getBytes(StandardCharsets.UTF_8));
		m.put("unicode/café 日本.txt", "éè".getBytes(StandardCharsets.UTF_8));
		return m;
	}

	static byte[] jdkZip(Map<String, byte[]> m, boolean storeNoise) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(bout)) {
			z.setComment("archive comment");
			for(Map.Entry<String, byte[]> e : m.entrySet()) {
				ZipEntry ze = new ZipEntry(e.getKey());
				if( e.getValue() != null && storeNoise && e.getKey().endsWith(".dat") ) {
					ze.setMethod(ZipEntry.STORED);
					ze.setSize(e.getValue().length);
					CRC32 c = new CRC32();
					c.update(e.getValue());
					ze.setCrc(c.getValue());
				}
				if( e.getKey().equals("readme.txt") ) {
					ze.setComment("entry comment");
				}
				ze.setTime(1_700_000_000_000L);
				z.putNextEntry(ze);
				if( e.getValue() != null ) {
					z.write(e.getValue());
				}
				z.closeEntry();
			}
		}
		return bout.toByteArray();
	}

	static byte[] read(InputStream in) throws IOException {
		try (in) {
			return in.readAllBytes();
		}
	}

	static void checkReader(ZipReader r, Map<String, byte[]> m) throws IOException {
		assertEquals(m.size(), r.getEntries().size());
		for(Map.Entry<String, byte[]> e : m.entrySet()) {
			ZipArchiveEntry z = r.getEntry(e.getKey());
			assertNotNull(z, e.getKey());
			assertEquals(e.getValue() == null, z.isDirectory(), e.getKey());
			if( e.getValue() != null ) {
				assertArrayEquals(e.getValue(), read(r.openStream(z)), e.getKey());
				assertEquals(e.getValue().length, z.getSize());
			}
		}
	}

	@Test
	public void readsWhatTheJdkWrites() throws IOException {
		Map<String, byte[]> m = sample();
		for(boolean store : new boolean[] { false, true }) {
			byte[] zip = jdkZip(m, store);
			try (SeekableSource src = SeekableSources.of(zip); ZipReader r = ZipReader.open(src)) {
				checkReader(r, m);
				assertEquals("archive comment", r.getComment());
				assertEquals("entry comment", r.getEntry("readme.txt").getComment());
				assertEquals(1_700_000_000_000L, r.getEntry("readme.txt").getLastModified());
				assertEquals(store ? ZipArchiveEntry.STORED : ZipArchiveEntry.DEFLATED, r.getEntry("bin/noise.dat").getMethod());
			}
		}
	}

	static byte[] ourZip(Map<String, byte[]> m, boolean storeNoise) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipWriter w = new ZipWriter(bout)) {
			w.setComment("archive comment");
			for(Map.Entry<String, byte[]> e : m.entrySet()) {
				ZipArchiveEntry.Builder b = ZipArchiveEntry.builder(e.getKey()).lastModified(1_700_000_000_000L);
				if( e.getKey().equals("readme.txt") ) {
					b.comment("entry comment");
				}
				if( e.getValue() != null && storeNoise && e.getKey().endsWith(".dat") ) {
					CRC32 c = new CRC32();
					c.update(e.getValue());
					b.method(ZipArchiveEntry.STORED).sizeAndCrc(e.getValue().length, c.getValue());
				}
				try (OutputStream out = w.putEntry(b.build())) {
					if( e.getValue() != null ) {
						out.write(e.getValue());
					}
				}
			}
		}
		return bout.toByteArray();
	}

	@Test
	public void theJdkReadsWhatWeWrite() throws IOException {
		Map<String, byte[]> m = sample();
		for(boolean store : new boolean[] { false, true }) {
			byte[] zip = ourZip(m, store);
			Path f = tmp.resolve("ours" + store + ".zip");
			Files.write(f, zip);
			try (ZipFile z = new ZipFile(f.toFile())) {
				assertEquals("archive comment", z.getComment());
				assertEquals(m.size(), z.size());
				for(Map.Entry<String, byte[]> e : m.entrySet()) {
					ZipEntry ze = z.getEntry(e.getKey());
					assertNotNull(ze, e.getKey());
					assertEquals(e.getValue() == null, ze.isDirectory());
					if( e.getValue() != null ) {
						assertArrayEquals(e.getValue(), read(z.getInputStream(ze)), e.getKey());
					}
					assertEquals(1_700_000_000_000L, ze.getTime(), e.getKey());
				}
				assertEquals("entry comment", z.getEntry("readme.txt").getComment());
			}
			// and the JDK's stream reader, which reads the local headers and data descriptors
			try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
				int n = 0;
				for(ZipEntry ze; (ze = zin.getNextEntry()) != null; n++) {
					byte[] want = m.get(ze.getName());
					assertTrue(m.containsKey(ze.getName()), ze.getName());
					byte[] got = zin.readAllBytes();
					if( want != null ) {
						assertArrayEquals(want, got, ze.getName());
					}
				}
				assertEquals(m.size(), n);
			}
			// and our own reader
			try (SeekableSource src = SeekableSources.of(zip); ZipReader r = ZipReader.open(src)) {
				checkReader(r, m);
				assertEquals(0644, r.getEntry("readme.txt").getUnixMode());
				assertEquals(0755, r.getEntry("docs").getUnixMode());
			}
		}
	}

	@Test
	public void streamReaderReadsBothWriters() throws IOException {
		Map<String, byte[]> m = sample();
		for(byte[] zip : List.of(jdkZip(m, false), jdkZip(m, true), ourZip(m, false), ourZip(m, true))) {
			Map<String, byte[]> got = new LinkedHashMap<>();
			try (ZipStreamReader r = new ZipStreamReader(new ByteArrayInputStream(zip))) {
				for(ArchiveEntry e; (e = r.nextEntry()) != null;) {
					got.put(e.isDirectory() ? e.getPath() + "/" : e.getPath(), e.isDirectory() ? null : read(r.getInputStream()));
				}
			}
			assertEquals(m.keySet(), got.keySet());
			for(String k : m.keySet()) {
				assertArrayEquals(m.get(k), got.get(k), k);
			}
		}
	}

	@Test
	public void streamReaderSkipsUnreadEntries() throws IOException {
		Map<String, byte[]> m = sample();
		try (ZipStreamReader r = new ZipStreamReader(new ByteArrayInputStream(ourZip(m, false)))) {
			List<String> names = new ArrayList<>();
			for(ArchiveEntry e; (e = r.nextEntry()) != null;) {
				names.add(e.getPath());
			}
			assertEquals(m.size(), names.size());
		}
	}

	@Test
	public void bytesInFront() throws IOException {
		Map<String, byte[]> m = sample();
		byte[] zip = ourZip(m, true);
		byte[] sfx = new byte[12345];
		Arrays.fill(sfx, (byte) 'X');
		byte[] all = new byte[sfx.length + zip.length];
		System.arraycopy(sfx, 0, all, 0, sfx.length);
		System.arraycopy(zip, 0, all, sfx.length, zip.length);
		try (SeekableSource src = SeekableSources.of(all); ZipReader r = ZipReader.open(src)) {
			checkReader(r, m);
		}
		try (SeekableSource src = SeekableSources.of(all)) {
			ArchiveFormat f = ArchiveFormats.detect(src, "setup.exe");
			assertNotNull(f);
			assertEquals("zip", f.getName());
		}
	}

	@Test
	public void damageIsFound() throws IOException {
		Map<String, byte[]> m = new LinkedHashMap<>();
		m.put("a.txt", "a stored entry that will be damaged".getBytes(StandardCharsets.UTF_8));
		byte[] zip = ourZip(m, false);
		// store it, so a changed byte is a changed byte of content
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		byte[] data = m.get("a.txt");
		CRC32 c = new CRC32();
		c.update(data);
		try (ZipWriter w = new ZipWriter(bout)) {
			try (OutputStream out = w.putEntry(ZipArchiveEntry.builder("a.txt").method(ZipArchiveEntry.STORED).sizeAndCrc(data.length, c.getValue()).build())) {
				out.write(data);
			}
		}
		zip = bout.toByteArray();
		int at = indexOf(zip, data);
		zip[at + 3] ^= 1;
		try (SeekableSource src = SeekableSources.of(zip); ZipReader r = ZipReader.open(src)) {
			ZipException e = assertThrows(ZipException.class, () -> read(r.openStream(r.getEntry("a.txt"))));
			assertTrue(e.getMessage().contains("CRC-32"), e.getMessage());
		}
		assertThrows(ZipException.class, () -> {
			try (SeekableSource src = SeekableSources.of(new byte[100])) {
				ZipReader.open(src);
			}
		});
	}

	static int indexOf(byte[] hay, byte[] needle) {
		outer: for(int i = 0; i <= hay.length - needle.length; i++) {
			for(int j = 0; j < needle.length; j++) {
				if( hay[i + j] != needle[j] ) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	@Test
	public void storedNeedsSizeAndCrc() throws IOException {
		try (ZipWriter w = new ZipWriter(new ByteArrayOutputStream())) {
			ZipException e = assertThrows(ZipException.class, () -> w.putEntry(ZipArchiveEntry.builder("x").method(ZipArchiveEntry.STORED).build()));
			assertTrue(e.getMessage().contains("size and CRC-32"));
			OutputStream out = w.putEntry(ZipArchiveEntry.builder("y").method(ZipArchiveEntry.STORED).sizeAndCrc(3, 0).build());
			out.write(new byte[] { 1, 2, 3 });
			assertThrows(ZipException.class, out::close);
			assertThrows(ZipException.class, () -> w.putEntry(ZipArchiveEntry.builder("y").build()), "a path is written once");
		}
	}

	@Test
	public void unsafeNamesStayInside() throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(bout)) {
			for(String n : new String[] { "../../etc/passwd", "/abs/x.txt", "./a/./b/../c.txt" }) {
				z.putNextEntry(new ZipEntry(n));
				z.write(n.getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		try (SeekableSource src = SeekableSources.of(bout.toByteArray()); ZipReader r = ZipReader.open(src)) {
			assertNotNull(r.getEntry("etc/passwd"));
			assertNotNull(r.getEntry("abs/x.txt"));
			assertNotNull(r.getEntry("a/c.txt"));
			assertEquals("../../etc/passwd", r.getEntry("etc/passwd").getRawName());
		}
	}

	@Test
	public void randomAccessToEntries() throws IOException {
		Map<String, byte[]> m = sample();
		for(boolean store : new boolean[] { false, true }) {
			byte[] zip = ourZip(m, store);
			try (SeekableSource src = SeekableSources.of(zip); ZipReader r = ZipReader.open(src)) {
				byte[] noise = m.get("bin/noise.dat");
				for(String limit : new String[] { null, "0" }) {
					if( limit != null ) {
						System.setProperty(InflatingSeekableSource.MEMORY_LIMIT_PROPERTY, limit);
					}
					try (SeekableSource e = r.openSource(r.getEntry("bin/noise.dat"), src)) {
						assertEquals(noise.length, e.length());
						for(long pos : new long[] { 150_000, 10, 199_990, 0, 100_000 }) {
							byte[] got = new byte[10];
							e.readFully(pos, got, 0, got.length);
							assertArrayEquals(Arrays.copyOfRange(noise, (int) pos, (int) pos + 10), got, "at " + pos);
						}
					} finally {
						System.clearProperty(InflatingSeekableSource.MEMORY_LIMIT_PROPERTY);
					}
				}
			}
		}
	}

	@Test
	public void nestedArchive() throws IOException {
		Map<String, byte[]> inner = sample();
		byte[] innerZip = ourZip(inner, false);
		for(boolean store : new boolean[] { false, true }) {
			Map<String, byte[]> outer = new LinkedHashMap<>();
			outer.put("lib/inner.jar", innerZip);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (ZipWriter w = new ZipWriter(bout)) {
				ZipArchiveEntry.Builder b = ZipArchiveEntry.builder("lib/inner.jar");
				if( store ) {
					CRC32 c = new CRC32();
					c.update(innerZip);
					b.method(ZipArchiveEntry.STORED).sizeAndCrc(innerZip.length, c.getValue());
				}
				try (OutputStream out = w.putEntry(b.build())) {
					out.write(innerZip);
				}
			}
			try (SeekableSource src = SeekableSources.of(bout.toByteArray()); ZipReader r = ZipReader.open(src);
					SeekableSource innerSrc = r.openSource(r.getEntry("lib/inner.jar"), src);
					ZipReader ir = ZipReader.open(innerSrc)) {
				checkReader(ir, inner);
			}
		}
	}

	@Test
	public void manyEntriesUseZip64() throws IOException {
		int n = 70_000;
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipWriter w = new ZipWriter(bout)) {
			for(int i = 0; i < n; i++) {
				try (OutputStream out = w.putEntry(ZipArchiveEntry.builder("f" + i).build())) {
					out.write(i);
				}
			}
		}
		byte[] zip = bout.toByteArray();
		Path f = tmp.resolve("many.zip");
		Files.write(f, zip);
		try (ZipFile z = new ZipFile(f.toFile())) {
			assertEquals(n, z.size());
			assertEquals(69_999 & 0xFF, read(z.getInputStream(z.getEntry("f69999")))[0] & 0xFF);
		}
		try (SeekableSource src = SeekableSources.of(f); ZipReader r = ZipReader.open(src)) {
			assertEquals(n, r.getEntries().size());
			assertEquals(12_345 & 0xFF, read(r.openStream(r.getEntry("f12345")))[0] & 0xFF);
		}
	}

	/** an entry of more than 4 GB: zeros, which deflate to about 4 MB, so it runs in memory */
	@Test
	public void entryOver4GbUsesZip64() throws IOException {
		long size = (1L << 32) + 1234;
		byte[] block = new byte[1 << 20];
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipWriter w = new ZipWriter(bout)) {
			w.setLevel(1);
			try (OutputStream out = w.putEntry(ZipArchiveEntry.builder("small.txt").build())) {
				out.write("before".getBytes(StandardCharsets.UTF_8));
			}
			try (OutputStream out = w.putEntry(ZipArchiveEntry.builder("zeros.bin").build())) {
				for(long done = 0; done < size;) {
					int cnt = (int) Math.min(block.length, size - done);
					out.write(block, 0, cnt);
					done += cnt;
				}
			}
			try (OutputStream out = w.putEntry(ZipArchiveEntry.builder("after.txt").build())) {
				out.write("after".getBytes(StandardCharsets.UTF_8));
			}
		}
		byte[] zip = bout.toByteArray();
		Path f = tmp.resolve("big.zip");
		Files.write(f, zip);
		try (ZipFile z = new ZipFile(f.toFile())) {
			assertEquals(size, z.getEntry("zeros.bin").getSize());
			assertEquals("after", new String(read(z.getInputStream(z.getEntry("after.txt"))), StandardCharsets.UTF_8));
		}
		try (SeekableSource src = SeekableSources.of(f); ZipReader r = ZipReader.open(src)) {
			ZipArchiveEntry e = r.getEntry("zeros.bin");
			assertEquals(size, e.getSize());
			assertEquals("after", new String(read(r.openStream(r.getEntry("after.txt"))), StandardCharsets.UTF_8));
			long total = 0;
			try (InputStream in = r.openStream(e)) {
				for(int cnt; (cnt = in.read(block)) >= 0;) {
					total += cnt;
				}
			}
			assertEquals(size, total);
		}
		// the stream reader reads the 8-byte data descriptor
		try (ZipStreamReader r = new ZipStreamReader(new ByteArrayInputStream(zip))) {
			List<String> names = new ArrayList<>();
			for(ArchiveEntry e; (e = r.nextEntry()) != null;) {
				names.add(e.getPath());
			}
			assertEquals(List.of("small.txt", "zeros.bin", "after.txt"), names);
		}
	}

	@Test
	public void codePage437Names() throws IOException {
		// a name written by an old tool: no UTF-8 flag, byte 0x82 is é in code page 437
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(bout, java.nio.charset.Charset.forName("IBM437"))) {
			z.putNextEntry(new ZipEntry("café.txt"));
			z.closeEntry();
		}
		try (SeekableSource src = SeekableSources.of(bout.toByteArray()); ZipReader r = ZipReader.open(src)) {
			assertNotNull(r.getEntry("café.txt"), r.getEntries().toString());
		}
	}
}
