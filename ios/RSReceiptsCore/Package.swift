// swift-tools-version: 6.0
import PackageDescription

// `platforms:` is required, not optional: with no clause SPM assumes the oldest supported
// macOS (10.13) and every modern Foundation API disappears behind an availability error.
//
// These are floors for availability checking inside this package, NOT the shipping deployment
// target — the app target owns that, and it is latest-iOS-only. The floor is deliberately
// lower and unambiguous: the Command Line Tools toolchain here predates Apple's 18-to-26
// version renumbering and rejects `.iOS(.v26)` outright. The macOS floor exists only so this
// package can be built and checked on this machine before Xcode is installed.
let package = Package(
    name: "RSReceiptsCore",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "RSReceiptsCore", targets: ["RSReceiptsCore"]),
    ],
    targets: [
        .target(name: "RSReceiptsCore"),
        // Stands in for a test target: neither XCTest nor swift-testing ships with the
        // Command Line Tools, so the checks are an executable that exits non-zero.
        // `swift run RSReceiptsCoreChecks` is the whole suite. Becomes a real test target
        // once Xcode is available.
        .executableTarget(name: "RSReceiptsCoreChecks", dependencies: ["RSReceiptsCore"]),
    ]
)
