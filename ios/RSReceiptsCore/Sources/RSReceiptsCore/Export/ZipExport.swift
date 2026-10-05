import Foundation

/// One line item, already resolved and numbered. The `index` is 1-based and is the single
/// source of ordering shared by the CSV rows, the zipped image filenames and the PDF's
/// per-image pages, so a reviewer can tie any image back to the row it belongs to.
public struct ExportRow: Equatable, Sendable {
    public let index: Int
    public let date: EpochMillis
    public let description: String
    public let amountMinor: Int64
    public let image: URL?

    public init(
        index: Int,
        date: EpochMillis,
        description: String,
        amountMinor: Int64,
        image: URL?
    ) {
        self.index = index
        self.date = date
        self.description = description
        self.amountMinor = amountMinor
        self.image = image
    }
}

/// `report.csv` plus the images, numbered to match the CSV row order.
public struct ZipExporter {

    private let timeZone: TimeZone

    public init(timeZone: TimeZone = .current) {
        self.timeZone = timeZone
    }

    public func write(
        to sink: ZipSink,
        rows: [ExportRow],
        currency: Currency,
        modified: Date = Date()
    ) throws {
        let writer = ZipWriter(sink: sink, modified: modified)
        try writer.add("report.csv", Data(buildCsv(rows, currency).utf8))
        for row in rows {
            guard let image = row.image, let bytes = try? Data(contentsOf: image) else { continue }
            try writer.add(imageEntryName(row), bytes)
        }
        try writer.finish()
    }

    public func imageEntryName(_ row: ExportRow) -> String {
        "images/" + String(format: "%02d", row.index) + "-" + slugify(row.description) + ".jpg"
    }

    public func buildCsv(_ rows: [ExportRow], _ currency: Currency) -> String {
        var out = Csv.row("date", "description", "amount", "currency", "image")
        for row in rows {
            out += Csv.row([
                Timestamps.isoDate(row.date, timeZone: timeZone),
                row.description,
                Money.formatPlain(row.amountMinor, currency),
                currency.code,
                row.image != nil ? imageEntryName(row) : "",
            ])
        }
        // Trailing total, so the file is self-checking when opened in a spreadsheet.
        out += Csv.row([
            "",
            "TOTAL",
            Money.formatPlain(rows.reduce(Int64(0)) { $0 + $1.amountMinor }, currency),
            currency.code,
            "",
        ])
        return out
    }

    /// `<report-name-slug>-<yyyyMMdd>.<extension>`
    public func fileName(
        reportName: String,
        extension fileExtension: String,
        date: Date = Date()
    ) -> String {
        let slug = slugify(reportName, fallback: "report")
        return "\(slug)-\(Timestamps.fileStamp(date, timeZone: timeZone)).\(fileExtension)"
    }
}
