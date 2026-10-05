package cc.rocketscience.receipts.zip

import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Adds [bytes] as a **STORED** (uncompressed) entry.
 *
 * Deflating a JPEG is work that makes the archive bigger: measured on a 40 KB image, the
 * default level produced 40,015 bytes. Images are the bulk of both the backup and the export
 * ZIP, so they are stored and only the text entries — `manifest.json`, `report.csv` — are
 * deflated, where compression is real (~29%).
 *
 * A side benefit matters for the iOS port: `ZipOutputStream` demands size and CRC up front for
 * this method, so a stored entry's local header carries both. Deflated entries get neither —
 * their local header holds zeros and a trailing data descriptor instead — which is why any
 * reader of these archives has to work from the central directory.
 */
fun ZipOutputStream.putStoredEntry(name: String, bytes: ByteArray) {
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}
