import Foundation
import RSReceiptsCore
import SwiftData

/// SwiftData's persistence types, kept separate from the value types in `RSReceiptsCore`.
///
/// The core owns everything the two platforms have to agree on — money, dates, the backup
/// manifest — and knows nothing about storage. These convert at the boundary, which is also
/// why the iOS schema does not have to match Room's: the backup format is JSON, not the
/// database file.
@Model
final class StoredReport {
    #Unique<StoredReport>([\.id])
    var id: String = UUID().uuidString
    var name: String = ""
    var createdAt: EpochMillis = 0
    var updatedAt: EpochMillis = 0

    /// Position in the manually ordered reports list, ascending — smallest at the top.
    ///
    /// Defaulted so adding it is a lightweight SwiftData migration, and so every existing row
    /// holds 0: the list sorts by `sortOrder` then `createdAt` descending, which means a store
    /// full of zeroes looks exactly as it did before dragging existed.
    var sortOrder: Int64 = 0

    /// Whether this report participates in shared mode. Local to this device: a report you
    /// share is one *you* publish, and the flag says nothing about anyone else.
    var isShared: Bool = false

    /// Room gets this from `onDelete = CASCADE`; SwiftData needs it spelled out. Deleting a
    /// report must take its receipts with it — image files are a separate sweep.
    @Relationship(deleteRule: .cascade, inverse: \StoredReceipt.report)
    var receipts: [StoredReceipt] = []

    init(
        id: String = UUID().uuidString,
        name: String,
        createdAt: EpochMillis,
        updatedAt: EpochMillis,
        sortOrder: Int64 = 0,
        isShared: Bool = false
    ) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.sortOrder = sortOrder
        self.isShared = isShared
    }

    /// Ordered by date, then insertion order, per the spec.
    var orderedReceipts: [StoredReceipt] {
        receipts.sorted {
            $0.date != $1.date ? $0.date < $1.date : $0.createdAt < $1.createdAt
        }
    }

    /// Always computed from the rows, never stored, so it cannot drift out of sync.
    var totalMinor: Int64 { receipts.reduce(0) { $0 + $1.amountMinor } }

    var dateRange: ClosedRange<EpochMillis>? {
        guard let first = receipts.map(\.date).min(), let last = receipts.map(\.date).max() else {
            return nil
        }
        return first...last
    }

    var summary: ReportSummary {
        ReportSummary(
            id: id, name: name, createdAt: createdAt,
            receiptCount: receipts.count, totalMinor: totalMinor,
            firstDate: dateRange?.lowerBound, lastDate: dateRange?.upperBound)
    }
}

@Model
final class StoredReceipt {
    #Unique<StoredReceipt>([\.id])
    var id: String = UUID().uuidString
    var receiptDescription: String = ""
    /// Minor units (cents). Never a floating point type.
    var amountMinor: Int64 = 0
    /// Epoch millis, defaults to capture time.
    var date: EpochMillis = 0
    /// Filename inside the image store, or nil until a photo is attached.
    var imageFile: String?
    var createdAt: EpochMillis = 0
    /// Last modification, for shared-mode last-write-wins. Defaults to 0 and is backfilled
    /// from `createdAt` at startup: without it an edited receipt would look as old as the day
    /// it was made, and a remote copy would win every merge, silently discarding local work.
    var updatedAt: EpochMillis = 0
    var report: StoredReport?

    init(
        id: String = UUID().uuidString,
        description: String,
        amountMinor: Int64,
        date: EpochMillis,
        imageFile: String? = nil,
        createdAt: EpochMillis,
        updatedAt: EpochMillis? = nil,
        report: StoredReport? = nil
    ) {
        self.id = id
        self.receiptDescription = description
        self.amountMinor = amountMinor
        self.date = date
        self.imageFile = imageFile
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.report = report
    }
}

// MARK: - Bridging to the shared value types

extension StoredReport {
    var asBackupReport: BackupReport {
        BackupReport(
            id: id, name: name, createdAt: createdAt, updatedAt: updatedAt, sortOrder: sortOrder)
    }
}

extension StoredReceipt {
    var asBackupReceipt: BackupReceipt {
        BackupReceipt(
            id: id,
            reportId: report?.id ?? "",
            description: receiptDescription,
            amountMinor: amountMinor,
            date: date,
            imageFile: imageFile,
            createdAt: createdAt)
    }
}


/// A deleted receipt, remembered so the deletion can propagate.
///
/// Absence has no timestamp: without this, a device that still holds the row would reintroduce
/// it on every merge. Kept as its own model rather than a soft-delete flag on `StoredReceipt`
/// so that no existing query — totals, exports, the orphan sweep — has to learn to exclude
/// anything.
@Model
final class StoredTombstone {
    #Unique<StoredTombstone>([\.receiptId])
    var receiptId: String = ""
    var reportId: String = ""
    var deletedAt: EpochMillis = 0
    var deletedBy: String = ""

    init(receiptId: String, reportId: String, deletedAt: EpochMillis, deletedBy: String) {
        self.receiptId = receiptId
        self.reportId = reportId
        self.deletedAt = deletedAt
        self.deletedBy = deletedBy
    }
}
