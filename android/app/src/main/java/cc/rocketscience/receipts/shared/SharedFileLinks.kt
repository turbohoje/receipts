package cc.rocketscience.receipts.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import cc.rocketscience.receipts.data.ReportRepository

/**
 * Remembers which file each shared report belongs to.
 *
 * Deliberately **not** in the database. A content URI is meaningless on any other device, so
 * putting it on the report row would carry junk into every backup and every shared file. It is
 * device-local state about where this device keeps its copy, which is what preferences are for
 * — and it saves a fourth schema migration.
 *
 * The grant has to be taken persistably or it dies with the activity, and the next sync would
 * be back to a Save-as dialog.
 */
class SharedFileLinks(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("shared-files", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    fun uriFor(reportId: String): Uri? =
        prefs.getString(reportId, null)?.let(Uri::parse)

    /**
     * The file's own name, asked of the provider.
     *
     * The URI's last path segment is no use: Drive's is an opaque document id like
     * `acc=2;doc=encoded=XFjAky9…`, which is what this used to show.
     */
    fun displayName(reportId: String): String? {
        val uri = uriFor(reportId) ?: return null
        val fromProvider = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
    }

    /**
     * Binds [uri] to [reportId] and keeps read/write access across reboots.
     *
     * Returns false when the provider refused a persistable grant, which some do — the caller
     * should fall back to asking for the file again next time rather than silently failing.
     */
    fun link(reportId: String, uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess
        prefs.edit().putString(reportId, uri.toString()).apply()
        return persisted
    }

    fun unlink(reportId: String) {
        uriFor(reportId)?.let { uri ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(reportId).apply()
    }

    /**
     * The whole location, decoded, for showing when someone asks where a report actually
     * goes. A content URI is opaque by design — this is the most honest thing there is to
     * show, so it is presented as reference text rather than dressed up as a path.
     */
    fun fullLocation(reportId: String): String? =
        uriFor(reportId)?.let { Uri.decode(it.toString()) }

    /** Whether the bound file is still reachable — the grant can be revoked or the file moved. */
    fun isReachable(reportId: String): Boolean {
        val uri = uriFor(reportId) ?: return false
        return runCatching {
            resolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    /**
     * Reads the bound file.
     *
     * The three outcomes are kept apart deliberately: a read that *failed* must never be
     * mistaken for an empty file, or a sync would overwrite a collaborator's work with local
     * state.
     */
    fun read(reportId: String): ReportRepository.SharedFileRead {
        val uri = uriFor(reportId) ?: return ReportRepository.SharedFileRead.Absent
        return runCatching {
            val text = resolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
            when {
                text == null -> ReportRepository.SharedFileRead.Failed("the file could not be opened")
                text.isBlank() -> ReportRepository.SharedFileRead.Absent
                else -> ReportRepository.SharedFileRead.Contents(text)
            }
        }.getOrElse {
            ReportRepository.SharedFileRead.Failed(it.message ?: "the file could not be read")
        }
    }

    /** Overwrites the bound file in place. */
    fun write(reportId: String, json: String): Boolean {
        val uri = uriFor(reportId) ?: return false
        return runCatching {
            // "wt" truncates. Without it a shorter document leaves the tail of the old one
            // behind, and the result is a file that parses as neither.
            resolver.openOutputStream(uri, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        }.getOrDefault(false)
    }

    /** Every report this device has a file for — what "check on open" iterates. */
    fun linkedReportIds(): Set<String> = prefs.all.keys
}
