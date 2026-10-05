import Foundation

/// All amounts are `Int64` minor units (cents) so that totalling is exact.
/// Nothing in this app ever holds money in a `Double` — including in the checks.
public enum Money {

    private static func pow10(_ n: Int) -> UInt64 {
        var result: UInt64 = 1
        for _ in 0..<n { result *= 10 }
        return result
    }

    /// Bare number for CSV and editing: no symbol, no grouping. e.g. 2450 -> `"24.50"`.
    ///
    /// This one is part of the cross-platform contract — it is what lands in `report.csv` —
    /// so it must agree with Android's `BigDecimal.valueOf(minor, digits).toPlainString()`
    /// exactly, for every currency precision and for negatives.
    public static func formatPlain(_ minorUnits: Int64, _ currency: Currency) -> String {
        let sign = minorUnits < 0 ? "-" : ""
        let magnitude = minorUnits.magnitude
        let digits = currency.fractionDigits
        if digits == 0 { return sign + String(magnitude) }
        let divisor = pow10(digits)
        let whole = magnitude / divisor
        var fraction = String(magnitude % divisor)
        if fraction.count < digits {
            fraction = String(repeating: "0", count: digits - fraction.count) + fraction
        }
        return "\(sign)\(whole).\(fraction)"
    }

    /// Exact decimal value. `Decimal` is base-ten, so this introduces no binary rounding.
    public static func decimal(_ minorUnits: Int64, _ currency: Currency) -> Decimal {
        Decimal(
            sign: minorUnits < 0 ? .minus : .plus,
            exponent: -currency.fractionDigits,
            significand: Decimal(minorUnits.magnitude)
        )
    }

    /// Display form, e.g. 2450 -> `"$24.50"`. Locale-formatted, so never compared across
    /// platforms: ICU data differs between Android and iOS releases.
    public static func format(
        _ minorUnits: Int64,
        _ currency: Currency,
        locale: Locale = .current
    ) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = locale
        formatter.currencyCode = currency.code
        formatter.minimumFractionDigits = currency.fractionDigits
        formatter.maximumFractionDigits = currency.fractionDigits
        let value = decimal(minorUnits, currency) as NSDecimalNumber
        return formatter.string(from: value) ?? formatPlain(minorUnits, currency)
    }

    /// Parses user input into minor units, or nil if there is no number in it.
    ///
    /// Separator meaning comes from `locale` rather than guesswork, because the keypad the
    /// user is typing on is the locale's. So in en-US "12.345" is twelve-point-three-four-five
    /// and "1,500" is fifteen hundred, while in de-DE those swap. Two fallbacks cover pasted
    /// text: when several separators are present the last one is the decimal point
    /// ("$1,234.56"), and a lone non-locale separator is read as a decimal point unless it has
    /// exactly three digits after it, which is thousands grouping ("24,50" in en-US is 24.50,
    /// but "1,500" is 1500).
    ///
    /// Ported line for line from Android's `Money.parse`, with one documented narrowing: only
    /// ASCII digits count. Kotlin's `Char.isDigit` accepts any Unicode digit, but `BigDecimal`
    /// then rejects the result, so both sides return nil for Arabic-Indic input.
    public static func parse(
        _ text: String,
        _ currency: Currency,
        locale: Locale = .current
    ) -> Int64? {
        let decimalSeparator = (locale.decimalSeparator ?? ".").first ?? "."
        let groupingSeparator = (locale.groupingSeparator ?? ",").first ?? ","

        let negative = text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("-")
        let kept = Array(text.filter {
            $0.isASCIIDigit || $0 == decimalSeparator || $0 == groupingSeparator
        })
        guard kept.contains(where: { $0.isASCIIDigit }) else { return nil }

        let separators = kept.enumerated().filter { !$0.element.isASCIIDigit }
        let decimalAt: Int?
        switch separators.count {
        case 0:
            decimalAt = nil
        case 1:
            let (index, character) = (separators[0].offset, separators[0].element)
            let trailingDigits = kept.count - index - 1
            if character == decimalSeparator {
                decimalAt = index
            } else if trailingDigits == 3 {
                decimalAt = nil                 // thousands grouping
            } else {
                decimalAt = index               // a foreign decimal separator
            }
        default:
            decimalAt = separators[separators.count - 1].offset
        }

        let wholeDigits = (decimalAt == nil ? kept : Array(kept[..<decimalAt!]))
            .filter { $0.isASCIIDigit }
        let fractionDigits = (decimalAt == nil ? [] : Array(kept[(decimalAt! + 1)...]))
            .filter { $0.isASCIIDigit }

        return minorUnits(
            whole: String(wholeDigits),
            fraction: String(fractionDigits),
            digits: currency.fractionDigits,
            negative: negative
        )
    }

    /// `whole.fraction` scaled to `digits` places, rounded half up, or nil on overflow.
    ///
    /// Integer arithmetic throughout: the equivalent of Android's
    /// `BigDecimal.movePointRight(digits).setScale(0, HALF_UP)` without ever holding the value
    /// in a floating point type.
    private static func minorUnits(
        whole: String,
        fraction: String,
        digits: Int,
        negative: Bool
    ) -> Int64? {
        var magnitude: UInt64 = 0
        for character in whole {
            guard let value = character.wholeNumberValue else { return nil }
            let (multiplied, overflowA) = magnitude.multipliedReportingOverflow(by: 10)
            guard !overflowA else { return nil }
            let (added, overflowB) = multiplied.addingReportingOverflow(UInt64(value))
            guard !overflowB else { return nil }
            magnitude = added
        }

        let fractionCharacters = Array(fraction)
        for position in 0..<digits {
            let digit = position < fractionCharacters.count
                ? (fractionCharacters[position].wholeNumberValue ?? 0)
                : 0
            let (multiplied, overflowA) = magnitude.multipliedReportingOverflow(by: 10)
            guard !overflowA else { return nil }
            let (added, overflowB) = multiplied.addingReportingOverflow(UInt64(digit))
            guard !overflowB else { return nil }
            magnitude = added
        }

        // HALF_UP: the first discarded digit decides, and nothing beyond it can change that.
        if fractionCharacters.count > digits,
           let next = fractionCharacters[digits].wholeNumberValue, next >= 5 {
            let (bumped, overflow) = magnitude.addingReportingOverflow(1)
            guard !overflow else { return nil }
            magnitude = bumped
        }

        if negative {
            guard magnitude <= UInt64(Int64.max) + 1 else { return nil }
            if magnitude == UInt64(Int64.max) + 1 { return Int64.min }
            return -Int64(magnitude)
        }
        guard magnitude <= UInt64(Int64.max) else { return nil }
        return Int64(magnitude)
    }
}

extension Character {
    /// Deliberately ASCII-only; see the note on `Money.parse`.
    var isASCIIDigit: Bool { self >= "0" && self <= "9" }
}
