package cc.rocketscience.receipts.backup

import cc.rocketscience.receipts.data.ImageStore
import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.data.ReceiptsDatabase
import cc.rocketscience.receipts.data.Report
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Currency

data class RestoreSummary(val reports: Int, val receipts: Int, val images: Int)

sealed interface RestoreResult {
    data class Success(val summary: RestoreSummary) : RestoreResult
    data class Refused(val problem: RestoreProblem) : RestoreResult
    data class Failed(val detail: String) : RestoreResult
}

/**
 * Builds and applies backups. Everything here works on a local [File], so the callers — the
 * file picker and Drive — only have to get the bytes onto disk.
 */
class BackupManager(
    private val database: ReceiptsDatabase,
    private val imageStore: ImageStore,
    private val appVersion: String,
    private val currency: () -> Currency,
    private val now: () -> Long = System::currentTimeMillis,
    private val writer: BackupWriter = BackupWriter(),
    private val reader: BackupReader = BackupReader(),
) {

    fun fileName(): String {
        val stamp = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(now()))
        return "rs-receipts-backup-$stamp.zip"
    }

    suspend fun buildManifest(): BackupManifest = withContext(Dispatchers.IO) {
        val reports = database.reportDao().all()
        val receipts = database.receiptDao().all()
        BackupManifest(
            schemaVersion = BackupFormat.SCHEMA_VERSION,
            appVersion = appVersion,
            createdAt = now(),
            currency = currency().currencyCode,
            reports = reports.map {
                BackupReport(it.id, it.name, it.createdAt, it.updatedAt, it.sortOrder)
            },
            receipts = receipts.map {
                BackupReceipt(
                    id = it.id,
                    reportId = it.reportId,
                    description = it.description,
                    amountMinor = it.amountMinor,
                    date = it.date,
                    imageFile = it.imageFile,
                    createdAt = it.createdAt,
                )
            },
        )
    }

    suspend fun writeTo(target: File): BackupStats = withContext(Dispatchers.IO) {
        val manifest = buildManifest()
        target.outputStream().use { out ->
            writer.write(out, manifest) { imageStore.file(it) }
        }
    }

    suspend fun inspect(source: File): RestoreOutcome = withContext(Dispatchers.IO) {
        source.inputStream().use { reader.peek(it) }
    }

    /**
     * Replaces all local data with the backup's contents.
     *
     * The database goes first, inside a transaction, so it is all-or-nothing. Images follow;
     * if the process dies between the two, rows point at files that are not there yet, which
     * renders as the missing-image placeholder and is fixed by restoring again. The reverse
     * order would instead delete the images belonging to rows that are still live.
     */
    suspend fun restore(source: File): RestoreResult = withContext(Dispatchers.IO) {
        when (val outcome = inspect(source)) {
            is RestoreOutcome.Refused -> RestoreResult.Refused(outcome.problem)
            is RestoreOutcome.Ready -> runCatching {
                val manifest = outcome.manifest

                database.withTransaction {
                    database.reportDao().deleteAll() // cascades to receipts
                    database.reportDao().insertAll(
                        manifest.reports.map {
                            Report(it.id, it.name, it.createdAt, it.updatedAt, it.sortOrder)
                        }
                    )
                    database.receiptDao().insertAll(
                        manifest.receipts.map {
                            Receipt(
                                id = it.id,
                                reportId = it.reportId,
                                description = it.description,
                                amountMinor = it.amountMinor,
                                date = it.date,
                                imageFile = it.imageFile,
                                createdAt = it.createdAt,
                            )
                        }
                    )
                }

                val dir = imageStore.ensureDir()
                dir.listFiles()?.forEach { it.delete() }
                val images = source.inputStream().use { reader.extractImages(it, dir) }

                RestoreResult.Success(
                    RestoreSummary(manifest.reports.size, manifest.receipts.size, images)
                )
            }.getOrElse {
                RestoreResult.Failed(it.message ?: it::class.simpleName ?: "restore failed")
            }
        }
    }
}
