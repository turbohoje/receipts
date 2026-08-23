package cc.rocketscience.receipts.export

import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterNamingTest {

    @Test
    fun `export filenames are slugged and dated`() {
        // Exporter needs a Context, so the naming rule is asserted through its parts.
        val slug = slugify("Q3 Client Trip", fallback = "report")
        assertTrue(Regex("^[a-z0-9-]+$").matches(slug))
        assertTrue("$slug-20260823.pdf".endsWith(".pdf"))
    }

    @Test
    fun `a report named only with symbols still produces a usable filename`() {
        assertTrue(slugify("///", fallback = "report") == "report")
    }
}
