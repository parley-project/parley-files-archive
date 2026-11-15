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
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUri;
import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.archive.format.ArchiveFormat;
import us.bringardner.parley.files.archive.format.ArchiveFormats;

/**
 * One archive as a file system of its own: its top folder is the root, {@code /}, and its
 * files are {@code /inner/x.txt}. Read only.
 * <p>
 * This is the optional factory of the parley-files-tree design (open question 5): for code that
 * wants an archive's paths as a file system (a URL, a connection in fsh or the viewer). Showing an
 * archive as a folder in place, in the file system it is in, is {@link ArchiveFileSource}'s job.
 * <p>
 * The connection's settings:
 * <ul>
 * <li>{@value #PROP_ARCHIVE} (required): the archive file, as a {@code filesource:} URL (any file
 * system, local or remote) or a path on the default factory, as
 * {@link FileSourceFactory#resolve(String)} takes them;</li>
 * <li>{@value #PROP_FORMAT}: the archive's format ({@code zip}); empty, the default, finds it from
 * the content.</li>
 * </ul>
 * A URL is {@code filesource:/inner/x.txt?sourcetype=archive&archive=<the archive's URL or path,
 * URL-encoded>}. {@link #ArchiveFileSourceFactory(FileSource)} opens a FileSource the caller
 * already has.
 * <p>
 * <h2>Locks, atomic create and FileChannel</h2>
 * <ul>
 * <li><b>Locks:</b> {@code PROCESS} scope (the FileSource default): this JVM only. Nothing is
 * written, so a lock only keeps out other readers that ask for one.</li>
 * <li><b>Atomic create:</b> nothing can be created.</li>
 * <li><b>FileChannel:</b> read only, through the entry's random access (a deflated entry is
 * inflated from its start when a read goes backwards, or held in memory when small).</li>
 * </ul>
 */
