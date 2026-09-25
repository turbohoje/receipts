package cc.rocketscience.receipts.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The running build's own identity.
 *
 * There is no setup screen showing this to every user — registering an OAuth client is a
 * one-off developer task, and the walkthrough lives in docs/google-drive-setup.md. But a Drive
 * client is registered against exactly one (package name, signing SHA-1) pair, and a mismatch
 * is the most common reason authorization fails, so the diagnostic behind "More info" reports
 * what this build actually is.
 */
object SigningInfo {

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    fun packageName(context: Context): String = context.packageName

    /** The build number Play increments; distinct from the human-facing version name. */
    @Suppress("DEPRECATION")
    fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    /** True for a debug-signed build, which is registered under a different SHA-1. */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Every SHA-1 this build is signed with.
     *
     * Usually one, and that one is the app's identity as far as the platform is concerned. Do
     * not read the APK file and choose between certificates: on a Play build the value to
     * register comes from Play Console, not from here (see docs/google-drive-setup.md).
     */
    @Suppress("DEPRECATION")
    fun signingSha1s(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty()
        }
        certificates.filterNotNull().map { certificate ->
            MessageDigest.getInstance("SHA-1")
                .digest(certificate.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        }
    }.getOrDefault(emptyList())

    /** True when Play re-signed this build, so the fingerprint is Google's, not the developer's. */
    @Suppress("DEPRECATION")
    fun installedFromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(context.packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)
}
