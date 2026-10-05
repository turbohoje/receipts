package cc.rocketscience.receipts.reorder

/**
 * The ordering rules for the reports list, kept out of the UI so they can be tested directly —
 * the same treatment the crop gesture maths gets.
 *
 * `sortOrder` is ascending: the smallest value is at the top. Rows that have never been
 * dragged all hold 0 and fall back to `createdAt DESC`, so the list looks exactly as it did
 * before manual ordering existed.
 */
object ReportOrder {

    /**
     * Where a newly created report goes: above everything already there, because the report
     * you just made is the one you are about to add receipts to.
     */
    fun sortOrderForNew(existingMinimum: Long?): Long =
        if (existingMinimum == null) 0 else existingMinimum - 1

    /**
     * Values to persist for a list already in its intended order.
     *
     * The whole list is renumbered from zero rather than nudging one row, so the stored order
     * can never drift into ties or run out of room between two neighbours.
     */
    fun sortOrders(count: Int): List<Long> = (0 until count).map { it.toLong() }

    /**
     * Where an item dragged from [fromIndex] by [offsetY] pixels should land.
     *
     * Half an item's height is the threshold, so the row swaps once it has visibly passed its
     * neighbour rather than at the first pixel of movement.
     */
    fun targetIndex(fromIndex: Int, offsetY: Float, itemHeight: Float, count: Int): Int {
        if (count <= 1 || itemHeight <= 0f) return fromIndex
        val moved = Math.round(offsetY / itemHeight)
        return (fromIndex + moved).coerceIn(0, count - 1)
    }

    /** Moves one element, shifting everything between. */
    fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from == to || from !in indices || to !in indices) return this
        val copy = toMutableList()
        copy.add(to, copy.removeAt(from))
        return copy
    }
}
