package cc.rocketscience.receipts.export

import cc.rocketscience.receipts.money.Money
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `report.csv` plus the images, numbered to match the CSV row order.
 *
 * Takes an [OutputStream] rather than a path so the whole thing is testable off-device.
 */
class ZipExporter(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

    fun write(
        out: OutputStream,
        rows: List<ExportRow>,
        currency: Currency,
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("report.csv"))
            zip.write(buildCsv(rows, currency).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (row in rows) {
                val image = row.image ?: continue
                if (!image.isFile) continue
                zip.putNextEntry(ZipEntry(imageEntryName(row)))
                image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun imageEntryName(row: ExportRow): String =
        "images/%02d-%s.jpg".format(row.index, slugify(row.description))

    fun buildCsv(rows: List<ExportRow>, currency: Currency): String = buildString {
        append(Csv.row("date", "description", "amount", "currency", "image"))
        for (row in rows) {
            append(
                Csv.row(
                    isoDate.format(Instant.ofEpochMilli(row.date).atZone(zone)),
                    row.description,
                    Money.formatPlain(row.amountMinor, currency),
                    currency.currencyCode,
                    if (row.image != null) imageEntryName(row) else "",
                )
            )
        }
        // Trailing total, so the file is self-checking when opened in a spreadsheet.
        append(
            Csv.row(
                "",
                "TOTAL",
                Money.formatPlain(rows.sumOf { it.amountMinor }, currency),
                currency.currencyCode,
                "",
            )
        )
    }
}
