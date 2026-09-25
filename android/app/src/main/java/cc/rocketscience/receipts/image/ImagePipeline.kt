package cc.rocketscience.receipts.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cc.rocketscience.receipts.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The one path every receipt image takes, whether it came from the camera or the photo picker.
 * Downstream — crop UI, storage, exports, backup — the two are indistinguishable.
 */
class ImagePipeline(
    private val context: Context,
    private val imageStore: ImageStore,
) {

    private val tempDir: File
        get() = File(context.cacheDir, "capture").apply { if (!exists()) mkdirs() }

    fun newTempFile(): File = File(tempDir, "${UUID.randomUUID()}.jpg")

    fun tempFile(name: String): File = File(tempDir, name)

    /**
     * Copies a picked `content://` image into our own cache.
     *
     * The read grant on a picked URI is transient — it does not survive a process restart —
     * so the bytes have to be taken now rather than the URI held onto.
     */
    suspend fun importFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val target = newTempFile()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target.name
        }.getOrElse {
            target.delete()
            null
        }
    }

    /**
     * Decodes a temp image ready for cropping: downscaled to [MAX_EDGE] and with EXIF
     * rotation already applied.
     *
     * Rotation is baked into the pixels and the tag dropped, rather than carried along — both
     * camera photos and gallery images routinely arrive rotated, and any consumer that ignores
     * the tag (a PDF renderer, say) would otherwise show the receipt on its side.
     */
    suspend fun loadNormalized(tempName: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = tempFile(tempName)
        if (!file.isFile) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            },
        ) ?: return@withContext null

        val rotation = runCatching {
            file.inputStream().use { ExifInterface(it) }
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotated = rotate(decoded, rotation)
        if (rotated !== decoded) decoded.recycle()
        val scaled = scaleDown(rotated, MAX_EDGE)
        if (scaled !== rotated) rotated.recycle()
        scaled
    }

    /**
     * Crops [source] to [crop] (normalised 0..1 of the bitmap), writes it into the image store
     * and returns the stored filename. The temp file is removed on success.
     */
    suspend fun writeCropped(
        source: Bitmap,
        crop: NormalizedRect,
        tempName: String?,
    ): String? = withContext(Dispatchers.IO) {
        val left = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (crop.right * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (crop.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)

        val cropped = runCatching {
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        }.getOrNull() ?: return@withContext null

        val scaled = scaleDown(cropped, MAX_EDGE)
        if (scaled !== cropped) cropped.recycle()
        val fileName = imageStore.newImageFileName()
        val out = imageStore.file(fileName)

        val ok = runCatching {
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        }.isSuccess

        if (!ok) {
            out.delete()
            return@withContext null
        }
        tempName?.let { tempFile(it).delete() }
        fileName
    }

    /** The stored file, or null when the row references an image that is no longer on disk. */
    fun imageFileOrNull(fileName: String): File? =
        imageStore.file(fileName).takeIf { it.isFile }

    fun discardTemp(tempName: String?) {
        tempName?.let { tempFile(it).delete() }
    }

    /** Called at app start: anything left in here is from a flow that was abandoned. */
    fun clearTempDir() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / longest
        val w = (bitmap.width * factor).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * factor).roundToInt().coerceAtLeast(1)
        return runCatching { bitmap.scale(w, h) }.getOrDefault(bitmap)
    }

    private fun Bitmap.scale(width: Int, height: Int): Bitmap =
        Bitmap.createScaledBitmap(this, width, height, true)

    companion object {
        /** Long edge cap. Keeps a 40-receipt backup ZIP a reasonable size to move around. */
        const val MAX_EDGE = 2048
        const val QUALITY = 85

        fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
            return sample
        }
    }
}

/** Crop rectangle in 0..1 coordinates, so it is independent of display and bitmap size. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        val Full = NormalizedRect(0f, 0f, 1f, 1f)
        /** Starts clear of the image edge so the corner handles are easy to grab. */
        val Inset = NormalizedRect(0.08f, 0.08f, 0.92f, 0.92f)
    }
}
