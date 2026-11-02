import PDFKit
import RSReceiptsCore
import UIKit

/// The PDF an expense report becomes: a summary table, then one page per receipt image.
///
/// Page geometry and pagination live in `PdfLayout` in the core, pinned by a check on each
/// platform, so a report exported from either phone breaks across pages at the same rows.
/// Only the drawing is here.
///
/// The per-image pages carry a caption keyed to the summary row number, which is what lets a
/// reviewer tie an image back to the line it belongs to.
struct PdfExporter {

    let timeZone: TimeZone
    let imageStore: ImageStore

    private var pageRect: CGRect {
        CGRect(x: 0, y: 0, width: PdfLayout.pageWidth, height: PdfLayout.pageHeight)
    }

    private let titleFont = UIFont.boldSystemFont(ofSize: 16)
    private let headerFont = UIFont.boldSystemFont(ofSize: 9)
    private let bodyFont = UIFont.systemFont(ofSize: 9)
    private let captionFont = UIFont.systemFont(ofSize: 8)
    private let footerFont = UIFont.systemFont(ofSize: 7)

    func build(reportName: String, rows: [ExportRow], currency: Currency) -> Data {
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        let pages = PdfLayout.paginate(rowCount: rows.count)

        return renderer.pdfData { context in
            for (index, range) in pages.enumerated() {
                context.beginPage()
                drawSummaryPage(
                    reportName: reportName,
                    rows: rows,
                    range: range,
                    currency: currency,
                    isFirst: index == 0,
                    pageNumber: index + 1,
                    pageCount: pages.count + rows.filter { $0.image != nil }.count)
            }
            var imagePage = pages.count
            for row in rows where row.image != nil {
                imagePage += 1
                context.beginPage()
                drawImagePage(
                    row: row, currency: currency,
                    pageNumber: imagePage,
                    pageCount: pages.count + rows.filter { $0.image != nil }.count)
            }
        }
    }

    // MARK: - Pages

    private func drawSummaryPage(
        reportName: String,
        rows: [ExportRow],
        range: Range<Int>,
        currency: Currency,
        isFirst: Bool,
        pageNumber: Int,
        pageCount: Int
    ) {
        let left = PdfLayout.margin
        let right = PdfLayout.pageWidth - PdfLayout.margin
        var y = PdfLayout.margin

        if isFirst {
            draw(reportName, at: CGPoint(x: left, y: y), font: titleFont)
            y += PdfLayout.titleHeight
            let total = rows.reduce(Int64(0)) { $0 + $1.amountMinor }
            let count = rows.count == 1 ? "1 receipt" : "\(rows.count) receipts"
            draw("\(count) · \(Money.formatPlain(total, currency)) \(currency.code)",
                 at: CGPoint(x: left, y: y), font: bodyFont)
            y += PdfLayout.subtitleHeight
        } else {
            draw("\(reportName) (continued)", at: CGPoint(x: left, y: y), font: headerFont)
            y += PdfLayout.titleHeight
        }

        draw("#", at: CGPoint(x: left, y: y), font: headerFont)
        draw("Date", at: CGPoint(x: left + 22, y: y), font: headerFont)
        draw("Description", at: CGPoint(x: left + 110, y: y), font: headerFont)
        drawRightAligned("Amount", rightEdge: right, y: y, font: headerFont)
        y += PdfLayout.tableHeaderHeight

        for index in range {
            let row = rows[index]
            draw("\(row.index)", at: CGPoint(x: left, y: y), font: bodyFont)
            draw(Timestamps.isoDate(row.date, timeZone: timeZone),
                 at: CGPoint(x: left + 22, y: y), font: bodyFont)
            draw(truncate(row.description, toWidth: right - left - 190, font: bodyFont),
                 at: CGPoint(x: left + 110, y: y), font: bodyFont)
            drawRightAligned(Money.formatPlain(row.amountMinor, currency),
                             rightEdge: right, y: y, font: bodyFont)
            y += PdfLayout.rowHeight
        }

        if isFirst {
            let total = rows.reduce(Int64(0)) { $0 + $1.amountMinor }
            y += 6
            draw("TOTAL", at: CGPoint(x: left + 110, y: y), font: headerFont)
            drawRightAligned("\(Money.formatPlain(total, currency)) \(currency.code)",
                             rightEdge: right, y: y, font: headerFont)
        }

        drawFooter(pageNumber: pageNumber, pageCount: pageCount)
    }

    private func drawImagePage(row: ExportRow, currency: Currency, pageNumber: Int, pageCount: Int) {
        let left = PdfLayout.margin
        let right = PdfLayout.pageWidth - PdfLayout.margin
        var y = PdfLayout.margin

        let caption = "#\(row.index) · \(Timestamps.isoDate(row.date, timeZone: timeZone))"
            + " · \(row.description) · \(Money.formatPlain(row.amountMinor, currency))"
        draw(caption, at: CGPoint(x: left, y: y), font: captionFont)
        y += PdfLayout.subtitleHeight

        guard let url = row.image,
              let image = UIImage(contentsOfFile: url.path) else {
            draw("(image missing)", at: CGPoint(x: left, y: y), font: bodyFont)
            drawFooter(pageNumber: pageNumber, pageCount: pageCount)
            return
        }

        // Fit, never fill: a receipt cropped to the page would lose the total at the bottom,
        // which is the one line that matters.
        let available = CGSize(
            width: right - left,
            height: PdfLayout.pageHeight - y - PdfLayout.margin - PdfLayout.footerHeight)
        let scale = min(available.width / image.size.width, available.height / image.size.height)
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        image.draw(in: CGRect(
            x: left + (available.width - size.width) / 2, y: y,
            width: size.width, height: size.height))

        drawFooter(pageNumber: pageNumber, pageCount: pageCount)
    }

    // MARK: - Text

    private func draw(_ text: String, at point: CGPoint, font: UIFont) {
        (text as NSString).draw(at: point, withAttributes: [.font: font])
    }

    private func drawRightAligned(_ text: String, rightEdge: Double, y: Double, font: UIFont) {
        let width = (text as NSString).size(withAttributes: [.font: font]).width
        draw(text, at: CGPoint(x: rightEdge - width, y: y), font: font)
    }

    /// Long descriptions must not run under the amount column.
    private func truncate(_ text: String, toWidth width: Double, font: UIFont) -> String {
        var candidate = text
        while (candidate as NSString).size(withAttributes: [.font: font]).width > width,
              candidate.count > 1 {
            candidate = String(candidate.dropLast())
        }
        return candidate == text ? text : candidate.dropLast() + "…"
    }

    private func drawFooter(pageNumber: Int, pageCount: Int) {
        let text = "Page \(pageNumber) of \(pageCount)"
        drawRightAligned(
            text,
            rightEdge: PdfLayout.pageWidth - PdfLayout.margin,
            y: PdfLayout.pageHeight - PdfLayout.margin,
            font: footerFont)
    }
}
