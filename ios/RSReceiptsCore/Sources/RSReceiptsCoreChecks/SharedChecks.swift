import Foundation
import RSReceiptsCore

/// The shared-report scenario both platforms run.
///
/// Four cases chosen because each one breaks a different naive merge: a conflicting edit to
/// the same field, a delete, an add only one side has, and two edits in the same millisecond.
enum SharedScenario {

    static let reportId = "rep-1"
    static let deviceA = "device-a"
    static let deviceB = "device-b"

    /// Where both devices started.
    static func base() -> SharedState {
        SharedState(
            reportId: reportId,
            currency: "EUR",
            versions: [deviceA: 1],
            report: SharedReport(
                id: reportId, name: "Lisbon Trip",
                createdAt: 1000, updatedAt: 1000, sortOrder: 0, updatedBy: deviceA),
            receipts: [
                receipt("r1", "Taxi", 2450, createdAt: 1, updatedAt: 1000, by: deviceA),
                receipt("r2", "Dinner", 8100, createdAt: 2, updatedAt: 1000, by: deviceA),
                receipt("r4", "Museum", 100, createdAt: 4, updatedAt: 1000, by: deviceA),
            ])
    }

    /// iOS edits: raises the taxi fare, adds a coffee, and touches the museum entry.
    static func deviceAState() -> SharedState {
        var state = base()
        state.versions = [deviceA: 3]
        state.receipts = [
            receipt("r1", "Taxi", 2600, createdAt: 1, updatedAt: 2000, by: deviceA),
            receipt("r2", "Dinner", 8100, createdAt: 2, updatedAt: 1000, by: deviceA),
            receipt("r3", "Coffee", 450, createdAt: 3, updatedAt: 2100, by: deviceA),
            receipt("r4", "Museum", 111, createdAt: 4, updatedAt: 5000, by: deviceA),
        ]
        return state
    }

    /// Android edits: raises the same taxi fare *later*, deletes the dinner, renames the
    /// report, and touches the museum entry in the very same millisecond as iOS.
    static func deviceBState() -> SharedState {
        var state = base()
        state.versions = [deviceB: 2]
        state.report = SharedReport(
            id: reportId, name: "Lisbon Trip (shared)",
            createdAt: 1000, updatedAt: 2300, sortOrder: 0, updatedBy: deviceB)
        state.receipts = [
            receipt("r1", "Taxi", 2700, createdAt: 1, updatedAt: 2050, by: deviceB),
            receipt("r2", "Dinner", 8100, createdAt: 2, updatedAt: 2200, by: deviceB,
                    deletedAt: 2200),
            receipt("r4", "Museum", 222, createdAt: 4, updatedAt: 5000, by: deviceB),
        ]
        return state
    }

    static func receipt(
        _ id: String, _ description: String, _ amount: Int64,
        createdAt: EpochMillis, updatedAt: EpochMillis, by: String,
        deletedAt: EpochMillis? = nil
    ) -> SharedReceipt {
        SharedReceipt(
            id: id, reportId: reportId, description: description, amountMinor: amount,
            date: 1000, imageFile: nil, createdAt: createdAt,
            updatedAt: updatedAt, updatedBy: by, deletedAt: deletedAt)
    }
}

