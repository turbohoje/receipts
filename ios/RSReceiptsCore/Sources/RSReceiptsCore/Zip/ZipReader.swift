import Foundation

/// Random access to an archive's bytes. A file-backed source is what lets restore read one
/// image at a time instead of holding the whole backup in memory.
public protocol ZipSource {
    var byteCount: Int { get }
    func read(at offset: Int, count: Int) throws -> Data
}

extension Data: ZipSource {
    public var byteCount: Int { count }

    public func read(at offset: Int, count requested: Int) throws -> Data {
        guard offset >= 0, requested >= 0, offset + requested <= self.count else {
            throw ZipError.truncated
        }
        let start = index(startIndex, offsetBy: offset)
        return Data(self[start..<index(start, offsetBy: requested)])
    }
}

public final class FileZipSource: ZipSource {
    private let handle: FileHandle
    public let byteCount: Int

    public init(url: URL) throws {
        handle = try FileHandle(forReadingFrom: url)
        let size = try FileManager.default.attributesOfItem(atPath: url.path)[.size]
        byteCount = (size as? Int) ?? 0
    }

    public func read(at offset: Int, count: Int) throws -> Data {
        guard offset >= 0, count >= 0, offset + count <= byteCount else { throw ZipError.truncated }
        try handle.seek(toOffset: UInt64(offset))
        guard let data = try handle.read(upToCount: count), data.count == count else {
            throw ZipError.truncated
        }
        return data
    }

    public func close() throws { try handle.close() }
}

/// Reads a ZIP through its **central directory**, which is the only correct way to read the
/// archives Android writes: `ZipOutputStream` emits deflated entries with a data descriptor,
/// leaving the local header's CRC and both sizes zero. See ``ZipFormat``.
public struct ZipArchive {

    public struct Entry: Sendable, Equatable {
        public let name: String
        public let method: UInt16
        public let crc: UInt32
        public let compressedSize: Int
        public let uncompressedSize: Int
        let localHeaderOffset: Int

        /// The bare filename, which is what an extractor may use.
        public var safeFileName: String? { ZipArchive.safeFileName(in: name) }

        public var isDirectory: Bool { name.hasSuffix("/") }
    }

    public let entries: [Entry]
    private let source: ZipSource

    public init(source: ZipSource) throws {
        self.source = source
        self.entries = try Self.readCentralDirectory(source)
    }

    /// Reduces an entry name to a bare filename, or nil if there isn't a usable one.
    ///
    /// A ZIP is untrusted input: an entry called `images/../../databases/receipts.db` must not
    /// be able to write outside the directory being extracted into.
    public static func safeFileName(in entryName: String) -> String? {
        // A trailing slash marks a directory entry, and `lastPathComponent` would quietly
        // strip it and hand back a plausible-looking filename ("images/" -> "images").
        guard !entryName.hasSuffix("/") else { return nil }
        let base = (entryName as NSString).lastPathComponent
        guard !base.isEmpty, base != ".", base != "..", !base.contains("/") else { return nil }
        return base
    }

    public func entry(named name: String) -> Entry? {
        entries.first { $0.name == name }
    }

    public func entries(withPrefix prefix: String) -> [Entry] {
        entries.filter { $0.name.hasPrefix(prefix) && !$0.isDirectory }
    }

    /// Reads, inflates and CRC-checks one entry.
    public func data(for entry: Entry) throws -> Data {
        let header = try source.read(at: entry.localHeaderOffset, count: 30)
        guard header.readUInt32(at: 0) == ZipFormat.localHeaderSignature else {
            throw ZipError.corruptEntry("\(entry.name): bad local header")
        }
        // Taken from the local header, which is authoritative for where the data starts;
        // sizes come from the central directory, which is authoritative for how long it is.
        let nameLength = Int(header.readUInt16(at: 26))
        let extraLength = Int(header.readUInt16(at: 28))
        let dataOffset = entry.localHeaderOffset + 30 + nameLength + extraLength

        let raw = try source.read(at: dataOffset, count: entry.compressedSize)
        let bytes: Data
        switch entry.method {
        case ZipFormat.Method.stored.rawValue:
            guard raw.count == entry.uncompressedSize else {
                throw ZipError.corruptEntry("\(entry.name): stored size mismatch")
            }
            bytes = raw
        case ZipFormat.Method.deflated.rawValue:
            bytes = try Deflate.decompress(raw, expectedSize: entry.uncompressedSize)
        default:
            throw ZipError.unsupportedMethod(name: entry.name, method: entry.method)
        }

        guard Crc32.of(bytes) == entry.crc else { throw ZipError.crcMismatch(name: entry.name) }
        return bytes
    }

