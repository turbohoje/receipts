package cc.rocketscience.receipts.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Currency
import java.util.Locale

class MoneyTest {

    private val usd = Currency.getInstance("USD")
    private val jpy = Currency.getInstance("JPY")

    @Test
    fun `formats minor units as currency`() {
        assertEquals("$24.50", Money.format(2450, usd, Locale.US))
        assertEquals("$0.00", Money.format(0, usd, Locale.US))
        assertEquals("$1,234.56", Money.format(123456, usd, Locale.US))
    }

    @Test
    fun `formats zero-decimal currency without a fraction`() {
        // The yen symbol varies across JDK/CLDR versions; the point is the missing decimals.
        val formatted = Money.format(1500, jpy, Locale.US)
        assertTrue(formatted, formatted.contains("1,500"))
        assertFalse(formatted, formatted.contains("."))
    }

    @Test
    fun `formats plain for editing`() {
        assertEquals("24.50", Money.formatPlain(2450, usd))
        assertEquals("1500", Money.formatPlain(1500, jpy))
    }

    @Test
    fun `parses ordinary input`() {
        assertEquals(2450L, Money.parse("24.50", usd, Locale.US))
        assertEquals(1250L, Money.parse("12.5", usd, Locale.US))
        assertEquals(1200L, Money.parse("12", usd))
        assertEquals(7L, Money.parse("0.07", usd, Locale.US))
    }

    @Test
    fun `parses pasted currency strings`() {
        assertEquals(123456L, Money.parse("$1,234.56", usd, Locale.US))
        assertEquals(123456L, Money.parse(" 1 234.56 USD ", usd, Locale.US))
    }

    @Test
    fun `reads a lone three-digit group after a grouping separator as thousands`() {
        assertEquals(150000L, Money.parse("1,500", usd, Locale.US))
    }

    @Test
    fun `respects the locale's decimal separator over the grouping heuristic`() {
        // In en-US the dot is the decimal point, so this is one-point-five.
        assertEquals(150L, Money.parse("1.500", usd, Locale.US))
        // In de-DE the roles swap.
        assertEquals(150000L, Money.parse("1.500", usd, Locale.GERMANY))
        assertEquals(150L, Money.parse("1,500", usd, Locale.GERMANY))
    }

    @Test
    fun `reads a non-locale separator as a decimal point when it is not a thousands group`() {
        assertEquals(2450L, Money.parse("24,50", usd, Locale.US))
        assertEquals(2450L, Money.parse("24.50", usd, Locale.GERMANY))
    }

    @Test
    fun `parses fully-grouped foreign input`() {
        assertEquals(123456L, Money.parse("1.234,56", usd, Locale.GERMANY))
        assertEquals(123456L, Money.parse("1,234.56", usd, Locale.US))
    }

    @Test
    fun `rounds half up beyond the currency's precision`() {
        assertEquals(1235L, Money.parse("12.345", usd, Locale.US))
        assertEquals(1234L, Money.parse("12.344", usd, Locale.US))
    }

    @Test
    fun `handles negatives`() {
        assertEquals(-500L, Money.parse("-5.00", usd, Locale.US))
    }

    @Test
    fun `returns null when there is no number`() {
        assertNull(Money.parse("", usd))
        assertNull(Money.parse("   ", usd))
        assertNull(Money.parse("abc", usd))
        assertNull(Money.parse("$", usd))
    }

    @Test
    fun `round trips through plain formatting`() {
        for (minor in listOf(0L, 1L, 99L, 100L, 2450L, 123456L, -2450L)) {
            assertEquals(minor, Money.parse(Money.formatPlain(minor, usd), usd, Locale.US))
        }
    }
}
