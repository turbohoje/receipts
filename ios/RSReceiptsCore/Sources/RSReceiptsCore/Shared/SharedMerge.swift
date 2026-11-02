import Foundation

/// Merges two views of a shared report into one both devices will agree on.
///
/// Last-write-wins per **record**, never per file: if the file were the unit, two people
/// editing different receipts would mean one person's work vanished.
///
/// The merge is commutative, associative and idempotent — `merge(a, b) == merge(b, a)`, and
/// merging the same state twice changes nothing. That is what makes a clobbered upload
/// self-healing: a device that loses a write still holds its own data, and republishes it on
/// its next sync.
public enum SharedMerge {

    /// Later wins; an exact tie is broken on `updatedBy`, compared as a string.
    ///
    /// The tie-break exists so both platforms pick the *same* winner. A non-deterministic one
    /// would let two devices converge to different states, which is worse than losing an edit
    /// because nothing looks wrong.
    static func wins(_ candidate: some SharedRecord, over incumbent: some SharedRecord) -> Bool {
        if candidate.updatedAt != incumbent.updatedAt {
            return candidate.updatedAt > incumbent.updatedAt
        }
        return candidate.updatedBy > incumbent.updatedBy
    }

    public static func merge(_ a: SharedState, _ b: SharedState) -> SharedState {
        let report = wins(b.report, over: a.report) ? b.report : a.report

        var byId: [String: SharedReceipt] = [:]
        for receipt in a.receipts { byId[receipt.id] = receipt }
        for receipt in b.receipts {
            if let existing = byId[receipt.id] {
                // A tombstone is just another record: a delete at a later timestamp beats an
                // edit at an earlier one, and an edit after a delete resurrects deliberately.
                byId[receipt.id] = wins(receipt, over: existing) ? receipt : existing
            } else {
                byId[receipt.id] = receipt
            }
        }

        // Sorted by id so the output is canonical: two devices that merged the same inputs
        // produce the same bytes, which is what the convergence check compares.
        let receipts = byId.values.sorted { $0.id < $1.id }

        var versions = a.versions
        for (device, counter) in b.versions {
            versions[device] = max(versions[device] ?? 0, counter)
        }

        return SharedState(
            sharedVersion: max(a.sharedVersion, b.sharedVersion),
            reportId: a.reportId,
            // Currency follows whichever report record won, so it cannot disagree with the
            // report's other fields.
            currency: wins(b.report, over: a.report) ? b.currency : a.currency,
            versions: versions,
            report: report,
            receipts: receipts)
    }

    /// What the user should see: tombstones are retained for merging but never shown.
    public static func visibleReceipts(_ state: SharedState) -> [SharedReceipt] {
        state.receipts
            .filter { !$0.isDeleted }
            .sorted {
                $0.date != $1.date ? $0.date < $1.date : $0.createdAt < $1.createdAt
            }
    }

    /// Canonical bytes for a state, so two platforms can be compared directly.
    public static func canonicalJSON(_ state: SharedState) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        var canonical = state
        canonical.receipts.sort { $0.id < $1.id }
        return try encoder.encode(canonical)
    }
}
