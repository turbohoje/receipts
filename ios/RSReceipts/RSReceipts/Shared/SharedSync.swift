import Foundation
import RSReceiptsCore
import SwiftData

/// Bridges SwiftData and `SharedState`: reads a report out for publishing, and folds an
/// incoming file back in.
///
/// The merge itself lives in `SharedMerge` in the core and is shared, tested logic. Everything
/// here is translation, so the rule deciding who wins exists in exactly one place.
@MainActor
struct SharedSync {

    let context: ModelContext
    let deviceId: String
    /// Owns the bytes photos are read from and written to.
    let imageStore: ImageStore
    var now: () -> EpochMillis = { Clock.now() }

    /// What a sync actually did, broken down so a surprise is visible rather than silent.
    struct ImportResult {
        let reportName: String
        let isNewReport: Bool
        /// In the file but not here — new to this device.
        let added: Int
        /// Here already, and the file's copy was newer.
        let updated: Int
        /// Identical on both sides; nothing to do.
        let matched: Int
        /// Tombstones applied — deleted by whoever last touched them.
        let deleted: Int
        /// Here but not in the file, so this device is publishing them.
        let published: Int
        /// Photos that arrived in the file and were written here.
        let imagesReceived: Int
        /// Images the file refers to that this device still does not have.
        let missingImages: Int

        var received: Int { added + updated }

        /// One line for a dialog, naming only what actually happened.
        var summary: String {
            var parts: [String] = []
            if added > 0 { parts.append("\(added) added") }
            if updated > 0 { parts.append("\(updated) updated") }
            if matched > 0 { parts.append("\(matched) already matched") }
            if deleted > 0 { parts.append("\(deleted) deleted by others") }
            if published > 0 { parts.append("\(published) sent from here") }
            if imagesReceived > 0 { parts.append("\(imagesReceived) photos received") }
            if missingImages > 0 { parts.append("\(missingImages) photos missing") }
            return parts.isEmpty ? "Already up to date." : parts.joined(separator: ", ") + "."
        }
    }

    enum Failure: LocalizedError {
        case unreadable(String)
        case tooNew(found: Int, supported: Int)

        var errorDescription: String? {
            switch self {
            case .unreadable(let detail):
                return "That file is not a shared report: \(detail)"
            case .tooNew(let found, let supported):
                return "This shared report was written by a newer version of the app "
                    + "(format \(found); this build understands \(supported))."
            }
        }
    }

