import Foundation
import RSReceiptsCore
import SwiftUI

/// Display formatting. Everything contractual lives in the core; this is the locale-facing
/// layer, which is deliberately *not* compared against Android — ICU data differs between the
/// platforms' releases.
enum Display {

    static func amount(_ minorUnits: Int64, _ currency: Currency) -> String {
        Money.format(minorUnits, currency)
    }

    static func date(_ millis: EpochMillis) -> String {
        Date(epochMillis: millis).formatted(date: .abbreviated, time: .omitted)
    }

    static func dateRange(_ range: ClosedRange<EpochMillis>?) -> String? {
        guard let range else { return nil }
        let start = date(range.lowerBound)
        let end = date(range.upperBound)
        return start == end ? start : "\(start) – \(end)"
    }

    static func receiptCount(_ count: Int) -> String {
        count == 1 ? "1 receipt" : "\(count) receipts"
    }
}
