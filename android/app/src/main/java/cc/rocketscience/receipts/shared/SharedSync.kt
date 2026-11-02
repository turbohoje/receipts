package cc.rocketscience.receipts.shared

import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.data.ReceiptDao
import cc.rocketscience.receipts.data.Report
import cc.rocketscience.receipts.data.ReportDao
import cc.rocketscience.receipts.data.Tombstone
import cc.rocketscience.receipts.data.TombstoneDao
import java.util.Base64
import java.util.UUID

/**
 * Bridges the database and [SharedState]: reads a report out for publishing, and folds an
 * incoming file back in.
 *
 * The merge itself lives in [SharedMerge] and is shared, tested logic. Everything here is
 * translation, so that the rule deciding who wins exists in exactly one place per platform.
 */
class SharedSync(
    private val reportDao: ReportDao,
    private val receiptDao: ReceiptDao,
    private val tombstoneDao: TombstoneDao,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
    // java.util.Base64, not android.util: it exists from API 26 (the floor) and, unlike the
    // Android one, actually runs in a JVM unit test rather than returning a stub.
    /** Reads a stored image's bytes, or null when the file is gone. */
    private val readImage: (String) -> ByteArray? = { null },
    /** Writes received bytes under the given filename and reports success. */
    private val writeImage: (String, ByteArray) -> Boolean = { _, _ -> false },
) {

    /** What a sync actually did, broken down so a surprise is visible rather than silent. */
    data class ImportResult(
        val reportName: String,
        val isNewReport: Boolean,
        /** In the file but not here — new to this device. */
        val added: Int,
        /** Here already, and the file's copy was newer. */
        val updated: Int,
        /** In the file and here already, unchanged; nothing to do. */
        val matched: Int,
        /** Tombstones that actually removed a row that was present here. */
        val deleted: Int,
        /** Here but not in the file, so this device is publishing them. */
        val published: Int,
        /** Photos that arrived in the file and were written here. */
        val imagesReceived: Int,
        /** Images the file refers to that this device still does not have. */
        val missingImages: Int,
    ) {
        val received: Int get() = added + updated

        /** One line for a dialog, naming only what actually happened. */
        val summary: String
            get() = buildList {
                if (added > 0) add("$added added")
                if (updated > 0) add("$updated updated")
                if (matched > 0) add("$matched already matched")
                if (deleted > 0) add("$deleted deleted by others")
                if (published > 0) add("$published sent from here")
                if (imagesReceived > 0) add("$imagesReceived photos received")
                if (missingImages > 0) add("$missingImages photos missing")
            }.ifEmpty { listOf("Already up to date") }.joinToString(", ") + "."
    }

    /** The local view of a report, ready to publish. Null when the report is gone. */
    suspend fun localState(reportId: String, currencyCode: String): SharedState? {
        val report = reportDao.findById(reportId) ?: return null
        val receipts = receiptDao.listForReport(reportId)
        val liveIds = receipts.mapTo(HashSet()) { it.id }

        // A receipt that exists cannot also be deleted. Publishing both for one id silently
        // removes it on the other device, because the merge keys by id and the tombstone is
        // appended last. Restoring a backup produces exactly this: the row comes back while
        // its tombstone stays behind.
        val stale = tombstoneDao.forReport(reportId).filter { it.receiptId in liveIds }
        stale.forEach { tombstoneDao.clear(it.receiptId) }
        val tombstones = tombstoneDao.forReport(reportId).filterNot { it.receiptId in liveIds }

        return SharedState(
            reportId = report.id,
            currency = currencyCode,
            versions = mapOf(deviceId to 1),
            report = SharedReport(
                id = report.id,
                name = report.name,
                createdAt = report.createdAt,
                updatedAt = report.updatedAt,
                sortOrder = report.sortOrder,
                updatedBy = deviceId,
            ),
            receipts = receipts.map {
                SharedReceipt(
                    id = it.id,
                    reportId = it.reportId,
                    description = it.description,
                    amountMinor = it.amountMinor,
                    date = it.date,
                    imageFile = it.imageFile,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    updatedBy = deviceId,
                    // The photo rides along, so the file is the whole report.
                    imageData = it.imageFile
                        ?.let(readImage)
                        ?.let { bytes -> Base64.getEncoder().encodeToString(bytes) },
                )
            } + tombstones.map {
                SharedReceipt(
                    id = it.receiptId,
                    reportId = it.reportId,
                    description = "",
                    amountMinor = 0,
                    date = 0,
                    imageFile = null,
                    createdAt = 0,
                    updatedAt = it.deletedAt,
                    updatedBy = it.deletedBy,
                    deletedAt = it.deletedAt,
                )
            },
        )
    }

    /**
     * Merges [incoming] with whatever is here and writes the result back.
     *
     * A report this device has never seen is created; one it already has is merged into. The
     * report is marked shared either way — importing it is the act of opting in.
     */
    suspend fun importState(incoming: SharedState, currencyCode: String): ImportResult {
        val existing = localState(incoming.reportId, currencyCode)
        val isNew = existing == null
        val merged = if (existing == null) incoming else SharedMerge.merge(existing, incoming)

        reportDao.upsert(
            Report(
                id = merged.report.id,
                name = merged.report.name,
                createdAt = merged.report.createdAt,
                updatedAt = merged.report.updatedAt,
                sortOrder = reportDao.findById(merged.report.id)?.sortOrder
                    ?: ReportOrderBridge.topOf(reportDao),
                isShared = true,
            )
        )

        var added = 0
        var updated = 0
        var matched = 0
        var deleted = 0
        var imagesReceived = 0
        // Anything here that the file did not carry is being published by this device.
        val incomingIds = incoming.receipts.mapTo(HashSet()) { it.id }
        val published = existing?.receipts.orEmpty()
            .count { !it.isDeleted && it.id !in incomingIds }
        for (receipt in merged.receipts) {
            if (receipt.isDeleted) {
                // The row goes; its image becomes an orphan and the startup sweep collects it.
                receiptDao.deleteById(receipt.id)
                tombstoneDao.upsert(
                    Tombstone(
                        receiptId = receipt.id,
                        reportId = merged.reportId,
                        deletedAt = receipt.deletedAt ?: now(),
                        deletedBy = receipt.updatedBy,
                    )
                )
                // Only count a deletion that actually removed something here. Re-syncing
                // after this device did the deleting replays its own tombstone, and
                // reporting that as "deleted by others" described the wrong thing happening.
                val before = existing?.receipts?.firstOrNull { it.id == receipt.id }
                if (before != null && !before.isDeleted) deleted++
            } else {
                tombstoneDao.clear(receipt.id)
                // A photo that arrived in the file and is not on this device yet gets written
                // under the name the sender used, so both sides agree on the filename.
                receipt.imageData?.let { encoded ->
                    val name = receipt.imageFile
                    if (name != null && readImage(name) == null) {
                        runCatching { Base64.getDecoder().decode(encoded) }
                            .getOrNull()
                            ?.let { bytes -> if (writeImage(name, bytes)) imagesReceived++ }
                    }
                }
                receiptDao.upsert(
                    Receipt(
                        id = receipt.id,
                        reportId = merged.reportId,
                        description = receipt.description,
                        amountMinor = receipt.amountMinor,
                        date = receipt.date,
                        imageFile = receipt.imageFile,
                        createdAt = receipt.createdAt,
                        updatedAt = receipt.updatedAt,
                    )
                )
                // Receipts the file did not carry are already counted as published, so
                // they are not also matched here — counting both described more receipts
                // than the report holds.
                val before = existing?.receipts?.firstOrNull { it.id == receipt.id }
                when {
                    receipt.id !in incomingIds -> Unit
                    before == null -> added++
                    before.updatedAt == receipt.updatedAt -> matched++
                    else -> updated++
                }
            }
        }

        return ImportResult(
            reportName = merged.report.name,
            isNewReport = isNew,
            added = added,
            updated = updated,
            matched = matched,
            deleted = deleted,
            published = published,
            imagesReceived = imagesReceived,
            missingImages = merged.receipts.count {
                !it.isDeleted && it.imageFile != null && readImage(it.imageFile) == null
            },
        )
    }

    /**
     * Forgets every deletion recorded here for one report.
     *
     * A tombstone always outranks a row older than it, which is correct — but it means a
     * tombstone written in error can never be undone by syncing. This is the way out: drop
     * them, and the next sync accepts whatever the file holds.
     */
    suspend fun forgetDeletions(reportId: String): Int {
        val stale = tombstoneDao.forReport(reportId)
        stale.forEach { tombstoneDao.clear(it.receiptId) }
        return stale.size
    }

    /** Records a deletion so it can propagate, instead of the row simply vanishing. */
    suspend fun recordDeletion(receiptId: String, reportId: String) {
        tombstoneDao.upsert(
            Tombstone(
                receiptId = receiptId,
                reportId = reportId,
                deletedAt = now(),
                deletedBy = deviceId,
            )
        )
    }

    companion object {
        fun newDeviceId(): String = UUID.randomUUID().toString()
    }
}

/** Keeps the import path from duplicating the new-report ordering rule. */
private object ReportOrderBridge {
    suspend fun topOf(reportDao: ReportDao): Long =
        cc.rocketscience.receipts.reorder.ReportOrder.sortOrderForNew(reportDao.minSortOrder())
}
