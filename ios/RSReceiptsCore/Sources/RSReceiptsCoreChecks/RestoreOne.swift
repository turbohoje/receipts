import Foundation
import RSReceiptsCore

/// Restores one archive given on the command line and prints what came out.
///
/// Used to put a backup written by the *other* platform's real app through this platform's
/// reader — the fixtures cover the format, this covers an actual device's output.
func restoreOne(_ path: String) throws {
    let source = try FileZipSource(url: URL(fileURLWithPath: path))
    defer { try? source.close() }

    switch BackupReader().peek(source) {
    case .refused(let problem):
        print("REFUSED: \(problem)")
        exit(1)
    case .ready(let manifest, let imageNames):
        print("schemaVersion : \(manifest.schemaVersion)")
        print("currency      : \(manifest.currency)")
        print("reports       : \(manifest.reports.count)")
        print("receipts      : \(manifest.receipts.count)")
        print("images in zip : \(imageNames.count)")
        let total = manifest.receipts.reduce(Int64(0)) { $0 + $1.amountMinor }
        print("total         : \(Money.formatPlain(total, .code(manifest.currency))) \(manifest.currency)")
        print("manual order  : \(manifest.reports.sorted { $0.sortOrder < $1.sortOrder }.map(\.name))")

        let referenced = Set(manifest.receipts.compactMap(\.imageFile))
        print("images referenced but absent: \(referenced.subtracting(imageNames).count)")

        // Extract for real, which CRC-checks every image on the way out.
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("rsr-restore-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: out) }
        let extracted = try BackupReader().extractImages(source, to: out)
        print("images extracted and CRC-verified: \(extracted)")
    }
}

/// Reads an archive and writes an equivalent one with *this* platform's writer.
///
/// The content is unchanged, so restoring the result is a no-op in effect — which is what
/// makes it safe to try on a device holding real receipts, while still proving the other
/// platform can read what this one produces.
func reencode(_ inPath: String, to outPath: String) throws {
    let source = try FileZipSource(url: URL(fileURLWithPath: inPath))
    defer { try? source.close() }
    guard case .ready(let manifest, _) = BackupReader().peek(source) else {
        print("REFUSED"); exit(1)
    }

    let staging = FileManager.default.temporaryDirectory
        .appendingPathComponent("rsr-reencode-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: staging) }
    let images = try BackupReader().extractImages(source, to: staging)

    var rewritten = manifest
    rewritten.appVersion = "0.2.0-ios"
    let sink = DataZipSink()
    let stats = try BackupWriter().write(to: sink, manifest: rewritten) { name in
        let url = staging.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
    try sink.data.write(to: URL(fileURLWithPath: outPath), options: .atomic)
    print("read \(images) images, wrote \(stats.imagesWritten) (\(sink.data.count) bytes) to \(outPath)")
}
