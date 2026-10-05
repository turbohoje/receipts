import Foundation
import RSReceiptsCore

/// The checks that actually justify the claim that a backup moves between platforms: read an
/// archive Android wrote, and write one Android can read.
///
/// `fixtures/interop/` holds one archive from each platform. Regenerating them is documented
/// in `fixtures/interop/README.md`; the Android side reads the iOS fixture in
/// `ArchiveInteropTest`.
func interopChecks(_ harness: Harness) {
    harness.section("Interop with Android")

    // Located from this file rather than the working directory, so it does not matter where
    // `swift run` was invoked from.
    let repositoryRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // RSReceiptsCoreChecks
        .deletingLastPathComponent()   // Sources
        .deletingLastPathComponent()   // RSReceiptsCore
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // repository root
    let fixtures = repositoryRoot.appendingPathComponent("fixtures/interop")
    let androidFixture = fixtures.appendingPathComponent("android-backup-v2.zip")

    harness.check("an archive written by java.util.zip is readable") {
        let source = try FileZipSource(url: androidFixture)
        defer { try? source.close() }
        let archive = try ZipArchive(source: source)

        // The whole point: Android's deflated entries carry a data descriptor, so their local
        // headers hold zeros. Reading via the central directory is what makes this work.
        let manifestEntry = archive.entry(named: BackupFormat.manifestEntry)
        try expectTrue(manifestEntry != nil, "no manifest.json in the fixture")
        try expect(manifestEntry!.method, ZipFormat.Method.deflated.rawValue)
        try expectTrue(manifestEntry!.uncompressedSize > 0, "manifest size came back zero")

        let images = archive.entries(withPrefix: BackupFormat.imagePrefix)
        try expectTrue(!images.isEmpty, "no images in the fixture")
        try expect(images[0].method, ZipFormat.Method.stored.rawValue)
    }

    harness.check("an Android backup restores to the expected data") {
        let source = try FileZipSource(url: androidFixture)
        defer { try? source.close() }
        guard case .ready(let manifest, let names) = BackupReader().peek(source) else {
            throw CheckFailure(description: "Android fixture was refused: \(BackupReader().peek(source))")
        }
        try expect(manifest.schemaVersion, BackupFormat.schemaVersion)
        try expect(manifest.currency, "USD")
        try expect(manifest.receipts.count, 2)

        // The manual order has to survive the trip, and has to beat creation order: the older
        // report sorts first because it was dragged there.
        let ordered = manifest.reports.sorted { $0.sortOrder < $1.sortOrder }
        try expect(ordered.map(\.name), ["Office Supplies", "Q3 Client Trip"])
        try expect(ordered.map(\.sortOrder), [0, 1])

        // Amounts as exact minor units, and a real millisecond timestamp.
        let taxi = manifest.receipts.first { $0.description == "Taxi, airport" }
        try expectTrue(taxi != nil, "missing the taxi receipt")
        try expect(taxi!.amountMinor, 2450)
        try expect(taxi!.date, 1_756_000_000_123)
        try expect(taxi!.imageFile, "3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg")

        // The receipt Android wrote with a null imageFile.
        let tip = manifest.receipts.first { $0.description == "Cash tip" }
        try expectTrue(tip != nil, "missing the cash tip receipt")
        try expectNil(tip!.imageFile)

        try expect(names, ["3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg"])
    }

    harness.check("images in an Android backup extract intact") {
        let source = try FileZipSource(url: androidFixture)
        defer { try? source.close() }
        let target = FileManager.default.temporaryDirectory
            .appendingPathComponent("rsr-interop-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: target) }

        // CRC-32 is verified per entry on the way out, so this also proves our CRC agrees
        // with java.util.zip's.
        try expect(try BackupReader().extractImages(source, to: target), 1)
        let extracted = try Data(contentsOf: target.appendingPathComponent(
            "3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg"))
        try expect(extracted.count, 40_000)
    }

    harness.check("a version 1 backup still restores after the bump") {
        // Bumping the format locks older builds out of newer backups, but must not lock this
        // build out of older ones. A v1 manifest has no sortOrder at all.
        let legacy = fixtures.appendingPathComponent("android-backup-v1.zip")
        let source = try FileZipSource(url: legacy)
        defer { try? source.close() }
        guard case .ready(let manifest, _) = BackupReader().peek(source) else {
            throw CheckFailure(description: "a version 1 backup must still restore")
        }
        try expect(manifest.schemaVersion, 1)
        try expect(manifest.reports.map(\.name), ["Q3 Client Trip"])
        // Absent in the file, so it falls back to the default.
        try expect(manifest.reports.map(\.sortOrder), [0])
    }

    harness.check("the iOS fixture Android reads is up to date") {
        // Written here and committed; ArchiveInteropTest on the Android side reads it back.
        let iosFixture = fixtures.appendingPathComponent("ios-backup-v2.zip")
        let regenerated = try writeIosFixture()
        let committed = try Data(contentsOf: iosFixture)
        try expect(
            committed, regenerated,
            "fixtures/interop/ios-backup-v1.zip is stale — see its README to regenerate")
    }
}

/// The iOS-written fixture, byte-for-byte reproducible: a fixed timestamp so the DOS date in
/// every header is stable, and deterministic image bytes.
///
/// Two reports whose manual order deliberately contradicts their creation order — the older
/// one sorts first. A fixture where the two agree would pass even if `sortOrder` were dropped.
func writeIosFixture() throws -> Data {
    let manifest = BackupManifest(
        appVersion: "0.1.0-ios",
        createdAt: 1_756_000_000_123,
        currency: "USD",
        reports: [
            BackupReport(
                id: "11111111-1111-4111-8111-111111111111",
                name: "Q3 Client Trip",
                createdAt: 1_756_000_100_000,   // newer
                updatedAt: 1_756_000_100_000,
                sortOrder: 1),                  // but second by hand
            BackupReport(
                id: "44444444-4444-4444-8444-444444444444",
                name: "Office Supplies",
                createdAt: 1_756_000_000_000,   // older
                updatedAt: 1_756_000_000_000,
                sortOrder: 0),                  // dragged to the top
        ],
        receipts: [
            BackupReceipt(
                id: "22222222-2222-4222-8222-222222222222",
                reportId: "11111111-1111-4111-8111-111111111111",
                description: "Taxi, airport",
                amountMinor: 2450,
                date: 1_756_000_000_123,
                imageFile: "3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg",
                createdAt: 1),
            BackupReceipt(
                id: "33333333-3333-4333-8333-333333333333",
                reportId: "11111111-1111-4111-8111-111111111111",
                description: "Cash tip",
                amountMinor: 500,
                date: 1_756_000_000_456,
                imageFile: nil,
                createdAt: 2),
        ])

    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("rsr-fixture-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }

    let imageName = "3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg"
    let imageUrl = directory.appendingPathComponent(imageName)
    try deterministicImageBytes().write(to: imageUrl)

    let sink = DataZipSink()
    _ = try BackupWriter().write(
        to: sink,
        manifest: manifest,
        modified: Date(epochMillis: 1_756_000_000_000)
    ) { $0 == imageName ? imageUrl : nil }
    return sink.data
}

/// Incompressible and reproducible, standing in for a JPEG.
func deterministicImageBytes(_ count: Int = 40_000) -> Data {
    var state: UInt64 = 0x2545_F491_4F6C_DD1D
    var bytes = Data(capacity: count)
    for _ in 0..<count {
        state ^= state << 13; state ^= state >> 7; state ^= state << 17
        bytes.append(UInt8(truncatingIfNeeded: state))
    }
    return bytes
}
