package cc.rocketscience.receipts.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * All amounts are stored as [Long] minor units (cents) so that totalling is exact.
 * Nothing in this app ever holds money in a Double.
 */
object Money {

    /** The app-wide currency. Phase 4 moves this behind a DataStore-backed setting. */
    fun defaultCurrency(locale: Locale = Locale.getDefault()): Currency =
        runCatching { Currency.getInstance(locale) }.getOrElse { Currency.getInstance("USD") }

    private fun digits(currency: Currency) = currency.defaultFractionDigits.coerceAtLeast(0)

    fun toBigDecimal(minorUnits: Long, currency: Currency): BigDecimal =
        BigDecimal.valueOf(minorUnits, digits(currency))

    /** e.g. 2450 -> "$24.50" */
    fun format(
        minorUnits: Long,
        currency: Currency,
        locale: Locale = Locale.getDefault(),
    ): String {
        val fmt = NumberFormat.getCurrencyInstance(locale)
        fmt.currency = currency
        fmt.minimumFractionDigits = digits(currency)
        fmt.maximumFractionDigits = digits(currency)
        return fmt.format(toBigDecimal(minorUnits, currency))
    }

    /** Bare number for CSV/editing, no symbol or grouping. e.g. 2450 -> "24.50" */
    fun formatPlain(minorUnits: Long, currency: Currency): String =
        toBigDecimal(minorUnits, currency).toPlainString()

    /**
     * Parses user input into minor units, or null if there is no number in it.
     *
     * Separator meaning comes from [locale] rather than guesswork, because the keypad the
     * user is typing on is the locale's. So in en-US "12.345" is twelve-point-three-four-five
     * and "1,500" is fifteen hundred, while in de-DE those swap. Two fallbacks cover pasted
     * text: when several separators are present the last one is the decimal point
     * ("$1,234.56"), and a lone non-locale separator is read as a decimal point unless it has
     * exactly three digits after it, which is thousands grouping ("24,50" in en-US is 24.50,
     * but "1,500" is 1500).
     */
    fun parse(
        text: String,
        currency: Currency,
        locale: Locale = Locale.getDefault(),
    ): Long? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val decimalSep = symbols.decimalSeparator
        val groupingSep = symbols.groupingSeparator

        val negative = text.trim().startsWith('-')
        val kept = text.filter { it.isDigit() || it == decimalSep || it == groupingSep }
        if (kept.none { it.isDigit() }) return null

        val separators = kept.withIndex().filterNot { it.value.isDigit() }
        val decimalAt: Int? = when (separators.size) {
            0 -> null
            1 -> {
                val (index, char) = separators.single()
                val trailingDigits = kept.length - index - 1
                when {
                    char == decimalSep -> index
                    trailingDigits == 3 -> null // thousands grouping
                    else -> index               // a foreign decimal separator
                }
            }
            else -> separators.last().index
        }

        val whole = (if (decimalAt == null) kept else kept.take(decimalAt))
            .filter { it.isDigit() }
        val fraction = (if (decimalAt == null) "" else kept.substring(decimalAt + 1))
            .filter { it.isDigit() }

        val decimal = runCatching {
            BigDecimal("${whole.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}")
        }.getOrNull() ?: return null

        val minor = runCatching {
            decimal.movePointRight(digits(currency))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull() ?: return null

        return if (negative) -minor else minor
    }
}
