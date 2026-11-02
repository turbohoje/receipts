package cc.rocketscience.receipts.shared

import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.data.ReceiptDao
import cc.rocketscience.receipts.data.Report
import cc.rocketscience.receipts.data.ReportDao
import cc.rocketscience.receipts.data.ReportSummary
import cc.rocketscience.receipts.data.Tombstone
import cc.rocketscience.receipts.data.TombstoneDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import cc.rocketscience.receipts.data.ReportRepository
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The database half of sharing: turning rows into a shared file and folding one back in.
 *
 * Identity is the whole question here. A shared report is keyed by its UUID, so importing the
 * same file twice — or importing a report this device already has — has to merge rather than
 * produce a second copy.
 */
class SharedSyncTest {

    private val db = FakeDb()
    private val sync = SharedSync(
        db.reportDao, db.receiptDao, db.tombstoneDao, "device-local",
        now = { 9_000 },
        readImage = { name -> db.images[name] },
        writeImage = { name, bytes -> db.images[name] = bytes; true },
    )

    private suspend fun seedLocalReport(): String {
        db.reportDao.upsert(Report("rep-1", "Lisbon Trip", 1000, 1000, sortOrder = 0))
        db.receiptDao.upsert(
            Receipt("r1", "rep-1", "Taxi", 2450, 1000, null, createdAt = 1, updatedAt = 1000)
        )
        return "rep-1"
    }

    private fun incomingFrom(reportId: String, device: String, amount: Long) = SharedState(
        reportId = reportId,
        currency = "USD",
        versions = mapOf(device to 1),
        report = SharedReport(reportId, "Lisbon Trip", 1000, 2000, 0, device),
        receipts = listOf(
            SharedReceipt("r1", reportId, "Taxi", amount, 1000, null, 1, 2000, device),
        ),
    )

    @Test
    fun `importing the same file twice leaves one report, not two`() = runBlocking {
        val incoming = incomingFrom("rep-1", "device-other", 2600)

        val first = sync.importState(incoming, "USD")
        val second = sync.importState(incoming, "USD")

        assertTrue("the first import should create it", first.isNewReport)
        assertFalse("the second must recognise it is already here", second.isNewReport)
        assertEquals(1, db.reports.size)
        assertEquals(1, db.receipts.size)
    }

    @Test
    fun `importing a report this device already has merges into it`() = runBlocking {
        seedLocalReport()

        val result = sync.importState(incomingFrom("rep-1", "device-other", 2600), "USD")

        assertFalse(result.isNewReport)
        assertEquals(1, db.reports.size)
        // The incoming edit is newer, so it wins — but it updates the row rather than adding one.
        assertEquals(2600L, db.receipts.getValue("r1").amountMinor)
    }

    @Test
    fun `sharing a report with yourself changes nothing`() = runBlocking {
        val reportId = seedLocalReport()
        val before = db.receipts.toMap()

        // Export, then import the very same file back onto the device that wrote it.
        val own = sync.localState(reportId, "USD")!!
        val result = sync.importState(own, "USD")

        assertFalse(result.isNewReport)
        assertEquals(1, db.reports.size)
        assertEquals(before.keys, db.receipts.keys)
        assertEquals(
            before.getValue("r1").amountMinor,
            db.receipts.getValue("r1").amountMinor,
        )
    }

    @Test
    fun `a receipt only this device has counts as sent, not also as matched`() = runBlocking {
        seedLocalReport()
        // A second local receipt the file has never seen.
        db.receiptDao.upsert(
            Receipt("r2", "rep-1", "Coffee", 300, 1000, null, createdAt = 2, updatedAt = 1000)
        )

        val result = sync.importState(incomingFrom("rep-1", "device-other", 2450), "USD")

        // r2 is published by this device, and the file's r1 is newer so it updates.
        // Before the fix r2 was counted as matched as well, describing two receipts
        // arriving where the file carried one.
        assertEquals("sent from here", 1, result.published)
        assertEquals("already matched", 0, result.matched)
        assertEquals("updated", 1, result.updated)
        assertEquals("added", 0, result.added)
    }

