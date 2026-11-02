import Foundation

/// Remembers which file each shared report belongs to, across launches.
///
/// The Android counterpart stores a SAF URI and takes a persistable grant. iOS has no such
/// thing: a URL from the document picker is security-scoped and dies with the process, so the
/// durable form is a **bookmark**, resolved back to a URL on each use.
///
/// Deliberately not in the database — a bookmark is meaningless on any other device, so
/// putting it on the report row would carry junk into every backup and every shared file.
struct SharedFileLinks {

    private let defaults = UserDefaults.standard
    private let prefix = "cc.rocketscience.receipts.sharedFile."
    private let indexKey = "cc.rocketscience.receipts.sharedFileIndex"

    private func key(_ reportId: String) -> String { prefix + reportId }

    /// Every report this device has a file for — what "check on open" iterates.
    func linkedReportIds() -> [String] {
        defaults.stringArray(forKey: indexKey) ?? []
    }

    /// Resolves the bookmark back to a usable URL.
    ///
    /// A bookmark can go stale when the file moves; the resolved URL is re-bookmarked so the
    /// next launch does not have to rediscover it.
    func url(for reportId: String) -> URL? {
        guard let data = defaults.data(forKey: key(reportId)) else { return nil }
        var stale = false
        guard let resolved = try? URL(
            resolvingBookmarkData: data,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &stale)
        else { return nil }
        if stale, let refreshed = try? resolved.bookmarkData() {
            defaults.set(refreshed, forKey: key(reportId))
        }
        return resolved
    }

    @discardableResult
    func link(reportId: String, url: URL) -> Bool {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? url.bookmarkData() else { return false }
        defaults.set(data, forKey: key(reportId))
        var index = linkedReportIds()
        if !index.contains(reportId) {
            index.append(reportId)
            defaults.set(index, forKey: indexKey)
        }
        return true
    }

    func unlink(reportId: String) {
        defaults.removeObject(forKey: key(reportId))
        defaults.set(linkedReportIds().filter { $0 != reportId }, forKey: indexKey)
    }

    func displayName(reportId: String) -> String? {
        url(for: reportId)?.lastPathComponent
    }

    /// The whole location, for showing when someone asks where a report actually goes.
    func fullLocation(reportId: String) -> String? {
        url(for: reportId)?.path.removingPercentEncoding ?? url(for: reportId)?.path
    }

    func isReachable(reportId: String) -> Bool {
        if case .contents = read(reportId) { return true }
        if case .absent = read(reportId) { return url(for: reportId) == nil ? false : true }
        return false
    }

    /// Every access has to be bracketed: a security-scoped URL is not readable otherwise, and
    /// failing to stop leaks the grant.
    private func withAccess<T>(_ reportId: String, _ body: (URL) throws -> T) -> T? {
        guard let url = url(for: reportId) else { return nil }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try? body(url)
    }

    /// What came back from the shared file, distinguishing "empty" from "could not look".
    ///
    /// Kept apart deliberately: a read that *failed* must never be mistaken for an empty
    /// file, or a sync would overwrite a collaborator's work with local state.
    enum Read {
        case contents(Data)
        case absent
        case failed(String)
    }

    func read(_ reportId: String) -> Read {
        guard let url = url(for: reportId) else { return .absent }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        // Coordinated, and that is the whole point: a shared file lives behind a File
        // Provider — Google Drive's, here — and a plain `Data(contentsOf:)` returns whatever
        // copy the provider last left on disk. That read is silently stale, so a collaborator's
        // newer receipts simply never appear. Coordinating makes the provider fetch the
        // current version first.
        var coordinationError: NSError?
        var result: Read = .failed("the file could not be read")
        NSFileCoordinator().coordinate(
            readingItemAt: url, options: [.withoutChanges], error: &coordinationError
        ) { target in
            do {
                let data = try Data(contentsOf: target)
                result = data.isEmpty ? .absent : .contents(data)
            } catch {
                result = .failed(error.localizedDescription)
            }
        }
        if let coordinationError {
            return .failed(coordinationError.localizedDescription)
        }
        return result
    }

    /// Overwrites the bound file in place.
    ///
    /// Coordinated, because the file may live in iCloud Drive or a Drive provider that is
    /// syncing it underneath us.
    @discardableResult
    func write(_ reportId: String, _ data: Data) -> Bool {
        let result: Bool? = withAccess(reportId) { url in
            var coordinationError: NSError?
            var wrote = false
            NSFileCoordinator().coordinate(
                writingItemAt: url, options: .forReplacing, error: &coordinationError
            ) { target in
                wrote = (try? data.write(to: target, options: .atomic)) != nil
            }
            return coordinationError == nil && wrote
        }
        return result ?? false
    }
}
