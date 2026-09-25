package cc.rocketscience.receipts.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The small amount of ContentResolver work backup and restore need. */
class ContentIo(private val context: Context) {

    private val dir: File
        get() = File(context.cacheDir, "backup").apply { if (!exists()) mkdirs() }

    fun tempFile(name: String): File = File(dir, name)

    /** Anything left in here is from an operation that did not finish. */
    fun clearTemp() {
        dir.listFiles()?.forEach { it.delete() }
    }

    suspend fun copyTo(source: File, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    /**
     * Copies a picked backup into our own cache before reading it.
     *
     * The grant on a picked URI is transient, and the restore path needs to read the archive
     * twice — once to validate the manifest, once to extract images — so the bytes have to be
     * local first.
     */
    suspend fun copyToTemp(source: Uri, name: String): File? = withContext(Dispatchers.IO) {
        val target = tempFile(name)
        runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            target
        }.getOrElse {
            target.delete()
            null
        }
    }
}
