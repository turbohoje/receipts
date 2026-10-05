package cc.rocketscience.receipts.data

import kotlinx.coroutines.flow.Flow
import cc.rocketscience.receipts.reorder.ReportOrder
import java.util.UUID

class ReportRepository(
    private val reportDao: ReportDao,
    private val receiptDao: ReceiptDao,
    private val imageStore: ImageStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    // ----- reports -----

    fun observeReports(): Flow<List<ReportSummary>> = reportDao.observeSummaries()

    fun observeReport(reportId: String): Flow<ReportSummary?> = reportDao.observeSummary(reportId)

    suspend fun createReport(name: String): String {
        val ts = now()
        val id = UUID.randomUUID().toString()
        reportDao.upsert(
            Report(
                id = id,
                name = name.trim(),
                createdAt = ts,
                updatedAt = ts,
                // Above everything already there: the report just created is the one about to
                // be filled in.
                sortOrder = ReportOrder.sortOrderForNew(reportDao.minSortOrder()),
            )
        )
        return id
    }

    /**
     * Persists a manual ordering. [orderedIds] is the list top to bottom.
     *
     * The whole list is renumbered from zero rather than nudging the row that moved, so the
     * stored order can never drift into ties or run out of room between two neighbours.
     */
    suspend fun reorderReports(orderedIds: List<String>) {
        val values = ReportOrder.sortOrders(orderedIds.size)
        orderedIds.forEachIndexed { index, id ->
            reportDao.setSortOrder(id, values[index])
        }
    }

    suspend fun renameReport(reportId: String, name: String) =
        reportDao.rename(reportId, name.trim(), now())

    /**
     * Deletes the report, its receipts (via CASCADE), and their image files.
     *
     * Rows go first so the database is never left referencing a file that is gone; a crash
     * between the two steps leaves orphan files, which the startup sweep collects.
     */
    suspend fun deleteReport(reportId: String) {
        val images = receiptDao.listForReport(reportId).mapNotNull { it.imageFile }
        reportDao.deleteById(reportId)
        imageStore.deleteAll(images)
    }

    // ----- receipts -----

    fun observeReceipts(reportId: String): Flow<List<Receipt>> =
        receiptDao.observeForReport(reportId)

    /** One-shot read, for jobs like export that must not depend on a UI snapshot. */
    suspend fun receiptsOnce(reportId: String): List<Receipt> = receiptDao.listForReport(reportId)

    fun observeReceipt(receiptId: String): Flow<Receipt?> = receiptDao.observeById(receiptId)

    suspend fun findReceipt(receiptId: String): Receipt? = receiptDao.findById(receiptId)

    suspend fun addReceipt(
        reportId: String,
        description: String,
        amountMinor: Long,
        date: Long,
        imageFile: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        receiptDao.upsert(
            Receipt(
                id = id,
                reportId = reportId,
                description = description.trim(),
                amountMinor = amountMinor,
                date = date,
                imageFile = imageFile,
                createdAt = now(),
            )
        )
        touch(reportId)
        return id
    }

    suspend fun updateReceipt(receipt: Receipt) {
        receiptDao.upsert(receipt.copy(description = receipt.description.trim()))
        touch(receipt.reportId)
    }

    /**
     * Points a receipt at a new image and deletes the one it replaced.
     *
     * The row is updated first: if the process dies before the old file is removed, the
     * startup orphan sweep collects it. The reverse order could delete the only copy while
     * the row still referenced it.
     */
    suspend fun replaceReceiptImage(receiptId: String, imageFile: String) {
        val receipt = receiptDao.findById(receiptId) ?: return
        val previous = receipt.imageFile
        receiptDao.upsert(receipt.copy(imageFile = imageFile))
        if (previous != null && previous != imageFile) imageStore.delete(previous)
        touch(receipt.reportId)
    }

    fun imageFile(fileName: String) = imageStore.file(fileName)

    suspend fun deleteReceipt(receiptId: String) {
        val receipt = receiptDao.findById(receiptId) ?: return
        receiptDao.deleteById(receiptId)
        imageStore.delete(receipt.imageFile)
        touch(receipt.reportId)
    }

    private suspend fun touch(reportId: String) {
        val report = reportDao.findById(reportId) ?: return
        reportDao.upsert(report.copy(updatedAt = now()))
    }

    // ----- images -----

    fun imageExists(fileName: String?): Boolean = imageStore.exists(fileName)

    suspend fun sweepOrphanImages(): Int =
        imageStore.sweepOrphans(receiptDao.allImageFiles().toSet())
}
