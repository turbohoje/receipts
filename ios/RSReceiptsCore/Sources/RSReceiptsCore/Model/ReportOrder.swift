import Foundation

/// The ordering rules for the reports list, matching Android's `ReportOrder` exactly.
///
/// `sortOrder` is ascending: the smallest value is at the top. Rows that have never been
/// dragged all hold 0 and fall back to `createdAt` descending, so the list looks exactly as it
/// did before manual ordering existed.
public enum ReportOrder {

    /// Where a newly created report goes: above everything already there, because the report
    /// you just made is the one you are about to add receipts to.
    public static func sortOrderForNew(existingMinimum: Int64?) -> Int64 {
        guard let existingMinimum else { return 0 }
        return existingMinimum - 1
    }

    /// Values to persist for a list already in its intended order.
    ///
    /// The whole list is renumbered from zero rather than nudging one row, so the stored order
    /// can never drift into ties or run out of room between two neighbours.
    public static func sortOrders(count: Int) -> [Int64] {
        count <= 0 ? [] : (0..<count).map(Int64.init)
    }
}
