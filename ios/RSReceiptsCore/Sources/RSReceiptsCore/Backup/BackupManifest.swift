import Foundation

/// Full database dump. Field names are part of the cross-platform contract — they have to
/// match Android's `@SerialName`s exactly.
public struct BackupManifest: Codable, Equatable, Sendable {
    public var schemaVersion: Int
    public var appVersion: String
    public var createdAt: EpochMillis
    public var currency: String
    public var reports: [BackupReport]
    public var receipts: [BackupReceipt]

    public init(
        schemaVersion: Int = BackupFormat.schemaVersion,
        appVersion: String,
        createdAt: EpochMillis,
        currency: String,
        reports: [BackupReport],
        receipts: [BackupReceipt]
    ) {
        self.schemaVersion = schemaVersion
        self.appVersion = appVersion
        self.createdAt = createdAt
        self.currency = currency
        self.reports = reports
        self.receipts = receipts
    }
}

public struct BackupReport: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var createdAt: EpochMillis
    public var updatedAt: EpochMillis
    /// Position in the manually ordered reports list, ascending. Defaulted so a version-1
    /// backup — which has no such field — still decodes, and then sorts by `createdAt` as it
    /// always did.
    public var sortOrder: Int64

    public init(
        id: String,
        name: String,
        createdAt: EpochMillis,
        updatedAt: EpochMillis,
        sortOrder: Int64 = 0
    ) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.sortOrder = sortOrder
    }

    enum CodingKeys: String, CodingKey {
        case id, name, createdAt, updatedAt, sortOrder
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        createdAt = try c.decode(EpochMillis.self, forKey: .createdAt)
        updatedAt = try c.decode(EpochMillis.self, forKey: .updatedAt)
        // Absent in a version-1 backup.
        sortOrder = try c.decodeIfPresent(Int64.self, forKey: .sortOrder) ?? 0
    }
}

public struct BackupReceipt: Codable, Equatable, Sendable {
    public var id: String
    public var reportId: String
    public var description: String
    public var amountMinor: Int64
    public var date: EpochMillis
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

    enum CodingKeys: String, CodingKey {
        case id, reportId, description, amountMinor, date, imageFile, createdAt
    }

    /// Hand-written so a nil `imageFile` is written as an explicit `null` rather than dropped.
    ///
    /// Swift's synthesized encoder omits nil; Android's writer sets `encodeDefaults = true`
    /// and emits `"imageFile": null`. Matching it keeps the two platforms' manifests the same
    /// shape, which is what makes a diff of two backups meaningful. Decoding accepts either.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(reportId, forKey: .reportId)
        try container.encode(description, forKey: .description)
        try container.encode(amountMinor, forKey: .amountMinor)
        try container.encode(date, forKey: .date)
        try container.encode(imageFile, forKey: .imageFile)
        try container.encode(createdAt, forKey: .createdAt)
    }
}

public enum BackupJson {
    public static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        // Pretty-printed like Android's, so a backup stays readable in a text editor. Key
        // order and indent width differ between the platforms and neither matters: the file
        // is parsed, never compared byte for byte.
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }

    /// Lenient on read so a backup from a newer minor version still parses — the
    /// `schemaVersion` check is what refuses a genuinely incompatible file.
    public static var decoder: JSONDecoder { JSONDecoder() }
}
