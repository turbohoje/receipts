package cc.rocketscience.receipts.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The state of one shared report, as it exists in the shared file.
 *
 * Deliberately *not* the backup format. A backup is a full-replace snapshot; this is a merge
 * source, so every record carries the metadata a merge needs — when it changed, which device
 * changed it, and whether it was deleted.
 *
 * Photos travel **inside** this file, base64-encoded on each receipt. One file is the whole
 * report, which is what lets it be handed over by any means at all — Drive, mail, AirDrop —
 * with nothing to go missing alongside it. The cost is size: base64 inflates a JPEG by about
 * a third, so a twenty-photo report is a file of some megabytes.
 *
 * Field names and semantics must match `SharedState.swift` exactly; `SharedMergeTest` and the
 * iOS `sharedChecks` merge the same fixtures and compare the results.
 */
@Serializable
data class SharedState(
    val sharedVersion: Int = CURRENT_VERSION,
    val reportId: String,
    /**
     * Carried explicitly. Amounts are bare minor units, so without this a shared report reads
     * as $24.50 to one person and €24.50 to another.
     */
    val currency: String,
    /**
     * `deviceId -> counter`. Lets a device notice the file is missing its own latest writes
     * and rewrite immediately, rather than waiting for its next natural sync.
     */
    val versions: Map<String, Int>,
    val report: SharedReport,
    val receipts: List<SharedReceipt>,
) {
    companion object {
        /** Versioned separately from the backup format: the two evolve for different reasons. */
        const val CURRENT_VERSION = 1
    }
}

/** Anything the merge can choose between carries these two fields. */
interface SharedRecord {
    val updatedAt: Long

    /**
     * The device that last wrote this record. Also the tie-break when two edits share a
     * millisecond — compared as a string so both platforms always pick the same winner.
     */
    val updatedBy: String
}

@Serializable
data class SharedReport(
    val id: String,
    val name: String,
    val createdAt: Long,
    override val updatedAt: Long,
    val sortOrder: Long,
    override val updatedBy: String,
) : SharedRecord

@Serializable
data class SharedReceipt(
    val id: String,
    val reportId: String,
    val description: String,
    val amountMinor: Long,
    val date: Long,
    val imageFile: String? = null,
    val createdAt: Long,
    override val updatedAt: Long,
    override val updatedBy: String,
    /**
     * A tombstone. Absence has no timestamp, so a delete has to be a record with one, or it
     * loses every merge and the receipt resurrects forever.
     */
    val deletedAt: Long? = null,
    /**
     * The photo itself, base64 JPEG, so the file is self-contained.
     *
     * Optional and defaulted: a file written before photos travelled still decodes, and a
     * receipt whose image has gone missing locally is still worth sharing.
     */
    val imageData: String? = null,
) : SharedRecord {
    val isDeleted: Boolean get() = deletedAt != null
}

/** Explicit nulls, matching the backup manifest's convention. */
val SharedJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}
