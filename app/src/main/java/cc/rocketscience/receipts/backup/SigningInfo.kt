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

    /** The build number Play increments; distinct from the human-facing version name. */
    fun versionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /** True for a debug-signed build, which is registered under a different SHA-1. */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Every SHA-1 this build is signed with, in APK order.
     *
     * Usually one, and that one is the app's identity as far as the platform and Google are
     * concerned. A Play-signed APK's *file* may hold several certificates — on Android 17 a
     * hybrid classical/post-quantum pair plus a v3.0 block — but do not go reading those and
     * choosing between them: only what the platform reports here is matched against a
     * registered OAuth client.
     */
    fun signingSha1s(context: Context): List<String> = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        signers.map { certificate ->
            MessageDigest.getInstance("SHA-1")
                .digest(certificate.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        }
    }.getOrDefault(emptyList())

    /** True when Play re-signed this build, so the fingerprint is Google's, not the developer's. */
    fun installedFromPlay(context: Context): Boolean = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName)
            .installingPackageName == "com.android.vending"
    }.getOrDefault(false)
}
