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
package us.bringardner.parley.files.archive.compress;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A compression a whole file can have (as tar.gz has), as opposed to an archive format: one
 * stream in, one stream out. Shared by the archive formats (a gzipped tar) and by fsh's gzip,
 * gunzip, zcat and tar commands, so the detection and the streams live in one place.
 * <p>
 * Only gzip (RFC 1952), through the JDK's GZIP streams, which read a file of several gzip
 * members as one. bzip2, xz and Zstandard aren't in the JDK and aren't here yet.
 */
public enum Compression {

	/** gzip (RFC 1952): .gz, and .tgz for a gzipped tar */
	GZIP("gzip", List.of("gz", "tgz"), new byte[] { 0x1f, (byte) 0x8b });

	private final String name;
	private final List<String> extensions;
	private final byte[] magic;

	Compression(String name, List<String> extensions, byte[] magic) {
		this.name = name;
		this.extensions = extensions;
		this.magic = magic;
	}

	/** @return its name, lower case ("gzip") */
	public String getName() {
		return name;
	}

	/** @return the file name extensions it usually has, lower case, without the dot */
	public List<String> getExtensions() {
		return extensions;
	}

	/** @return how many bytes {@link #matches(byte[], int)} needs to look at */
	public int magicLength() {
		return magic.length;
	}

	/**
	 * @param head the first bytes of a file
	 * @param len how many of them there are
	 * @return true if they start this compression's data
	 */
	public boolean matches(byte[] head, int len) {
		if( len < magic.length ) {
			return false;
		}
		for(int i = 0; i < magic.length; i++) {
			if( head[i] != magic[i] ) {
				return false;
			}
		}
		return true;
	}

	/** @return the compression whose data head starts, or null */
	public static Compression detect(byte[] head, int len) {
		for(Compression c : values()) {
			if( c.matches(head, len) ) {
				return c;
			}
		}
		return null;
	}

	/** @return the compression fileName's extension says, or null */
	public static Compression forFileName(String fileName) {
		if( fileName == null ) {
			return null;
		}
		String n = fileName.toLowerCase(Locale.ROOT);
		for(Compression c : values()) {
			for(String ext : c.extensions) {
				if( n.endsWith("." + ext) ) {
					return c;
				}
			}
		}
		return null;
	}

	/**
	 * @param in compressed bytes; closed when the returned stream is
	 * @return them uncompressed (every gzip member, one after another)
	 * @throws IOException if in doesn't start with this compression's header
	 */
	public InputStream decompress(InputStream in) throws IOException {
		Objects.requireNonNull(in, "in");
		return new GZIPInputStream(in instanceof BufferedInputStream || in instanceof PushbackInputStream ? in : new BufferedInputStream(in, 64 * 1024), 64 * 1024);
	}

	/**
	 * @param out where the compressed bytes go; closed when the returned stream is (which first
	 *        finishes the compressed data)
	 * @param level 0 to 9, or -1 for the default
	 * @return where to write the bytes to compress
	 */
	public OutputStream compress(OutputStream out, int level) throws IOException {
		Objects.requireNonNull(out, "out");
		if( level < -1 || level > 9 ) {
			throw new IllegalArgumentException("level=" + level);
		}
		return new GZIPOutputStream(out, 64 * 1024) {
			{
				def.setLevel(level);
			}
		};
	}

	/** @return {@link #compress(OutputStream, int)} at the default level */
	public OutputStream compress(OutputStream out) throws IOException {
		return compress(out, Deflater.DEFAULT_COMPRESSION);
	}

	/**
	 * Looks at the start of in: compressed data is decompressed, anything else is passed as it is.
	 *
	 * @param in a stream that may be compressed; closed when the returned stream is
	 * @return the uncompressed bytes
	 */
	public static InputStream decompressIfCompressed(InputStream in) throws IOException {
		int need = 0;
		for(Compression c : values()) {
			need = Math.max(need, c.magic.length);
		}
		PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(in, 64 * 1024), need);
		byte[] head = new byte[need];
		int got = 0;
		while( got < need ) {
			int cnt = pb.read(head, got, need - got);
			if( cnt < 0 ) {
				break;
			}
			got += cnt;
		}
		if( got > 0 ) {
			pb.unread(head, 0, got);
		}
		Compression c = detect(head, got);
		return c == null ? pb : c.decompress(pb);
	}

	@Override
	public String toString() {
		return name;
	}
}
