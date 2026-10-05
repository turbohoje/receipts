import Foundation

// The suite. `swift run RSReceiptsCoreChecks` from ios/RSReceiptsCore.
// `--write-fixtures` instead rewrites the iOS interop fixture; see fixtures/interop/README.md.
if CommandLine.arguments.contains("--write-fixtures") {
    let destination = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // RSReceiptsCoreChecks
        .deletingLastPathComponent()   // Sources
        .deletingLastPathComponent()   // RSReceiptsCore
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // repository root
        .appendingPathComponent("fixtures/interop/ios-backup-v2.zip")
    let bytes = try writeIosFixture()
    try FileManager.default.createDirectory(
        at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
    try bytes.write(to: destination, options: .atomic)
    print("wrote \(destination.path) (\(bytes.count) bytes)")
    exit(0)
}

let harness = Harness()
moneyChecks(harness)
exportChecks(harness)
zipChecks(harness)
imageChecks(harness)
reportOrderChecks(harness)
backupChecks(harness)
interopChecks(harness)
harness.summarize()
