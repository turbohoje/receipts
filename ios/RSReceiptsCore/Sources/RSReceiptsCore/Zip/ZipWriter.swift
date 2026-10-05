import Foundation

/// Where a `ZipWriter` puts its bytes. A file sink keeps peak memory at one entry, which is
/// what makes a several-hundred-megabyte backup possible on a phone.
public protocol ZipSink: AnyObject {
    func write(_ data: Data) throws
}

public final class DataZipSink: ZipSink {
    public private(set) var data = Data()
    public init() {}
    public func write(_ chunk: Data) throws { data.append(chunk) }
}

public final class FileZipSink: ZipSink {
    private let handle: FileHandle

    public init(url: URL) throws {
        let manager = FileManager.default
        if !manager.fileExists(atPath: url.path) {
            manager.createFile(atPath: url.path, contents: nil)
        }
        handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: 0)
    }

    public func write(_ chunk: Data) throws { try handle.write(contentsOf: chunk) }
    public func close() throws { try handle.close() }
}

/// Writes a ZIP that honours the profile in ``ZipFormat``.
///
/// Entries are added one at a time and their bytes are released straight after, so writing a
/// backup holds one image in memory rather than the whole archive.
public final class ZipWriter {

    private struct CentralRecord {
        let name: Data
        let method: UInt16
        let crc: UInt32
        let compressedSize: UInt32
        let uncompressedSize: UInt32
        let localOffset: UInt32
        let time: UInt16
        let date: UInt16
    }

    private let sink: ZipSink
    private let timestamp: DosTimestamp
    private var offset: UInt64 = 0
    private var records: [CentralRecord] = []
    private var finished = false

    public init(sink: ZipSink, modified: Date = Date()) {
        self.sink = sink
        self.timestamp = DosTimestamp(modified)
    }

    /// Adds one entry. The method defaults to what the profile prescribes for the name:
    /// stored for `.jpg`, deflated for everything else.
    public func add(_ name: String, _ bytes: Data, method: ZipFormat.Method? = nil) throws {
        precondition(!finished, "ZipWriter.add after finish()")
        try validate(name)

        let chosen = method ?? ZipFormat.method(for: name)
        var payload = bytes
        var actual = ZipFormat.Method.stored
        if chosen == .deflated, let compressed = Deflate.compress(bytes) {
            payload = compressed
            actual = .deflated
        }

        guard bytes.count <= Int(ZipFormat.sizeLimit), payload.count <= Int(ZipFormat.sizeLimit) else {
            throw ZipError.tooLarge("entry \(name) exceeds 4 GB")
        }
        guard offset + UInt64(payload.count) + 30 <= ZipFormat.sizeLimit else {
            throw ZipError.tooLarge("archive exceeds 4 GB")
        }
        guard records.count < ZipFormat.entryLimit else {
            throw ZipError.tooLarge("more than \(ZipFormat.entryLimit) entries")
        }

        let nameBytes = Data(name.utf8)
        let crc = Crc32.of(bytes)
        let localOffset = UInt32(offset)

        var header = Data()
        header.appendUInt32(ZipFormat.localHeaderSignature)
        header.appendUInt16(ZipFormat.versionNeeded)
        header.appendUInt16(ZipFormat.utf8NameFlag)     // never bit 3: sizes are known here
        header.appendUInt16(actual.rawValue)
        header.appendUInt16(timestamp.time)
        header.appendUInt16(timestamp.date)
        header.appendUInt32(crc)
        header.appendUInt32(UInt32(payload.count))
        header.appendUInt32(UInt32(bytes.count))
        header.appendUInt16(UInt16(nameBytes.count))
        header.appendUInt16(0)                          // no extra field
        header.append(nameBytes)

        try sink.write(header)
        try sink.write(payload)
        offset += UInt64(header.count) + UInt64(payload.count)

        records.append(CentralRecord(
            name: nameBytes,
            method: actual.rawValue,
            crc: crc,
            compressedSize: UInt32(payload.count),
            uncompressedSize: UInt32(bytes.count),
            localOffset: localOffset,
            time: timestamp.time,
            date: timestamp.date))
    }

    /// Writes the central directory and the end record. Must be called exactly once.
    public func finish() throws {
        precondition(!finished, "ZipWriter.finish called twice")
        finished = true

        let directoryOffset = offset
        var directory = Data()
        for record in records {
            directory.appendUInt32(ZipFormat.centralHeaderSignature)
            directory.appendUInt16(ZipFormat.versionNeeded)  // version made by
            directory.appendUInt16(ZipFormat.versionNeeded)
            directory.appendUInt16(ZipFormat.utf8NameFlag)
            directory.appendUInt16(record.method)
            directory.appendUInt16(record.time)
            directory.appendUInt16(record.date)
            directory.appendUInt32(record.crc)
            directory.appendUInt32(record.compressedSize)
            directory.appendUInt32(record.uncompressedSize)
            directory.appendUInt16(UInt16(record.name.count))
            directory.appendUInt16(0)                        // extra
            directory.appendUInt16(0)                        // comment
            directory.appendUInt16(0)                        // disk number
            directory.appendUInt16(0)                        // internal attributes
            directory.appendUInt32(0)                        // external attributes
            directory.appendUInt32(record.localOffset)
            directory.append(record.name)
        }

        var end = Data()
        end.appendUInt32(ZipFormat.endOfCentralDirectorySignature)
        end.appendUInt16(0)                                  // this disk
        end.appendUInt16(0)                                  // disk with the directory
        end.appendUInt16(UInt16(records.count))
        end.appendUInt16(UInt16(records.count))
        end.appendUInt32(UInt32(directory.count))
        end.appendUInt32(UInt32(directoryOffset))
        end.appendUInt16(0)                                  // no archive comment

        try sink.write(directory)
        try sink.write(end)
        offset += UInt64(directory.count) + UInt64(end.count)
    }

    /// A ZIP is untrusted input on the way out too: these names end up in other people's
    /// extractors.
    private func validate(_ name: String) throws {
        guard !name.isEmpty,
              !name.hasPrefix("/"),
              !name.contains("\\"),
              !name.split(separator: "/").contains(where: { $0 == ".." })
        else { throw ZipError.unsafeEntryName(name) }
    }
}
