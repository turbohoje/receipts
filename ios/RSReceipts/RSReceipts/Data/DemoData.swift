import Foundation
import RSReceiptsCore
import SwiftData

/// Sample data for simulator runs and screenshots, behind a launch argument so it can never
/// appear in an ordinary run.
///
/// Anything user-facing is invented: the Android side's rule is that real receipts never end
/// up in captures, and the same applies here.
enum DemoData {

    static var isRequested: Bool {
        CommandLine.arguments.contains("-seedDemoData")
    }

    static func seed(into context: ModelContext) {
        let existing = try? context.fetch(FetchDescriptor<StoredReport>())
        guard existing?.isEmpty ?? true else { return }

        func day(_ year: Int, _ month: Int, _ dayOfMonth: Int) -> EpochMillis {
            var components = DateComponents()
            components.year = year; components.month = month; components.day = dayOfMonth
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = .current
            return (calendar.date(from: components) ?? Date()).epochMillis
        }

        let reports: [(String, EpochMillis, [(String, Int64, EpochMillis)])] = [
            ("Q3 Client Trip", day(2026, 8, 20), [
                ("Taxi to airport", 2450, day(2026, 8, 21)),
                ("Dinner, drinks", 8100, day(2026, 8, 21)),
                ("Hotel, two nights", 39800, day(2026, 8, 22)),
                ("Cash tip", 500, day(2026, 8, 22)),
                ("Return taxi", 2675, day(2026, 8, 23)),
            ]),
            ("Office Supplies", day(2026, 9, 2), [
                ("Monitor stand", 7999, day(2026, 9, 3)),
                ("Notebooks", 1250, day(2026, 9, 3)),
            ]),
            ("Conference — Lisbon", day(2026, 9, 15), [
                ("Registration", 45000, day(2026, 9, 16)),
                ("Airport transfer", 3120, day(2026, 9, 16)),
                ("Lunch", 1840, day(2026, 9, 17)),
            ]),
        ]

        for (name, createdAt, lines) in reports {
            let report = StoredReport(name: name, createdAt: createdAt, updatedAt: createdAt)
            context.insert(report)
            for (index, line) in lines.enumerated() {
                context.insert(StoredReceipt(
                    description: line.0,
                    amountMinor: line.1,
                    date: line.2,
                    createdAt: createdAt + EpochMillis(index),
                    report: report))
            }
        }
        try? context.save()
    }
}
