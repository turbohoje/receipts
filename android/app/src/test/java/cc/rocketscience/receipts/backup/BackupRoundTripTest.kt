package cc.rocketscience.receipts.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class BackupRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val writer = BackupWriter()
    private val reader = BackupReader()

    private fun manifest(
        schemaVersion: Int = BackupFormat.SCHEMA_VERSION,
        receipts: List<BackupReceipt> = defaultReceipts,
    ) = BackupManifest(
        schemaVersion = schemaVersion,
        appVersion = "0.1.0",
        createdAt = 1_787_000_000_000,
        currency = "USD",
        reports = listOf(
            BackupReport("r1", "Q3 Client Trip", 1_787_000_000_000, 1_787_000_000_000),
        ),
        receipts = receipts,
    )

    private val defaultReceipts = listOf(
        BackupReceipt("c1", "r1", "Taxi, airport", 2450, 1_787_000_000_000, "a.jpg", 1),
        BackupReceipt("c2", "r1", "Cash tip", 500, 1_787_000_000_000, null, 2),
    )

    private fun images(vararg names: String): Map<String, File> =
        names.associateWith { temp.newFile(it).apply { writeText("jpeg-$it") } }

    @Test
    fun `a backup round-trips to identical data`() {
        val files = images("a.jpg")
        val out = ByteArrayOutputStream()
        val original = manifest()

        writer.write(out, original) { files[it] }

        val outcome = reader.peek(out.toByteArray().inputStream())
        assertTrue(outcome is RestoreOutcome.Ready)
        val ready = outcome as RestoreOutcome.Ready
        assertEquals(original, ready.manifest)
        assertEquals(setOf("a.jpg"), ready.imageNames)
    }

    @Test
    fun `stats report what was written`() {
        val files = images("a.jpg")
        val stats = writer.write(ByteArrayOutputStream(), manifest()) { files[it] }
        assertEquals(1, stats.reports)
        assertEquals(2, stats.receipts)
        assertEquals(1, stats.imagesWritten)
        assertEquals(0, stats.imagesMissing)
    }

    @Test
    fun `a receipt whose image is gone is still backed up`() {
        // Losing the image is one problem; losing the line item too would be a second.
        val out = ByteArrayOutputStream()
        val stats = writer.write(out, manifest()) { null }

        assertEquals(1, stats.imagesMissing)
        assertEquals(0, stats.imagesWritten)
        val ready = reader.peek(out.toByteArray().inputStream()) as RestoreOutcome.Ready
        assertEquals(2, ready.manifest.receipts.size)
    }

    @Test
    fun `descriptions with commas quotes and newlines survive`() {
        val awkward = listOf(
            BackupReceipt("c1", "r1", "Dinner, \"drinks\"\nand tip", 8100, 1, "a.jpg", 1),
        )
        val files = images("a.jpg")
        val out = ByteArrayOutputStream()
        writer.write(out, manifest(receipts = awkward)) { files[it] }

        val ready = reader.peek(out.toByteArray().inputStream()) as RestoreOutcome.Ready
        assertEquals("Dinner, \"drinks\"\nand tip", ready.manifest.receipts.single().description)
    }

    @Test
    fun `a newer schema version is refused, not half-imported`() {
        val out = ByteArrayOutputStream()
        writer.write(out, manifest(schemaVersion = BackupFormat.SCHEMA_VERSION + 1)) { null }

        val outcome = reader.peek(out.toByteArray().inputStream())
        val refused = outcome as RestoreOutcome.Refused
        val problem = refused.problem as RestoreProblem.TooNew
        assertEquals(BackupFormat.SCHEMA_VERSION + 1, problem.found)
        assertEquals(BackupFormat.SCHEMA_VERSION, problem.supported)
    }

    @Test
    fun `an older schema version is accepted`() {
        // Only newer is refused; a backup from an older app must still restore.
        val out = ByteArrayOutputStream()
        writer.write(out, manifest(schemaVersion = 1)) { null }
        assertTrue(reader.peek(out.toByteArray().inputStream()) is RestoreOutcome.Ready)
    }

    @Test
    fun `a zip with no manifest is refused`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("images/a.jpg"))
            it.write("jpeg".toByteArray())
            it.closeEntry()
        }
        val refused = reader.peek(out.toByteArray().inputStream()) as RestoreOutcome.Refused
        assertTrue(refused.problem is RestoreProblem.NoManifest)
    }

    @Test
    fun `random bytes are refused rather than throwing`() {
        val refused = reader.peek("not a zip at all".toByteArray().inputStream())
            as RestoreOutcome.Refused
        assertTrue(refused.problem is RestoreProblem.NoManifest ||
            refused.problem is RestoreProblem.Unreadable)
    }

    @Test
    fun `extracted images match the originals byte for byte`() {
        val files = images("a.jpg", "b.jpg")
        val receipts = listOf(
            BackupReceipt("c1", "r1", "one", 100, 1, "a.jpg", 1),
            BackupReceipt("c2", "r1", "two", 200, 1, "b.jpg", 2),
        )
        val out = ByteArrayOutputStream()
        writer.write(out, manifest(receipts = receipts)) { files[it] }

        val target = temp.newFolder("restored")
        val count = reader.extractImages(out.toByteArray().inputStream(), target)

        assertEquals(2, count)
        assertEquals("jpeg-a.jpg", File(target, "a.jpg").readText())
        assertEquals("jpeg-b.jpg", File(target, "b.jpg").readText())
    }

    @Test
    fun `a traversal entry name cannot escape the target directory`() {
        // A backup file is untrusted input; it may not be able to write outside images/.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("images/../../evil.jpg"))
            it.write("pwned".toByteArray())
            it.closeEntry()
        }
        val target = temp.newFolder("safe")
        reader.extractImages(out.toByteArray().inputStream(), target)

        assertTrue("must land inside the target", File(target, "evil.jpg").isFile)
        assertFalse(
            "must not escape upwards",
            File(target.parentFile.parentFile, "evil.jpg").exists(),
        )
    }

    @Test
    fun `one image shared by two receipts is written once`() {
        val files = images("shared.jpg")
        val receipts = listOf(
            BackupReceipt("c1", "r1", "one", 100, 1, "shared.jpg", 1),
            BackupReceipt("c2", "r1", "two", 200, 1, "shared.jpg", 2),
        )
        val stats = writer.write(ByteArrayOutputStream(), manifest(receipts = receipts)) { files[it] }
        assertEquals(1, stats.imagesWritten)
    }
}
