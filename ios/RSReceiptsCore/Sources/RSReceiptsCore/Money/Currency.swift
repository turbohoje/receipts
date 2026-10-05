import Foundation

/// A currency and the number of minor units it divides into.
///
/// Stands in for `java.util.Currency`, which the Android side uses for exactly one thing:
/// `defaultFractionDigits`. Foundation has no equivalent property, so it is recovered from
/// `NumberFormatter`, which carries CLDR's per-currency precision (USD 2, JPY 0, KWD 3).
public struct Currency: Hashable, Sendable {

    public let code: String

    /// Minor units per major unit, as a power of ten. Never negative.
    public let fractionDigits: Int

    public init(code: String, fractionDigits: Int) {
        self.code = code
        self.fractionDigits = max(0, fractionDigits)
    }

    /// Resolves a currency by ISO code, e.g. `"USD"`.
    public static func code(_ code: String) -> Currency {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = code
        return Currency(code: code, fractionDigits: formatter.maximumFractionDigits)
    }

    /// The app-wide currency, taken from the device locale, falling back to USD.
    ///
    /// Matches Android's `Money.defaultCurrency`: derived, never stored. The backup manifest
    /// records the currency it was written with, but restore ignores it — so a backup moved
    /// between devices shows amounts in the receiving device's currency, on both platforms.
    public static func forLocale(_ locale: Locale = .current) -> Currency {
        guard let identifier = locale.currency?.identifier, !identifier.isEmpty else {
            return .code("USD")
        }
        return .code(identifier)
    }
}
