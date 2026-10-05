import Foundation
import RSReceiptsCore

/// Ported from Android's `CsvTest`, `SlugifyTest`, `ExporterNamingTest` and `ZipExporterTest`.
func exportChecks(_ harness: Harness) {
    harness.section("CSV")

    harness.check("plain fields are not quoted") {
        try expect(Csv.field("Taxi"), "Taxi")
        try expect(Csv.field("24.50"), "24.50")
        try expect(Csv.field(""), "")
    }

    harness.check("commas force quoting") {
        try expect(Csv.field("Dinner, drinks"), "\"Dinner, drinks\"")
    }

    harness.check("quotes are doubled") {
        try expect(Csv.field("He said \"cheap\""), "\"He said \"\"cheap\"\"\"")
    }

    harness.check("newlines force quoting") {
        try expect(Csv.field("line one\nline two"), "\"line one\nline two\"")
        try expect(Csv.field("a\rb"), "\"a\rb\"")
    }

    harness.check("leading and trailing whitespace is preserved by quoting") {
        try expect(Csv.field(" padded "), "\" padded \"")
    }

    harness.check("rows end with CRLF as the spec requires") {
        try expect(Csv.row("a", "b", "c"), "a,b,c\r\n")
    }

    harness.check("a row mixes quoted and unquoted fields") {
        try expect(
            Csv.row("2026-08-23", "Dinner, drinks", "81.00", "USD"),
            "2026-08-23,\"Dinner, drinks\",81.00,USD\r\n")
    }

    harness.section("Slug")

    harness.check("spaces and punctuation collapse to single dashes") {
        try expect(slugify("Q3 Client Trip"), "q3-client-trip")
        try expect(slugify("Dinner, drinks"), "dinner-drinks")
    }

    harness.check("path separators cannot survive") {
        try expect(slugify("../../etc/passwd"), "etc-passwd")
        try expect(slugify("a/b"), "a-b")
        try expect(slugify("../.."), "receipt")
    }

    harness.check("leading and trailing separators are trimmed") {
        try expect(slugify("  --Trip--  "), "trip")
    }

    harness.check("empty and symbol-only names fall back") {
        try expect(slugify(""), "receipt")
        try expect(slugify("!!!"), "receipt")
        try expect(slugify("", fallback: "report"), "report")
        try expect(slugify("///", fallback: "report"), "report")
    }

    harness.check("long names are truncated without a trailing dash") {
        try expect(slugify(String(repeating: "a", count: 80)).count, 40)
        let truncated = slugify(
            "abcdefghij klmnopqrst uvwxyzabcd efghijklmn opqrstuvwx", max: 33)
        try expectTrue(!truncated.hasSuffix("-"), "got \(truncated)")
    }

    harness.section("Export")

    let utc = TimeZone(identifier: "UTC")!
    let exporter = ZipExporter(timeZone: utc)
    let usd = Currency.code("USD")

    func day(_ year: Int, _ month: Int, _ day: Int) -> EpochMillis {
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(from: components)!.epochMillis
    }

    let rows = [
        ExportRow(index: 1, date: day(2026, 8, 21), description: "Taxi to airport",
                  amountMinor: 2450, image: URL(fileURLWithPath: "/tmp/a.jpg")),
        ExportRow(index: 2, date: day(2026, 8, 22), description: "Dinner, drinks",
                  amountMinor: 8100, image: URL(fileURLWithPath: "/tmp/b.jpg")),
        ExportRow(index: 3, date: day(2026, 8, 23), description: "Cash tip",
                  amountMinor: 500, image: nil),
    ]

    harness.check("csv has a header, a row each and a total") {
        let lines = exporter.buildCsv(rows, usd)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: "\r\n")
        try expect(lines.first, "date,description,amount,currency,image")
        try expect(lines.count, 5)
        try expectTrue(
            lines[1].hasPrefix("2026-08-21,Taxi to airport,24.50,USD,"), "got \(lines[1])")
        try expect(lines.last, ",TOTAL,110.50,USD,")
    }

    harness.check("a description containing a comma stays one column") {
        let dinner = exporter.buildCsv(rows, usd)
            .components(separatedBy: "\r\n").first { $0.contains("Dinner") }!
        try expectTrue(dinner.contains("\"Dinner, drinks\""), "got \(dinner)")
    }

    harness.check("a receipt with no image gets an empty image column") {
        let tip = exporter.buildCsv(rows, usd)
            .components(separatedBy: "\r\n").first { $0.contains("Cash tip") }!
        try expectTrue(tip.hasSuffix("USD,"), "got \(tip)")
    }

    harness.check("image names are numbered to match the csv order") {
        try expect(
            rows.filter { $0.image != nil }.map(exporter.imageEntryName),
            ["images/01-taxi-to-airport.jpg", "images/02-dinner-drinks.jpg"])
    }

    harness.check("export filenames are slugged and dated") {
        var components = DateComponents()
        components.year = 2026; components.month = 8; components.day = 23
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        let when = calendar.date(from: components)!
        try expect(
            exporter.fileName(reportName: "Q3 Client Trip", extension: "pdf", date: when),
            "q3-client-trip-20260823.pdf")
        try expect(
            exporter.fileName(reportName: "///", extension: "zip", date: when),
            "report-20260823.zip")
    }

    harness.check("a non-Gregorian device locale cannot leak into a filename") {
        // The trap DateTimeFormatter avoids for free on Android: a Buddhist-calendar locale
        // would otherwise stamp the year 2569.
        var components = DateComponents()
        components.year = 2026; components.month = 8; components.day = 23
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        let when = calendar.date(from: components)!
        try expect(Timestamps.fileStamp(when, timeZone: utc), "20260823")
    }
}
