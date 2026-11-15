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

import static us.bringardner.parley.files.archive.zip.ZipConstants.u16;
import static us.bringardner.parley.files.archive.zip.ZipConstants.u32;
import static us.bringardner.parley.files.archive.zip.ZipConstants.u64;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

import us.bringardner.parley.files.archive.format.ArchiveEntry;

/**
 * The extra fields this reader uses (APPNOTE.TXT 4.5 and 4.6): ZIP64 sizes and offset, the
 * extended timestamp (0x5455), NTFS times (0x000a) and the Info-ZIP Unicode path (0x7075).
 * Others are skipped. A field that runs past the end of the block ends the parse: the rest is
 * ignored rather than trusted.
 */
final class ExtraFields {

	/** milliseconds from 1601-01-01 (NTFS) to 1970-01-01 */
	private static final long NTFS_EPOCH_OFFSET_MILLIS = 11644473600000L;

	long size = -1;
	long compressedSize = -1;
	long localHeaderOffset = -1;
	long modified = ArchiveEntry.UNKNOWN;
	long accessed = ArchiveEntry.UNKNOWN;
	long created = ArchiveEntry.UNKNOWN;
	String unicodePath;
	boolean hasZip64;

	/**
	 * @param zip64Size, zip64Csize, zip64Offset whether each is in the ZIP64 field (its 32-bit
	 *        header field was 0xFFFFFFFF), in this order, as APPNOTE says
	 * @param rawName the name's bytes, for the Unicode path's check, or null
	 */
	static ExtraFields parse(byte[] b, int at, int len, boolean zip64Size, boolean zip64Csize, boolean zip64Offset,
			byte[] rawName) throws ZipException {
		ExtraFields ret = new ExtraFields();
		int end = at + len;
		int p = at;
		while( p + 4 <= end ) {
			int id = u16(b, p);
			int size = u16(b, p + 2);
			int data = p + 4;
			if( data + size > end ) {
				break;
			}
			switch (id) {
			case ZipConstants.EXTRA_ZIP64:
				ret.zip64(b, data, size, zip64Size, zip64Csize, zip64Offset);
				break;
			case ZipConstants.EXTRA_EXT_TIME:
				ret.extendedTime(b, data, size);
				break;
			case ZipConstants.EXTRA_NTFS:
				ret.ntfsTimes(b, data, size);
				break;
			case ZipConstants.EXTRA_UNICODE_PATH:
				ret.unicodePath(b, data, size, rawName);
				break;
			default:
				break;
			}
			p = data + size;
		}
		return ret;
	}

	private void zip64(byte[] b, int data, int size, boolean zip64Size, boolean zip64Csize, boolean zip64Offset) throws ZipException {
		hasZip64 = true;
		int q = data;
		int end = data + size;
		if( zip64Size && q + 8 <= end ) {
			this.size = u64(b, q);
			q += 8;
		}
		if( zip64Csize && q + 8 <= end ) {
			compressedSize = u64(b, q);
			q += 8;
		}
		if( zip64Offset && q + 8 <= end ) {
			localHeaderOffset = u64(b, q);
		}
	}

	/** 0x5455: a flags byte, then the times it flags (a central header holds only the modification time) */
	private void extendedTime(byte[] b, int data, int size) {
		if( size < 1 ) {
			return;
		}
		int flags = b[data] & 0xFF;
		int t = data + 1;
		int end = data + size;
		if( (flags & 1) != 0 && t + 4 <= end ) {
			modified = signed32(b, t) * 1000L;
			t += 4;
		}
		if( (flags & 2) != 0 && t + 4 <= end ) {
			accessed = signed32(b, t) * 1000L;
			t += 4;
		}
		if( (flags & 4) != 0 && t + 4 <= end ) {
			created = signed32(b, t) * 1000L;
		}
	}

	/** 0x000a: 4 reserved bytes, then tags; tag 1 holds the modification, access and creation times */
	private void ntfsTimes(byte[] b, int data, int size) {
		int t = data + 4;
		int end = data + size;
		while( t + 4 <= end ) {
			int tag = u16(b, t);
			int tsize = u16(b, t + 2);
			if( tag == 1 && tsize == 24 && t + 4 + 24 <= end ) {
				// these win over the DOS time, not over 0x5455's
				if( modified == ArchiveEntry.UNKNOWN ) {
					modified = ntfs(b, t + 4);
				}
				if( accessed == ArchiveEntry.UNKNOWN ) {
					accessed = ntfs(b, t + 12);
				}
				if( created == ArchiveEntry.UNKNOWN ) {
					created = ntfs(b, t + 20);
				}
			}
			t += 4 + tsize;
		}
	}

	/** 0x7075: version 1, the CRC-32 of the header's name, then the name in UTF-8 (used only if the CRC matches) */
	private void unicodePath(byte[] b, int data, int size, byte[] rawName) {
		if( size >= 5 && b[data] == 1 && rawName != null ) {
			CRC32 crc = new CRC32();
			crc.update(rawName);
			if( crc.getValue() == u32(b, data + 1) ) {
				unicodePath = new String(b, data + 5, size - 5, StandardCharsets.UTF_8);
			}
		}
	}

	private static long signed32(byte[] b, int at) {
		return (int) u32(b, at);
	}

	private static long ntfs(byte[] b, int at) {
		long ticks = u32(b, at) | u32(b, at + 4) << 32;
		if( ticks == 0 ) {
			return ArchiveEntry.UNKNOWN;
		}
		return ticks / 10000 - NTFS_EPOCH_OFFSET_MILLIS;
	}
}
