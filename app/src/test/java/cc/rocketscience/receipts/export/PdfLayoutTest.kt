package cc.rocketscience.receipts.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagination arithmetic. When this is wrong it does not crash — it silently drops line items
 * off the bottom of a page, which is the worst possible failure for an expense report.
 */
class PdfLayoutTest {

    @Test
    fun `the first page holds fewer rows than the rest`() {
        assertTrue(PdfLayout.rowsPerPage(firstPage = true) < PdfLayout.rowsPerPage(firstPage = false))
    }

    @Test
    fun `an empty report still produces one page`() {
        assertEquals(listOf(IntRange.EMPTY), PdfLayout.paginate(0))
    }

    @Test
    fun `a short report fits on a single page`() {
        val pages = PdfLayout.paginate(5)
        assertEquals(1, pages.size)
        assertEquals(0..4, pages.single())
    }

    @Test
    fun `every row appears exactly once across pages`() {
        for (count in listOf(1, 14, 25, 26, 27, 60, 200)) {
            val covered = PdfLayout.paginate(count).flatten()
            assertEquals("count=$count", count, covered.size)
            assertEquals("count=$count", (0 until count).toList(), covered)
        }
    }

    @Test
    fun `pages are contiguous with no gaps or overlaps`() {
        val pages = PdfLayout.paginate(200)
        pages.zipWithNext().forEach { (a, b) ->
            assertEquals("page boundary", a.last + 1, b.first)
        }
    }

    @Test
    fun `no page exceeds its row capacity`() {
        val pages = PdfLayout.paginate(200)
        pages.forEachIndexed { i, range ->
            val capacity = PdfLayout.rowsPerPage(firstPage = i == 0)
            assertTrue("page $i held ${range.count()} > $capacity", range.count() <= capacity)
        }
    }
}
