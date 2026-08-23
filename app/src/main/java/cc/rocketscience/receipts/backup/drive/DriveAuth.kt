package cc.rocketscience.receipts.backup.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Obtains an OAuth access token for the `drive.file` scope.
 *
 * `drive.file` grants access only to files this app itself created — the rest of the user's
 * Drive stays invisible to it. That is the whole reason for choosing it over a broader scope.
 *
 * Uses AuthorizationClient rather than the deprecated GoogleSignIn: this app needs a scope
 * grant, not an identity, and AuthorizationClient is the supported way to ask for one.
 */
class DriveAuth(private val context: Context) {

    sealed interface Result {
        data class Token(val accessToken: String) : Result

        /** The user has not consented yet; the UI must launch this and try again. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Result

        data class Failed(val message: String, val statusCode: Int? = null) : Result
    }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .build()

    suspend fun authorize(): Result = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                cont.resume(
                    when {
                        result.hasResolution() && pending != null ->
                            Result.NeedsConsent(pending)
                        result.accessToken != null ->
                            Result.Token(result.accessToken!!)
                        else ->
                            Result.Failed("Google returned no access token")
                    }
                )
            }
            .addOnFailureListener { error ->
                cont.resume(
                    Result.Failed(
                        error.message ?: "Authorization failed",
                        (error as? ApiException)?.statusCode,
                    )
                )
            }
    }

    /** Called with the data returned from the consent screen. */
    fun tokenFromConsent(data: Intent?): Result = runCatching {
        val result = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)
        result.accessToken?.let { Result.Token(it) }
            ?: Result.Failed("Consent returned no access token")
    }.getOrElse {
        Result.Failed(it.message ?: "Consent failed", (it as? ApiException)?.statusCode)
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