    @Test
    fun `re-syncing this device's own deletion is not reported as someone else's`() =
        runBlocking {
            val reportId = seedLocalReport()
            sync.recordDeletion("r1", reportId)
            db.receiptDao.deleteById("r1")

            // Publish, then read the very same file back, as the next sync would.
            val own = sync.localState(reportId, "USD")!!
            val result = sync.importState(own, "USD")

            // The row went when this device deleted it. Replaying the tombstone removes
            // nothing, so there is nothing to report.
            assertEquals("deleted by others", 0, result.deleted)
            assertFalse(result.summary.contains("deleted by others"))
        }

    @Test
    fun `a deletion that actually removes a local row is still counted`() = runBlocking {
        seedLocalReport()
        val incoming = incomingFrom("rep-1", "device-other", 2450).let {
            it.copy(receipts = listOf(it.receipts.first().copy(
                deletedAt = 5000, updatedAt = 5000, updatedBy = "device-other")))
        }

        val result = sync.importState(incoming, "USD")

        assertEquals("deleted by others", 1, result.deleted)
        assertTrue(db.receipts.isEmpty())
    }

    @Test
    fun `importing marks the report shared and keeps its place in the list`() = runBlocking {
        db.reportDao.upsert(Report("rep-1", "Lisbon Trip", 1000, 1000, sortOrder = 7))

        sync.importState(incomingFrom("rep-1", "device-other", 2600), "USD")

        assertTrue(db.reports.getValue("rep-1").isShared)
        // Importing must not shuffle a report the user has deliberately positioned.
        assertEquals(7L, db.reports.getValue("rep-1").sortOrder)
    }

    @Test
    fun `two reports with the same name but different ids stay separate`() = runBlocking {
        sync.importState(incomingFrom("rep-1", "device-other", 2600), "USD")
        sync.importState(incomingFrom("rep-2", "device-other", 2600), "USD")

        // Identity is the UUID, never the name — two trips can legitimately share a title.
        assertEquals(2, db.reports.size)
    }

    @Test
    fun `a photo travels inside the shared file`() = runBlocking {
        val jpeg = ByteArray(2048) { (it % 251).toByte() }
        db.reportDao.upsert(Report("rep-1", "Lisbon Trip", 1000, 1000, isShared = true))
        db.receiptDao.upsert(
            Receipt("r1", "rep-1", "Taxi", 2450, 1000, "a.jpg", createdAt = 1, updatedAt = 1000)
        )
        db.images["a.jpg"] = jpeg

        // What this device would publish.
        val published = sync.localState("rep-1", "USD")!!
        assertTrue("the photo must be in the file", published.receipts.single().imageData != null)

        // A second device: same file, no photos of its own.
        val other = FakeDb()
        val otherSync = SharedSync(
            other.reportDao, other.receiptDao, other.tombstoneDao, "device-other",
            now = { 9_000 },
            readImage = { name -> other.images[name] },
            writeImage = { name, bytes -> other.images[name] = bytes; true },
        )

        val result = otherSync.importState(published, "USD")

        assertEquals(1, result.imagesReceived)
        assertEquals(0, result.missingImages)
        assertArrayEquals("the photo must arrive byte for byte", jpeg, other.images["a.jpg"])
        assertEquals("a.jpg", other.receipts.getValue("r1").imageFile)
    }

    @Test
    fun `a receipt whose photo is gone locally still shares`() = runBlocking {
        db.reportDao.upsert(Report("rep-1", "Lisbon Trip", 1000, 1000, isShared = true))
        db.receiptDao.upsert(
            Receipt("r1", "rep-1", "Taxi", 2450, 1000, "gone.jpg", createdAt = 1, updatedAt = 1000)
        )
        // No bytes in the store: losing the image must not cost the line item too.
        val published = sync.localState("rep-1", "USD")!!
        assertEquals(null, published.receipts.single().imageData)
        assertEquals("gone.jpg", published.receipts.single().imageFile)
    }

