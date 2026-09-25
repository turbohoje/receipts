package cc.rocketscience.receipts.data

import java.io.File

/**
 * Owns `filesDir/images/`. Room's CASCADE deletes rows in SQLite and knows nothing about
 * files on disk, so file lifecycle is handled here and reconciled by [sweepOrphans].
 */
class ImageStore(private val dir: File) {

    fun ensureDir(): File = dir.apply { if (!exists()) mkdirs() }

    /**
     * Images are named by their own id, not the receipt's. That way "replace photo" writes a
     * new file and only deletes the old one after the swap has been committed, instead of
     * overwriting the single copy in place and losing it if the write fails. It also stops
     * an image cache from serving the previous photo for a path that was reused.
     */
    fun newImageFileName(): String = "${java.util.UUID.randomUUID()}.jpg"

    fun file(fileName: String): File = File(ensureDir(), fileName)

    fun exists(fileName: String?): Boolean =
        fileName != null && file(fileName).isFile

    fun delete(fileName: String?): Boolean {
        if (fileName == null) return false
        return file(fileName).delete()
    }

    fun deleteAll(fileNames: Collection<String>) {
        fileNames.forEach { delete(it) }
    }

    /**
     * Deletes any image with no receipt row pointing at it and returns how many went.
     *
     * Runs at app start because a delete is two steps (rows, then files) and the process
     * can die between them. Without this, interrupted deletes would leak disk forever.
     */
    fun sweepOrphans(referenced: Set<String>): Int {
        val files = ensureDir().listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.isFile && f.name !in referenced && f.delete()) removed++
        }
        return removed
    }
}
