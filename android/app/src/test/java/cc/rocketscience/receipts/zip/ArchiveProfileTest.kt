package cc.rocketscience.receipts.zip

import cc.rocketscience.receipts.backup.BackupFormat
import cc.rocketscience.receipts.backup.BackupManifest
import cc.rocketscience.receipts.backup.BackupReader
import cc.rocketscience.receipts.backup.BackupReceipt
import cc.rocketscience.receipts.backup.BackupReport
import cc.rocketscience.receipts.backup.BackupWriter
import cc.rocketscience.receipts.backup.RestoreOutcome
import cc.rocketscience.receipts.export.ExportRow
import cc.rocketscience.receipts.export.ZipExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.Currency
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Pins the archive profile that both platforms have to honour, since a backup written on one
 * is meant to restore on the other. Read through [ZipFile], i.e. the central directory, which
 * is the only place a deflated entry's real size and CRC are recorded.
 */
class ArchiveProfileTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Incompressible, like a real JPEG. Deterministic so sizes are stable across runs. */
    private fun jpegBytes(size: Int = 40_000): ByteArray =
        ByteArray(size).also { Random(7).nextBytes(it) }

    private fun manifest(imageFile: String?) = BackupManifest(
        schemaVersion = BackupFormat.SCHEMA_VERSION,
        appVersion = "0.1.0",
        createdAt = 1_787_000_000_000,
        currency = "USD",
        reports = listOf(BackupReport("r1", "Q3 Client Trip", 1_787_000_000_000, 1_787_000_000_000)),
        receipts = listOf(BackupReceipt("c1", "r1", "Taxi", 2450, 1_787_000_000_000, imageFile, 1)),
    )

    private fun writeBackup(image: ByteArray): File {
        val file = temp.newFile("backup.zip")
        val source = temp.newFile("a.jpg").apply { writeBytes(image) }
        file.outputStream().use { out ->
            BackupWriter().write(out, manifest("a.jpg")) { source }
        }
        return file
    }

    @Test
    fun `images are stored and text is deflated`() {
        val archive = writeBackup(jpegBytes())

        ZipFile(archive).use { zip ->
            val manifest = zip.getEntry(BackupFormat.MANIFEST_ENTRY)
            val image = zip.getEntry("images/a.jpg")
            assertEquals(ZipEntry.DEFLATED, manifest.method)
            assertEquals(ZipEntry.STORED, image.method)
            // Text compresses for real; the assertion is that we still bother.
            assertTrue("manifest should shrink", manifest.compressedSize < manifest.size)
        }
    }

    @Test
    fun `storing an image is smaller than deflating it`() {
        val image = jpegBytes()

        // One entry each, so the only difference is the method.
        fun archive(name: String, write: (java.util.zip.ZipOutputStream) -> Unit): Long =
            temp.newFile(name).also { file ->
                java.util.zip.ZipOutputStream(file.outputStream()).use(write)
            }.length()

        val stored = archive("stored.zip") { it.putStoredEntry("images/a.jpg", image) }
        val deflated = archive("deflated.zip") { zip ->
            zip.putNextEntry(ZipEntry("images/a.jpg"))
            zip.write(image)
            zip.closeEntry()
        }

        assertTrue(
            "stored ($stored) should beat deflated ($deflated) for JPEG bytes",
            stored < deflated,
        )
        // Deflating incompressible bytes does not merely fail to help, it costs.
        assertTrue("deflating should exceed the raw image size", deflated > image.size)
    }

    @Test
    fun `a stored entry carries its own size and CRC`() {
        val image = jpegBytes()
        // Deflated entries cannot: their local header holds zeros plus a trailing data
        // descriptor, which is why a reader must consult the central directory.
        ZipFile(writeBackup(image)).use { zip ->
            val entry = zip.getEntry("images/a.jpg")
            assertEquals(image.size.toLong(), entry.size)
            assertEquals(image.size.toLong(), entry.compressedSize)
            assertEquals(CRC32().apply { update(image) }.value, entry.crc)
        }
    }

    @Test
    fun `a backup of stored images still restores`() {
        val image = jpegBytes()
        val archive = writeBackup(image)

        val outcome = archive.inputStream().use { BackupReader().peek(it) }
        assertTrue(outcome is RestoreOutcome.Ready)
        assertEquals(setOf("a.jpg"), (outcome as RestoreOutcome.Ready).imageNames)

        val target = temp.newFolder("images")
        val extracted = archive.inputStream().use { BackupReader().extractImages(it, target) }
        assertEquals(1, extracted)
        assertTrue("the image must survive byte for byte", File(target, "a.jpg").readBytes().contentEquals(image))
    }

    @Test
    fun `export archives follow the same profile`() {
        val image = jpegBytes()
        val source = temp.newFile("receipt.jpg").apply { writeBytes(image) }
        val archive = temp.newFile("export.zip")
        archive.outputStream().use { out ->
            ZipExporter(ZoneId.of("UTC")).write(
                out,
                listOf(ExportRow(1, 1_787_000_000_000, "Taxi to airport", 2450, source)),
                Currency.getInstance("USD"),
            )
        }

        ZipFile(archive).use { zip ->
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("report.csv").method)
            assertEquals(ZipEntry.STORED, zip.getEntry("images/01-taxi-to-airport.jpg").method)
        }
    }

    @Test
    fun `every entry name is ASCII with forward slashes`() {
        // Keeps filename encoding a non-issue across platforms: no code page to disagree on.
        ZipFile(writeBackup(jpegBytes())).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                assertTrue("$name must be ASCII", name.all { it.code in 0x20..0x7E })
                assertTrue("$name must not contain a backslash", '\\' !in name)
                assertTrue("$name must not be absolute", !name.startsWith("/"))
                assertTrue("$name must not traverse", ".." !in name)
            }
        }
    }
}