func sharedChecks(_ harness: Harness) {
    harness.section("Shared report merge")

    let a = SharedScenario.deviceAState()
    let b = SharedScenario.deviceBState()
    let merged = SharedMerge.merge(a, b)

    func receipt(_ id: String, in state: SharedState) -> SharedReceipt? {
        state.receipts.first { $0.id == id }
    }

    harness.check("a later edit to the same field wins") {
        // Both devices raised the taxi fare; Android's edit is 50ms later.
        try expect(receipt("r1", in: merged)?.amountMinor, 2700)
        try expect(receipt("r1", in: merged)?.updatedBy, SharedScenario.deviceB)
    }

    harness.check("an add only one side has survives") {
        try expect(receipt("r3", in: merged)?.description, "Coffee")
        try expect(receipt("r3", in: merged)?.amountMinor, 450)
    }

    harness.check("a delete propagates instead of resurrecting") {
        // The side that still holds the row must not reintroduce it.
        try expectTrue(receipt("r2", in: merged)?.isDeleted == true, "the tombstone did not win")
        try expectTrue(
            !SharedMerge.visibleReceipts(merged).contains { $0.id == "r2" },
            "a deleted receipt is still visible")
    }

    harness.check("an exact tie is broken deterministically, not arbitrarily") {
        // Same millisecond, different devices. Whoever wins, both platforms must pick the
        // same one — otherwise the two converge to different states and nothing looks wrong.
        try expect(receipt("r4", in: merged)?.amountMinor, 222)
        try expect(receipt("r4", in: merged)?.updatedBy, SharedScenario.deviceB)
    }

    harness.check("the report itself merges by the same rule") {
        try expect(merged.report.name, "Lisbon Trip (shared)")
        try expect(merged.report.updatedBy, SharedScenario.deviceB)
    }

    harness.check("the version vector takes the elementwise maximum") {
        try expect(merged.versions, ["device-a": 3, "device-b": 2])
    }

    harness.check("merging is commutative") {
        // Order of arrival must not matter: the two devices read each other's files in
        // whichever order they happen to sync.
        let forward = try SharedMerge.canonicalJSON(SharedMerge.merge(a, b))
        let backward = try SharedMerge.canonicalJSON(SharedMerge.merge(b, a))
        try expect(forward, backward)
    }

    harness.check("merging is idempotent") {
        // A device that syncs twice with no changes must write the same bytes.
        let once = try SharedMerge.canonicalJSON(merged)
        let twice = try SharedMerge.canonicalJSON(SharedMerge.merge(merged, merged))
        try expect(once, twice)
        let withA = try SharedMerge.canonicalJSON(SharedMerge.merge(merged, a))
        try expect(once, withA)
    }

    harness.check("a clobbered upload is recovered on the next sync") {
        // B overwrites A's file, so A's additions are briefly absent from it. A still holds
        // them, and republishes the full merged state next time.
        let clobbered = b
        try expectTrue(
            !clobbered.receipts.contains { $0.id == "r3" },
            "the scenario should have B lacking A's addition")
        let recovered = SharedMerge.merge(clobbered, a)
        try expect(recovered.receipts.first { $0.id == "r3" }?.description, "Coffee")
    }

    harness.check("a photo travels inside the file and survives a merge") {
        // One file is the whole report: base64 on the receipt, so nothing can go missing
        // alongside it. The merge must carry it like any other field.
        let jpeg = Data((0..<2048).map { UInt8($0 % 251) })
        let encoded = jpeg.base64EncodedString()

        var withPhoto = SharedScenario.deviceAState()
        withPhoto.receipts = [SharedScenario.receipt(
            "r1", "Taxi", 2600, createdAt: 1, updatedAt: 9000, by: SharedScenario.deviceA)]
        withPhoto.receipts[0].imageFile = "a.jpg"
        withPhoto.receipts[0].imageData = encoded

        let result = SharedMerge.merge(SharedScenario.deviceBState(), withPhoto)
        let carried = result.receipts.first { $0.id == "r1" }
        try expect(carried?.imageFile, "a.jpg")
        try expect(carried?.imageData, encoded)
        try expect(Data(base64Encoded: carried?.imageData ?? ""), jpeg)
    }

    harness.check("a file written before photos travelled still decodes") {
        // imageData is optional and omitted when absent, so an older file is still readable.
        let json = """
        {"sharedVersion":1,"reportId":"rep-1","currency":"EUR","versions":{},
         "report":{"id":"rep-1","name":"T","createdAt":1,"updatedAt":1,"sortOrder":0,
                   "updatedBy":"d"},
         "receipts":[{"id":"r1","reportId":"rep-1","description":"Taxi","amountMinor":1,
                      "date":1,"imageFile":"a.jpg","createdAt":1,"updatedAt":1,
                      "updatedBy":"d","deletedAt":null}]}
        """
        let decoded = try JSONDecoder().decode(SharedState.self, from: Data(json.utf8))
        try expectNil(decoded.receipts[0].imageData)
        try expect(decoded.receipts[0].imageFile, "a.jpg")
    }

    harness.check("a stale tombstone outranks the row it refers to") {
        // Why the publishers must never emit both for one id. Delete a receipt at t=5000,
        // then restore a backup: the row returns carrying its original t=1000, while the
        // tombstone stays at 5000. Last-write-wins then correctly prefers the *newer* record
        // — the delete — and the receipt vanishes on the other device.
        let live = SharedScenario.receipt(
            "r1", "Taxi", 2450, createdAt: 1, updatedAt: 1000, by: SharedScenario.deviceA)
        var tomb = live
        tomb.deletedAt = 5000
        tomb.updatedAt = 5000

        var bad = SharedScenario.base()
        bad.receipts = [live, tomb]          // what a stale tombstone used to publish
        try expectTrue(
            SharedMerge.merge(bad, bad).receipts.first { $0.id == "r1" }?.isDeleted == true,
            "the newer tombstone wins, which is how the receipt was lost")

        var good = SharedScenario.base()
        good.receipts = [live]               // what the publishers emit now
        try expectTrue(
            SharedMerge.merge(good, good).receipts.first { $0.id == "r1" }?.isDeleted == false,
            "with the tombstone suppressed at the source, the receipt survives")
    }

    harness.check("currency travels with the report that won") {
        try expect(merged.currency, "EUR")
    }

    harness.check("both platforms converge on the same state") {
        // The real proof: decode what Android merged and compare it to what this platform
        // merged from the same two inputs. Values, not bytes — the two serialisers differ in
        // key order and indentation, and neither matters.
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        let directory = root.appendingPathComponent("fixtures/shared")
        let decoder = JSONDecoder()

        let aFromFile = try decoder.decode(
            SharedState.self,
            from: try Data(contentsOf: directory.appendingPathComponent("state-device-a.json")))
        let bFromFile = try decoder.decode(
            SharedState.self,
            from: try Data(contentsOf: directory.appendingPathComponent("state-device-b.json")))
        let androidMerged = try decoder.decode(
            SharedState.self,
            from: try Data(contentsOf: directory.appendingPathComponent("android-merged.json")))

        try expect(SharedMerge.merge(aFromFile, bFromFile), androidMerged)
    }
}


