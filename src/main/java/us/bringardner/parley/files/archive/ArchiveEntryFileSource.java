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
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.archive.format.ArchiveEntry;
import us.bringardner.parley.files.fileproxy.FileProxy;

/**
 * A file or folder inside an archive. It reads its bytes from the archive (no FileSource of its
 * own), and is read only: what would change it fails (an IOException from a stream or a create,
 * false from a delete, mkdir, rename or a setter, as java.io.File answers).
 * <p>
 * Its permissions are the Unix mode the archive holds, where it holds one (read and execute;
 * nothing is writable), and otherwise readable, with folders executable. Its owner and group are
 * the archive file's. A symbolic link stored in an archive is a symbolic link here too (D78): it answers
 * its own mode, time and length (that of its text) and {@link #getLinkText()}, and what it is asked to do
 * (exist, be read or listed) it does as the entry it leads to. A relative target is taken from the link's
 * folder and an absolute one from the archive's top, as the archive can't hold anything outside itself.
 */
public class ArchiveEntryFileSource implements FileSource {

	private static final long serialVersionUID = 1L;

	private final transient ArchiveHandle handle;
	private final transient ArchiveMount mount;
	/** the path in the archive, "" for the top */
	private final String entryPath;

	ArchiveEntryFileSource(ArchiveHandle handle, ArchiveMount mount, String entryPath) {
		this.handle = Objects.requireNonNull(handle, "handle");
		this.mount = Objects.requireNonNull(mount, "mount");
		this.entryPath = ArchiveEntry.normalize(entryPath);
	}

	/** @return its path in the archive, {@code /}-separated, "" for the archive's top folder */
	public String getEntryPath() {
		return entryPath;
	}

	/** @return the archive's entry, or null for a folder the archive only implies, or a path not in it */
	public ArchiveEntry getArchiveEntry() throws IOException {
		ArchiveIndex.Node n = handle.node(entryPath);
		return n == null ? null : n.entry;
	}

	/** @return the archive file */
	public FileSource getArchiveFile() {
		return handle.backing();
	}

	static IOException readOnly(FileSource f) {
		return new IOException(f.getAbsolutePath() + " is in an archive, which is read only");
	}

	private ArchiveIndex.Node node() throws IOException {
		return handle.node(realPath());
	}

	/** How many links are followed, in a path or a chain of them, before it is taken to be a loop. */
	private static final int MAX_LINKS = 40;

	/**
	 * This path with every folder in it that is a symbolic link replaced by what it leads to (not the
	 * last part, which is this file's own). A path that has no link in it is returned as it is.
	 */
	private String realPath() throws IOException {
		String path = entryPath;
		int links = 0;
		int from = 0;
		while( true ) {
			int slash = path.indexOf('/', from);
			if( slash < 0 ) {
				return path;
			}
			String folder = path.substring(0, slash);
			ArchiveIndex.Node n = handle.node(folder);
			if( n != null && n.entry != null && n.entry.isSymbolicLink() ) {
				if( ++links > MAX_LINKS ) {
					return path;   // a loop: it leads nowhere, as the path is not in the archive
				}
				path = ArchiveEntry.normalize(targetOf(folder) + path.substring(slash));
				from = 0;
			} else {
				from = slash + 1;
			}
		}
	}

	/** The path in the archive a link at linkPath points to, from its text. */
	private String targetOf(String linkPath) throws IOException {
		String text = textOf(linkPath);
		if( text.startsWith("/") ) {
			return ArchiveEntry.normalize(text);
		}
		String parent = ArchiveEntry.parentOf(linkPath);
		return ArchiveEntry.normalize(parent == null || parent.isEmpty() ? text : parent + "/" + text);
	}

