package cc.rocketscience.receipts.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Android half of the cross-OS sharing simulation.
 *
 * The scenario is defined identically in `SharedChecks.swift`: a conflicting edit, a delete,
 * an add only one side has, and two edits in the very same millisecond. Each case breaks a
 * different naive merge.
 */
class SharedMergeTest {

    private val reportId = "rep-1"
    private val deviceA = "device-a"
    private val deviceB = "device-b"
    private val fixtures = File("../../fixtures/shared")

    private fun receipt(
        id: String,
        description: String,
        amount: Long,
        createdAt: Long,
        updatedAt: Long,
        by: String,
        deletedAt: Long? = null,
    ) = SharedReceipt(
        id = id,
        reportId = reportId,
        description = description,
        amountMinor = amount,
        date = 1000,
        imageFile = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        updatedBy = by,
        deletedAt = deletedAt,
    )

    /** Android edits: a later taxi fare, a deleted dinner, a renamed report, a tied museum. */
    private fun deviceBState() = SharedState(
        reportId = reportId,
        currency = "EUR",
        versions = mapOf(deviceB to 2),
        report = SharedReport(
            id = reportId,
            name = "Lisbon Trip (shared)",
            createdAt = 1000,
            updatedAt = 2300,
            sortOrder = 0,
            updatedBy = deviceB,
        ),
        receipts = listOf(
            receipt("r1", "Taxi", 2700, createdAt = 1, updatedAt = 2050, by = deviceB),
            receipt("r2", "Dinner", 8100, createdAt = 2, updatedAt = 2200, by = deviceB,
                deletedAt = 2200),
            receipt("r4", "Museum", 222, createdAt = 4, updatedAt = 5000, by = deviceB),
        ),
    )

    private fun deviceAState() = SharedState(
        reportId = reportId,
        currency = "EUR",
        versions = mapOf(deviceA to 3),
        report = SharedReport(
            id = reportId, name = "Lisbon Trip",
            createdAt = 1000, updatedAt = 1000, sortOrder = 0, updatedBy = deviceA,
        ),
        receipts = listOf(
            receipt("r1", "Taxi", 2600, createdAt = 1, updatedAt = 2000, by = deviceA),
            receipt("r2", "Dinner", 8100, createdAt = 2, updatedAt = 1000, by = deviceA),
            receipt("r3", "Coffee", 450, createdAt = 3, updatedAt = 2100, by = deviceA),
            receipt("r4", "Museum", 111, createdAt = 4, updatedAt = 5000, by = deviceA),
        ),
    )

    private fun merged() = SharedMerge.merge(deviceAState(), deviceBState())

    private fun byId(state: SharedState, id: String) = state.receipts.single { it.id == id }

    @Test
    fun `a later edit to the same field wins`() {
        // Both devices raised the taxi fare; Android's edit is 50ms later.
        assertEquals(2700L, byId(merged(), "r1").amountMinor)
        assertEquals(deviceB, byId(merged(), "r1").updatedBy)
    }

    @Test
    fun `an add only one side has survives`() {
        assertEquals("Coffee", byId(merged(), "r3").description)
        assertEquals(450L, byId(merged(), "r3").amountMinor)
    }

    @Test
    fun `a delete propagates instead of resurrecting`() {
        assertTrue("the tombstone did not win", byId(merged(), "r2").isDeleted)
        assertFalse(
            "a deleted receipt is still visible",
            SharedMerge.visibleReceipts(merged()).any { it.id == "r2" },
        )
    }

    @Test
    fun `an exact tie is broken deterministically, not arbitrarily`() {
        // Same millisecond, different devices. Whoever wins, both platforms must pick the
        // same one — otherwise the two converge to different states and nothing looks wrong.
        assertEquals(222L, byId(merged(), "r4").amountMinor)
        assertEquals(deviceB, byId(merged(), "r4").updatedBy)
    }

    @Test
    fun `merging is commutative and idempotent`() {
        val a = deviceAState()
        val b = deviceBState()
        assertEquals(SharedMerge.merge(a, b), SharedMerge.merge(b, a))
        assertEquals(merged(), SharedMerge.merge(merged(), merged()))
        assertEquals(merged(), SharedMerge.merge(merged(), a))
    }

    @Test
    fun `a clobbered upload is recovered on the next sync`() {
        // B overwrites A's file, so A's addition is briefly absent from it. A still holds it,
        // and republishes the full merged state next time.
        val clobbered = deviceBState()
        assertFalse(clobbered.receipts.any { it.id == "r3" })
        assertEquals("Coffee", byId(SharedMerge.merge(clobbered, deviceAState()), "r3").description)
    }

    @Test
    fun `the version vector takes the elementwise maximum`() {
        assertEquals(mapOf(deviceA to 3, deviceB to 2), merged().versions)
    }

    @Test
    fun `both platforms converge on the same state`() {
        // The real proof: decode what iOS merged and compare it to what this platform merged
        // from the same two inputs. Values, not bytes — the two serialisers differ in key
        // order and indentation, and neither matters.
        val aFile = File(fixtures, "state-device-a.json")
        val bFile = File(fixtures, "state-device-b.json")
        val iosFile = File(fixtures, "ios-merged.json")
        assertTrue("missing ${iosFile.path} — see fixtures/shared/README.md", iosFile.isFile)

        val a = SharedJson.decodeFromString<SharedState>(aFile.readText())
        val b = SharedJson.decodeFromString<SharedState>(bFile.readText())
        val iosMerged = SharedJson.decodeFromString<SharedState>(iosFile.readText())

        assertEquals(iosMerged, SharedMerge.merge(a, b))
    }

    /**
     * Reads the state iOS actually serialised, merges Android's half into it, and writes both
     * halves out for the convergence comparison. See `fixtures/shared/README.md`.
     */
    @Test
    fun `write the Android half of the cross-OS exchange`() {
        assumeTrue(System.getProperty("rsreceipts.writeFixture") == "true")
        fixtures.mkdirs()

        val pretty = Json { prettyPrint = true; encodeDefaults = true }
        File(fixtures, "state-device-b.json")
            .writeText(pretty.encodeToString(deviceBState()))

        val aFile = File(fixtures, "state-device-a.json")
        assertTrue("run the iOS side first: ${aFile.path}", aFile.isFile)

        // Decoded from the file rather than rebuilt locally: the point is to merge what the
        // other platform actually serialised.
        val a = SharedJson.decodeFromString<SharedState>(aFile.readText())
        val result = SharedMerge.merge(a, deviceBState())
        File(fixtures, "android-merged.json").writeText(pretty.encodeToString(result))
        println("wrote android-merged.json from iOS's ${a.receipts.size} records + Android's ${deviceBState().receipts.size}")
    }
}
