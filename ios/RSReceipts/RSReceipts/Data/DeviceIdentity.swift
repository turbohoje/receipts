import Foundation

/// A stable id for this install, used as the `updatedBy` on every shared record.
///
/// Also the merge's tie-break when two edits land in the same millisecond, which is why it has
/// to be stable: a device whose id changed would start losing ties it used to win, and the two
/// sides could disagree about a record neither had touched.
enum DeviceIdentity {
    private static let key = "cc.rocketscience.receipts.deviceId"

    static var current: String {
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let fresh = UUID().uuidString.lowercased()
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }
}