	/** What the archive holds in a symbolic link: the text of where it points (read, not followed). */
	private String textOf(String linkPath) throws IOException {
		try (InputStream in = handle.openStream(linkPath)) {
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	private boolean isLink() throws IOException {
		ArchiveIndex.Node n = node();
		return n != null && n.entry != null && n.entry.isSymbolicLink();
	}

	/**
	 * What this leads to: itself if it is not a link, the entry at the end of the links if it is (which may
	 * not be in the archive), null if the links lead back to themselves.
	 */
	private ArchiveEntryFileSource followed() throws IOException {
		ArchiveEntryFileSource f = this;
		for(int hops = 0; hops < MAX_LINKS; hops++) {
			if( !f.isLink() ) {
				return f;
			}
			f = (ArchiveEntryFileSource) f.getLinkedTo();
		}
		return null;
	}

	/** The one that reading, listing and the like are done to. @throws FileNotFoundException if it leads nowhere */
	private ArchiveEntryFileSource target() throws IOException {
		ArchiveEntryFileSource f = followed();
		if( f == null ) {
			throw new FileNotFoundException(getAbsolutePath() + " (Too many levels of symbolic links)");
		}
		return f;
	}

	/** The text of where this link points, or null if this isn't a symbolic link. */
	@Override
	public String getLinkText() throws IOException {
		return isLink() ? textOf(node().path) : null;
	}

	private FileSource child(String path) {
		return path.isEmpty() ? mount.top() : new ArchiveEntryFileSource(handle, mount, path);
	}

	// ---- names

	@Override
	public String getAbsolutePath() {
		return mount.absolutePath(entryPath);
	}

	@Override
	public String getCanonicalPath() {
		return getAbsolutePath();
	}

	@Override
	public String getName() {
		if( entryPath.isEmpty() ) {
			// only an ArchiveFileSourceFactory's root has no path in the archive
			return "/";
		}
		return ArchiveIndex.name(entryPath);
	}

	@Override
	public String getTitle() {
		return getName();
	}

	@Override
	public String getParent() {
		try {
			FileSource p = getParentFile();
			return p == null ? null : p.getAbsolutePath();
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public FileSource getParentFile() throws IOException {
		String p = ArchiveEntry.parentOf(entryPath);
		if( p == null ) {
			return mount.parentOfTop();
		}
		return child(p);
	}

	/**
	 * A path inside the archive (a/b, ../c, which can't go above the archive's top), or an
	 * absolute path of the factory.
	 */
	@Override
	public FileSource getChild(String path) throws IOException {
		if( path.startsWith("/") || path.startsWith(String.valueOf(mount.separator())) ) {
			return mount.resolveAbsolute(path);
		}
		String p = mount.separator() == '/' ? path : path.replace(mount.separator(), '/');
		return child(ArchiveEntry.normalize(entryPath.isEmpty() ? p : entryPath + "/" + p));
	}

	// ---- what it is

	@Override
	public boolean exists() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t != null && t.exists();   // as a file: a link to nothing is not there
		}
		return node() != null;
	}

	@Override
	public boolean isDirectory() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t != null && t.isDirectory();
		}
		ArchiveIndex.Node n = node();
		return n != null && n.directory;
	}

