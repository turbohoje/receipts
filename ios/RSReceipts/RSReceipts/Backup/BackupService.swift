import Foundation
import RSReceiptsCore
import SwiftData

/// Turns the SwiftData store into a backup archive and back again, on top of the core's
/// `BackupWriter`/`BackupReader` — the same format Android reads and writes.
@MainActor
struct BackupService {

    let container: AppContainer
    let context: ModelContext

    struct RestoreSummary {
        let reports: Int
        let receipts: Int
        let imagesRestored: Int
        let imagesMissing: Int
    }

    enum Failure: LocalizedError {
        case build(String)
        case refused(RestoreProblem)

        var errorDescription: String? {
            switch self {
            case .build(let detail):
                return detail
            case .refused(.tooNew(let found, let supported)):
                return "This backup was written by a newer version of the app "
                    + "(format \(found); this build understands \(supported)). Update the app "
                    + "and try again."
            case .refused(.noManifest):
                return "That file is not an RS Receipts backup — it has no manifest."
            case .refused(.unreadable(let detail)):
                return "That backup could not be read: \(detail)"
            }
        }
    }

    // MARK: - Backing up

    func suggestedFileName() -> String { BackupFormat.fileName() }

    /// Builds the archive in memory. A backup is the manifest plus one stored JPEG per image,
    /// so this is roughly the size of the photos themselves.
    func makeArchive() throws -> Data {
        let reports = (try? context.fetch(FetchDescriptor<StoredReport>())) ?? []
        let receipts = (try? context.fetch(FetchDescriptor<StoredReceipt>())) ?? []

        let manifest = BackupManifest(
            appVersion: Bundle.main.shortVersion,
            createdAt: Clock.now(),
            currency: Currency.forLocale().code,
            reports: reports.map(\.asBackupReport),
            receipts: receipts.map(\.asBackupReceipt))

        let sink = DataZipSink()
        let store = container.imageStore
        do {
            _ = try BackupWriter().write(to: sink, manifest: manifest) { name in
                store.exists(name) ? store.url(for: name) : nil
            }
        } catch {
            throw Failure.build("The backup could not be built: \(error)")
        }
        return sink.data
    }

    // MARK: - Restoring

    /// Reads a backup without changing anything, so the confirmation can say what it holds.
    func inspect(_ url: URL) throws -> BackupManifest {
        let data = try readSecurityScoped(url)
        switch BackupReader().peek(data) {
        case .ready(let manifest, _):
            return manifest
        case .refused(let problem):
            throw Failure.refused(problem)
        }
    }

    /// Replaces **all** local data, then the images.
    ///
    /// That order is deliberate and matches Android: rows first, images second. The reverse
    /// would delete images still owned by live rows if the process died in between. If it dies
    /// here instead, rows point at images that are not there yet, which renders as the
    /// missing-image placeholder and is fixed by restoring again.
    @discardableResult
    func restore(from url: URL) throws -> RestoreSummary {
        let data = try readSecurityScoped(url)
        let manifest: BackupManifest
        let imageNames: Set<String>
        switch BackupReader().peek(data) {
        case .ready(let found, let names):
            manifest = found
            imageNames = names
        case .refused(let problem):
            throw Failure.refused(problem)
        }

        // Rows.
        try? context.delete(model: StoredReceipt.self)
        try? context.delete(model: StoredReport.self)

        var reportsById: [String: StoredReport] = [:]
        for report in manifest.reports {
            let stored = StoredReport(
                id: report.id, name: report.name,
                createdAt: report.createdAt, updatedAt: report.updatedAt,
                sortOrder: report.sortOrder)
            context.insert(stored)
            reportsById[report.id] = stored
        }
        for receipt in manifest.receipts {
            let stored = StoredReceipt(
                id: receipt.id,
                description: receipt.description,
                amountMinor: receipt.amountMinor,
                date: receipt.date,
                imageFile: receipt.imageFile,
                createdAt: receipt.createdAt,
                report: reportsById[receipt.reportId])
            context.insert(stored)
        }
        try context.save()

        // Images. Everything already in the store belongs to the data just replaced.
        let store = container.imageStore
        store.sweepOrphans(referenced: [])
        let restored = (try? BackupReader().extractImages(data, to: store.ensureDirectory())) ?? 0

        let referenced = Set(manifest.receipts.compactMap(\.imageFile))
        return RestoreSummary(
            reports: manifest.reports.count,
            receipts: manifest.receipts.count,
            imagesRestored: restored,
            imagesMissing: referenced.subtracting(imageNames).count)
    }

    /// A URL from the document picker is security-scoped and has to be opened explicitly.
    private func readSecurityScoped(_ url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            return try Data(contentsOf: url)
        } catch {
            throw Failure.build("That file could not be opened: \(error.localizedDescription)")
        }
    }
}

extension Bundle {
    var shortVersion: String {
        (object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }
}
