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

import java.util.Objects;

import us.bringardner.parley.files.archive.format.ArchiveEntry;

/**
 * An entry of a zip archive: what {@link ArchiveEntry} says, and the zip fields (APPNOTE.TXT
 * section 4.4). Immutable.
 * <p>
 * An entry to write is made with {@link #builder(String)}. Of its fields, the writer uses the
 * path, folder or not, method, time, mode and comment; a stored entry must also have its size
 * and CRC-32 (the writer checks them).
 */
public final class ZipArchiveEntry extends ArchiveEntry {

	/** Method 0: the bytes as they are */
	public static final int STORED = 0;
	/** Method 8: raw DEFLATE (RFC 1951) */
	public static final int DEFLATED = 8;

	private final String rawName;
	private final int method;
	private final long crc;
	private final long compressedSize;
	private final int flags;
	private final int versionMadeBy;
	private final long externalAttributes;
	private final String comment;
	private final long lastAccessTime;
	private final long creationTime;
	/** where its local header starts, in the source (prefix included); -1 when it isn't known */
	final long localHeaderOffset;

	private ZipArchiveEntry(Builder b) {
		super(b.path, b.directory, b.size, b.lastModified);
		this.rawName = b.rawName == null ? b.path : b.rawName;
		this.method = b.method;
		this.crc = b.crc;
		this.compressedSize = b.directory ? 0 : b.compressedSize;
		this.flags = b.flags;
		this.versionMadeBy = b.versionMadeBy;
		this.externalAttributes = b.externalAttributes;
		this.comment = b.comment;
		this.lastAccessTime = b.lastAccessTime;
		this.creationTime = b.creationTime;
		this.localHeaderOffset = b.localHeaderOffset;
	}

	/** @return the name as the archive holds it, before {@link ArchiveEntry#normalize(String)} (and without a folder's trailing /) */
	public String getRawName() {
		return rawName;
	}

	/** @return the compression method: {@link #STORED}, {@link #DEFLATED}, or another APPNOTE number */
	public int getMethod() {
		return method;
	}

	/** @return the CRC-32 of the uncompressed bytes, or {@link #UNKNOWN} */
	public long getCrc() {
		return crc;
	}

	/** @return the size of the compressed bytes, or {@link #UNKNOWN} */
	public long getCompressedSize() {
		return compressedSize;
	}

	/** @return the general purpose bit flags */
	public int getFlags() {
		return flags;
	}

	/** @return true if the entry is encrypted (flag bit 0) */
	public boolean isEncrypted() {
		return (flags & 1) != 0;
	}

	/** @return "version made by": the system (high byte, 3 = Unix) and the APPNOTE version (low byte) */
	public int getVersionMadeBy() {
		return versionMadeBy;
	}

	/** @return the external file attributes: for an archive made on Unix the mode is the high 16 bits */
	public long getExternalAttributes() {
		return externalAttributes;
	}

	/** @return the entry's comment, or null */
	public String getComment() {
		return comment;
	}

	@Override
	public long getLastAccessTime() {
		return lastAccessTime;
	}

	@Override
	public long getCreationTime() {
		return creationTime;
	}

	/** @return the Unix file type and mode, or -1 when the archive wasn't made on Unix */
	long unixFileMode() {
		if( (versionMadeBy >> 8) != ZipConstants.HOST_UNIX ) {
			return UNKNOWN;
		}
		long mode = (externalAttributes >> 16) & 0xFFFF;
		return mode == 0 ? UNKNOWN : mode;
	}

	@Override
	public long getUnixMode() {
		long mode = unixFileMode();
		return mode < 0 ? UNKNOWN : mode & 07777;
	}

	@Override
	public boolean isSymbolicLink() {
		long mode = unixFileMode();
		return mode >= 0 && (mode & ZipConstants.S_IFMT) == ZipConstants.S_IFLNK;
	}

	/** @return a builder for an entry to write, at path */
	public static Builder builder(String path) {
		return new Builder(path);
	}

