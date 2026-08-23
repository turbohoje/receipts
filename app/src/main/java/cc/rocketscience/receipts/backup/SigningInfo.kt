package cc.rocketscience.receipts.backup

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Reads the *running* build's own identity.
 *
 * The Google Cloud OAuth client must be registered against the package name and the SHA-1 of
 * the signing key, and those differ between the debug and release builds. Getting one digit
 * wrong is the single most common way this setup fails, so the app reads its live values and
 * offers them for copying rather than asking anyone to run keytool and transcribe the output.
 */
object SigningInfo {

    fun packageName(context: Context): String = context.packageName

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /** Colon-separated uppercase SHA-1, the format the Cloud Console expects. */
    fun signingSha1(context: Context): String? = digest(context, "SHA-1")

    fun signingSha256(context: Context): String? = digest(context, "SHA-256")

    private fun digest(context: Context, algorithm: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val certificate = signers.firstOrNull() ?: return null
        MessageDigest.getInstance(algorithm)
            .digest(certificate.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