	@Override
	public boolean isFile() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t != null && t.isFile();
		}
		ArchiveIndex.Node n = node();
		return n != null && !n.directory;
	}

	@Override
	public boolean isHidden() {
		return getName().startsWith(".");
	}

	@Override
	public long length() throws IOException {
		ArchiveIndex.Node n = node();
		if( n == null || n.directory ) {
			return 0;
		}
		long size = n.entry.getSize();
		return size < 0 ? 0 : size;
	}

	private long time(int which) throws IOException {
		ArchiveIndex.Node n = node();
		if( n == null ) {
			return 0;
		}
		if( n.entry == null ) {
			// an implied folder: the archive's own time
			return handle.backing().lastModified();
		}
		long t = which == 0 ? n.entry.getLastModified() : which == 1 ? n.entry.getLastAccessTime() : n.entry.getCreationTime();
		if( t == ArchiveEntry.UNKNOWN && which != 0 ) {
			t = n.entry.getLastModified();
		}
		return t == ArchiveEntry.UNKNOWN ? 0 : t;
	}

	@Override
	public long lastModified() throws IOException {
		return time(0);
	}

	@Override
	public long lastAccessTime() throws IOException {
		return time(1);
	}

	@Override
	public long creationTime() throws IOException {
		return time(2);
	}

	@Override
	public long getCreateDate() throws IOException {
		return creationTime();
	}

	@Override
	public String getContentType() {
		return isDirectoryQuietly() ? null : FileProxy.getContentType(getName());
	}

	private boolean isDirectoryQuietly() {
		try {
			return isDirectory();
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public FileSourceFactory getFileSourceFactory() {
		return mount.factory();
	}

	/** @return the archive file's owner */
	@Override
	public UserPrincipal getOwner() throws IOException {
		return handle.backing().getOwner();
	}

	/** @return the archive file's group */
	@Override
	public GroupPrincipal getGroup() throws IOException {
		return handle.backing().getGroup();
	}

	/** Where a symbolic link points, which may not be in the archive; null if this isn't a link. */
	@Override
	public FileSource getLinkedTo() throws IOException {
		ArchiveIndex.Node n = node();
		if( n == null || n.entry == null || !n.entry.isSymbolicLink() ) {
			return null;
		}
		return child(targetOf(n.path));
	}

	// ---- listing

	@Override
	public String[] list() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t == null ? null : t.list();
		}
		ArchiveIndex.Node n = node();
		if( n == null || !n.directory ) {
			return null;
		}
		return n.children().toArray(new String[0]);
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		FileSource[] files = listFiles(filter);
		if( files == null ) {
			return null;
		}
		String[] ret = new String[files.length];
		for(int i = 0; i < files.length; i++) {
			ret[i] = files[i].getName();
		}
		return ret;
	}

	@Override
	public FileSource[] listFiles() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t == null ? null : t.listFiles();
		}
		ArchiveIndex.Node n = node();
		if( n == null || !n.directory ) {
			return null;
		}
		List<String> names = n.children();
		FileSource[] ret = new FileSource[names.size()];
		for(int i = 0; i < ret.length; i++) {
			String name = names.get(i);
			ret[i] = new ArchiveEntryFileSource(handle, mount, entryPath.isEmpty() ? name : entryPath + "/" + name);
		}
		return ret;
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		FileSource[] all = listFiles();
		if( all == null || filter == null ) {
			return all;
		}
		List<FileSource> ret = new ArrayList<>();
		for(FileSource f : all) {
			if( filter.accept(f) ) {
				ret.add(f);
			}
		}
		return ret.toArray(new FileSource[0]);
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		FileSource[] ret = listFiles();
		if( progress != null ) {
			progress.setProgress(progress.getMaximum());
		}
		return ret;
	}

	@Override
	public void dereferenceChilderen() {
		// nothing is kept
	}

	/** Reads the archive's index again (if it changed, its files are what it holds now). */
	@Override
	public void refresh() {
		handle.refresh();
	}

	// ---- reading

	@Override
	public InputStream getInputStream() throws IOException {
		if( isLink() ) {
			return target().getInputStream();
		}
		check();
		return handle.openStream(realPath());
	}

	@Override
	public InputStream getInputStream(long startingPosition) throws IOException {
		InputStream in = getInputStream();
		try {
			long left = startingPosition;
			while( left > 0 ) {
				long skipped = in.skip(left);
				if( skipped <= 0 ) {
					if( in.read() < 0 ) {
						break;
					}
					skipped = 1;
				}
				left -= skipped;
			}
			return in;
		} catch (IOException | RuntimeException e) {
			in.close();
			throw e;
		}
	}

	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		if( isLink() ) {
			return target().getSeekableInputStream();
		}
		check();
		return new ArchiveSeekableInputStream(this, handle.openSource(realPath()));
	}

	/** "r" only: the archive is read only. */
	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		if( !"r".equals(mode) ) {
			if( mode.equals("w") || mode.equals("rw") || mode.equals("rws") || mode.equals("rwd") ) {
				throw readOnly(this);
			}
			throw new IllegalArgumentException("invalid mode = " + mode);
		}
		if( isLink() ) {
			return target().getRandomAccessStream(mode);
		}
		check();
		return new FileSourceRandomAccessStream(new ArchiveRandomAccessIoController(this, handle.openSource(realPath())), mode);
	}

	/** @throws FileNotFoundException for a folder, or a path not in the archive */
	private void check() throws IOException {
		ArchiveIndex.Node n = node();
		if( n == null ) {
			throw new FileNotFoundException(getAbsolutePath() + " doesn't exist");
		}
		if( n.directory ) {
			throw new FileNotFoundException(getAbsolutePath() + " is a folder");
		}
	}

	// ---- permissions

	private long mode() throws IOException {
		ArchiveIndex.Node n = node();
		return n == null || n.entry == null ? ArchiveEntry.UNKNOWN : n.entry.getUnixMode();
	}

	private boolean bit(int mask, boolean otherwise) throws IOException {
		if( !exists() ) {
			return false;
		}
		long m = mode();
		return m < 0 ? otherwise : (m & mask) != 0;
	}

	@Override
	public boolean canRead() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t != null && t.canRead();
		}
		return exists() && handle.backing().canRead() && bit(0444, true);
	}

	@Override
	public boolean canWrite() {
		return false;
	}

	@Override
	public boolean canExecute() throws IOException {
		if( isLink() ) {
			ArchiveEntryFileSource t = followed();
			return t != null && t.canExecute();
		}
		return bit(0111, isDirectory());
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return bit(0400, true);
	}

	@Override
	public boolean canOwnerWrite() {
		return false;
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return bit(0100, isDirectory());
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return bit(0040, true);
	}

	@Override
	public boolean canGroupWrite() {
		return false;
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return bit(0010, isDirectory());
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return bit(0004, true);
	}

	@Override
	public boolean canOtherWrite() {
		return false;
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return bit(0001, isDirectory());
	}

	// ---- changes: none (the archive is read only)

	@Override
	public boolean createNewFile() throws IOException {
		if( exists() ) {
			return false;
		}
		throw readOnly(this);
	}

	@Override
	public boolean delete() {
		return false;
	}

	@Override
	public boolean mkdir() {
		return false;
	}

	@Override
	public boolean mkdirs() {
		return false;
	}

	@Override
	public boolean renameTo(FileSource dest) {
		return false;
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		throw readOnly(this);
	}

	@Override
	public OutputStream getOutputStream(boolean append) throws IOException {
		throw readOnly(this);
	}

	@Override
	public boolean setLastModifiedTime(long time) {
		return false;
	}

	@Override
	public boolean setLastAccessTime(long time) {
		return false;
	}

	@Override
	public boolean setCreateTime(long time) {
		return false;
	}

	@Override
	public boolean setExecutable(boolean b) {
		return false;
	}

	@Override
	public boolean setReadable(boolean b) {
		return false;
	}

	@Override
	public boolean setWritable(boolean b) {
		return false;
	}

	@Override
	public boolean setExecutable(boolean b, boolean ownerOnly) {
		return false;
	}

	@Override
	public boolean setReadable(boolean b, boolean ownerOnly) {
		return false;
	}

	@Override
	public boolean setWritable(boolean b, boolean ownerOnly) {
		return false;
	}

	@Override
	public boolean setOwnerExecutable(boolean b) {
		return false;
	}

	@Override
	public boolean setOwnerReadable(boolean b) {
		return false;
	}

	@Override
	public boolean setOwnerWritable(boolean b) {
		return false;
	}

	@Override
	public boolean setGroupExecutable(boolean b) {
		return false;
	}

	@Override
	public boolean setGroupReadable(boolean b) {
		return false;
	}

	@Override
	public boolean setGroupWritable(boolean b) {
		return false;
	}

	@Override
	public boolean setOtherExecutable(boolean b) {
		return false;
	}

	@Override
	public boolean setOtherReadable(boolean b) {
		return false;
	}

	@Override
	public boolean setOtherWritable(boolean b) {
		return false;
	}

	/** already read only: true for a file or folder that is there */
	@Override
	public boolean setReadOnly() throws IOException {
		return exists();
	}

	@Override
	public boolean setGroup(GroupPrincipal group) {
		return false;
	}

	@Override
	public boolean setOwner(UserPrincipal owner) {
		return false;
	}

	// ---- versions: none

	@Override
	public boolean isVersionSupported() {
		return false;
	}

	@Override
	public long getVersion() {
		return 0;
	}

	@Override
	public long getVersionDate() throws IOException {
		return lastModified();
	}

	@Override
	public boolean setVersionDate(long time) {
		return false;
	}

	@Override
	public boolean setVersion(long version, boolean saveChange) {
		return false;
	}

	@Override
	public long getMaxVersion() {
		return 0;
	}

	// ---- identity

	/**
	 * @return an {@code archive} URL: the path in the archive, and the archive's own URL (or, from a
	 *         connected ArchiveFileSourceFactory, its session)
	 */
	@Override
	public URL toURL() throws MalformedURLException {
		return ArchiveFileSourceFactory.urlOf(handle.backing(), mount, "/" + entryPath);
	}

	@Override
	public boolean equals(Object obj) {
		if( !(obj instanceof ArchiveEntryFileSource) ) {
			return false;
		}
		ArchiveEntryFileSource o = (ArchiveEntryFileSource) obj;
		return o.mount == mount && o.entryPath.equals(entryPath);
	}

	@Override
	public int hashCode() {
		return getAbsolutePath().hashCode();
	}

	@Override
	public int compareTo(Object o) {
		return getAbsolutePath().compareTo(o instanceof FileSource ? ((FileSource) o).getAbsolutePath() : String.valueOf(o));
	}

	@Override
	public String toString() {
		return getAbsolutePath();
	}
}
