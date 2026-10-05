import Foundation
import RSReceiptsCore

/// Ported from Android's `BackupRoundTripTest`.
func backupChecks(_ harness: Harness) {
    harness.section("Backup")

    let writer = BackupWriter()
    let reader = BackupReader()

    func manifest(
        schemaVersion: Int = BackupFormat.schemaVersion,
        receipts: [BackupReceipt]? = nil
    ) -> BackupManifest {
        BackupManifest(
            schemaVersion: schemaVersion,
            appVersion: "0.1.0",
            createdAt: 1_787_000_000_000,
            currency: "USD",
            reports: [BackupReport(
                id: "r1", name: "Q3 Client Trip",
                createdAt: 1_787_000_000_000, updatedAt: 1_787_000_000_000)],
            receipts: receipts ?? [
                BackupReceipt(id: "c1", reportId: "r1", description: "Taxi, airport",
                              amountMinor: 2450, date: 1_787_000_000_000,
                              imageFile: "a.jpg", createdAt: 1),
                BackupReceipt(id: "c2", reportId: "r1", description: "Cash tip",
                              amountMinor: 500, date: 1_787_000_000_000,
                              imageFile: nil, createdAt: 2),
            ])
    }

    /// A scratch directory holding image files, cleaned up by the caller.
    func scratch() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("rsr-backup-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func images(_ directory: URL, _ names: String...) throws -> [String: URL] {
        var result: [String: URL] = [:]
        for name in names {
            let url = directory.appendingPathComponent(name)
            try Data("jpeg-\(name)".utf8).write(to: url)
            result[name] = url
        }
        return result
    }

    harness.check("a backup round-trips to identical data") {
        let directory = try scratch()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = try images(directory, "a.jpg")
        let original = manifest()

        let sink = DataZipSink()
        _ = try writer.write(to: sink, manifest: original) { files[$0] }

        guard case .ready(let read, let names) = reader.peek(sink.data) else {
            throw CheckFailure(description: "expected .ready, got \(reader.peek(sink.data))")
        }
        try expect(read, original)
        try expect(names, ["a.jpg"])
    }

    harness.check("stats report what was written") {
        let directory = try scratch()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = try images(directory, "a.jpg")
        let stats = try writer.write(to: DataZipSink(), manifest: manifest()) { files[$0] }
        try expect(stats.reports, 1)
        try expect(stats.receipts, 2)
        try expect(stats.imagesWritten, 1)
        try expect(stats.imagesMissing, 0)
    }

    harness.check("a receipt whose image is gone is still backed up") {
        // Losing the image is one problem; losing the line item too would be a second.
        let sink = DataZipSink()
        let stats = try writer.write(to: sink, manifest: manifest()) { _ in nil }
        try expect(stats.imagesMissing, 1)
        try expect(stats.imagesWritten, 0)
        guard case .ready(let read, let names) = reader.peek(sink.data) else {
            throw CheckFailure(description: "expected .ready")
        }
        try expect(read.receipts.count, 2)
        try expect(names, [])
    }

    harness.check("a newer schema version is refused") {
        // Relative to the current version, so this keeps meaning what it says after a bump.
        let future = BackupFormat.schemaVersion + 1
        let sink = DataZipSink()
        _ = try writer.write(to: sink, manifest: manifest(schemaVersion: future)) { _ in nil }
        try expect(
            reader.peek(sink.data),
            .refused(.tooNew(found: future, supported: BackupFormat.schemaVersion)))
    }

    harness.check("an older schema version is still accepted") {
        let sink = DataZipSink()
        _ = try writer.write(to: sink, manifest: manifest(schemaVersion: 1)) { _ in nil }
        guard case .ready = reader.peek(sink.data) else {
            throw CheckFailure(description: "a version 1 backup must still restore")
        }
    }

    harness.check("an archive with no manifest is refused") {
        let sink = DataZipSink()
        let zip = ZipWriter(sink: sink)
        try zip.add("images/a.jpg", Data("jpeg".utf8))
        try zip.finish()
        try expect(reader.peek(sink.data), .refused(.noManifest))
    }

    harness.check("a non-archive is refused without crashing") {
        guard case .refused(.unreadable) = reader.peek(Data(repeating: 0x41, count: 1000)) else {
            throw CheckFailure(description: "expected .unreadable")
        }
    }

    harness.check("a corrupt manifest is refused") {
        let sink = DataZipSink()
        let zip = ZipWriter(sink: sink)
        try zip.add(BackupFormat.manifestEntry, Data("{not json".utf8))
        try zip.finish()
        guard case .refused(.unreadable) = reader.peek(sink.data) else {
            throw CheckFailure(description: "expected .unreadable")
        }
    }

    harness.check("images extract byte for byte, by bare filename") {
        let directory = try scratch()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = try images(directory, "a.jpg")
        let sink = DataZipSink()
        _ = try writer.write(to: sink, manifest: manifest()) { files[$0] }

        let target = directory.appendingPathComponent("restored")
        try expect(try reader.extractImages(sink.data, to: target), 1)
        try expect(
            try Data(contentsOf: target.appendingPathComponent("a.jpg")),
            Data("jpeg-a.jpg".utf8))
    }

    harness.check("a nil imageFile is written as an explicit null") {
        // Android's writer sets encodeDefaults = true and emits `"imageFile": null`; Swift's
        // synthesized encoder would drop the key entirely.
        let sink = DataZipSink()
        _ = try writer.write(to: sink, manifest: manifest()) { _ in nil }
        let archive = try ZipArchive(source: sink.data)
        let json = try archive.data(forEntryNamed: BackupFormat.manifestEntry)!
        let text = String(data: json, encoding: .utf8)!
        try expectTrue(text.contains("\"imageFile\" : null"), "manifest was:\n\(text)")
    }

    harness.check("a manifest with imageFile absent still decodes") {
        let json = """
        {"schemaVersion":1,"appVersion":"0.1.0","createdAt":1,"currency":"USD","reports":[],
         "receipts":[{"id":"c1","reportId":"r1","description":"x","amountMinor":1,"date":1,
         "createdAt":1}]}
        """
        let decoded = try BackupJson.decoder.decode(BackupManifest.self, from: Data(json.utf8))
        try expectNil(decoded.receipts[0].imageFile)
    }

    harness.check("epoch millis survive the manifest unchanged") {
        let odd: EpochMillis = 1_756_000_000_123
        let sink = DataZipSink()
        var subject = manifest()
        subject.createdAt = odd
        subject.receipts[0].date = odd
        _ = try writer.write(to: sink, manifest: subject) { _ in nil }
        guard case .ready(let read, _) = reader.peek(sink.data) else {
            throw CheckFailure(description: "expected .ready")
        }
        try expect(read.createdAt, odd)
        try expect(read.receipts[0].date, odd)
        // And that the millisecond survives a Date round-trip.
        try expect(Date(epochMillis: odd).epochMillis, odd)
    }

    harness.check("the backup filename carries a sortable stamp") {
        var components = DateComponents()
        components.year = 2026; components.month = 9; components.day = 25
        components.hour = 14; components.minute = 6; components.second = 3
        var calendar = Calendar(identifier: .gregorian)
        let utc = TimeZone(identifier: "UTC")!
        calendar.timeZone = utc
        let when = calendar.date(from: components)!
        try expect(
            BackupFormat.fileName(date: when, timeZone: utc),
            "rs-receipts-backup-20260925-140603.zip")
    }
}
