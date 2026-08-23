package cc.rocketscience.receipts.backup

import android.content.Context

/**
 * The app's own version string, for stamping into a backup manifest.
 *
 * This used to also expose the signing fingerprint for an in-app Drive setup screen. That
 * screen is gone — registering an OAuth client is a one-off developer task, not something to
 * put in front of every user — so the fingerprints now live in docs/google-drive-setup.md
 * along with the command that prints them.
 */
object SigningInfo {

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

}
