import Foundation
import RSReceiptsCore

func zipChecks(_ harness: Harness) {
    harness.section("ZIP")

    /// Incompressible, like a real JPEG. Deterministic so sizes are stable across runs.
    func jpegBytes(_ count: Int = 40_000) -> Data {
        var state: UInt64 = 0x2545_F491_4F6C_DD1D
        var bytes = Data(capacity: count)
        for _ in 0..<count {
            state ^= state << 13; state ^= state >> 7; state ^= state << 17
            bytes.append(UInt8(truncatingIfNeeded: state))
        }
        return bytes
    }

    func archive(_ build: (ZipWriter) throws -> Void) throws -> Data {
        let sink = DataZipSink()
        let writer = ZipWriter(sink: sink)
        try build(writer)
        try writer.finish()
        return sink.data
    }

    harness.check("CRC-32 matches the known zlib check value") {
        // "123456789" has the canonical CRC-32 0xCBF43926.
        try expect(Crc32.of(Data("123456789".utf8)), 0xCBF4_3926)
        try expect(Crc32.of(Data()), 0)
    }

    harness.check("a stored entry round-trips byte for byte") {
        let image = jpegBytes()
        let bytes = try archive { try $0.add("images/a.jpg", image) }
        let read = try ZipArchive(source: bytes)
        try expect(read.entries.count, 1)
        try expect(read.entries[0].method, ZipFormat.Method.stored.rawValue)
        try expect(try read.data(for: read.entries[0]), image)
    }

    harness.check("a deflated entry round-trips and actually compresses") {
        let text = Data(String(repeating: "receipt,", count: 500).utf8)
        let bytes = try archive { try $0.add("report.csv", text) }
        let read = try ZipArchive(source: bytes)
        try expect(read.entries[0].method, ZipFormat.Method.deflated.rawValue)
        try expectTrue(
            read.entries[0].compressedSize < text.count,
            "expected compression, got \(read.entries[0].compressedSize) of \(text.count)")
        try expect(try read.data(for: read.entries[0]), text)
    }

    harness.check("the profile picks the method from the entry name") {
        try expect(ZipFormat.method(for: "images/a.jpg"), .stored)
        try expect(ZipFormat.method(for: "images/A.JPEG"), .stored)
        try expect(ZipFormat.method(for: "manifest.json"), .deflated)
        try expect(ZipFormat.method(for: "report.csv"), .deflated)
    }

    harness.check("storing a JPEG beats deflating it") {
        let image = jpegBytes()
        let stored = try archive { try $0.add("a.jpg", image, method: .stored) }
        let deflated = try archive { try $0.add("a.bin", image, method: .deflated) }
        try expectTrue(
            stored.count < deflated.count || stored.count == deflated.count,
            "stored \(stored.count) vs deflated \(deflated.count)")
        // Incompressible input must fall back to stored rather than growing the archive.
        let read = try ZipArchive(source: deflated)
        try expect(read.entries[0].method, ZipFormat.Method.stored.rawValue)
    }

    harness.check("an empty entry round-trips") {
        let bytes = try archive { try $0.add("empty.txt", Data()) }
        let read = try ZipArchive(source: bytes)
        try expect(read.entries[0].uncompressedSize, 0)
        try expect(try read.data(for: read.entries[0]), Data())
    }

    harness.check("many entries keep their order and content") {
        let bytes = try archive { writer in
            for index in 0..<50 {
                try writer.add("images/\(index).jpg", Data("image-\(index)".utf8))
            }
        }
        let read = try ZipArchive(source: bytes)
        try expect(read.entries.count, 50)
        try expect(read.entries.map(\.name).first, "images/0.jpg")
        try expect(try read.data(forEntryNamed: "images/37.jpg"), Data("image-37".utf8))
    }

    harness.check("a corrupted byte is caught by the CRC") {
        var bytes = try archive { try $0.add("images/a.jpg", jpegBytes(1000)) }
        // Flip a byte inside the stored payload, past the local header.
        bytes[60] = bytes[60] ^ 0xFF
        let read = try ZipArchive(source: bytes)
        try expectThrows(ZipError.crcMismatch(name: "images/a.jpg")) {
            _ = try read.data(for: read.entries[0])
        }
    }

    harness.check("a truncated archive is refused, not misread") {
        let bytes = try archive { try $0.add("images/a.jpg", jpegBytes(1000)) }
        try expectThrows(ZipError.notAZipArchive) {
            _ = try ZipArchive(source: bytes.prefix(bytes.count / 2))
        }
    }

    harness.check("garbage is not mistaken for an archive") {
        try expectThrows(ZipError.notAZipArchive) {
            _ = try ZipArchive(source: Data(repeating: 0x41, count: 5000))
        }
    }

    harness.check("unsafe entry names are refused on write") {
        for name in ["/etc/passwd", "images/../../db", "a\\b", ""] {
            try expectThrows(ZipError.unsafeEntryName(name)) {
                let sink = DataZipSink()
                try ZipWriter(sink: sink).add(name, Data("x".utf8))
            }
        }
    }

    harness.check("an entry name is reduced to its basename on extraction") {
        // The writer refuses to produce these, but a hostile archive can still contain them.
        try expect(ZipArchive.safeFileName(in: "images/a.jpg"), "a.jpg")
        try expect(
            ZipArchive.safeFileName(in: "images/../../databases/receipts.db"), "receipts.db")
        try expectNil(ZipArchive.safeFileName(in: "images/"))
        try expectNil(ZipArchive.safeFileName(in: "images/.."))
    }

    harness.check("a UTF-8 entry name survives") {
        // slugify keeps non-ASCII letters, so export archives can carry them.
        let name = "images/01-café-trip.jpg"
        let bytes = try archive { try $0.add(name, Data("x".utf8)) }
        try expect(try ZipArchive(source: bytes).entries[0].name, name)
    }

    harness.check("a file-backed source reads the same as an in-memory one") {
        let image = jpegBytes(5000)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("rsr-zip-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let url = directory.appendingPathComponent("a.zip")
        let sink = try FileZipSink(url: url)
        let writer = ZipWriter(sink: sink)
        try writer.add("images/a.jpg", image)
        try writer.add("manifest.json", Data("{}".utf8))
        try writer.finish()
        try sink.close()

        let source = try FileZipSource(url: url)
        defer { try? source.close() }
        let read = try ZipArchive(source: source)
        try expect(read.entries.count, 2)
        try expect(try read.data(forEntryNamed: "images/a.jpg"), image)
        try expect(try read.data(forEntryNamed: "manifest.json"), Data("{}".utf8))
    }
}
