import Foundation

/// UTC milliseconds since 1970 — the unit every timestamp in the data model and the backup
/// manifest is in.
///
/// The single likeliest cross-platform bug: Foundation's `Date` is seconds (as a `Double`)
/// since 2001, so nothing may reach the model without passing through here. `Int64`
/// milliseconds up to the year 2100 are ~4.1e12, far inside `Double`'s exact-integer range,
/// so the conversion is lossless in both directions.
public typealias EpochMillis = Int64

public extension Date {
    init(epochMillis: EpochMillis) {
        self.init(timeIntervalSince1970: Double(epochMillis) / 1000)
    }

    var epochMillis: EpochMillis {
        EpochMillis((timeIntervalSince1970 * 1000).rounded())
    }
}
