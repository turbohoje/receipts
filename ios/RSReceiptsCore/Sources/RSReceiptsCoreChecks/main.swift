import Foundation

// The suite. `swift run RSReceiptsCoreChecks` from ios/RSReceiptsCore.
// `--write-fixtures` instead rewrites the iOS interop fixture; see fixtures/interop/README.md.
let repositoryRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent()

if let i = CommandLine.arguments.firstIndex(of: "--reencode"),
   i + 2 < CommandLine.arguments.count {
    try reencode(CommandLine.arguments[i + 1], to: CommandLine.arguments[i + 2])
    exit(0)
}

if let i = CommandLine.arguments.firstIndex(of: "--restore"),
   i + 1 < CommandLine.arguments.count {
    try restoreOne(CommandLine.arguments[i + 1])
    exit(0)
}

if CommandLine.arguments.contains("--write-shared-fixtures") {
    try writeSharedFixtures(repositoryRoot)
    exit(0)
}

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
pdfLayoutChecks(harness)
backupChecks(harness)
sharedChecks(harness)
interopChecks(harness)
harness.summarize()