public class ArchiveFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = "archive";
	/** the archive file: a filesource: URL or a path on the default factory */
	public static final String PROP_ARCHIVE = "archive";
	/** the archive's format by name ({@code zip}); empty finds it from the content */
	public static final String PROP_FORMAT = "format";

	/** as set: a URL or a path, or null when the archive was given as a FileSource */
	private String archive;
	private String format;
	/** given to the constructor: used instead of resolving {@link #archive} */
	private transient FileSource archiveFile;

	private transient volatile ArchiveHandle handle;
	private transient volatile ArchiveEntryFileSource root;
	private transient volatile FileSource currentDirectory;
	private final transient ArchiveMount mount = new Mount();

	/** A factory to configure with {@link #setConnectionProperties(Properties)} (or a URL). */
	public ArchiveFileSourceFactory() {
		super();
	}

	/** @param archive the archive file; its format is found from its content */
	public ArchiveFileSourceFactory(FileSource archive) {
		this();
		this.archiveFile = java.util.Objects.requireNonNull(archive, "archive");
	}

	/** Names files by their path in the archive, from /. */
	private final class Mount implements ArchiveMount {
		@Override
		public FileSourceFactory factory() {
			return ArchiveFileSourceFactory.this;
		}

		@Override
		public String absolutePath(String entryPath) {
			return "/" + entryPath;
		}

		@Override
		public FileSource top() {
			return root;
		}

		@Override
		public FileSource parentOfTop() {
			return null;
		}

		@Override
		public FileSource resolveAbsolute(String path) throws IOException {
			return createFileSource(path);
		}

		@Override
		public char separator() {
			return '/';
		}
	}

	// ---- settings

	/** @return the archive setting: a filesource: URL or a path, or null */
	public String getArchive() {
		return archive;
	}

	/** @param archive a filesource: URL or a path on the default factory, for the next connect */
	public void setArchive(String archive) {
		this.archive = archive == null || archive.trim().isEmpty() ? null : archive.trim();
		this.archiveFile = null;
	}

	/** @return the format setting, or null to find it from the content */
	public String getFormat() {
		return format;
	}

	/** @param format a format's name ({@code zip}), or null to find it from the content */
	public void setFormat(String format) {
		this.format = format == null || format.trim().isEmpty() ? null : format.trim();
	}

	/** @return the archive file, once connected (or as given to the constructor) */
	public FileSource getArchiveFile() {
		ArchiveHandle h = handle;
		return h != null ? h.backing() : archiveFile;
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		String[] names = ArchiveFormats.all().stream().map(ArchiveFormat::getName).toArray(String[]::new);
		String[] choices = new String[names.length + 1];
		choices[0] = "";
		System.arraycopy(names, 0, choices, 1, names.length);
		return List.of(
				ConnectionSetting.text(PROP_ARCHIVE, "Archive").asRequired()
					.withDescription("The archive file: a filesource: URL (any file system) or a local path"),
				ConnectionSetting.choice(PROP_FORMAT, "Format", choices).asAdvanced()
					.withDescription("Empty: found from the archive's content"));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_ARCHIVE, archive != null ? archive : archiveFile != null ? urlOrPath(archiveFile) : "");
		if( format != null ) {
			ret.setProperty(PROP_FORMAT, format);
		}
		return ret;
	}

	private static String urlOrPath(FileSource f) {
		try {
			return f.toURL().toString();
		} catch (MalformedURLException | RuntimeException e) {
			return f.getAbsolutePath();
		}
	}

	/**
	 * No setting is secret: the archive setting is shown and saved. A remote archive's password
	 * belongs in a credential reference in its URL, as any Parley connection's does.
	 */
	@Override
	public boolean isSecretProperty(String name) {
		return false;
	}

	@Override
	public void setConnectionProperties(Properties prop) {
		if( prop == null ) {
			setArchive(null);
			setFormat(null);
		} else {
			setArchive(prop.getProperty(PROP_ARCHIVE));
			setFormat(prop.getProperty(PROP_FORMAT));
		}
	}

	@Override
	public void setConnectionProperties(URL url) {
		if( url == null ) {
			setArchive(null);
			setFormat(null);
			return;
		}
		try {
			FileSourceUri u = new FileSourceUri(url.toURI());
			String a = u.getQueryByName(PROP_ARCHIVE);
			if( a != null ) {
				setArchive(decode(a));
			}
			String f = u.getQueryByName(PROP_FORMAT);
			if( f != null ) {
				setFormat(decode(f));
			}
		} catch (IOException | URISyntaxException e) {
			logError("Can't read the archive from " + FileSourceFactory.safe(String.valueOf(url)), e);
		}
	}

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String encode(String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The URL of a file in an archive: from a connected factory, its session; otherwise the
	 * archive file's own URL, so the URL opens a factory on the same archive.
	 */
	static URL urlOf(FileSource archiveFile, ArchiveMount mount, String path) throws MalformedURLException {
		StringBuilder b = new StringBuilder(FILE_SOURCE_PROTOCOL).append(':').append(path)
				.append('?').append(QUERY_STRING_SOURCE_TYPE).append('=').append(FACTORY_ID);
		if( mount.factory() instanceof ArchiveFileSourceFactory && mount.factory().getSessionId() >= 0 ) {
			b.append('&').append(QUERY_STRING_SESSION_ID).append('=').append(mount.factory().getSessionId());
		} else {
			b.append('&').append(PROP_ARCHIVE).append('=').append(encode(archiveFile.toURL().toString()));
		}
		return new URL(b.toString());
	}

	// ---- connection

	@Override
	protected boolean connectImpl() throws IOException {
		FileSource file = archiveFile;
		if( file == null ) {
			if( archive == null ) {
				throw new IOException("No archive: set " + PROP_ARCHIVE + " to a filesource: URL or a path");
			}
			file = FileSourceFactory.getDefaultFactory().resolve(archive);
		}
		ArchiveFormat f = null;
		if( format != null ) {
			f = ArchiveFormats.forName(format);
			if( f == null ) {
				throw new IOException("No archive format is named " + format);
			}
		}
		ArchiveHandle h = new ArchiveHandle(file, f);
		// read the index now, so a connection to something that isn't an archive fails here
		h.load();
		ArchiveEntryFileSource r;
		synchronized (this) {
			handle = h;
			r = new ArchiveEntryFileSource(h, mount, "");
			root = r;
			currentDirectory = r;
		}
		return true;
	}

	@Override
	protected void disConnectImpl() {
		synchronized (this) {
			handle = null;
			root = null;
			currentDirectory = null;
		}
	}

	@Override
	public boolean isConnected() {
		return handle != null;
	}

	private ArchiveHandle handle() throws IOException {
		ArchiveHandle h = handle;
		if( h == null ) {
			if( !connect() ) {
				throw new IOException("Can't open the archive");
			}
			h = handle;
		}
		return h;
	}

	// ---- files

	@Override
	public FileSource[] listRoots() throws IOException {
		handle();
		return new FileSource[] { root };
	}

	@Override
	public FileSource getCurrentDirectory() throws IOException {
		handle();
		return currentDirectory;
	}

	/** @param dir a folder of this archive */
	@Override
	public void setCurrentDirectory(FileSource dir) throws IOException {
		if( !(dir instanceof ArchiveEntryFileSource) || dir.getFileSourceFactory() != this ) {
			throw new IOException("The current directory has to be a folder of this archive");
		}
		currentDirectory = dir;
	}

	/** A relative path is taken from the current directory; .. can't go above the root. */
	@Override
	public FileSource createFileSource(String path) throws IOException {
		ArchiveHandle h = handle();
		String p = path == null ? "" : path.trim();
		String entryPath;
		if( p.startsWith("/") ) {
			entryPath = ArchiveEntry.normalize(p);
		} else {
			String cwd = ((ArchiveEntryFileSource) currentDirectory).getEntryPath();
			entryPath = ArchiveEntry.normalize(cwd.isEmpty() ? p : cwd + "/" + p);
		}
		return entryPath.isEmpty() ? root : new ArchiveEntryFileSource(h, mount, entryPath);
	}

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	@Override
	public boolean isVersionSupported() {
		return false;
	}

	/**
	 * Each copy reads the archive on its own. Nothing is written, so this factory could be shared;
	 * a copy keeps one thread's connect and disconnect from another's.
	 */
	@Override
	public FileSourceFactory createThreadSafeCopy() {
		ArchiveFileSourceFactory ret = new ArchiveFileSourceFactory();
		ret.archive = archive;
		ret.archiveFile = archiveFile;
		ret.format = format;
		return ret;
	}

	@Override
	public String getTitle() {
		FileSource f = getArchiveFile();
		return FACTORY_ID + ":" + (f != null ? f.getName() : archive == null ? "" : FileSourceFactory.safe(archive));
	}

	@Override
	public String getURL() {
		FileSource f = getArchiveFile();
		String a = archive != null ? archive : f != null ? urlOrPath(f) : "";
		return FILE_SOURCE_PROTOCOL + ":/?" + QUERY_STRING_SOURCE_TYPE + "=" + FACTORY_ID + "&" + PROP_ARCHIVE + "=" + encode(a);
	}

	@Override
	public char getPathSeparatorChar() {
		return ':';
	}

	@Override
	public char getSeparatorChar() {
		return '/';
	}

	/** Nothing can be made in an archive: always the root. */
	@Override
	public FileSource getTempDirectory() throws IOException {
		return getCurrentDirectory();
	}

	/** An archive is read only */
	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new IOException("An archive is read only: no link can be made in it");
	}

	/** An archive is read only */
	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new IOException("An archive is read only: no link can be made in it");
	}
}
