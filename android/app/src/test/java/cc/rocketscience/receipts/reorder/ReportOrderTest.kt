package cc.rocketscience.receipts.reorder

import cc.rocketscience.receipts.reorder.ReportOrder.moved
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportOrderTest {

    @Test
    fun `a new report goes above everything already there`() {
        assertEquals(-1L, ReportOrder.sortOrderForNew(0L))
        assertEquals(-6L, ReportOrder.sortOrderForNew(-5L))
        // The first report in an empty app needs no room above it.
        assertEquals(0L, ReportOrder.sortOrderForNew(null))
    }

    @Test
    fun `the whole list is renumbered from zero`() {
        // Renumbering rather than nudging one row is what stops ties and exhausted gaps.
        assertEquals(listOf(0L, 1L, 2L), ReportOrder.sortOrders(3))
        assertEquals(emptyList<Long>(), ReportOrder.sortOrders(0))
    }

    @Test
    fun `a drag shorter than half a row does not move anything`() {
        assertEquals(2, ReportOrder.targetIndex(2, offsetY = 20f, itemHeight = 100f, count = 5))
        assertEquals(2, ReportOrder.targetIndex(2, offsetY = -49f, itemHeight = 100f, count = 5))
    }

    @Test
    fun `a drag past half a row moves one place`() {
        assertEquals(3, ReportOrder.targetIndex(2, offsetY = 60f, itemHeight = 100f, count = 5))
        assertEquals(1, ReportOrder.targetIndex(2, offsetY = -60f, itemHeight = 100f, count = 5))
    }

    @Test
    fun `a long drag moves several places`() {
        assertEquals(4, ReportOrder.targetIndex(0, offsetY = 420f, itemHeight = 100f, count = 5))
    }

    @Test
    fun `a drag cannot leave the list`() {
        assertEquals(0, ReportOrder.targetIndex(2, offsetY = -9999f, itemHeight = 100f, count = 5))
        assertEquals(4, ReportOrder.targetIndex(2, offsetY = 9999f, itemHeight = 100f, count = 5))
    }

    @Test
    fun `a single row and a zero height are no-ops`() {
        assertEquals(0, ReportOrder.targetIndex(0, offsetY = 500f, itemHeight = 100f, count = 1))
        assertEquals(2, ReportOrder.targetIndex(2, offsetY = 500f, itemHeight = 0f, count = 5))
    }

    @Test
    fun `moving shifts everything between`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
        assertEquals(listOf("a", "d", "b", "c"), list.moved(3, 1))
        assertEquals(list, list.moved(1, 1))
        assertEquals(list, list.moved(1, 9))
    }
}
