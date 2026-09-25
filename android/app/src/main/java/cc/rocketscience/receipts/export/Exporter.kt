package cc.rocketscience.receipts.export

import android.content.Context
import cc.rocketscience.receipts.data.ImageStore
import cc.rocketscience.receipts.data.Receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency

sealed interface ExportResult {
    data class Success(val file: File, val mimeType: String) : ExportResult
    data class Failure(val message: String) : ExportResult
}

/**
 * Builds export files into `cacheDir/export/` and hands back the file for the share sheet.
 *
 * Cache, not files: these are disposable derivatives of the database, and Android may reclaim
 * the space. The directory is also swept at app start so old exports do not accumulate.
 */
class Exporter(
    private val context: Context,
    private val imageStore: ImageStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd")

    private val dir: File
        get() = File(context.cacheDir, "export").apply { if (!exists()) mkdirs() }

    fun rowsFor(receipts: List<Receipt>): List<ExportRow> =
        receipts.mapIndexed { i, receipt ->
            ExportRow(
                index = i + 1,
                date = receipt.date,
                description = receipt.description,
                amountMinor = receipt.amountMinor,
                image = receipt.imageFile?.let { imageStore.file(it).takeIf(File::isFile) },
            )
        }

    suspend fun exportPdf(
        reportName: String,
        receipts: List<Receipt>,
        currency: Currency,
    ): ExportResult = withContext(Dispatchers.IO) {
        val target = File(dir, fileName(reportName, "pdf"))
        runCatching {
            target.outputStream().use { out ->
                PdfExporter(zone).write(out, reportName, rowsFor(receipts), currency)
            }
            ExportResult.Success(target, "application/pdf")
        }.getOrElse {
            target.delete()
            ExportResult.Failure(it.message ?: "Could not build the PDF")
        }
    }

    suspend fun exportZip(
        reportName: String,
        receipts: List<Receipt>,
        currency: Currency,
    ): ExportResult = withContext(Dispatchers.IO) {
        val target = File(dir, fileName(reportName, "zip"))
        runCatching {
            target.outputStream().use { out ->
                ZipExporter(zone).write(out, rowsFor(receipts), currency)
            }
            ExportResult.Success(target, "application/zip")
        }.getOrElse {
            target.delete()
            ExportResult.Failure(it.message ?: "Could not build the ZIP")
        }
    }

    fun fileName(reportName: String, extension: String): String {
        val date = stamp.format(Instant.now().atZone(zone))
        return "${slugify(reportName, fallback = "report")}-$date.$extension"
    }

    /** Old exports are already shared or abandoned; nothing needs them after a restart. */
    fun pruneExports() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
