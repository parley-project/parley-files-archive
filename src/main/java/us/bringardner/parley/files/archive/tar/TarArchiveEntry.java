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

import java.util.Objects;

import us.bringardner.parley.files.archive.format.ArchiveEntry;

/**
 * An entry of a tar archive: what {@link ArchiveEntry} says, and the tar fields (POSIX ustar and
 * pax, with GNU's long names). Immutable.
 * <p>
 * An entry to write is made with {@link #builder(String)}; a file's size is required, since tar
 * writes it before the data.
 */
public final class TarArchiveEntry extends ArchiveEntry {

	/** a file ('0'; an old archive's NUL means the same) */
	public static final char REGULAR = '0';
	/** a hard link to an entry earlier in the archive ({@link #getLinkName()}) */
	public static final char HARD_LINK = '1';
	/** a symbolic link ({@link #getLinkName()}) */
	public static final char SYMBOLIC_LINK = '2';
	public static final char CHARACTER_DEVICE = '3';
	public static final char BLOCK_DEVICE = '4';
	public static final char DIRECTORY = '5';
	public static final char FIFO = '6';
	/** a file (contiguous, which nothing treats differently) */
	public static final char CONTIGUOUS = '7';
	/** a GNU sparse file: listed, but its bytes can't be read */
	public static final char GNU_SPARSE = 'S';

	private final String rawName;
	private final char type;
	private final String linkName;
	private final long mode;
	private final long uid;
	private final long gid;
	private final String userName;
	private final String groupName;
	private final long lastAccessTime;
	private final long creationTime;
	private final boolean sparse;
	/** where its header starts in the (uncompressed) archive; -1 for an entry to write */
	final long headerOffset;
	/** where its data starts in the (uncompressed) archive; -1 for an entry to write */
	final long dataOffset;
	/** its bytes in the archive (0 for a link, whose bytes are its target) */
	final long storedSize;

	private TarArchiveEntry(Builder b) {
		super(b.path, b.type == DIRECTORY, sizeOf(b), b.lastModified);
		this.rawName = b.rawName == null ? b.path : b.rawName;
		this.type = b.type;
		this.linkName = b.linkName;
		this.mode = b.mode;
		this.uid = b.uid;
		this.gid = b.gid;
		this.userName = b.userName;
		this.groupName = b.groupName;
		this.lastAccessTime = b.lastAccessTime;
		this.creationTime = b.creationTime;
		this.sparse = b.sparse || b.type == GNU_SPARSE;
		this.headerOffset = b.headerOffset;
		this.dataOffset = b.dataOffset;
		this.storedSize = b.storedSize >= 0 ? b.storedSize : b.size;
	}

	/** a hard link with its target's size (its own header says 0): its bytes are its target's */
	TarArchiveEntry withTargetSize(long targetSize) {
		Builder b = new Builder(getPath());
		b.rawName = rawName;
		b.type = type;
		b.linkName = linkName;
		b.size = targetSize;
		b.lastModified = getLastModified();
		b.mode = mode;
		b.uid = uid;
		b.gid = gid;
		b.userName = userName;
		b.groupName = groupName;
		b.lastAccessTime = lastAccessTime;
		b.creationTime = creationTime;
		b.sparse = sparse;
		b.headerOffset = headerOffset;
		b.dataOffset = dataOffset;
		b.storedSize = storedSize;
		return new TarArchiveEntry(b);
	}

	/** a symbolic link's bytes are its target, as in a zip archive */
	private static long sizeOf(Builder b) {
		if( b.type == SYMBOLIC_LINK && b.linkName != null ) {
			return b.linkName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		}
		return b.size;
	}

	/** @return the name as the archive holds it, before {@link ArchiveEntry#normalize(String)} */
	public String getRawName() {
		return rawName;
	}

	/** @return the type flag ({@link #REGULAR}, {@link #DIRECTORY}, {@link #SYMBOLIC_LINK} ...) */
	public char getType() {
		return type;
	}

