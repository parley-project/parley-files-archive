# parley-files-archive

Archives for [parley-files](https://github.com/parley-project/parley-files), part of **Parley**, a
family of Java libraries for implementing internet protocols:

1. a zip reader and writer written from scratch from PKWARE's APPNOTE.TXT, with ZIP64, that reads
   with random access over any `FileSource` that can be read at a position, local or remote;
2. `ArchiveFileSource`, which shows an archive file as a folder, in place, in the file system it is in;
3. `ArchiveFileSourceFactory`, which opens one archive as a file system of its own (type `archive`).

Compression and CRC-32 are the JDK's (`java.util.zip.Deflater`, `Inflater`, `CRC32`); the zip
container is this module's own code. No third-party libraries. Requires Java 11 or later. Depends
on `parley-files`.

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-archive</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

The design is in the doc "parley-files-tree: Views and Archives Design". This module is its
archive half: layer 1 (formats) and the archive's layer 2 FileSources. The tree that shows archives
as folders automatically (`FileTreeFactory`, the `ArchiveResolver` and the file-or-folder setting)
is a separate module, still to come.

## This branch: read only

Above the formats layer everything is read only: what would change a file inside an archive fails
(an `IOException` from a stream or `createNewFile`, `false` from `delete`, `mkdir`, `renameTo` and
the setters, as `java.io.File` answers). Changing an archive (writing a new one and replacing the
original, copying unchanged entries as their compressed bytes) comes on a later branch. `ZipWriter`
already writes archives.

## Layer 1: formats (`us.bringardner.parley.files.archive.format`, `.zip`)

| Class | What it does |
|---|---|
| `ArchiveFormat` | One format. Found with `ServiceLoader` (`ArchiveFormats.all()`, `forName`, `forFileName`, `detect`) |
| `ArchiveReader` | Random access: the index is read at open, then any entry is read on its own |
| `ArchiveStreamReader` | Front to back from a plain stream, for a source that can't seek |
| `ArchiveWriter` | Writes an archive as a stream: entries, then the index |
| `ArchiveEntry` | A file or folder in an archive (immutable): path, folder or not, size, times, Unix mode |
| `SeekableSource` | Bytes read at a position (no file pointer), so entries can be read at the same time. `SeekableSources` makes one from a byte array, a local `Path` or an `ISeekableInputStream` |
| `ZipFormat`, `ZipReader`, `ZipStreamReader`, `ZipWriter`, `ZipArchiveEntry` | Zip |

Usable alone, by any code that reads or writes a zip:

```java
try (SeekableSource src = SeekableSources.of(Path.of("app.jar"));
     ZipReader zip = ZipReader.open(src);
     InputStream in = zip.openStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
    ...
}

try (ZipWriter zip = new ZipWriter(out)) {
    try (OutputStream entry = zip.putEntry(ZipArchiveEntry.builder("logs/today.log").build())) {
        ...
    }
}
```

### Reading

1. `ZipReader` reads from the end: the end of central directory record (searched in the last 4 KB,
   then in the most it can be, 22 bytes and a 65,535-byte comment), the ZIP64 end record when there
   is one, then the whole central directory in one read. Two or three reads, whatever the archive's
   size; each entry then takes one or two more (its local header, whose name and extra lengths can
   differ from the central ones, then its data).
2. The central directory is the source of truth, so entries written as a stream (sizes in a data
   descriptor) read correctly, and so does an archive with bytes in front of it (a self-extracting
   program): every offset is moved by the same amount.
3. Every entry's CRC-32 and size are checked when its stream reaches the end; a mismatch is a
   `ZipException`.
4. Names are UTF-8 when flag bit 11 says so, else code page 437 (decoded by this module: the JDK's
   `IBM437` charset isn't always there). The Info-ZIP Unicode path field (0x7075) wins when its CRC
   matches. Times come from the extended timestamp (0x5455), else NTFS times (0x000a), else the DOS
   time.
5. Paths are made safe (`ArchiveEntry.normalize`): no leading `/`, and `..` can't go above the
   archive's top (`../../etc/passwd` is `etc/passwd`). The name as stored is `getRawName()`.
6. Supported: stored (0) and deflated (8). An entry with another method (Deflate64, bzip2, LZMA,
   Zstandard ...) or encryption is listed, and fails with a `ZipException` naming its method when it
   is read. Archives split over several files are refused.
7. `openSource(entry, source)` gives an entry random access: a stored entry is read in place; a
   deflated one is inflated into memory on first read when it is up to 16 MB (system property
   `parley.files.archive.inflateInMemoryLimit`, bytes), and otherwise inflated from its start
   whenever a read goes backwards (DEFLATE can't be entered in the middle). That is what makes a jar
   inside a war readable.
8. `ZipStreamReader` reads the local headers front to back. A deflated entry marks its own end, so it
   reads reliably even with a data descriptor; a stored entry with a data descriptor has no length to
   go by and is refused (read it with `ZipReader`). Central-directory-only data (Unix modes, comments)
   isn't known this way.

### Writing (`ZipWriter`)

1. Deflated entries (the default) are written with flag bit 3: zeros in the local header, then the
   data, then a data descriptor with the CRC-32 and sizes. Their size needn't be known first.
2. A stored entry must be given its size and CRC-32 first (`ZipArchiveEntry.Builder.sizeAndCrc`),
   which go in its local header and are checked when it ends. Without them it is refused: nothing is
   buffered (design open question 3).
3. ZIP64 only where needed, as the JDK does (open question 2): a known size of 4 GB or more gets a
   ZIP64 field in the local header; an entry that passes 4 GB as it is written gets 8-byte sizes in
   its data descriptor; the central directory and end records use ZIP64 for whatever doesn't fit
   (sizes, offsets, more than 65,535 entries). A small archive has no ZIP64.
4. Names are UTF-8 with bit 11 set when they aren't plain ASCII. Each entry gets the DOS time and an
   extended timestamp (UTC seconds), and is marked as made on Unix with its mode (default 0644,
   folders 0755). A path may be written once.

Checked against the JDK (`ZipFile`, `ZipInputStream`, `ZipOutputStream`) in the tests, and by hand
against Info-ZIP (`unzip -t`, `zipinfo`; archives made by `zip`, `zip -0` and `zip -` read back,
symbolic links included).

## Layer 2: the archive as FileSources (`us.bringardner.parley.files.archive`)

### `ArchiveFileSource`: an archive as a folder, in place

```java
FileSource zip = sftp.createFileSource("/remote/dir/foo.zip");
FileSource dir = new ArchiveFileSource(zip);
dir.isDirectory();                       // true
dir.getChild("inner/x.txt");             // /remote/dir/foo.zip/inner/x.txt
```

1. It extends `DelegatingFileSource`: everything is the archive file's (bytes, size, times,
   permissions, delete, rename) except that it is always a folder (`isDirectory()` true,
   `isFile()` false) whose files come from the archive's index (`listFiles`, `list`, `getChild`).
   Code that wants the archive as a file uses the file itself or a plain `DelegatingFileSource`.
2. The files inside are `ArchiveEntryFileSource`s, named from the archive's path. They report the
   archive file's factory, but a path inside the archive means nothing to that factory. Folders the
   archive implies but doesn't list (`a/b/c.txt` implies `a` and `a/b`) are there.
3. The index is read when the folder is first listed or entered, and again after `refresh()` or when
   the archive's length has changed when an entry is opened. Nothing is held open in between: reading
   the index opens the archive and closes it; reading an entry opens it and closes it when the entry's
   stream is closed. So a remote archive costs no connection while it is only listed, and nothing
   leaks when an `ArchiveFileSource` is dropped.
4. Archives nest: `new ArchiveFileSource(war.getChild("WEB-INF/lib/x.jar"))`.

### `ArchiveFileSourceFactory`: an archive as a file system (type `archive`)

| Setting | Default | What it is |
|---|---|---|
| `archive` | (required) | The archive file: a `filesource:` URL (any file system, local or remote) or a path on the default factory, as `FileSourceFactory.resolve` takes them |
| `format` | (found from the content) | The archive's format by name (`zip`) |

The archive's top folder is `/`. `new ArchiveFileSourceFactory(fileSource)` opens a FileSource the
caller has. A file's URL is `filesource:/inner/x.txt?sourcetype=archive&sessionid=N` from a connected
factory, or `...&archive=<the archive file's URL, URL-encoded>` from an `ArchiveFileSource` used on its
own, which opens a factory on the same archive. `connect()` reads the index, so a setting that doesn't
name an archive fails there.

### Reading the archive file

`FileSourceSources.open(file)` is how the archive's bytes are read: `getSeekableInputStream()` where
the file system has it; where it throws `UnsupportedOperationException`, or an `IOException` for a
file that is there, `getInputStream(position)`,
kept open while reads go forward and opened again when one goes back. Either way the last 64 KB block
read is kept, so the small headers cost one round trip per block over a remote protocol.

### Permissions, times and links inside

1. Read and execute come from the Unix mode the archive holds; without one, files are readable and
   folders executable. Nothing is writable. Owner and group are the archive file's.
2. Times are the entry's; an implied folder has the archive file's modification time.
3. A symbolic link stored in an archive (Info-ZIP keeps them) is a file whose bytes are its target
   (`ArchiveEntry.isSymbolicLink()` says which); `getLinkedTo()` is null.
4. Locks are the FileSource default (`PROCESS` scope).

## Known limits

1. Seeking inside a large deflated entry inflates everything before the position (a checkpoint
   index, zlib's `zran`, could make it cheap; not done).
2. A path that is both a file and a folder in an archive (a file `a` and a file `a/b`) is a folder.
3. A path listed twice is its last entry.
4. Random access (design open question 1): FileProxy, memory, SFTP, FTP, WebDAV and JDBC implement
   `getSeekableInputStream()`; parley-files-vfs does for the schemes VFS gives random access, and the
   rest fall back to positioned streams, which are slower for reads that go backwards. How cheap each
   is over the network depends on the protocol (FTP opens a data connection per positioned read).

## Building

```
mvn clean install
```

The tests write an entry of more than 4 GB (zeros, which deflate to about 4 MB in memory) and an
archive of 70,000 entries, to check ZIP64; together they take a few seconds.