	/**
	 * @return a builder holding what entry says: for a zip entry all of it (but not where it
	 *         was), for another format's the path, folder or not, size, times and mode
	 */
	public static Builder builder(ArchiveEntry entry) {
		Builder b = new Builder(entry.getPath())
				.directory(entry.isDirectory())
				.lastModified(entry.getLastModified());
		if( !(entry instanceof ZipArchiveEntry) ) {
			// (a zip entry's attributes are copied whole below, file type included)
			b.unixMode(entry.getUnixMode());
		}
		b.size = entry.getSize();
		b.lastAccessTime = entry.getLastAccessTime();
		b.creationTime = entry.getCreationTime();
		if( entry instanceof ZipArchiveEntry ) {
			ZipArchiveEntry z = (ZipArchiveEntry) entry;
			b.method = z.method;
			b.crc = z.crc;
			b.compressedSize = z.compressedSize;
			b.comment = z.comment;
			b.externalAttributes = z.externalAttributes;
			b.versionMadeBy = z.versionMadeBy;
		}
		return b;
	}

	/** Makes a ZipArchiveEntry. */
	public static final class Builder {
		private final String path;
		String rawName;
		private boolean directory;
		long size = UNKNOWN;
		private long lastModified = UNKNOWN;
		int method = DEFLATED;
		long crc = UNKNOWN;
		long compressedSize = UNKNOWN;
		int flags;
		int versionMadeBy = (ZipConstants.HOST_UNIX << 8) | ZipConstants.VERSION_DEFLATE;
		long externalAttributes;
		String comment;
		long lastAccessTime = UNKNOWN;
		long creationTime = UNKNOWN;
		long localHeaderOffset = -1;
		private long unixMode = UNKNOWN;

		Builder(String path) {
			this.path = Objects.requireNonNull(path, "path");
		}

		/** A folder; a path ending with / is one too. */
		public Builder directory(boolean b) {
			this.directory = b;
			return this;
		}

		/** @param millis when it was last changed, or {@link ArchiveEntry#UNKNOWN} for now */
		public Builder lastModified(long millis) {
			this.lastModified = millis;
			return this;
		}

		/** {@link ZipArchiveEntry#STORED} or {@link ZipArchiveEntry#DEFLATED} (the default) */
		public Builder method(int method) {
			if( method != STORED && method != DEFLATED ) {
				throw new IllegalArgumentException("A zip entry can be written STORED (0) or DEFLATED (8), not " + method);
			}
			this.method = method;
			return this;
		}

		/**
		 * The size and CRC-32 of the uncompressed bytes, which a stored entry must have before it
		 * is written (the writer checks them when the entry ends).
		 */
		public Builder sizeAndCrc(long size, long crc) {
			if( size < 0 || crc < 0 || crc > 0xFFFFFFFFL ) {
				throw new IllegalArgumentException("size=" + size + " crc=" + crc);
			}
			this.size = size;
			this.crc = crc;
			return this;
		}

		/** @param mode the Unix permission bits, or {@link ArchiveEntry#UNKNOWN} for 0644 (a folder 0755) */
		public Builder unixMode(long mode) {
			this.unixMode = mode;
			return this;
		}

		/** @param comment the entry's comment, or null */
		public Builder comment(String comment) {
			this.comment = comment;
			return this;
		}

		boolean isDirectory() {
			return directory || path.endsWith("/");
		}

		long unixMode() {
			return unixMode;
		}

		public ZipArchiveEntry build() {
			if( path.endsWith("/") ) {
				directory = true;
			}
			if( unixMode >= 0 ) {
				long type = directory ? ZipConstants.S_IFDIR : ZipConstants.S_IFREG;
				externalAttributes = ((type | (unixMode & 07777)) << 16) | (directory ? ZipConstants.DOS_DIRECTORY : 0);
				versionMadeBy = (ZipConstants.HOST_UNIX << 8) | (versionMadeBy & 0xFF);
			}
			return new ZipArchiveEntry(this);
		}
	}
}
