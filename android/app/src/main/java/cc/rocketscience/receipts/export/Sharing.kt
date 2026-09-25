package cc.rocketscience.receipts.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a generated file to the Android share sheet.
 *
 * Exports leave app-internal storage only through a FileProvider grant, which is scoped to the
 * single file and to whichever app the user picks.
 */
fun shareExport(context: Context, file: File, mimeType: String, subject: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share $subject"))
}
