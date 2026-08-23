package cc.rocketscience.receipts.export

import org.junit.Assert.assertEquals
import org.junit.Test

class SlugifyTest {

    @Test
    fun `spaces and punctuation collapse to single dashes`() {
        assertEquals("q3-client-trip", slugify("Q3 Client Trip"))
        assertEquals("dinner-drinks", slugify("Dinner, drinks"))
    }

    @Test
    fun `path separators cannot survive`() {
        // A name must never be able to walk out of its directory or its zip entry.
        assertEquals("etc-passwd", slugify("../../etc/passwd"))
        assertEquals("a-b", slugify("a/b"))
        assertEquals("receipt", slugify("../..")) // default fallback
    }

    @Test
    fun `leading and trailing separators are trimmed`() {
        assertEquals("trip", slugify("  --Trip--  "))
    }

    @Test
    fun `empty and symbol-only names fall back`() {
        assertEquals("receipt", slugify(""))
        assertEquals("receipt", slugify("!!!"))
        assertEquals("report", slugify("", fallback = "report"))
    }

    @Test
    fun `long names are truncated without a trailing dash`() {
        val slug = slugify("a".repeat(80))
        assertEquals(40, slug.length)

        val truncatedAtDash = slugify("abcdefghij klmnopqrst uvwxyzabcd efghijklmn opqrstuvwx", max = 33)
        assertEquals(false, truncatedAtDash.endsWith("-"))
    }
}
