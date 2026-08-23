package cc.rocketscience.receipts.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The backup file is a ZIP containing `manifest.json` plus `images/<imageId>.jpg`.
 *
 * JSON rather than a platform serialization format on purpose: a backup outlives the app
 * version that wrote it, and its contents should be readable — and repairable — with nothing
 * more than a text editor.
 */
object BackupFormat {

    /**
     * Bump only for changes a reader cannot infer. Restore refuses anything newer than this,
     * rather than importing half of it and leaving the database in a state nobody designed.
     */
    const val SCHEMA_VERSION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val IMAGE_PREFIX = "images/"

    fun imageEntry(fileName: String) = "$IMAGE_PREFIX$fileName"
}

@Serializable
data class BackupManifest(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("appVersion") val appVersion: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("currency") val currency: String,
    @SerialName("reports") val reports: List<BackupReport>,
    @SerialName("receipts") val receipts: List<BackupReceipt>,
)

@Serializable
data class BackupReport(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupReceipt(
    val id: String,
    val reportId: String,
    val description: String,
    val amountMinor: Long,
    val date: Long,
    val imageFile: String? = null,
    val createdAt: Long,
)

/** Why a restore was refused, so the UI can say something specific. */
sealed interface RestoreProblem {
    data class TooNew(val found: Int, val supported: Int) : RestoreProblem
    data object NoManifest : RestoreProblem
    data class Unreadable(val detail: String) : RestoreProblem
}

sealed interface RestoreOutcome {
    data class Ready(
        val manifest: BackupManifest,
        /** Image entries present in the archive, keyed by filename. */
        val imageNames: Set<String>,
    ) : RestoreOutcome

    data class Refused(val problem: RestoreProblem) : RestoreOutcome
}
