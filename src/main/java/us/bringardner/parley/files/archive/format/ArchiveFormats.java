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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/** The archive formats on the class path ({@link ArchiveFormat} services), and finding one for an archive. */
public final class ArchiveFormats {

	private static volatile List<ArchiveFormat> formats;

	private ArchiveFormats() {
	}

	/** @return every format found, in class path order */
	public static List<ArchiveFormat> all() {
		List<ArchiveFormat> ret = formats;
		if( ret == null ) {
			List<ArchiveFormat> found = new ArrayList<>();
			for(ArchiveFormat f : ServiceLoader.load(ArchiveFormat.class, ArchiveFormats.class.getClassLoader())) {
				found.add(f);
			}
			formats = ret = Collections.unmodifiableList(found);
		}
		return ret;
	}

	/** @return the format with this name (any case), or null */
	public static ArchiveFormat forName(String name) {
		if( name == null ) {
			return null;
		}
		for(ArchiveFormat f : all()) {
			if( f.getName().equalsIgnoreCase(name) ) {
				return f;
			}
		}
		return null;
	}

	/** @return the format whose archives usually have fileName's extension, or null */
	public static ArchiveFormat forFileName(String fileName) {
		if( fileName == null ) {
			return null;
		}
		String n = fileName.toLowerCase(Locale.ROOT);
		for(ArchiveFormat f : all()) {
			for(String ext : f.getExtensions()) {
				if( n.endsWith("." + ext) ) {
					return f;
				}
			}
		}
		return null;
	}

	/**
	 * The format of the archive in source, by its content: the format fileName's extension
	 * suggests is asked first, then every other.
	 *
	 * @param fileName the archive's name, or null
	 * @return the format, or null if none recognizes it
	 * @throws IOException if source can't be read
	 */
	public static ArchiveFormat detect(SeekableSource source, String fileName) throws IOException {
		ArchiveFormat guess = forFileName(fileName);
		if( guess != null && guess.probe(source) ) {
			return guess;
		}
		for(ArchiveFormat f : all()) {
			if( f != guess && f.probe(source) ) {
				return f;
			}
		}
		return null;
	}
}