    @Test
    fun `a receipt that still exists is never published as deleted`() = runBlocking {
        // Restoring a backup brings the row back but leaves its tombstone behind. Publishing
        // both for one id silently removed the receipt on the other device.
        seedLocalReport()
        db.tombstones["r1"] = Tombstone("r1", "rep-1", deletedAt = 500, deletedBy = "old")

        val published = sync.localState("rep-1", "USD")!!

        val forR1 = published.receipts.filter { it.id == "r1" }
        assertEquals("one record per id, never two", 1, forR1.size)
        assertFalse("the live row must win over the stale tombstone", forR1.single().isDeleted)
        assertTrue("and the stale tombstone should be cleaned up", db.tombstones.isEmpty())
    }

    @Test
    fun `importing a stale-tombstoned report keeps the receipt on the other device`() = runBlocking {
        seedLocalReport()
        db.tombstones["r1"] = Tombstone("r1", "rep-1", deletedAt = 500, deletedBy = "old")
        val published = sync.localState("rep-1", "USD")!!

        val other = FakeDb()
        val otherSync = SharedSync(
            other.reportDao, other.receiptDao, other.tombstoneDao, "device-other",
            now = { 9_000 },
            readImage = { other.images[it] },
            writeImage = { n, b -> other.images[n] = b; true },
        )
        otherSync.importState(published, "USD")

        assertTrue("the receipt must survive the trip", other.receipts.containsKey("r1"))
    }

    @Test
    fun `a tombstone in the file deletes the local row`() = runBlocking {
        seedLocalReport()
        val withDelete = incomingFrom("rep-1", "device-other", 2450).copy(
            receipts = listOf(
                SharedReceipt(
                    "r1", "rep-1", "", 0, 0, null, 0,
                    updatedAt = 3000, updatedBy = "device-other", deletedAt = 3000,
                )
            )
        )

        val result = sync.importState(withDelete, "USD")

        assertEquals(1, result.deleted)
        assertFalse("the deleted receipt must not survive", db.receipts.containsKey("r1"))
        assertTrue("and it must be remembered, or it comes back", db.tombstones.containsKey("r1"))
    }
}

/** In-memory stand-in for the three DAOs sharing touches. */
private class FakeDb {
    val reports = linkedMapOf<String, Report>()
    val receipts = linkedMapOf<String, Receipt>()
    val tombstones = linkedMapOf<String, Tombstone>()
    val images = linkedMapOf<String, ByteArray>()

    val reportDao = object : ReportDao {
        override fun observeSummaries(): Flow<List<ReportSummary>> = flowOf(emptyList())
        override fun observeSummary(reportId: String): Flow<ReportSummary?> = flowOf(null)
        override suspend fun findById(reportId: String): Report? = reports[reportId]
        override suspend fun upsert(report: Report) { reports[report.id] = report }
        override suspend fun rename(reportId: String, name: String, updatedAt: Long) {
            reports[reportId]?.let { reports[reportId] = it.copy(name = name, updatedAt = updatedAt) }
        }
        override suspend fun deleteById(reportId: String) {
            reports.remove(reportId)
            receipts.values.filter { it.reportId == reportId }.forEach { receipts.remove(it.id) }
        }
        override suspend fun minSortOrder(): Long? = reports.values.minOfOrNull { it.sortOrder }
        override suspend fun setSortOrder(reportId: String, sortOrder: Long) {
            reports[reportId]?.let { reports[reportId] = it.copy(sortOrder = sortOrder) }
        }
        override suspend fun setShared(reportId: String, shared: Boolean) {
            reports[reportId]?.let { reports[reportId] = it.copy(isShared = shared) }
        }
        override suspend fun all(): List<Report> = reports.values.toList()
        override suspend fun deleteAll() { reports.clear(); receipts.clear() }
        override suspend fun insertAll(reports: List<Report>) {
            reports.forEach { this@FakeDb.reports[it.id] = it }
        }
    }

