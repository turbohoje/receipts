package cc.rocketscience.receipts.backup.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import cc.rocketscience.receipts.backup.SigningInfo
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

/**
 * A failure the user can act on: a one-line [summary], and the raw [detail] behind "More info".
 *
 * The two exist separately because the useful information and the readable information are not
 * the same. "Access was not granted" tells nobody why; a status code and a signing fingerprint
 * are exactly what identify the cause, and are exactly what nobody wants shown by default.
 */
data class DriveDiagnostic(
    val summary: String,
    val detail: String,
)

object DriveDiagnostics {

    /**
     * Explains a consent screen that closed without returning a token.
     *
     * A plain user cancel and a genuine failure both arrive here as a non-OK result code, which
     * is why the previous "Google Drive access was not granted." was misleading: it asserted an
     * intent the app cannot observe. The returned intent often still carries an [ApiException]
     * naming the real cause, so it is parsed before assuming anything.
     */
    fun forConsentResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
    ): DriveDiagnostic {
        val status = statusFrom(context, data)

        val summary = when {
            // The status code for an unregistered app is a generic INTERNAL_ERROR; only the
            // message names the real cause, so match on it before falling back to the code.
            status?.second?.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) == true ->
                "This build is not registered with Google. No OAuth client matches its " +
                    "package name and signing certificate — see \"More info\" for which " +
                    "fingerprint to register."
            status != null -> adviceFor(status.first)
            resultCode == Activity.RESULT_CANCELED && data == null ->
                "The Google sign-in screen was dismissed before access was granted."
            else -> "Google closed the sign-in screen without granting access."
        }

        return DriveDiagnostic(summary, detail(context, resultCode, data, status))
    }

    /** Explains a failure reported by the authorization call itself. */
    fun forAuthFailure(
        context: Context,
        message: String,
        statusCode: Int?,
    ): DriveDiagnostic = DriveDiagnostic(
        summary = statusCode?.let { adviceFor(it) } ?: "Google sign-in failed: $message",
        detail = buildString {
            appendLine("Stage: authorization request")
            appendLine("Message: $message")
            statusCode?.let {
                appendLine("Status code: $it (${statusName(it)})")
            }
            appendLine()
            append(environment(context))
        },
    )

    /** Explains a failure from a Drive REST call, after authorization succeeded. */
    fun forDriveError(context: Context, error: Throwable): DriveDiagnostic {
        val http = (error as? DriveException)?.status
        val summary = when (http) {
            401 -> "Google rejected the access token. Try again; if it persists, revoke the " +
                "app at myaccount.google.com/permissions and re-grant."
            403 -> "Google refused the request. The Drive API may not be enabled on the " +
                "project, or the granted scope is insufficient."
            404 -> "That file no longer exists in Drive."
            in 500..599 -> "Google Drive is having trouble. Try again shortly."
            else -> "Drive request failed: ${error.message}"
        }
        return DriveDiagnostic(
            summary = summary,
            detail = buildString {
                appendLine("Stage: Drive API call")
                http?.let { appendLine("HTTP status: $it") }
                appendLine("Message: ${error.message}")
                appendLine("Type: ${error::class.qualifiedName}")
                appendLine()
                append(environment(context))
            },
        )
    }

    /**
     * Status code plus message, if the returned intent carries one.
     *
     * Google reports the real cause by having the parse throw an [ApiException], so the throw
     * is the signal here rather than an error.
     */
    private fun statusFrom(context: Context, data: Intent?): Pair<Int, String?>? {
        if (data == null) return null
        return try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            null
        } catch (e: ApiException) {
            e.statusCode to e.statusMessage
        } catch (e: Exception) {
            null
        }
    }

    private fun statusName(code: Int): String =
        runCatching { CommonStatusCodes.getStatusCodeString(code) }.getOrDefault("UNKNOWN")

    /**
     * Maps a status code to the thing that actually needs fixing. DEVELOPER_ERROR is the one
     * that matters most: it means Google has no OAuth client matching this build's package
     * name and signing certificate, which no amount of retrying will change.
     */
    private fun adviceFor(code: Int): String = when (code) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google does not recognise this build (DEVELOPER_ERROR). No OAuth client is " +
                "registered for this app's package name and signing fingerprint — see " +
                "\"More info\" for the exact values this build is using."
        CommonStatusCodes.CANCELED ->
            "The sign-in screen was cancelled."
        CommonStatusCodes.NETWORK_ERROR ->
            "No network connection reached Google."
        CommonStatusCodes.SIGN_IN_REQUIRED ->
            "No Google account is signed in on this device for this app."
        CommonStatusCodes.INVALID_ACCOUNT ->
            "That Google account cannot be used here."
        CommonStatusCodes.API_NOT_CONNECTED ->
            "Google Play services is unavailable or out of date on this device."
        CommonStatusCodes.INTERNAL_ERROR ->
            // Google reports an unregistered app as INTERNAL_ERROR with the real cause only
            // in the status message, which reads as a Google fault when it is a registration
            // problem. The caller checks the message and overrides this where it can.
            "Google Play services reported an internal error."
        else ->
            "Google sign-in failed with status ${statusName(code)} ($code)."
    }

    private fun detail(
        context: Context,
        resultCode: Int,
        data: Intent?,
        status: Pair<Int, String?>?,
    ): String = buildString {
        appendLine("Stage: consent screen result")
        appendLine("Result code: $resultCode (${resultCodeName(resultCode)})")
        appendLine("Returned intent: ${if (data == null) "none" else "present"}")
        data?.extras?.keySet()?.takeIf { it.isNotEmpty() }?.let {
            appendLine("Intent extras: ${it.joinToString(", ")}")
        }
        if (status != null) {
            appendLine("Status code: ${status.first} (${statusName(status.first)})")
            status.second?.let { appendLine("Status message: $it") }
        } else {
            appendLine("Status code: none reported by Google")
        }
        appendLine()
        append(environment(context))
    }

    private fun resultCodeName(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK"
        Activity.RESULT_CANCELED -> "RESULT_CANCELED"
        else -> "custom"
    }

    /**
     * The build's own identity. This is what a registration is matched against, so it is the
     * first thing to compare with the Cloud Console when authorization fails.
     */
    private fun environment(context: Context): String = buildString {
        appendLine("Package: ${SigningInfo.packageName(context)}")
        val fromPlay = SigningInfo.installedFromPlay(context)
        appendLine(
            "Build: ${if (SigningInfo.isDebuggable(context)) "debug" else "release"} " +
                "(${if (fromPlay) "Google Play" else "sideloaded"})"
        )
        appendLine("Version: ${SigningInfo.versionName(context)}")

        val fingerprints = SigningInfo.signingSha1s(context)
        if (fingerprints.isEmpty()) {
            appendLine("Signing SHA-1: unreadable")
        } else {
            appendLine("Signing SHA-1 reported by Android:")
            fingerprints.forEach { appendLine("  $it") }
        }

        if (fromPlay) {
            // Learned the hard way: on Android 17 a Play-signed APK carries a hybrid
            // classical/post-quantum pair plus a v3.0 block, PackageManager reports the
            // post-quantum certificate, and Google's OAuth check matches the v3.0 one. The
            // app therefore cannot see the value it needs, and saying "register what is
            // printed above" sends the reader to a fingerprint that never matches.
            appendLine(
                "Play re-signs uploads, and on Android 17 the certificate above is NOT the " +
                    "one to register. Use Play Console -> Test and release -> Setup -> App " +
                    "integrity -> App signing key certificate (SHA-1). That is a different " +
                    "value from the Upload key certificate shown beside it."
            )
        } else {
            appendLine("Register the fingerprint above against this package name.")
        }
        appendLine("Scope: ${DriveAuth.DRIVE_FILE_SCOPE}")
        append("A Drive OAuth client matches exactly one package + SHA-1 pair.")
    }
}
