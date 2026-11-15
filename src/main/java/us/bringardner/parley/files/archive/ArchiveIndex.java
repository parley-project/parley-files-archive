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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import us.bringardner.parley.files.archive.format.ArchiveEntry;

/**
 * An archive's entries as a tree of folders, any format. Immutable once built.
 * <ul>
 * <li>A folder the archive implies but doesn't list (a/b/c.txt implies a and a/b) is there, with
 * no entry.</li>
 * <li>A path listed more than once is its last entry (an archive that was appended to).</li>
 * <li>A path that is both a file and a folder (a file a, and a file a/b) is a folder: the file is
 * hidden, since what is under it could not be reached otherwise.</li>
 * </ul>
 */
final class ArchiveIndex {

	/** A file or folder of the tree. */
	static final class Node {
		final String path;
		/** null for a folder the archive only implies */
		final ArchiveEntry entry;
		final boolean directory;
		private final Set<String> children;

		Node(String path, ArchiveEntry entry, boolean directory, Set<String> children) {
			this.path = path;
			this.entry = entry;
			this.directory = directory;
			this.children = children;
		}

		/** @return the names in this folder, in archive order */
		List<String> children() {
			return children == null ? Collections.emptyList() : new ArrayList<>(children);
		}
	}

	private final Map<String, Node> nodes;

	ArchiveIndex(List<? extends ArchiveEntry> entries) {
		Map<String, Node> map = new HashMap<>(entries.size() * 2 + 4);
		map.put("", new Node("", null, true, new LinkedHashSet<>()));
		for(ArchiveEntry e : entries) {
			String path = e.getPath();
			if( path.isEmpty() ) {
				continue;
			}
			ensureParents(map, path);
			Node old = map.get(path);
			if( old != null && old.directory && !e.isDirectory() ) {
				// a folder already: the file is hidden
				continue;
			}
			Set<String> kids = e.isDirectory() ? (old != null && old.children != null ? old.children : new LinkedHashSet<>()) : null;
			map.put(path, new Node(path, e, e.isDirectory(), kids));
		}
		this.nodes = map;
	}

	/** makes the folders above path, and adds each to its own folder's children */
	private static void ensureParents(Map<String, Node> map, String path) {
		String child = path;
		String parent = ArchiveEntry.parentOf(child);
		while( parent != null ) {
			Node p = map.get(parent);
			if( p == null || !p.directory ) {
				// missing, or a file that is a folder too: a folder (the file is hidden)
				p = new Node(parent, null, true, new LinkedHashSet<>());
				map.put(parent, p);
				p.children.add(name(child));
				child = parent;
				parent = ArchiveEntry.parentOf(child);
				continue;
			}
			p.children.add(name(child));
			// the folders above p are there already
			return;
		}
	}

	static String name(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	/** @return the node at path (normalized), or null */
	Node get(String path) {
		return nodes.get(path);
	}

	/** @return how many files and folders there are, implied folders and the top included */
	int size() {
		return nodes.size();
	}
}
