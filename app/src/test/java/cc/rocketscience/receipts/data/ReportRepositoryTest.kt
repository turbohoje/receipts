package cc.rocketscience.receipts.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the half of deletion that Room cannot do for us: image files on disk.
 * SQLite's CASCADE is exercised here by [FakeDb], which mimics it — the point of these
 * tests is the repository's ordering and file cleanup, not SQLite's own behaviour.
 */
class ReportRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var imageStore: ImageStore
    private lateinit var db: FakeDb
    private lateinit var repo: ReportRepository

    private fun setUp() {
        imageStore = ImageStore(File(temp.root, "images"))
        db = FakeDb()
        repo = ReportRepository(db.reportDao, db.receiptDao, imageStore) { 1_000L }
    }

    private fun withImage(receiptId: String): String =
        imageStore.fileNameFor(receiptId).also { imageStore.file(it).writeText("jpeg") }

    @Test
    fun `deleting a report removes its receipts and their image files`() = runBlocking {
        setUp()
        val reportId = repo.createReport("Q3 Client Trip")
        val a = repo.addReceipt(reportId, "Taxi", 2450, 0L)
        val b = repo.addReceipt(reportId, "Dinner", 8100, 0L)
        val imageA = withImage(a)
        val imageB = withImage(b)
        db.attachImage(a, imageA)
        db.attachImage(b, imageB)

        repo.deleteReport(reportId)

        assertNull(db.reportDao.findById(reportId))
        assertEquals(emptyList<Receipt>(), db.receiptDao.listForReport(reportId))
        assertFalse("image A should be gone", imageStore.exists(imageA))
        assertFalse("image B should be gone", imageStore.exists(imageB))
    }

    @Test
    fun `deleting a report leaves another report's images alone`() = runBlocking {
        setUp()
        val doomed = repo.createReport("Doomed")
        val kept = repo.createReport("Kept")
        val a = repo.addReceipt(doomed, "Taxi", 2450, 0L)
        val b = repo.addReceipt(kept, "Hotel", 19900, 0L)
        val imageA = withImage(a).also { db.attachImage(a, it) }
        val imageB = withImage(b).also { db.attachImage(b, it) }

        repo.deleteReport(doomed)

        assertFalse(imageStore.exists(imageA))
        assertTrue("the surviving report's image must remain", imageStore.exists(imageB))
    }

    @Test
    fun `deleting a receipt removes its row and image`() = runBlocking {
        setUp()
        val reportId = repo.createReport("Trip")
        val receiptId = repo.addReceipt(reportId, "Taxi", 2450, 0L)
        val image = withImage(receiptId).also { db.attachImage(receiptId, it) }

        repo.deleteReceipt(receiptId)

        assertNull(db.receiptDao.findById(receiptId))
        assertFalse(imageStore.exists(image))
        assertTrue("the report itself survives", db.reportDao.findById(reportId) != null)
    }

    @Test
    fun `deleting a receipt with no image does not fail`() = runBlocking {
        setUp()
        val reportId = repo.createReport("Trip")
        val receiptId = repo.addReceipt(reportId, "Cash tip", 500, 0L)

        repo.deleteReceipt(receiptId)

        assertNull(db.receiptDao.findById(receiptId))
    }

    @Test
    fun `deleting an already-deleted receipt is a no-op`() = runBlocking {
        setUp()
        val reportId = repo.createReport("Trip")
        val receiptId = repo.addReceipt(reportId, "Taxi", 2450, 0L)
        repo.deleteReceipt(receiptId)

        repo.deleteReceipt(receiptId) // must not throw
    }

    @Test
    fun `orphan sweep clears files left by an interrupted delete`() = runBlocking {
        setUp()
        val reportId = repo.createReport("Trip")
        val live = repo.addReceipt(reportId, "Taxi", 2450, 0L)
        val liveImage = withImage(live).also { db.attachImage(live, it) }
        // Simulate the process dying after rows were deleted but before files were.
        val leaked = withImage("dead-receipt-id")

        val removed = repo.sweepOrphanImages()

        assertEquals(1, removed)
        assertFalse(imageStore.exists(leaked))
        assertTrue(imageStore.exists(liveImage))
    }
}

/** In-memory stand-in for Room, including its ON DELETE CASCADE. */
private class FakeDb {
    val reports = linkedMapOf<String, Report>()
    val receipts = linkedMapOf<String, Receipt>()

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
            // ON DELETE CASCADE
            receipts.values.filter { it.reportId == reportId }.forEach { receipts.remove(it.id) }
        }
    }

    val receiptDao = object : ReceiptDao {
        override fun observeForReport(reportId: String): Flow<List<Receipt>> = flowOf(emptyList())
        override suspend fun listForReport(reportId: String): List<Receipt> =
            receipts.values.filter { it.reportId == reportId }
        override fun observeById(receiptId: String): Flow<Receipt?> = flowOf(receipts[receiptId])
        override suspend fun findById(receiptId: String): Receipt? = receipts[receiptId]
        override suspend fun allImageFiles(): List<String> = receipts.values.mapNotNull { it.imageFile }
        override suspend fun upsert(receipt: Receipt) { receipts[receipt.id] = receipt }
        override suspend fun deleteById(receiptId: String) { receipts.remove(receiptId) }
    }

    fun attachImage(receiptId: String, fileName: String) {
        receipts[receiptId]?.let { receipts[receiptId] = it.copy(imageFile = fileName) }
    }
}
