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
package us.bringardner.parley.files.archive.format;

import java.util.Objects;

/**
 * A file or folder in an archive, as its format describes it. Immutable.
 * <p>
 * The path is the entry's name in the archive, {@code /}-separated and made safe by
 * {@link #normalize(String)}: no leading or trailing {@code /}, and no empty, {@code .} or
 * {@code ..} parts. A format's own class adds what that format knows (a zip entry's method and
 * CRC, say).
 */
public class ArchiveEntry {

	/** A value that isn't known: a size, a time, a mode. */
	public static final long UNKNOWN = -1;

	private final String path;
	private final boolean directory;
	private final long size;
	private final long lastModified;

	/**
	 * @param path the entry's path; it is normalized
	 * @param directory true for a folder
	 * @param size the uncompressed size in bytes, or {@link #UNKNOWN}
	 * @param lastModified milliseconds since 1970, or {@link #UNKNOWN}
	 */
	public ArchiveEntry(String path, boolean directory, long size, long lastModified) {
		this.path = normalize(Objects.requireNonNull(path, "path"));
		this.directory = directory;
		this.size = directory ? 0 : size;
		this.lastModified = lastModified;
	}

	/** @return the path in the archive, {@code /}-separated, "" for the archive's top folder */
	public String getPath() {
		return path;
	}

	/** @return the last part of the path */
	public String getName() {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	public boolean isDirectory() {
		return directory;
	}

	/** @return the uncompressed size in bytes (0 for a folder), or {@link #UNKNOWN} */
	public long getSize() {
		return size;
	}

	/** @return when it was last changed, milliseconds since 1970, or {@link #UNKNOWN} */
	public long getLastModified() {
		return lastModified;
	}

	/** @return when it was last read, milliseconds since 1970, or {@link #UNKNOWN} (the default) */
	public long getLastAccessTime() {
		return UNKNOWN;
	}

	/** @return when it was made, milliseconds since 1970, or {@link #UNKNOWN} (the default) */
	public long getCreationTime() {
		return UNKNOWN;
	}

	/**
	 * @return the Unix permission bits (0777 and the set-id and sticky bits), or {@link #UNKNOWN}
	 *         when the archive doesn't say (the default)
	 */
	public long getUnixMode() {
		return UNKNOWN;
	}

	/** @return true if the archive says it is a symbolic link (its bytes are the target) */
	public boolean isSymbolicLink() {
		return false;
	}

	@Override
	public String toString() {
		return directory ? path + "/" : path;
	}

	/**
	 * Makes a name from an archive safe to use as a path: {@code /}-separated, no leading or
	 * trailing {@code /}, empty and {@code .} parts dropped, and each {@code ..} removing the
	 * part before it, never going above the archive's top folder (so {@code ../../etc/passwd}
	 * is {@code etc/passwd}). A backslash is part of a name, not a separator, as APPNOTE says.
	 *
	 * @return the path, "" for the top folder
	 */
	public static String normalize(String name) {
		if( name.isEmpty() ) {
			return name;
		}
		boolean clean = name.charAt(0) != '/' && name.charAt(name.length() - 1) != '/'
				&& !name.contains("//") && !name.equals(".") && !name.equals("..")
				&& !name.startsWith("./") && !name.startsWith("../")
				&& !name.contains("/./") && !name.contains("/../")
				&& !name.endsWith("/.") && !name.endsWith("/..");
		if( clean ) {
			return name;
		}
		String[] parts = name.split("/");
		String[] keep = new String[parts.length];
		int n = 0;
		for(String p : parts) {
			if( p.isEmpty() || p.equals(".") ) {
				continue;
			}
			if( p.equals("..") ) {
				if( n > 0 ) {
					n--;
				}
				continue;
			}
			keep[n++] = p;
		}
		return String.join("/", java.util.Arrays.copyOf(keep, n));
	}

	/** @return the folder path holds, "" for the top folder, null for the top folder itself */
	public static String parentOf(String path) {
		if( path.isEmpty() ) {
			return null;
		}
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}
}
