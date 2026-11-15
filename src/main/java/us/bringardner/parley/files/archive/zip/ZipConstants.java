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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;

import us.bringardner.parley.files.archive.format.ArchiveEntry;

/** Numbers and helpers from APPNOTE.TXT, shared by the zip classes. */
final class ZipConstants {

	private ZipConstants() {
	}

	static final int LOCAL_HEADER_SIG = 0x04034b50;
	static final int DATA_DESCRIPTOR_SIG = 0x08074b50;
	static final int CENTRAL_HEADER_SIG = 0x02014b50;
	static final int EOCD_SIG = 0x06054b50;
	static final int ZIP64_EOCD_SIG = 0x06064b50;
	static final int ZIP64_LOCATOR_SIG = 0x07064b50;

	static final int LOCAL_HEADER_SIZE = 30;
	static final int CENTRAL_HEADER_SIZE = 46;
	static final int EOCD_SIZE = 22;
	static final int ZIP64_EOCD_SIZE = 56;
	static final int ZIP64_LOCATOR_SIZE = 20;
	/** the largest an EOCD record can be: its fixed part and a comment of 65,535 bytes */
	static final int MAX_EOCD_SEARCH = EOCD_SIZE + 0xFFFF;

	/** a 32-bit field that says "see the ZIP64 extra field" */
	static final long ZIP64_MAGIC = 0xFFFFFFFFL;
	/** a 16-bit field that says "see the ZIP64 end record" */
	static final int ZIP64_MAGIC_16 = 0xFFFF;

	static final int EXTRA_ZIP64 = 0x0001;
	static final int EXTRA_NTFS = 0x000a;
	static final int EXTRA_EXT_TIME = 0x5455;
	static final int EXTRA_UNICODE_PATH = 0x7075;

	static final int FLAG_ENCRYPTED = 1;
	static final int FLAG_DATA_DESCRIPTOR = 1 << 3;
	static final int FLAG_UTF8 = 1 << 11;

	static final int VERSION_STORED = 10;
	static final int VERSION_DEFLATE = 20;
	static final int VERSION_ZIP64 = 45;

	static final int HOST_UNIX = 3;

	static final long S_IFMT = 0170000;
	static final long S_IFREG = 0100000;
	static final long S_IFDIR = 0040000;
	static final long S_IFLNK = 0120000;
	static final long DOS_DIRECTORY = 0x10;

	static int u16(byte[] b, int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
	}

	static long u32(byte[] b, int at) {
		return (u16(b, at) | (long) u16(b, at + 2) << 16) & 0xFFFFFFFFL;
	}

	static int sig(byte[] b, int at) {
		return (int) u32(b, at);
	}

	/** an unsigned 64-bit field; one that doesn't fit in a long can't be a size or offset */
	static long u64(byte[] b, int at) throws java.util.zip.ZipException {
		long v = u32(b, at) | u32(b, at + 4) << 32;
		if( v < 0 ) {
			throw new java.util.zip.ZipException("A ZIP64 value is too large: " + Long.toUnsignedString(v));
		}
		return v;
	}

	static void put16(byte[] b, int at, int v) {
		b[at] = (byte) v;
		b[at + 1] = (byte) (v >> 8);
	}

	static void put32(byte[] b, int at, long v) {
		put16(b, at, (int) v);
		put16(b, at + 2, (int) (v >> 16));
	}

	static void put64(byte[] b, int at, long v) {
		put32(b, at, v);
		put32(b, at + 4, v >>> 32);
	}

	/** @return a DOS date (high 16 bits) and time (low 16 bits), as local time, as a millisecond time */
	static long dosToMillis(long dos) {
		int date = (int) (dos >> 16) & 0xFFFF;
		int time = (int) dos & 0xFFFF;
		if( date == 0 ) {
			return ArchiveEntry.UNKNOWN;
		}
		try {
			LocalDateTime t = LocalDateTime.of(((date >> 9) & 0x7f) + 1980, Math.max(1, (date >> 5) & 0x0f),
					Math.max(1, date & 0x1f), (time >> 11) & 0x1f, (time >> 5) & 0x3f, (time << 1) & 0x3e);
			return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (java.time.DateTimeException e) {
			// a field out of range: no usable time
			return ArchiveEntry.UNKNOWN;
		}
	}

	/** @return millis as a DOS date and time (local time), clamped to 1980 through 2107 */
	static long millisToDos(long millis) {
		LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		if( t.getYear() < 1980 ) {
			return (1 << 21) | (1 << 16);   // 1980-01-01 00:00
		}
		if( t.getYear() > 2107 ) {
			return 0xFF9FBF7DL;   // 2107-12-31 23:59:58
		}
		long date = (t.getYear() - 1980) << 9 | t.getMonthValue() << 5 | t.getDayOfMonth();
		long time = t.getHour() << 11 | t.getMinute() << 5 | t.getSecond() >> 1;
		return date << 16 | time;
	}

	/** A name or comment: UTF-8 when the flag says so, else IBM code page 437, as APPNOTE says. */
	static String decode(byte[] b, int at, int len, boolean utf8) {
		if( utf8 ) {
			return new String(b, at, len, StandardCharsets.UTF_8);
		}
		char[] c = new char[len];
		for(int i = 0; i < len; i++) {
			int v = b[at + i] & 0xFF;
			c[i] = v < 0x80 ? (char) v : CP437_HIGH.charAt(v - 0x80);
		}
		return new String(c);
	}

	/** @return true if s has a character outside ASCII (so it is written as UTF-8 with the flag) */
	static boolean needsUtf8(String s) {
		if( s == null ) {
			return false;
		}
		for(int i = 0; i < s.length(); i++) {
			if( s.charAt(i) >= 0x80 ) {
				return true;
			}
		}
		return false;
	}

	/** code page 437, 0x80 to 0xFF (0x00 to 0x7F are ASCII); the JDK's IBM437 charset isn't always there */
	private static final String CP437_HIGH =
			"ÇüéâäàåçêëèïîìÄÅ"
			+ "ÉæÆôöòûùÿÖÜ¢£¥₧ƒ"
			+ "áíóúñÑªº¿⌐¬½¼¡«»"
			+ "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐"
			+ "└┴┬├─┼╞╟╚╔╩╦╠═╬╧"
			+ "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀"
			+ "αßΓπΣσµτΦΘΩδ∞φε∩"
			+ "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ";
}
