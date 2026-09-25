package cc.rocketscience.receipts.export

import java.io.File

/**
 * One line item, already resolved and numbered. The [index] is 1-based and is the single
 * source of ordering shared by the CSV rows, the zipped image filenames and the PDF's
 * per-image pages, so a reviewer can tie any image back to the row it belongs to.
 */
data class ExportRow(
    val index: Int,
    val date: Long,
    val description: String,
    val amountMinor: Long,
    val image: File?,
)

/**
 * Filename-safe version of arbitrary user text.
 *
 * Report names and descriptions become filenames, so anything that would break a path, a ZIP
 * entry or a shell has to go — including the slashes and dots that would let a name escape
 * its directory.
 */
fun slugify(text: String, max: Int = 40, fallback: String = "receipt"): String {
    val slug = text
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(max)
        .trim('-')
    return slug.ifEmpty { fallback }
}