    val receiptDao = object : ReceiptDao {
        override fun observeForReport(reportId: String): Flow<List<Receipt>> = flowOf(emptyList())
        override suspend fun listForReport(reportId: String): List<Receipt> =
            receipts.values.filter { it.reportId == reportId }
        override fun observeById(receiptId: String): Flow<Receipt?> = flowOf(receipts[receiptId])
        override suspend fun findById(receiptId: String): Receipt? = receipts[receiptId]
        override suspend fun allImageFiles(): List<String> = receipts.values.mapNotNull { it.imageFile }
        override suspend fun backfillUpdatedAt(): Int {
            val stale = receipts.values.filter { it.updatedAt == 0L }
            stale.forEach { receipts[it.id] = it.copy(updatedAt = it.createdAt) }
            return stale.size
        }
        override suspend fun upsert(receipt: Receipt) { receipts[receipt.id] = receipt }
        override suspend fun deleteById(receiptId: String) { receipts.remove(receiptId) }
        override suspend fun all(): List<Receipt> = receipts.values.toList()
        override suspend fun insertAll(receipts: List<Receipt>) {
            receipts.forEach { this@FakeDb.receipts[it.id] = it }
        }
    }

    val tombstoneDao = object : TombstoneDao {
        override suspend fun forReport(reportId: String): List<Tombstone> =
            tombstones.values.filter { it.reportId == reportId }
        override suspend fun all(): List<Tombstone> = tombstones.values.toList()
        override suspend fun upsert(tombstone: Tombstone) { tombstones[tombstone.receiptId] = tombstone }
        override suspend fun upsertAll(tombstones: List<Tombstone>) {
            tombstones.forEach { this@FakeDb.tombstones[it.receiptId] = it }
        }
        override suspend fun clear(receiptId: String) { tombstones.remove(receiptId) }
        override suspend fun clearAll() { tombstones.clear() }
    }
}

/**
 * The deletion path, which is where tombstones are actually created.
 *
 * Separate from [SharedSyncTest] because it exercises the repository rather than the sync:
 * the bug this guards against was that nothing ever called `recordDeletion`, so the merge
 * engine's tombstone handling was correct and entirely unreachable.
 */
class SharedDeletionTest {

    @Test
    fun `deleting from a shared report leaves a tombstone`() = runBlocking {
        val db = FakeDb()
        val repo = cc.rocketscience.receipts.data.ReportRepository(
            db.reportDao, db.receiptDao,
            cc.rocketscience.receipts.data.ImageStore(createTempDir()),
            db.tombstoneDao, "device-local",
        ) { 5_000 }

        db.reportDao.upsert(Report("rep-1", "Shared trip", 1000, 1000, isShared = true))
        db.receiptDao.upsert(Receipt("r1", "rep-1", "Taxi", 2450, 1000, null, 1, 1000))

        repo.deleteReceipt("r1")

        assertFalse(db.receipts.containsKey("r1"))
        val tombstone = db.tombstones["r1"]
        assertTrue("a shared delete must be remembered", tombstone != null)
        assertEquals(5_000L, tombstone!!.deletedAt)
        assertEquals("device-local", tombstone.deletedBy)
    }

    @Test
    fun `deleting from an ordinary report leaves no tombstone`() = runBlocking {
        val db = FakeDb()
        val repo = cc.rocketscience.receipts.data.ReportRepository(
            db.reportDao, db.receiptDao,
            cc.rocketscience.receipts.data.ImageStore(createTempDir()),
            db.tombstoneDao, "device-local",
        ) { 5_000 }

        db.reportDao.upsert(Report("rep-1", "Private trip", 1000, 1000, isShared = false))
        db.receiptDao.upsert(Receipt("r1", "rep-1", "Taxi", 2450, 1000, null, 1, 1000))

        repo.deleteReceipt("r1")

        assertFalse(db.receipts.containsKey("r1"))
        assertTrue("an unshared report should accumulate nothing", db.tombstones.isEmpty())
    }

    private fun createTempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("rsr-images").toFile()
}

/**
 * What a sync is allowed to write, and when.
 *
 * The bug these guard against: a sync that could not read the shared file went ahead and
 * wrote local state over it, discarding whatever the other person had put there.
 */
