package cc.rocketscience.receipts.zip

import cc.rocketscience.receipts.backup.BackupFormat
import cc.rocketscience.receipts.backup.BackupManifest
import cc.rocketscience.receipts.backup.BackupReader
import cc.rocketscience.receipts.backup.BackupReceipt
import cc.rocketscience.receipts.backup.BackupReport
import cc.rocketscience.receipts.backup.BackupWriter
import cc.rocketscience.receipts.backup.RestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * The Android half of the cross-platform check: read the archive iOS wrote, and — on demand —
 * regenerate the archive iOS reads. See `fixtures/interop/README.md`.
 */
class ArchiveInteropTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fixtures = File("../../fixtures/interop")

    private val imageName = "3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg"

    /** Incompressible, like a real JPEG. Deterministic so the fixture's size is stable. */
    private fun jpegBytes(size: Int = 40_000): ByteArray =
        ByteArray(size).also { Random(7).nextBytes(it) }

    /**
     * Two reports whose manual order deliberately contradicts their creation order: the older
     * one sorts first. A fixture where the two agree would pass even if `sortOrder` were
     * dropped on the way through.
     */
    private fun fixtureManifest() = BackupManifest(
        schemaVersion = BackupFormat.SCHEMA_VERSION,
        appVersion = "0.1.0",
        createdAt = 1_756_000_000_123,
        currency = "USD",
        reports = listOf(
            BackupReport(
                id = "11111111-1111-4111-8111-111111111111",
                name = "Q3 Client Trip",
                createdAt = 1_756_000_100_000,   // newer
                updatedAt = 1_756_000_100_000,
                sortOrder = 1,                   // but second by hand
            ),
            BackupReport(
                id = "44444444-4444-4444-8444-444444444444",
                name = "Office Supplies",
                createdAt = 1_756_000_000_000,   // older
                updatedAt = 1_756_000_000_000,
                sortOrder = 0,                   // dragged to the top
            ),
        ),
        receipts = listOf(
            BackupReceipt(
                id = "22222222-2222-4222-8222-222222222222",
                reportId = "11111111-1111-4111-8111-111111111111",
                description = "Taxi, airport",
                amountMinor = 2450,
                date = 1_756_000_000_123,
                imageFile = imageName,
                createdAt = 1,
            ),
            BackupReceipt(
                id = "33333333-3333-4333-8333-333333333333",
                reportId = "11111111-1111-4111-8111-111111111111",
                description = "Cash tip",
                amountMinor = 500,
                date = 1_756_000_000_456,
                imageFile = null,
                createdAt = 2,
            ),
        ),
    )

    @Test
    fun `the iOS fixture restores on Android`() {
        val fixture = File(fixtures, "ios-backup-v2.zip")
        assertTrue(
            "missing ${fixture.path} — see fixtures/interop/README.md",
            fixture.isFile,
        )

        val outcome = fixture.inputStream().use { BackupReader().peek(it) }
        assertTrue("iOS fixture was refused: $outcome", outcome is RestoreOutcome.Ready)
        val manifest = (outcome as RestoreOutcome.Ready).manifest

        assertEquals(BackupFormat.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("USD", manifest.currency)
        assertEquals(1_756_000_000_123, manifest.createdAt)
        assertEquals(2, manifest.receipts.size)

        // The manual order has to survive the trip, and has to beat creation order: the older
        // report sorts first because it was dragged there.
        assertEquals(
            listOf("Office Supplies", "Q3 Client Trip"),
            manifest.reports.sortedBy { it.sortOrder }.map { it.name },
        )
        assertEquals(
            listOf(0L, 1L),
            manifest.reports.sortedBy { it.sortOrder }.map { it.sortOrder },
        )

        val taxi = manifest.receipts.single { it.description == "Taxi, airport" }
        assertEquals(2450L, taxi.amountMinor)
        // The millisecond has to survive: Foundation's Date is seconds, so this is where a
        // unit slip on the iOS side would show up.
        assertEquals(1_756_000_000_123, taxi.date)
        assertEquals(imageName, taxi.imageFile)

        val tip = manifest.receipts.single { it.description == "Cash tip" }
        assertNull("an explicit JSON null must decode as null", tip.imageFile)

        assertEquals(setOf(imageName), outcome.imageNames)

        // Extracting also verifies the archive is well-formed enough for ZipInputStream, which
        // is a stricter reader than the central-directory one iOS uses.
        val target = temp.newFolder("restored")
        val extracted = fixture.inputStream().use { BackupReader().extractImages(it, target) }
        assertEquals(1, extracted)
        assertEquals(40_000, File(target, imageName).length())
    }

    @Test
    fun `a version 1 backup still restores after the bump`() {
        // Bumping the format locks older builds out of newer backups, but must not lock this
        // build out of older ones. A v1 manifest has no sortOrder at all.
        val fixture = File(fixtures, "ios-backup-v1.zip")
        assertTrue("missing ${fixture.path}", fixture.isFile)

        val outcome = fixture.inputStream().use { BackupReader().peek(it) }
        assertTrue("a version 1 backup must still restore: $outcome", outcome is RestoreOutcome.Ready)
        val manifest = (outcome as RestoreOutcome.Ready).manifest
        assertEquals(1, manifest.schemaVersion)
        assertEquals(listOf("Q3 Client Trip"), manifest.reports.map { it.name })
        // Absent in the file, so it falls back to the default and the list sorts by createdAt.
        assertEquals(listOf(0L), manifest.reports.map { it.sortOrder })
    }

    /**
     * Writes `fixtures/interop/android-backup-v2.zip` for the iOS checks to read.
     *
     * Skipped unless asked for, because `java.util.zip` stamps every entry with the current
     * time: the output is not reproducible, so it must not churn on ordinary test runs.
     */
    @Test
    fun `regenerate the Android fixture when asked`() {
        assumeTrue(System.getProperty("rsreceipts.writeFixture") == "true")

        val source = temp.newFile(imageName).apply { writeBytes(jpegBytes()) }
        val target = File(fixtures, "android-backup-v2.zip")
        target.parentFile?.mkdirs()
        val stats = target.outputStream().use { out ->
            BackupWriter().write(out, fixtureManifest()) { if (it == imageName) source else null }
        }

        assertEquals(1, stats.imagesWritten)
        assertTrue(target.isFile && target.length() > 40_000)
        println("wrote ${target.canonicalPath} (${target.length()} bytes)")
    }
}