    private func report(_ id: String) -> StoredReport? {
        let descriptor = FetchDescriptor<StoredReport>(
            predicate: #Predicate { $0.id == id })
        return try? context.fetch(descriptor).first
    }

    private func tombstones(_ reportId: String) -> [StoredTombstone] {
        let descriptor = FetchDescriptor<StoredTombstone>(
            predicate: #Predicate { $0.reportId == reportId })
        return (try? context.fetch(descriptor)) ?? []
    }

    /// The local view of a report, ready to publish. Nil when the report is gone.
    func localState(reportId: String, currency: String) -> SharedState? {
        guard let stored = report(reportId) else { return nil }

        let live = stored.orderedReceipts.map { receipt in
            // The photo rides along, so the file is the whole report. A receipt whose image
            // has gone missing locally is still worth sharing — losing the picture must not
            // cost the line item too.
            let encoded = receipt.imageFile
                .flatMap { imageStore.exists($0) ? imageStore.url(for: $0) : nil }
                .flatMap { try? Data(contentsOf: $0) }
                .map { $0.base64EncodedString() }
            return SharedReceipt(
                id: receipt.id,
                reportId: reportId,
                description: receipt.receiptDescription,
                amountMinor: receipt.amountMinor,
                date: receipt.date,
                imageFile: receipt.imageFile,
                createdAt: receipt.createdAt,
                updatedAt: receipt.updatedAt,
                updatedBy: deviceId,
                imageData: encoded)
        }
        // A receipt that exists cannot also be deleted. Publishing both for one id silently
        // removes it on the other device, because the merge keys by id. Restoring a backup
        // produces exactly this: the row comes back while its tombstone stays behind.
        let liveIds = Set(stored.orderedReceipts.map(\.id))
        for stale in tombstones(reportId) where liveIds.contains(stale.receiptId) {
            context.delete(stale)
        }
        let dead = tombstones(reportId)
            .filter { !liveIds.contains($0.receiptId) }
            .map { tombstone in
            SharedReceipt(
                id: tombstone.receiptId, reportId: reportId, description: "",
                amountMinor: 0, date: 0, imageFile: nil, createdAt: 0,
                updatedAt: tombstone.deletedAt, updatedBy: tombstone.deletedBy,
                deletedAt: tombstone.deletedAt)
        }

        return SharedState(
            reportId: reportId,
            currency: currency,
            versions: [deviceId: 1],
            report: SharedReport(
                id: stored.id, name: stored.name, createdAt: stored.createdAt,
                updatedAt: stored.updatedAt, sortOrder: stored.sortOrder,
                updatedBy: deviceId),
            receipts: live + dead)
    }

    func encode(_ state: SharedState) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        return try encoder.encode(state)
    }

    func decode(_ data: Data) throws -> SharedState {
        let state: SharedState
        do {
            state = try JSONDecoder().decode(SharedState.self, from: data)
        } catch {
            throw Failure.unreadable(error.localizedDescription)
        }
        guard state.sharedVersion <= SharedState.currentVersion else {
            throw Failure.tooNew(
                found: state.sharedVersion, supported: SharedState.currentVersion)
        }
        return state
    }

    /// Merges `incoming` with whatever is here and writes the result back.
    ///
    /// A report this device has never seen is created; one it already has is merged into. The
    /// report is marked shared either way — importing it is the act of opting in.
    @discardableResult
    func importState(_ incoming: SharedState, currency: String) throws -> ImportResult {
        let existing = localState(reportId: incoming.reportId, currency: currency)
        let isNew = existing == nil
        let merged = existing.map { SharedMerge.merge($0, incoming) } ?? incoming

        let stored: StoredReport
        if let found = report(merged.reportId) {
            stored = found
        } else {
            let allReports = (try? context.fetch(FetchDescriptor<StoredReport>())) ?? []
            stored = StoredReport(
                id: merged.reportId, name: merged.report.name,
                createdAt: merged.report.createdAt, updatedAt: merged.report.updatedAt,
                sortOrder: ReportOrder.sortOrderForNew(
                    existingMinimum: allReports.map(\.sortOrder).min()))
            context.insert(stored)
        }
        stored.name = merged.report.name
        stored.updatedAt = merged.report.updatedAt
        stored.isShared = true

        var added = 0
        var updated = 0
        var matched = 0
        var deleted = 0
        var missing = 0
        var imagesReceived = 0
        // Anything here that the file did not carry is being published by this device.
        let incomingIds = Set(incoming.receipts.map(\.id))
        let published = (existing?.receipts ?? [])
            .filter { !$0.isDeleted && !incomingIds.contains($0.id) }
            .count
        let existingTombstones = Dictionary(
            tombstones(merged.reportId).map { ($0.receiptId, $0) }, uniquingKeysWith: { a, _ in a })

        for incomingReceipt in merged.receipts {
            let descriptor = FetchDescriptor<StoredReceipt>(
                predicate: #Predicate { $0.id == incomingReceipt.id })
            let local = try? context.fetch(descriptor).first

            if incomingReceipt.isDeleted {
                // The row goes; its image becomes an orphan and the startup sweep collects it.
                if let local { context.delete(local) }
                if let tombstone = existingTombstones[incomingReceipt.id] {
                    tombstone.deletedAt = incomingReceipt.deletedAt ?? now()
                    tombstone.deletedBy = incomingReceipt.updatedBy
                } else {
                    context.insert(StoredTombstone(
                        receiptId: incomingReceipt.id,
                        reportId: merged.reportId,
                        deletedAt: incomingReceipt.deletedAt ?? now(),
                        deletedBy: incomingReceipt.updatedBy))
                }
                // Only count a deletion that actually removed something here. Re-syncing
                // after this device did the deleting replays its own tombstone, and
                // reporting that as "deleted by others" described the wrong thing happening.
                if local != nil { deleted += 1 }
            } else {
                if let tombstone = existingTombstones[incomingReceipt.id] {
                    context.delete(tombstone)
                }
                let row = local ?? StoredReceipt(
                    id: incomingReceipt.id,
                    description: incomingReceipt.description,
                    amountMinor: incomingReceipt.amountMinor,
                    date: incomingReceipt.date,
                    imageFile: incomingReceipt.imageFile,
                    createdAt: incomingReceipt.createdAt,
                    updatedAt: incomingReceipt.updatedAt,
                    report: stored)
                if local != nil {
                    row.receiptDescription = incomingReceipt.description
                    row.amountMinor = incomingReceipt.amountMinor
                    row.date = incomingReceipt.date
                    row.imageFile = incomingReceipt.imageFile
                    row.updatedAt = incomingReceipt.updatedAt
                    row.report = stored
                } else {
                    context.insert(row)
                }
                // A photo that arrived in the file and is not on this device yet is written
                // under the name the sender used, so both sides agree on the filename.
                if let file = incomingReceipt.imageFile, !imageStore.exists(file) {
                    if let encoded = incomingReceipt.imageData,
                       let bytes = Data(base64Encoded: encoded),
                       (try? bytes.write(to: imageStore.url(for: file), options: .atomic)) != nil {
                        imagesReceived += 1
                    } else {
                        missing += 1
                    }
                }
                if !incomingIds.contains(incomingReceipt.id) {
                    // Only this device has it, so it is already counted as published.
                    // Counting it as matched too described more receipts than the file holds.
                } else if local == nil {
                    added += 1
                } else {
                    // It was in the file too: did the file's copy actually change anything?
                    let before = existing?.receipts.first { $0.id == incomingReceipt.id }
                    if before?.updatedAt == incomingReceipt.updatedAt { matched += 1 }
                    else { updated += 1 }
                }
            }
        }

        try context.save()
        return ImportResult(
            reportName: merged.report.name, isNewReport: isNew,
            added: added, updated: updated, matched: matched, deleted: deleted,
            published: published, imagesReceived: imagesReceived, missingImages: missing)
    }

    /// Forgets every deletion recorded here for one report.
    ///
    /// A tombstone always outranks a row older than it, which is correct — but it means a
    /// tombstone written in error can never be undone by syncing. This is the way out: drop
    /// them, and the next sync accepts whatever the file holds.
    @discardableResult
    func forgetDeletions(reportId: String) -> Int {
        let stale = tombstones(reportId)
        for tombstone in stale { context.delete(tombstone) }
        try? context.save()
        return stale.count
    }

    /// Records a deletion so it can propagate, instead of the row simply vanishing.
    func recordDeletion(receiptId: String, reportId: String) {
        context.insert(StoredTombstone(
            receiptId: receiptId, reportId: reportId,
            deletedAt: now(), deletedBy: deviceId))
    }
}
