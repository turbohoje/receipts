import Foundation

/// The state of one shared report, as it exists in the shared file.
///
/// Deliberately *not* the backup format. A backup is a full-replace snapshot; this is a merge
/// source, so every record carries the metadata a merge needs — when it changed, which device
/// changed it, and whether it was deleted.
///
/// Photos travel **inside** this file, base64-encoded on each receipt. One file is the whole
/// report, which is what lets it be handed over by any means at all — Drive, mail, AirDrop —
/// with nothing to go missing alongside it. The cost is size: base64 inflates a JPEG by about
/// a third, so a twenty-photo report is a file of some megabytes.
public struct SharedState: Codable, Equatable, Sendable {

    /// Versioned separately from the backup format: the two evolve for different reasons.
    public static let currentVersion = 1

    public var sharedVersion: Int
    public var reportId: String
    /// Carried explicitly. Amounts are bare minor units, so without this a shared report reads
    /// as $24.50 to one person and €24.50 to another.
    public var currency: String
    /// `deviceId -> counter`. Lets a device notice the file is missing its own latest writes
    /// and rewrite immediately, rather than waiting for its next natural sync.
    public var versions: [String: Int]
    public var report: SharedReport
    public var receipts: [SharedReceipt]

    public init(
        sharedVersion: Int = SharedState.currentVersion,
        reportId: String,
        currency: String,
        versions: [String: Int],
        report: SharedReport,
        receipts: [SharedReceipt]
    ) {
        self.sharedVersion = sharedVersion
        self.reportId = reportId
        self.currency = currency
        self.versions = versions
        self.report = report
        self.receipts = receipts
    }
}

/// Anything the merge can choose between carries these two fields.
public protocol SharedRecord {
    var updatedAt: EpochMillis { get }
    /// The device that last wrote this record. Also the tie-break when two edits share a
    /// millisecond — compared as a string so both platforms always pick the same winner.
    var updatedBy: String { get }
}

public struct SharedReport: Codable, Equatable, Sendable, SharedRecord {
    public var id: String
    public var name: String
    public var createdAt: EpochMillis
    public var updatedAt: EpochMillis
    public var sortOrder: Int64
    public var updatedBy: String

    public init(
        id: String, name: String, createdAt: EpochMillis, updatedAt: EpochMillis,
        sortOrder: Int64, updatedBy: String
    ) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.sortOrder = sortOrder
        self.updatedBy = updatedBy
    }
}

public struct SharedReceipt: Codable, Equatable, Sendable, SharedRecord {
    public var id: String
    public var reportId: String
    public var description: String
    public var amountMinor: Int64
    public var date: EpochMillis
    public var imageFile: String?
    public var createdAt: EpochMillis
    public var updatedAt: EpochMillis
    public var updatedBy: String
    /// The photo itself, base64 JPEG, so the file is self-contained.
    ///
    /// Optional and defaulted: a file written before photos travelled still decodes, and a
    /// receipt whose image has gone missing locally is still worth sharing.
    public var imageData: String?
    /// A tombstone. Absence has no timestamp, so a delete has to be a record with one, or it
    /// loses every merge and the receipt resurrects forever.
    public var deletedAt: EpochMillis?

    public var isDeleted: Bool { deletedAt != nil }

    public init(
        id: String, reportId: String, description: String, amountMinor: Int64,
        date: EpochMillis, imageFile: String?, createdAt: EpochMillis,
        updatedAt: EpochMillis, updatedBy: String, deletedAt: EpochMillis? = nil,
        imageData: String? = nil
    ) {
        self.id = id
        self.reportId = reportId
        self.description = description
        self.amountMinor = amountMinor
        self.date = date
        self.imageFile = imageFile
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.updatedBy = updatedBy
        self.deletedAt = deletedAt
        self.imageData = imageData
    }

    enum CodingKeys: String, CodingKey {
        case id, reportId, description, amountMinor, date, imageFile
        case createdAt, updatedAt, updatedBy, deletedAt, imageData
    }

    /// Explicit nulls, matching the backup manifest's convention so both files look the same
    /// in a text editor and neither platform's decoder has to guess.
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(reportId, forKey: .reportId)
        try c.encode(description, forKey: .description)
        try c.encode(amountMinor, forKey: .amountMinor)
        try c.encode(date, forKey: .date)
        try c.encode(imageFile, forKey: .imageFile)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encode(updatedAt, forKey: .updatedAt)
        try c.encode(updatedBy, forKey: .updatedBy)
        try c.encode(deletedAt, forKey: .deletedAt)
        // Omitted when absent rather than written as null: this one is bulky, and an explicit
        // null per receipt is noise in a file people are expected to be able to read.
        try c.encodeIfPresent(imageData, forKey: .imageData)
    }
}
