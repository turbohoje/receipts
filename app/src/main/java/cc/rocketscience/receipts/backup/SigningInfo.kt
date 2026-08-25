package cc.rocketscience.receipts.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The running build's own identity.
 *
 * There is no longer a setup screen showing this to every user — registering an OAuth client
 * is a one-off developer task, and the walkthrough lives in docs/google-drive-setup.md. But a
 * Drive client is registered against exactly one (package name, signing SHA-1) pair, and a
 * mismatch is the single most common reason authorization fails, so the diagnostic behind
 * "More info" reports what this build actually is.
 */
object SigningInfo {

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    fun packageName(context: Context): String = context.packageName

    /** True for a debug-signed build, which is registered under a different SHA-1. */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Colon-separated uppercase SHA-1, the format the Cloud Console expects. */
    fun signingSha1(context: Context): String? = digest(context, "SHA-1")

    private fun digest(context: Context, algorithm: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance(algorithm)
            .digest(certificate.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