	/** @return true for a file whose bytes are in the archive (a regular or contiguous file) */
	public boolean isRegularFile() {
		return type == REGULAR || type == CONTIGUOUS;
	}

	/** @return true for a hard link (its bytes are its target's) */
	public boolean isHardLink() {
		return type == HARD_LINK;
	}

	@Override
	public boolean isSymbolicLink() {
		return type == SYMBOLIC_LINK;
	}

	/** @return a link's target (a path in the archive for a hard link), or null */
	public String getLinkName() {
		return linkName;
	}

	/** @return true for a sparse file, whose bytes can't be read */
	public boolean isSparse() {
		return sparse;
	}

	@Override
	public long getUnixMode() {
		return mode < 0 ? UNKNOWN : mode & 07777;
	}

	/** @return the owner's user id, or {@link #UNKNOWN} */
	public long getUid() {
		return uid;
	}

	/** @return the group id, or {@link #UNKNOWN} */
	public long getGid() {
		return gid;
	}

	/** @return the owner's user name, or null */
	public String getUserName() {
		return userName;
	}

	/** @return the group name, or null */
	public String getGroupName() {
		return groupName;
	}

	@Override
	public long getLastAccessTime() {
		return lastAccessTime;
	}

	@Override
	public long getCreationTime() {
		return creationTime;
	}

	/** @return a builder for an entry to write, at path (a folder if it ends with /) */
	public static Builder builder(String path) {
		return new Builder(path);
	}

	/** Makes a TarArchiveEntry. */
	public static final class Builder {
		private final String path;
		String rawName;
		char type = REGULAR;
		String linkName;
		long size = UNKNOWN;
		long lastModified = UNKNOWN;
		long mode = UNKNOWN;
		long uid = UNKNOWN;
		long gid = UNKNOWN;
		String userName;
		String groupName;
		long lastAccessTime = UNKNOWN;
		long creationTime = UNKNOWN;
		boolean sparse;
		long headerOffset = -1;
		long dataOffset = -1;
		/** -1: the size */
		long storedSize = -1;

		Builder(String path) {
			this.path = Objects.requireNonNull(path, "path");
			if( path.endsWith("/") ) {
				type = DIRECTORY;
			}
		}

		/** A folder. */
		public Builder directory() {
			type = DIRECTORY;
			size = 0;
			return this;
		}

		/** @param size a file's size in bytes, which must be written exactly */
		public Builder size(long size) {
			if( size < 0 ) {
				throw new IllegalArgumentException("size=" + size);
			}
			this.size = size;
			return this;
		}

		/** A symbolic link to target. */
		public Builder symbolicLink(String target) {
			type = SYMBOLIC_LINK;
			linkName = Objects.requireNonNull(target, "target");
			size = 0;
			return this;
		}

		/** A hard link to target, an entry earlier in the archive. */
		public Builder hardLink(String target) {
			type = HARD_LINK;
			linkName = Objects.requireNonNull(target, "target");
			size = 0;
			return this;
		}

		/** @param millis when it was last changed, or {@link ArchiveEntry#UNKNOWN} for now */
		public Builder lastModified(long millis) {
			this.lastModified = millis;
			return this;
		}

		/** @param mode the Unix permission bits, or {@link ArchiveEntry#UNKNOWN} for 0644 (a folder 0755, a symbolic link 0777) */
		public Builder unixMode(long mode) {
			this.mode = mode;
			return this;
		}

		/** @param uid, gid the owner's ids, or {@link ArchiveEntry#UNKNOWN} for 0 */
		public Builder ids(long uid, long gid) {
			this.uid = uid;
			this.gid = gid;
			return this;
		}

		/** @param user, group the owner's names, or null */
		public Builder names(String user, String group) {
			this.userName = user;
			this.groupName = group;
			return this;
		}

		Builder type(char t) {
			this.type = t;
			return this;
		}

		public TarArchiveEntry build() {
			if( type == DIRECTORY ) {
				size = 0;
			}
			return new TarArchiveEntry(this);
		}
	}
}