class SharedSyncWriteRulesTest {

    private val db = FakeDb()
    private val repo = cc.rocketscience.receipts.data.ReportRepository(
        db.reportDao, db.receiptDao,
        cc.rocketscience.receipts.data.ImageStore(
            java.nio.file.Files.createTempDirectory("rsr").toFile()),
        db.tombstoneDao, "device-local",
    ) { 7_000 }

    private suspend fun seed() {
        db.reportDao.upsert(Report("rep-1", "Trip", 1000, 1000, isShared = true))
        db.receiptDao.upsert(Receipt("r1", "rep-1", "Taxi", 2450, 1000, null, 1, 1000))
    }

    @Test
    fun `a file that cannot be read is never overwritten`() = runBlocking {
        seed()
        var written: String? = null

        val outcome = repo.syncShared(
            "rep-1", "USD",
            read = { ReportRepository.SharedFileRead.Failed("permission withdrawn") },
            write = { written = it; true },
        )

        assertTrue(outcome is ReportRepository.SyncOutcome.ReadFailed)
        assertNull("nothing may be written when the file could not be read", written)
    }

    @Test
    fun `a first publish writes, because there is nothing to lose`() = runBlocking {
        seed()
        var written: String? = null

        val outcome = repo.syncShared(
            "rep-1", "USD",
            read = { ReportRepository.SharedFileRead.Absent },
            write = { written = it; true },
        )

        assertTrue(outcome is ReportRepository.SyncOutcome.Synced)
        assertTrue((outcome as ReportRepository.SyncOutcome.Synced).firstPublish)
        assertTrue("the report should have been published", written != null)
    }

    @Test
    fun `a remote edit survives the write-back`() = runBlocking {
        seed()
        // The other device changed the same receipt, later than this one did.
        val remote = SharedState(
            reportId = "rep-1", currency = "USD", versions = mapOf("device-other" to 1),
            report = SharedReport("rep-1", "Trip", 1000, 1000, 0, "device-other"),
            receipts = listOf(
                SharedReceipt("r1", "rep-1", "Taxi", 9900, 1000, null, 1, 6000, "device-other"),
            ),
        )
        var written: String? = null

        repo.syncShared(
            "rep-1", "USD",
            read = {
                ReportRepository.SharedFileRead.Contents(
                    SharedJson.encodeToString(SharedState.serializer(), remote))
            },
            write = { written = it; true },
        )

        // Merged locally...
        assertEquals(9900L, db.receipts.getValue("r1").amountMinor)
        // ...and still present in what went back to the file.
        val back = SharedJson.decodeFromString(SharedState.serializer(), written!!)
        assertEquals(9900L, back.receipts.single { it.id == "r1" }.amountMinor)
    }

    @Test
    fun `a local edit is newer than what it replaces, so it wins`() = runBlocking {
        seed()
        // An edit made now must beat a collaborator's older copy.
        repo.updateReceipt(db.receipts.getValue("r1").copy(amountMinor = 3300))
        assertEquals(7_000L, db.receipts.getValue("r1").updatedAt)

        val older = SharedState(
            reportId = "rep-1", currency = "USD", versions = emptyMap(),
            report = SharedReport("rep-1", "Trip", 1000, 1000, 0, "device-other"),
            receipts = listOf(
                SharedReceipt("r1", "rep-1", "Taxi", 1111, 1000, null, 1, 2000, "device-other"),
            ),
        )
        repo.syncShared(
            "rep-1", "USD",
            read = {
                ReportRepository.SharedFileRead.Contents(
                    SharedJson.encodeToString(SharedState.serializer(), older))
            },
            write = { true },
        )

        assertEquals("the newer local edit must survive", 3300L, db.receipts.getValue("r1").amountMinor)
    }

    @Test
    fun `a new receipt is not stamped 1970`() = runBlocking {
        seed()
        val id = repo.addReceipt("rep-1", "Coffee", 450, 1000)
        // A zero here loses every merge it is ever in.
        assertEquals(7_000L, db.receipts.getValue(id).updatedAt)
    }
}
