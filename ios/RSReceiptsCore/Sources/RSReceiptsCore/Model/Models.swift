import Foundation

/// Mirrors the Android entities. Money is `Int64` minor units; dates are UTC epoch
/// **milliseconds**, matching the backup format and the Room schema.
///
/// These are plain values, not persistence types. Whatever the app ends up storing with
/// (SwiftData, SQLite) maps to and from these, so the export and backup code — the part that
/// has to agree with Android byte for byte — never depends on the storage layer.
public struct Report: Hashable, Sendable, Identifiable {
    public let id: String            // UUID
    public var name: String
    public var createdAt: EpochMillis
    public var updatedAt: EpochMillis

    public init(id: String, name: String, createdAt: EpochMillis, updatedAt: EpochMillis) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

public struct Receipt: Hashable, Sendable, Identifiable {
    public let id: String            // UUID
    public var reportId: String
    public var description: String
    public var amountMinor: Int64    // cents
    public var date: EpochMillis     // defaults to capture time
    /// Filename inside the image store, or nil until a photo is attached.
    public var imageFile: String?
    public var createdAt: EpochMillis

    public init(
        id: String,
        reportId: String,
        description: String,
        amountMinor: Int64,
        date: EpochMillis,
        imageFile: String?,
        createdAt: EpochMillis
    ) {
        self.id = id
        self.reportId = reportId
        self.description = description
        self.amountMinor = amountMinor
        self.date = date
        self.imageFile = imageFile
        self.createdAt = createdAt
    }
}

/// A report plus its aggregates. Totals are always computed from the receipt rows and never
/// stored on the report, so they cannot drift out of sync with reality.
public struct ReportSummary: Hashable, Sendable, Identifiable {
    public let id: String
    public var name: String
    public var createdAt: EpochMillis
    public var receiptCount: Int
    public var totalMinor: Int64
    public var firstDate: EpochMillis?
    public var lastDate: EpochMillis?

    public init(
        id: String,
        name: String,
        createdAt: EpochMillis,
        receiptCount: Int,
        totalMinor: Int64,
        firstDate: EpochMillis?,
        lastDate: EpochMillis?
    ) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.receiptCount = receiptCount
        self.totalMinor = totalMinor
        self.firstDate = firstDate
        self.lastDate = lastDate
    }
}

public extension Array where Element == Receipt {
    /// Ordered by date, then insertion order — the ordering the spec fixes, and the one the
    /// Room query applies.
    func inSpecOrder() -> [Receipt] {
        sorted { left, right in
            left.date != right.date ? left.date < right.date : left.createdAt < right.createdAt
        }
    }
}