/// Writes this platform's half of the cross-OS exchange, and — once Android has written its
/// half — the merged result for the two to be compared.
func writeSharedFixtures(_ root: URL) throws {
    let directory = root.appendingPathComponent("fixtures/shared")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .prettyPrinted]

    let aUrl = directory.appendingPathComponent("state-device-a.json")
    try encoder.encode(SharedScenario.deviceAState()).write(to: aUrl, options: .atomic)
    print("wrote \(aUrl.lastPathComponent)")

    // Android's half, if it has run yet. Decoded from the file rather than rebuilt locally:
    // the point is to merge what the other platform actually serialised.
    let bUrl = directory.appendingPathComponent("state-device-b.json")
    guard let bData = try? Data(contentsOf: bUrl) else {
        print("state-device-b.json not present yet — run the Android side, then re-run")
        return
    }
    let b = try JSONDecoder().decode(SharedState.self, from: bData)
    let a = try JSONDecoder().decode(SharedState.self, from: try Data(contentsOf: aUrl))

    let merged = SharedMerge.merge(a, b)
    let out = directory.appendingPathComponent("ios-merged.json")
    try encoder.encode(merged).write(to: out, options: .atomic)
    print("wrote \(out.lastPathComponent) — merged \(a.receipts.count) + \(b.receipts.count) records")
}
