import Foundation

/// Owns `images/` inside the app's documents directory.
///
/// The database deletes rows and knows nothing about files on disk, so file lifecycle is
/// handled here and reconciled by ``sweepOrphans(referenced:)``.
public struct ImageStore: Sendable {

    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    @discardableResult
    public func ensureDirectory() -> URL {
        try? FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// Images are named by their own id, not the receipt's. That way "replace photo" writes a
    /// new file and only deletes the old one after the swap has been committed, instead of
    /// overwriting the single copy in place and losing it if the write fails. It also stops an
    /// image cache from serving the previous photo for a path that was reused.
    ///
    /// Lowercased because these filenames travel between platforms inside a backup, and the
    /// Android side writes `UUID.randomUUID()`, which is lowercase.
    public func newImageFileName() -> String {
        UUID().uuidString.lowercased() + ".jpg"
    }

    public func url(for fileName: String) -> URL {
        ensureDirectory().appendingPathComponent(fileName)
    }

    public func exists(_ fileName: String?) -> Bool {
        guard let fileName else { return false }
        return FileManager.default.fileExists(atPath: url(for: fileName).path)
    }

    @discardableResult
    public func delete(_ fileName: String?) -> Bool {
        guard let fileName, exists(fileName) else { return false }
        return (try? FileManager.default.removeItem(at: url(for: fileName))) != nil
    }

    public func deleteAll(_ fileNames: some Sequence<String>) {
        for name in fileNames { delete(name) }
    }

    /// Deletes any image with no receipt row pointing at it and returns how many went.
    ///
    /// Runs at app start because a delete is two steps (rows, then files) and the process can
    /// die between them. Without this, interrupted deletes would leak disk forever.
    @discardableResult
    public func sweepOrphans(referenced: Set<String>) -> Int {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: ensureDirectory(), includingPropertiesForKeys: nil)) ?? []
        var removed = 0
        for file in contents where !referenced.contains(file.lastPathComponent) {
            if (try? FileManager.default.removeItem(at: file)) != nil { removed += 1 }
        }
        return removed
    }
}
