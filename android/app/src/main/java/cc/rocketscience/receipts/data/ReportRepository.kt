package cc.rocketscience.receipts.data

import kotlinx.coroutines.flow.Flow
import cc.rocketscience.receipts.reorder.ReportOrder
import cc.rocketscience.receipts.shared.SharedJson
import cc.rocketscience.receipts.shared.SharedState
import cc.rocketscience.receipts.shared.SharedSync
import java.util.UUID

class ReportRepository(
    private val reportDao: ReportDao,
    private val receiptDao: ReceiptDao,
    private val imageStore: ImageStore,
    private val tombstoneDao: TombstoneDao,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val sharedSync by lazy {
        SharedSync(
            reportDao, receiptDao, tombstoneDao, deviceId, now,
            readImage = { name ->
                imageStore.file(name).takeIf { it.isFile }?.readBytes()
            },
            writeImage = { name, bytes ->
                runCatching { imageStore.file(name).writeBytes(bytes) }.isSuccess
            },
        )
    }

    // ----- shared mode -----

    suspend fun setShared(reportId: String, shared: Boolean) =
        reportDao.setShared(reportId, shared)

    /** The report's shared state as JSON, ready to hand to the file picker. */
    suspend fun sharedStateJson(reportId: String, currencyCode: String): String? =
        sharedSync.localState(reportId, currencyCode)
            ?.let { SharedJson.encodeToString(SharedState.serializer(), it) }

    suspend fun importSharedJson(json: String, currencyCode: String): SharedSync.ImportResult {
        val state = SharedJson.decodeFromString(SharedState.serializer(), json)
        require(state.sharedVersion <= SharedState.CURRENT_VERSION) {
            "This shared report was written by a newer version of the app " +
                "(format ${state.sharedVersion}; this build understands " +
                "${SharedState.CURRENT_VERSION})."
        }
        return sharedSync.importState(state, currencyCode)
    }

    /**
     * One sync: merge whatever is in the bound file, then write the merged state back to it.
     *
     * Both entry points go through here — the reports list and the report's own menu — so
     * they cannot drift apart. Reading and writing stay with the caller, because a content URI
     * needs a ContentResolver the data layer has no business holding.
     */
    suspend fun syncShared(
        reportId: String,
        currencyCode: String,
        read: () -> SharedFileRead,
        write: (String) -> Boolean,
    ): SyncOutcome {
        var lastSummary: String? = null
        val incoming = read()
        when (incoming) {
            is SharedFileRead.Contents ->
                runCatching { importSharedJson(incoming.json, currencyCode) }
                    .onSuccess { lastSummary = it.summary }
                    .onFailure {
                        return SyncOutcome.Unreadable(it.message ?: "could not be merged")
                    }
            // Nothing there yet: this is the first publish, and writing is correct.
            SharedFileRead.Absent -> Unit
            // A read that *failed* is emphatically not "nothing to merge". Writing here would
            // overwrite a collaborator's work with local state — the file is the shared truth,
            // and we have just proved we cannot see it.
            is SharedFileRead.Failed -> return SyncOutcome.ReadFailed(incoming.detail)
        }
        val merged = sharedStateJson(reportId, currencyCode)
            ?: return SyncOutcome.Unreadable("this report could not be read")
        return if (write(merged)) {
            SyncOutcome.Synced(
                firstPublish = incoming is SharedFileRead.Absent,
                summary = lastSummary ?: "Published for the first time.",
            )
        } else {
            SyncOutcome.NotWritten
        }
    }

    /** Drops this report's tombstones, so the next sync accepts what the file holds. */
    suspend fun forgetDeletions(reportId: String): Int = sharedSync.forgetDeletions(reportId)

    /** What came back from the shared file, distinguishing "empty" from "could not look". */
    sealed interface SharedFileRead {
        data class Contents(val json: String) : SharedFileRead
        data object Absent : SharedFileRead
        data class Failed(val detail: String) : SharedFileRead
    }

    sealed interface SyncOutcome {
        data class Synced(val firstPublish: Boolean, val summary: String) : SyncOutcome
        data class Unreadable(val detail: String) : SyncOutcome
        data class ReadFailed(val detail: String) : SyncOutcome
        data object NotWritten : SyncOutcome
    }

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
                // Without this a new receipt is stamped 1970 and loses every merge it is in.
                updatedAt = now(),
            )
        )
        touch(reportId)
        return id
    }

    suspend fun updateReceipt(receipt: Receipt) {
        // The edit has to be newer than whatever a collaborator holds, or last-write-wins
        // silently discards it on the next sync.
        receiptDao.upsert(
            receipt.copy(description = receipt.description.trim(), updatedAt = now())
        )
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
        receiptDao.upsert(receipt.copy(imageFile = imageFile, updatedAt = now()))
        if (previous != null && previous != imageFile) imageStore.delete(previous)
        touch(receipt.reportId)
    }

    fun imageFile(fileName: String) = imageStore.file(fileName)

    suspend fun deleteReceipt(receiptId: String) {
        val receipt = receiptDao.findById(receiptId) ?: return
        // In a shared report the deletion has to be remembered, not just performed. Absence
        // has no timestamp, so without a tombstone the collaborator who still holds this row
        // reinstates it on the next merge — permanently.
        if (reportDao.findById(receipt.reportId)?.isShared == true) {
            sharedSync.recordDeletion(receiptId, receipt.reportId)
        }
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

    /** See [ReceiptDao.backfillUpdatedAt]. Runs at startup, costs nothing once done. */
    suspend fun repairMissingTimestamps(): Int = receiptDao.backfillUpdatedAt()

    suspend fun sweepOrphanImages(): Int =
        imageStore.sweepOrphans(receiptDao.allImageFiles().toSet())
}
