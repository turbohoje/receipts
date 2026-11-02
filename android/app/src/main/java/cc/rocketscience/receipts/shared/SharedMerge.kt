package cc.rocketscience.receipts.shared

/**
 * Merges two views of a shared report into one both devices will agree on.
 *
 * Last-write-wins per **record**, never per file: if the file were the unit, two people
 * editing different receipts would mean one person's work vanished.
 *
 * The merge is commutative, associative and idempotent — `merge(a, b) == merge(b, a)`, and
 * merging the same state twice changes nothing. That is what makes a clobbered upload
 * self-healing: a device that loses a write still holds its own data, and republishes it on
 * its next sync.
 *
 * Must stay identical to `SharedMerge.swift`. The convergence test merges the same inputs on
 * both platforms and compares the results.
 */
object SharedMerge {

    /**
     * Later wins; an exact tie is broken on [SharedRecord.updatedBy], compared as a string.
     *
     * The tie-break exists so both platforms pick the *same* winner. A non-deterministic one
     * would let two devices converge to different states, which is worse than losing an edit
     * because nothing looks wrong.
     */
    internal fun wins(candidate: SharedRecord, over: SharedRecord): Boolean =
        if (candidate.updatedAt != over.updatedAt) {
            candidate.updatedAt > over.updatedAt
        } else {
            candidate.updatedBy > over.updatedBy
        }

    fun merge(a: SharedState, b: SharedState): SharedState {
        val reportFromB = wins(b.report, a.report)
        val report = if (reportFromB) b.report else a.report

        val byId = LinkedHashMap<String, SharedReceipt>()
        for (receipt in a.receipts) byId[receipt.id] = receipt
        for (receipt in b.receipts) {
            val existing = byId[receipt.id]
            byId[receipt.id] = when {
                existing == null -> receipt
                // A tombstone is just another record: a delete at a later timestamp beats an
                // edit at an earlier one, and an edit after a delete resurrects deliberately.
                wins(receipt, existing) -> receipt
                else -> existing
            }
        }

        // Sorted by id so the output is canonical: two devices that merged the same inputs
        // produce the same bytes, which is what the convergence check compares.
        val receipts = byId.values.sortedBy { it.id }

        val versions = HashMap(a.versions)
        for ((device, counter) in b.versions) {
            versions[device] = maxOf(versions[device] ?: 0, counter)
        }

        return SharedState(
            sharedVersion = maxOf(a.sharedVersion, b.sharedVersion),
            reportId = a.reportId,
            // Currency follows whichever report record won, so it cannot disagree with the
            // report's other fields.
            currency = if (reportFromB) b.currency else a.currency,
            versions = versions.toSortedMap(),
            report = report,
            receipts = receipts,
        )
    }

    /** What the user should see: tombstones are retained for merging but never shown. */
    fun visibleReceipts(state: SharedState): List<SharedReceipt> =
        state.receipts
            .filterNot { it.isDeleted }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))
}