    public func data(forEntryNamed name: String) throws -> Data? {
        guard let entry = entry(named: name) else { return nil }
        return try data(for: entry)
    }

    // MARK: - Central directory

    private static func readCentralDirectory(_ source: ZipSource) throws -> [Entry] {
        let total = source.byteCount
        guard total >= 22 else { throw ZipError.notAZipArchive }

        // The end record is last, but a (never written by us) archive comment can push it back
        // by up to 65,535 bytes, so scan the tail.
        let tailLength = min(total, 22 + 0xFFFF)
        let tail = try source.read(at: total - tailLength, count: tailLength)

        if findSignature(ZipFormat.zip64EndLocatorSignature, in: tail) != nil {
            throw ZipError.zip64Unsupported
        }
        guard let endOffsetInTail = findSignature(ZipFormat.endOfCentralDirectorySignature, in: tail) else {
            throw ZipError.notAZipArchive
        }
        let end = tail.advanced(by: endOffsetInTail)
        guard end.count >= 22 else { throw ZipError.truncated }

        let entryCount = Int(end.readUInt16(at: 10))
        let directorySize = Int(end.readUInt32(at: 16 - 4))
        let directoryOffset = Int(end.readUInt32(at: 16))
        guard entryCount != 0xFFFF,
              directorySize != Int(ZipFormat.sizeLimit),
              directoryOffset != Int(ZipFormat.sizeLimit)
        else { throw ZipError.zip64Unsupported }

        let directory = try source.read(at: directoryOffset, count: directorySize)
        var entries: [Entry] = []
        entries.reserveCapacity(entryCount)
        var cursor = 0
        while entries.count < entryCount {
            guard cursor + 46 <= directory.count else { throw ZipError.truncated }
            let record = directory.advanced(by: cursor)
            guard record.readUInt32(at: 0) == ZipFormat.centralHeaderSignature else {
                throw ZipError.corruptEntry("bad central directory record at \(cursor)")
            }
            let nameLength = Int(record.readUInt16(at: 28))
            let extraLength = Int(record.readUInt16(at: 30))
            let commentLength = Int(record.readUInt16(at: 32))
            guard cursor + 46 + nameLength <= directory.count else { throw ZipError.truncated }

            let nameBytes = try directory.read(at: cursor + 46, count: nameLength)
            guard let name = String(data: nameBytes, encoding: .utf8) else {
                throw ZipError.corruptEntry("entry name is not UTF-8")
            }
            entries.append(Entry(
                name: name,
                method: record.readUInt16(at: 10),
                crc: record.readUInt32(at: 16),
                compressedSize: Int(record.readUInt32(at: 20)),
                uncompressedSize: Int(record.readUInt32(at: 24)),
                localHeaderOffset: Int(record.readUInt32(at: 42))))

            cursor += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /// Last occurrence, since the tail may contain the bytes coincidentally.
    private static func findSignature(_ signature: UInt32, in data: Data) -> Int? {
        guard data.count >= 4 else { return nil }
        var index = data.count - 4
        while index >= 0 {
            if data.readUInt32(at: index) == signature { return index }
            index -= 1
        }
        return nil
    }
}

private extension Data {
    /// A view starting at `offset` whose own index 0 is that byte, so the readUInt helpers
    /// stay readable.
    func advanced(by offset: Int) -> Data {
        Data(self[index(startIndex, offsetBy: offset)...])
    }
}
