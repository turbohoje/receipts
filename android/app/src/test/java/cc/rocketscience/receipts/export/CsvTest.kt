package cc.rocketscience.receipts.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test
    fun `plain fields are not quoted`() {
        assertEquals("Taxi", Csv.field("Taxi"))
        assertEquals("24.50", Csv.field("24.50"))
        assertEquals("", Csv.field(""))
    }

    @Test
    fun `commas force quoting`() {
        // Without this, one description silently becomes two columns.
        assertEquals("\"Dinner, drinks\"", Csv.field("Dinner, drinks"))
    }

    @Test
    fun `quotes are doubled`() {
        assertEquals("\"He said \"\"cheap\"\"\"", Csv.field("He said \"cheap\""))
    }

    @Test
    fun `newlines force quoting`() {
        assertEquals("\"line one\nline two\"", Csv.field("line one\nline two"))
        assertEquals("\"a\rb\"", Csv.field("a\rb"))
    }

    @Test
    fun `leading and trailing whitespace is preserved by quoting`() {
        assertEquals("\" padded \"", Csv.field(" padded "))
    }

    @Test
    fun `rows end with CRLF as the spec requires`() {
        assertEquals("a,b,c\r\n", Csv.row("a", "b", "c"))
    }

    @Test
    fun `a row mixes quoted and unquoted fields`() {
        assertEquals(
            "2026-08-23,\"Dinner, drinks\",81.00,USD\r\n",
            Csv.row("2026-08-23", "Dinner, drinks", "81.00", "USD"),
        )
    }
}
