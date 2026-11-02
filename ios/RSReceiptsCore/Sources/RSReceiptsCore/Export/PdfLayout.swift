import Foundation

/// Page geometry and pagination for the PDF, kept free of platform types so the arithmetic —
/// the part that silently drops rows off the bottom of a page when it is wrong — is testable.
///
/// A4 at 72 points per inch. Must stay identical to Android's `PdfLayout`, so a report
/// exported from either phone paginates the same way.
public enum PdfLayout {

    public static let pageWidth = 595.0
    public static let pageHeight = 842.0
    public static let margin = 42.0

    public static let titleHeight = 30.0
    public static let subtitleHeight = 22.0
    public static let tableHeaderHeight = 24.0
    public static let rowHeight = 20.0
    public static let totalRowHeight = 34.0
    public static let footerHeight = 18.0

    /// Vertical space available for table rows, which is smaller on page 1 (title + totals).
    public static func rowSpace(firstPage: Bool) -> Double {
        let usable = pageHeight - 2 * margin - footerHeight - tableHeaderHeight
        return firstPage
            ? usable - titleHeight - subtitleHeight - totalRowHeight
            : usable
    }

    public static func rowsPerPage(firstPage: Bool) -> Int {
        max(1, Int(rowSpace(firstPage: firstPage) / rowHeight))
    }

    /// Splits `rowCount` rows across summary pages. Always returns at least one page, so an
    /// empty report still produces a document with its header and a zero total.
    public static func paginate(rowCount: Int) -> [Range<Int>] {
        guard rowCount > 0 else { return [0..<0] }
        var pages: [Range<Int>] = []
        var start = 0
        var first = true
        while start < rowCount {
            let end = min(start + rowsPerPage(firstPage: first), rowCount)
            pages.append(start..<end)
            start = end
            first = false
        }
        return pages
    }
}
