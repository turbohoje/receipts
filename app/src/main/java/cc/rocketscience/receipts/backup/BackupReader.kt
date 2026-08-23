package cc.rocketscience.receipts.backup

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a backup ZIP in a single pass, extracting images into [imageTarget] as it goes.
 *
 * The manifest is validated before any image is written, so a refused restore leaves nothing
 * behind.
 */
class BackupReader(private val json: Json = BackupJson) {

    fun peek(input: InputStream): RestoreOutcome {
        var manifest: BackupManifest? = null
        val images = mutableSetOf<String>()

        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == BackupFormat.MANIFEST_ENTRY ->
                            manifest = json.decodeFromString(zip.readBytes().decodeToString())
                        entry.name.startsWith(BackupFormat.IMAGE_PREFIX) && !entry.isDirectory ->
                            images += entry.name.removePrefix(BackupFormat.IMAGE_PREFIX)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return RestoreOutcome.Refused(
                RestoreProblem.Unreadable(e.message ?: e::class.simpleName ?: "unreadable")
            )
        }

        val found = manifest ?: return RestoreOutcome.Refused(RestoreProblem.NoManifest)
        if (found.schemaVersion > BackupFormat.SCHEMA_VERSION) {
            return RestoreOutcome.Refused(
                RestoreProblem.TooNew(found.schemaVersion, BackupFormat.SCHEMA_VERSION)
            )
        }
        return RestoreOutcome.Ready(found, images)
    }

    /**
     * Extracts the archive's images into [targetDir], returning how many landed.
     *
     * Entry names are taken as bare filenames: a ZIP is untrusted input, and an entry called
     * `images/../../databases/receipts.db` must not be able to write outside the target.
     */
    fun extractImages(input: InputStream, targetDir: File): Int {
        if (!targetDir.exists()) targetDir.mkdirs()
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.startsWith(BackupFormat.IMAGE_PREFIX)) {
                    val safeName = File(entry.name).name
                    if (safeName.isNotEmpty() && safeName != "." && safeName != "..") {
                        File(targetDir, safeName).outputStream().use { zip.copyTo(it) }
                        count++
                    }
                }
                zip.closeEntry()
            }
        }
        return count
    }
}
