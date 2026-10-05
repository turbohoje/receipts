import Foundation

/// Why a restore was refused, so the UI can say something specific.
public enum RestoreProblem: Equatable, Sendable {
    case tooNew(found: Int, supported: Int)
    case noManifest
    case unreadable(String)
}

public enum RestoreOutcome: Equatable, Sendable {
    /// `imageNames` are the image entries present in the archive, by bare filename.
    case ready(manifest: BackupManifest, imageNames: Set<String>)
    case refused(RestoreProblem)
}

/// Reads a backup ZIP.
///
/// The manifest is validated before any image is written, so a refused restore leaves nothing
/// behind. Unlike the Android reader this works through the central directory rather than
/// streaming, which is what lets it read the archives Android itself writes — see ``ZipFormat``.
public struct BackupReader {

    public init() {}

    public func peek(_ source: ZipSource) -> RestoreOutcome {
        let archive: ZipArchive
        do {
            archive = try ZipArchive(source: source)
        } catch {
            return .refused(.unreadable(describe(error)))
        }

        guard let entry = archive.entry(named: BackupFormat.manifestEntry) else {
            return .refused(.noManifest)
        }

        let manifest: BackupManifest
        do {
            manifest = try BackupJson.decoder.decode(
                BackupManifest.self, from: try archive.data(for: entry))
        } catch {
            return .refused(.unreadable(describe(error)))
        }

        if manifest.schemaVersion > BackupFormat.schemaVersion {
            return .refused(.tooNew(
                found: manifest.schemaVersion, supported: BackupFormat.schemaVersion))
        }

        let names = archive.entries(withPrefix: BackupFormat.imagePrefix)
            .compactMap(\.safeFileName)
        return .ready(manifest: manifest, imageNames: Set(names))
    }

    /// Extracts the archive's images into `directory`, returning how many landed.
    ///
    /// Entry names are taken as bare filenames: a ZIP is untrusted input, and an entry called
    /// `images/../../databases/receipts.db` must not be able to write outside the target.
    /// One image is held in memory at a time.
    @discardableResult
    public func extractImages(_ source: ZipSource, to directory: URL) throws -> Int {
        let archive = try ZipArchive(source: source)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true)

        var count = 0
        for entry in archive.entries(withPrefix: BackupFormat.imagePrefix) {
            guard let safeName = entry.safeFileName else { continue }
            let bytes = try archive.data(for: entry)
            try bytes.write(to: directory.appendingPathComponent(safeName), options: .atomic)
            count += 1
        }
        return count
    }

    private func describe(_ error: any Error) -> String {
        (error as? ZipError)?.description ?? error.localizedDescription
    }
}
