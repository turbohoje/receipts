import Foundation
import RSReceiptsCore
import SwiftData
import UIKit

/// Sample data for simulator runs and screenshots, behind a launch argument so it can never
/// appear in an ordinary run.
///
/// Anything user-facing is invented: the Android side's rule is that real receipts never end
/// up in captures, and the same applies here.
enum DemoData {

    static var isRequested: Bool {
        CommandLine.arguments.contains("-seedDemoData")
    }

    static func seed(into context: ModelContext, pipeline: ImagePipeline? = nil) {
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
                ("Airport parking", 2400, day(2026, 8, 22)),
                ("Cash tip", 500, day(2026, 8, 22)),
                ("Return taxi", 2675, day(2026, 8, 23)),
            ]),
            ("Office Supplies", day(2026, 9, 2), [
                ("Monitor stand", 7999, day(2026, 9, 3)),
                ("Notebooks", 1250, day(2026, 9, 3)),
                ("Desk lamp", 3450, day(2026, 9, 4)),
                ("Cable tidy", 899, day(2026, 9, 5)),
            ]),
            ("Conference — Lisbon", day(2026, 9, 15), [
                ("Registration", 45000, day(2026, 9, 16)),
                ("Airport transfer", 3120, day(2026, 9, 16)),
                ("Lunch, day one", 2080, day(2026, 9, 17)),
                ("Coffee, day two", 1280, day(2026, 9, 17)),
            ]),
            ("Vehicle — October", day(2026, 10, 1), [
                ("Fuel", 6240, day(2026, 10, 2)),
                ("Toll", 870, day(2026, 10, 2)),
            ]),
            ("Home Office", day(2026, 10, 12), [
                ("Broadband, October", 4500, day(2026, 10, 12)),
            ]),
        ]

        // Which receipts get a picture, and which invented receipt each one gets. Left
        // deliberately partial: a real report is a mix of photographed and typed receipts.
        let pictures: [String: Int] = [
            "Dinner, drinks": 0,
            "Return taxi": 1,
            "Hotel, two nights": 3,
            "Airport parking": 5,
            "Monitor stand": 2,
            "Desk lamp": 5,
            "Lunch, day one": 4,
        ]

        for (reportIndex, (name, createdAt, lines)) in reports.enumerated() {
            let report = StoredReport(name: name, createdAt: createdAt, updatedAt: createdAt)
            // The middle report stands in for a report shared with someone else, so the
            // sharing and sync screens have something real to render.
            report.isShared = reportIndex == 1
            context.insert(report)
            for (index, line) in lines.enumerated() {
                let receipt = StoredReceipt(
                    description: line.0,
                    amountMinor: line.1,
                    date: line.2,
                    createdAt: createdAt + EpochMillis(index),
                    report: report)
                if let pipeline, let which = pictures[line.0] {
                    let image = DemoReceiptImage.render(
                        DemoReceiptImage.specs[which], tilt: [-1.5, 1.2, -0.8, 2.0, 1.6, -2.1][which])
                    receipt.imageFile = pipeline.writeCropped(
                        image, crop: .full, discarding: nil)
                }
                context.insert(receipt)
            }
        }
        try? context.save()
    }
}
