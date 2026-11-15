# Changelog

## parley-files-archive 2.0.0 (unreleased)

### Added (2026-11-14: new module)

- **Zip, from scratch** (`us.bringardner.parley.files.archive.zip`): `ZipReader` (random access from the
  central directory, ZIP64, bytes in front of the archive, UTF-8 and code page 437 names, extended and
  NTFS times, CRC-32 checked), `ZipStreamReader` (front to back from a plain stream) and `ZipWriter`
  (streaming, ZIP64 only where needed as the JDK does, stored entries only with their size and CRC-32
  given). Stored and deflated entries; compression and CRC-32 from java.util.zip.
- **Format layer** (`us.bringardner.parley.files.archive.format`): `ArchiveFormat` (found with
  ServiceLoader), `ArchiveReader`, `ArchiveStreamReader`, `ArchiveWriter`, `ArchiveEntry`,
  `SeekableSource`.
- **`ArchiveFileSource`**: an archive file shown as a folder in place, over any FileSource; read only.
- **`archive` FileSource type** (`ArchiveFileSourceFactory`): one archive as a file system of its own;
  settings `archive` and `format`; read only.
