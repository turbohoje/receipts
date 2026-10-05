import Foundation
import RSReceiptsCore

/// Ported from Android's `MoneyTest`, assertion for assertion.
func moneyChecks(_ harness: Harness) {
    harness.section("Money")

    let usd = Currency.code("USD")
    let jpy = Currency.code("JPY")
    let enUS = Locale(identifier: "en_US")
    let deDE = Locale(identifier: "de_DE")

    harness.check("currency precision comes from CLDR") {
        try expect(usd.fractionDigits, 2)
        try expect(jpy.fractionDigits, 0)
        try expect(Currency.code("KWD").fractionDigits, 3)
    }

    harness.check("formats minor units as currency") {
        try expect(Money.format(2450, usd, locale: enUS), "$24.50")
        try expect(Money.format(0, usd, locale: enUS), "$0.00")
        try expect(Money.format(123456, usd, locale: enUS), "$1,234.56")
    }

    harness.check("formats zero-decimal currency without a fraction") {
        let formatted = Money.format(1500, jpy, locale: enUS)
        try expectTrue(formatted.contains("1,500"), "got \(formatted)")
        try expectTrue(!formatted.contains("."), "got \(formatted)")
    }

    harness.check("formats plain for editing") {
        try expect(Money.formatPlain(2450, usd), "24.50")
        try expect(Money.formatPlain(1500, jpy), "1500")
        try expect(Money.formatPlain(0, usd), "0.00")
        try expect(Money.formatPlain(-5, usd), "-0.05")
        try expect(Money.formatPlain(7, usd), "0.07")
    }

    harness.check("parses ordinary input") {
        try expect(Money.parse("24.50", usd, locale: enUS), 2450)
        try expect(Money.parse("12.5", usd, locale: enUS), 1250)
        try expect(Money.parse("12", usd, locale: enUS), 1200)
        try expect(Money.parse("0.07", usd, locale: enUS), 7)
    }

    harness.check("parses pasted currency strings") {
        try expect(Money.parse("$1,234.56", usd, locale: enUS), 123456)
        try expect(Money.parse(" 1 234.56 USD ", usd, locale: enUS), 123456)
    }

    harness.check("reads a lone three-digit group as thousands") {
        try expect(Money.parse("1,500", usd, locale: enUS), 150000)
    }

    harness.check("respects the locale's decimal separator over the grouping heuristic") {
        try expect(Money.parse("1.500", usd, locale: enUS), 150)
        try expect(Money.parse("1.500", usd, locale: deDE), 150000)
        try expect(Money.parse("1,500", usd, locale: deDE), 150)
    }

    harness.check("reads a non-locale separator as a decimal point") {
        try expect(Money.parse("24,50", usd, locale: enUS), 2450)
        try expect(Money.parse("24.50", usd, locale: deDE), 2450)
    }

    harness.check("parses fully-grouped foreign input") {
        try expect(Money.parse("1.234,56", usd, locale: deDE), 123456)
        try expect(Money.parse("1,234.56", usd, locale: enUS), 123456)
    }

    harness.check("rounds half up beyond the currency's precision") {
        try expect(Money.parse("12.345", usd, locale: enUS), 1235)
        try expect(Money.parse("12.344", usd, locale: enUS), 1234)
    }

    harness.check("handles negatives") {
        try expect(Money.parse("-5.00", usd, locale: enUS), -500)
    }

    harness.check("returns nil when there is no number") {
        try expectNil(Money.parse("", usd, locale: enUS))
        try expectNil(Money.parse("   ", usd, locale: enUS))
        try expectNil(Money.parse("abc", usd, locale: enUS))
        try expectNil(Money.parse("$", usd, locale: enUS))
    }

    harness.check("round trips through plain formatting") {
        for minor: Int64 in [0, 1, 99, 100, 2450, 123456, -2450] {
            try expect(
                Money.parse(Money.formatPlain(minor, usd), usd, locale: enUS), minor,
                "round trip of \(minor)")
        }
    }

    harness.check("overflow yields nil rather than a wrapped number") {
        try expectNil(Money.parse(String(repeating: "9", count: 40), usd, locale: enUS))
    }
}
