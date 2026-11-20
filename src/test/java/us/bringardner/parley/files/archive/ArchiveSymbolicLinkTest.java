package us.bringardner.parley.files.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.archive.tar.TarArchiveEntry;
import us.bringardner.parley.files.archive.tar.TarWriter;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * A symbolic link in an archive is a symbolic link (D78): its own mode, time and length (that of its text),
 * and what it is asked to do (exist, be read, be listed) is done to what it leads to.
 */
public class ArchiveSymbolicLinkTest {

	private static final long FILE_TIME = 1_500_000_000_000L;
	private static final long LINK_TIME = 1_600_000_000_000L;

	private MemoryFileSourceFactory memory;
	private ArchiveFileSource a;

	private static void file(TarWriter w, String path, String text) throws IOException {
		byte[] b = text.getBytes(StandardCharsets.UTF_8);
		try (OutputStream o = w.putEntry(TarArchiveEntry.builder(path).size(b.length).lastModified(FILE_TIME).unixMode(0640).build())) {
			o.write(b);
		}
	}

	private static void link(TarWriter w, String path, String target) throws IOException {
		w.putEntry(TarArchiveEntry.builder(path).symbolicLink(target).lastModified(LINK_TIME).unixMode(0777).build()).close();
	}

	@BeforeEach
	void setUp() throws IOException {
		memory = new MemoryFileSourceFactory();
		memory.connect();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarWriter w = new TarWriter(bytes)) {
			file(w, "a.txt", "alpha\n");
			w.putEntry(TarArchiveEntry.builder("dir/").lastModified(FILE_TIME).build()).close();
			file(w, "dir/inner.txt", "inner");
			link(w, "ln", "a.txt");
			link(w, "dir/rel", "../a.txt");
			link(w, "abs", "/a.txt");
			link(w, "lnd", "dir");
			link(w, "dangling", "nothing");
			link(w, "loop1", "loop2");
			link(w, "loop2", "loop1");
			link(w, "chain", "ln");
		}
		FileSource dir = memory.createFileSource("/data");
		assertTrue(dir.mkdirs() || dir.isDirectory());
		FileSource tar = dir.getChild("links.tar");
		try (OutputStream out = tar.getOutputStream()) {
			out.write(bytes.toByteArray());
		}
		a = new ArchiveFileSource(tar);
	}

	@AfterEach
	void tearDown() throws IOException {
		memory.disConnect();
	}

	private static String text(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void aLinkHasItsOwnTimeModeAndLengthAndSaysWhereItPoints() throws Exception {
		FileSource ln = a.getChild("ln");
		assertEquals("a.txt", ln.getLinkText());
		assertEquals("/data/links.tar/a.txt", ln.getLinkedTo().getAbsolutePath());
		assertEquals(LINK_TIME, ln.lastModified(), "its own time, not the file's");
		assertEquals("a.txt".length(), ln.length(), "the length of its text, as lstat has it");
		assertEquals(6, ln.contentLength(), "what reading gives");
		assertTrue(ln.canOwnerWrite() == false, "an archive can't be written");
		assertTrue(ln.canGroupExecute(), "the link's own mode is 0777");
		assertFalse(a.getChild("a.txt").canGroupExecute());
		assertNull(a.getChild("a.txt").getLinkText());
		assertNull(a.getChild("a.txt").getLinkedTo());
	}

	@Test
	void aLinkActsLikeWhatItPointsTo() throws Exception {
		FileSource ln = a.getChild("ln");
		assertTrue(ln.exists());
		assertTrue(ln.isFile());
		assertFalse(ln.isDirectory());
		assertEquals("alpha\n", text(ln));
		us.bringardner.parley.files.ISeekableInputStream seekable = ln.getSeekableInputStream();
		try {
			assertEquals(6, seekable.length());
		} finally {
			seekable.close();
		}
		assertTrue(ln.canRead());
		assertEquals("alpha\n", text(a.getChild("chain")), "a link to a link follows both");
		assertEquals("alpha\n", text(a.getChild("abs")), "an absolute target is taken from the archive's top");
		assertEquals("alpha\n", text(a.getChild("dir/rel")), "a relative one from the link's folder");
	}

	@Test
	void aLinkToAFolderListsAndLeadsThrough() throws Exception {
		FileSource lnd = a.getChild("lnd");
		assertTrue(lnd.isDirectory());
		assertFalse(lnd.isFile());
		assertEquals(new HashSet<>(Arrays.asList("inner.txt", "rel")), new HashSet<>(Arrays.asList(lnd.list())));
		assertEquals("inner", text(a.getChild("lnd/inner.txt")), "a path through a linked folder");
		assertTrue(a.getChild("lnd/inner.txt").exists());
	}

	@Test
	void aLinkToNothingIsThereButDoesNotExist() throws Exception {
		FileSource d = a.getChild("dangling");
		assertEquals("nothing", d.getLinkText());
		assertFalse(d.exists());
		assertFalse(d.isFile());
		assertFalse(d.isDirectory());
		assertEquals("nothing".length(), d.length());
		assertEquals(0, d.contentLength());
		assertThrows(FileNotFoundException.class, d::getInputStream);
		assertNotNull(a.getChild("dangling").getParentFile());
	}

	@Test
	void linksThatLeadBackToThemselvesDoNotHang() throws Exception {
		FileSource l = a.getChild("loop1");
		assertNotNull(l.getLinkedTo());
		assertFalse(l.exists());
		assertFalse(l.isFile());
		assertEquals(0, l.contentLength());
		assertThrows(FileNotFoundException.class, l::getInputStream);
	}

	@Test
	void theArchiveListsLinksByName() throws Exception {
		assertTrue(new HashSet<>(Arrays.asList(a.list())).containsAll(Arrays.asList("ln", "lnd", "dangling", "abs")));
	}
}
