package cc.rocketscience.receipts.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import cc.rocketscience.receipts.money.Money
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import kotlin.math.min

/**
 * The expense-report artifact: a summary table, then one page per receipt image.
 *
 * The per-image pages carry a caption keyed to the summary row number, which is what makes the
 * document reviewable — an image with no way back to its line item is just an attachment.
 */
class PdfExporter(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    private val title = Paint().apply {
        isAntiAlias = true
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitle = Paint().apply {
        isAntiAlias = true
        textSize = 11f
        color = 0xFF555555.toInt()
    }
    private val header = Paint().apply {
        isAntiAlias = true
        textSize = 10f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val body = Paint().apply {
        isAntiAlias = true
        textSize = 11f
    }
    private val bodyRight = Paint().apply {
        isAntiAlias = true
        textSize = 11f
        textAlign = Paint.Align.RIGHT
    }
    private val totalPaint = Paint().apply {
        isAntiAlias = true
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val rule = Paint().apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 0.7f
    }
    private val caption = Paint().apply {
        isAntiAlias = true
        textSize = 10f
        color = 0xFF333333.toInt()
    }
    private val placeholder = Paint().apply {
        isAntiAlias = true
        textSize = 11f
        color = 0xFF999999.toInt()
        textAlign = Paint.Align.CENTER
    }

    fun write(
        out: OutputStream,
        reportName: String,
        rows: List<ExportRow>,
        currency: Currency,
    ) {
        val document = PdfDocument()
        var pageNumber = 1
        val total = rows.sumOf { it.amountMinor }

        PdfLayout.paginate(rows.size).forEachIndexed { pageIndex, range ->
            val page = document.startPage(pageInfo(pageNumber++))
            drawSummaryPage(
                canvas = page.canvas,
                reportName = reportName,
                rows = rows,
                range = range,
                currency = currency,
                total = total,
                isFirst = pageIndex == 0,
            )
            document.finishPage(page)
        }

        for (row in rows) {
            val page = document.startPage(pageInfo(pageNumber++))
            drawImagePage(page.canvas, row, currency)
            document.finishPage(page)
        }

        document.writeTo(out)
        document.close()
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(PdfLayout.PAGE_WIDTH, PdfLayout.PAGE_HEIGHT, number).create()

    private fun drawSummaryPage(
        canvas: Canvas,
        reportName: String,
        rows: List<ExportRow>,
        range: IntRange,
        currency: Currency,
        total: Long,
        isFirst: Boolean,
    ) {
        val left = PdfLayout.MARGIN
        val right = PdfLayout.PAGE_WIDTH - PdfLayout.MARGIN
        var y = PdfLayout.MARGIN + 14f

        if (isFirst) {
            canvas.drawText(reportName, left, y, title)
            y += PdfLayout.TITLE_HEIGHT
            val count = rows.size
            canvas.drawText(
                "$count ${if (count == 1) "receipt" else "receipts"}  ·  " +
                    "Total ${Money.format(total, currency)}",
                left,
                y,
                subtitle,
            )
            y += PdfLayout.SUBTITLE_HEIGHT
        } else {
            canvas.drawText("$reportName (continued)", left, y, header)
            y += PdfLayout.TITLE_HEIGHT
        }

        // Column headers
        canvas.drawText("#", left, y, header)
        canvas.drawText("Date", left + 22f, y, header)
        canvas.drawText("Description", left + 110f, y, header)
        bodyRight.let { canvas.drawText("Amount", right, y, header.rightAligned()) }
        y += 6f
        canvas.drawLine(left, y, right, y, rule)
        y += PdfLayout.ROW_HEIGHT - 6f

        val descriptionWidth = right - (left + 110f) - 80f
        for (i in range) {
            val row = rows[i]
            canvas.drawText(row.index.toString(), left, y, body)
            canvas.drawText(
                dateFormat.format(Instant.ofEpochMilli(row.date).atZone(zone)),
                left + 22f,
                y,
                body,
            )
            canvas.drawText(
                ellipsize(row.description.ifBlank { "(no description)" }, descriptionWidth, body),
                left + 110f,
                y,
                body,
            )
            canvas.drawText(Money.format(row.amountMinor, currency), right, y, bodyRight)
            y += PdfLayout.ROW_HEIGHT
        }

        if (isFirst || range.last == rows.lastIndex) {
            y += 4f
            canvas.drawLine(left, y, right, y, rule)
            y += 20f
            canvas.drawText("Total  ${Money.format(total, currency)}", right, y, totalPaint)
        }
    }

    private fun drawImagePage(canvas: Canvas, row: ExportRow, currency: Currency) {
        val left = PdfLayout.MARGIN
        val right = PdfLayout.PAGE_WIDTH - PdfLayout.MARGIN
        val top = PdfLayout.MARGIN

        val captionText = buildString {
            append("#${row.index}")
            append("  ·  ")
            append(dateFormat.format(Instant.ofEpochMilli(row.date).atZone(zone)))
            if (row.description.isNotBlank()) {
                append("  ·  ")
                append(row.description)
            }
            append("  ·  ")
            append(Money.format(row.amountMinor, currency))
        }
        canvas.drawText(
            ellipsize(captionText, right - left, caption),
            left,
            top + 10f,
            caption,
        )

        val frame = RectF(
            left,
            top + 26f,
            right,
            PdfLayout.PAGE_HEIGHT - PdfLayout.MARGIN,
        )

        val file = row.image
        if (file == null || !file.isFile) {
            canvas.drawText(
                if (file == null) "No photo for this receipt" else "Photo missing from storage",
                (left + right) / 2f,
                frame.centerY(),
                placeholder,
            )
            return
        }

        val bitmap = decodeToFit(file.path, frame.width().toInt(), frame.height().toInt())
        if (bitmap == null) {
            canvas.drawText(
                "Photo could not be read",
                (left + right) / 2f,
                frame.centerY(),
                placeholder,
            )
            return
        }

        // Letterbox inside the frame, preserving aspect ratio.
        val scale = min(frame.width() / bitmap.width, frame.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val dest = RectF(
            frame.centerX() - w / 2f,
            frame.top,
            frame.centerX() + w / 2f,
            frame.top + h,
        )
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dest, null)
        bitmap.recycle()
    }

    /**
     * Decodes at roughly [PRINT_DPI_FACTOR]× the drawn size. Drawing a page-sized bitmap at
     * 72dpi would look soft in print, and decoding the full 2048px original for every page
     * would risk running out of memory on a long report.
     */
    private fun decodeToFit(path: String, frameWidth: Int, frameHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        val targetW = frameWidth * PRINT_DPI_FACTOR
        val targetH = frameHeight * PRINT_DPI_FACTOR
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return text.take(end).trimEnd() + "…"
    }

    private fun Paint.rightAligned(): Paint =
        Paint(this).apply { textAlign = Paint.Align.RIGHT }

    private companion object {
        const val PRINT_DPI_FACTOR = 3
    }
}
