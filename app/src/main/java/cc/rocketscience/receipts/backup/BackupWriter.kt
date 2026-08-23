package cc.rocketscience.receipts.backup

import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a backup ZIP. Takes an [OutputStream] and a resolver for image files so it can be
 * exercised without a device.
 */
class BackupWriter(private val json: Json = BackupJson) {

    fun write(
        out: OutputStream,
        manifest: BackupManifest,
        resolveImage: (String) -> File?,
    ): BackupStats {
        var written = 0
        var missing = 0

        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // Distinct, because two receipts could in principle reference one file.
            for (name in manifest.receipts.mapNotNull { it.imageFile }.distinct()) {
                val file = resolveImage(name)
                if (file == null || !file.isFile) {
                    // A row whose image has vanished is still worth backing up; losing the
                    // line item as well would turn one problem into two.
                    missing++
                    continue
                }
                zip.putNextEntry(ZipEntry(BackupFormat.imageEntry(name)))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                written++
            }
        }
        return BackupStats(
            reports = manifest.reports.size,
            receipts = manifest.receipts.size,
            imagesWritten = written,
            imagesMissing = missing,
        )
    }
}

data class BackupStats(
    val reports: Int,
    val receipts: Int,
    val imagesWritten: Int,
    val imagesMissing: Int,
)

/** Lenient on read so a backup from a newer minor version still parses. */
val BackupJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
