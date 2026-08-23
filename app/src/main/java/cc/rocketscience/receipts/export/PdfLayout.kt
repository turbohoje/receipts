package cc.rocketscience.receipts.export

/**
 * Page geometry and pagination for the PDF, kept free of Android types so the arithmetic —
 * the part that silently drops rows off the bottom of a page when it is wrong — is testable.
 *
 * A4 at 72 points per inch.
 */
object PdfLayout {

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 42f

    const val TITLE_HEIGHT = 30f
    const val SUBTITLE_HEIGHT = 22f
    const val TABLE_HEADER_HEIGHT = 24f
    const val ROW_HEIGHT = 20f
    const val TOTAL_ROW_HEIGHT = 34f
    const val FOOTER_HEIGHT = 18f

    /** Vertical space available for table rows, which is smaller on page 1 (title + totals). */
    fun rowSpace(firstPage: Boolean): Float {
        val usable = PAGE_HEIGHT - 2 * MARGIN - FOOTER_HEIGHT - TABLE_HEADER_HEIGHT
        return if (firstPage) {
            usable - TITLE_HEIGHT - SUBTITLE_HEIGHT - TOTAL_ROW_HEIGHT
        } else {
            usable
        }
    }

    fun rowsPerPage(firstPage: Boolean): Int =
        (rowSpace(firstPage) / ROW_HEIGHT).toInt().coerceAtLeast(1)

    /**
     * Splits [rowCount] rows across summary pages. Always returns at least one page, so an
     * empty report still produces a document with its header and a zero total.
     */
    fun paginate(rowCount: Int): List<IntRange> {
        if (rowCount <= 0) return listOf(IntRange.EMPTY)
        val pages = mutableListOf<IntRange>()
        var start = 0
        var first = true
        while (start < rowCount) {
            val size = rowsPerPage(first)
            val end = minOf(start + size, rowCount)
            pages += start until end
            start = end
            first = false
        }
        return pages
    }
}
