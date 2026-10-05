import Foundation

public struct BackupStats: Equatable, Sendable {
    public let reports: Int
    public let receipts: Int
    public let imagesWritten: Int
    public let imagesMissing: Int
}

/// Writes a backup ZIP: `manifest.json`, then one stored entry per referenced image.
///
/// Takes a ``ZipSink`` and a resolver for image files so the whole thing is exercisable
/// without a device — the same shape as Android's `BackupWriter`.
public struct BackupWriter {

    public init() {}

    public func write(
        to sink: ZipSink,
        manifest: BackupManifest,
        modified: Date = Date(),
        resolveImage: (String) -> URL?
    ) throws -> BackupStats {
        let writer = ZipWriter(sink: sink, modified: modified)
        try writer.add(BackupFormat.manifestEntry, try BackupJson.encoder.encode(manifest))

        var written = 0
        var missing = 0
        // Distinct, because two receipts could in principle reference one file.
        var seen = Set<String>()
        for name in manifest.receipts.compactMap(\.imageFile) where seen.insert(name).inserted {
            guard let url = resolveImage(name),
                  let bytes = try? Data(contentsOf: url) else {
                // A row whose image has vanished is still worth backing up; losing the line
                // item as well would turn one problem into two.
                missing += 1
                continue
            }
            try writer.add(BackupFormat.imageEntry(name), bytes)
            written += 1
        }

        try writer.finish()
        return BackupStats(
            reports: manifest.reports.count,
            receipts: manifest.receipts.count,
            imagesWritten: written,
            imagesMissing: missing)
    }
}
