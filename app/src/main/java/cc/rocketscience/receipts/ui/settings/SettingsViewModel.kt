package cc.rocketscience.receipts.ui.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.rocketscience.receipts.backup.BackupManager
import cc.rocketscience.receipts.backup.ContentIo
import cc.rocketscience.receipts.backup.RestoreProblem
import cc.rocketscience.receipts.backup.RestoreResult
import cc.rocketscience.receipts.backup.drive.DriveAuth
import cc.rocketscience.receipts.backup.drive.DriveDiagnostic
import cc.rocketscience.receipts.backup.drive.DriveDiagnostics
import cc.rocketscience.receipts.backup.drive.DriveClient
import cc.rocketscience.receipts.backup.drive.DriveException
import cc.rocketscience.receipts.backup.drive.DriveFile
import cc.rocketscience.receipts.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Currency

class SettingsViewModel(
    private val context: Context,
    private val backups: BackupManager,
    private val settings: SettingsStore,
    private val contentIo: ContentIo,
    private val driveAuth: DriveAuth,
    private val driveClient: DriveClient,
    val currency: Currency,
) : ViewModel() {

    val lastBackupAt: StateFlow<Long?> = settings.lastBackupAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastBackupTarget: StateFlow<String?> = settings.lastBackupTarget
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<DriveDiagnostic?>(null)
        private set
    var driveBackups by mutableStateOf<List<DriveFile>>(emptyList())
        private set
    var driveReady by mutableStateOf(false)
        private set

    fun clearMessages() { status = null; problem = null }

    private fun fail(summary: String, detail: String? = null) {
        problem = DriveDiagnostic(summary, detail ?: summary)
    }

    /** Suggested filename for the file-picker path. */
    fun suggestedFileName(): String = backups.fileName()

    // ---------------------------------------------------------------- path A

    fun backupToUri(target: Uri) = run {
        if (busy) return@run
        busy = true
        viewModelScope.launch {
            val staged = contentIo.tempFile(backups.fileName())
            val result = runCatching {
                val stats = backups.writeTo(staged)
                if (!contentIo.copyTo(staged, target)) error("could not write to that location")
                stats
            }
            staged.delete()
            result.fold(
                onSuccess = { stats ->
                    settings.recordBackup(System.currentTimeMillis(), "File")
                    status = "Backed up ${stats.reports} report(s), ${stats.receipts} " +
                        "receipt(s) and ${stats.imagesWritten} image(s)." +
                        if (stats.imagesMissing > 0) {
                            " ${stats.imagesMissing} image(s) were already missing."
                        } else {
                            ""
                        }
                },
                onFailure = {
                    fail(
                        "Backup failed: ${it.message}",
                        buildString {
                            appendLine("Stage: building or writing the backup file")
                            appendLine("Message: ${it.message}")
                            append("Type: ${it::class.qualifiedName}")
                        },
                    )
                },
            )
            busy = false
        }
    }

    fun restoreFromUri(source: Uri) = run {
        if (busy) return@run
        busy = true
        viewModelScope.launch {
            val local = contentIo.copyToTemp(source, "restore.zip")
            if (local == null) {
                fail(
                    "Could not read that file.",
                    "Stage: copying the chosen file into app storage\n" +
                        "The picker returned a location this app could not open.",
                )
                busy = false
                return@launch
            }
            applyRestore(local)
            local.delete()
            busy = false
        }
    }

    private suspend fun applyRestore(file: java.io.File) {
        when (val result = backups.restore(file)) {
            is RestoreResult.Success -> status = with(result.summary) {
                "Restored $reports report(s), $receipts receipt(s) and $images image(s)."
            }
            is RestoreResult.Refused -> fail(describe(result.problem), "Stage: validating the backup\n${result.problem}")
            is RestoreResult.Failed -> fail("Restore failed: ${result.detail}", "Stage: applying the backup\n${result.detail}")
        }
    }

    private fun describe(problem: RestoreProblem): String = when (problem) {
        is RestoreProblem.TooNew ->
            "That backup was written by a newer version of RS Receipts " +
                "(format ${problem.found}, this app understands ${problem.supported}). " +
                "Update the app and try again."
        RestoreProblem.NoManifest ->
            "That file is not an RS Receipts backup — it has no manifest."
        is RestoreProblem.Unreadable ->
            "That backup could not be read: ${problem.detail}"
    }

    // ---------------------------------------------------------------- path B

    /** Set while waiting on the consent screen, so the action can resume afterwards. */
    private var pending: (suspend (String) -> Unit)? = null

    fun driveBackup(launchConsent: (PendingIntent) -> Unit) =
        withDriveToken(launchConsent) { token ->
            val folderId = driveClient.ensureFolder(token)
            settings.setDriveFolderId(folderId)
            val staged = contentIo.tempFile(backups.fileName())
            try {
                val stats = backups.writeTo(staged)
                val uploaded = driveClient.upload(token, folderId, staged, staged.name)
                val pruned = driveClient.prune(token, folderId)
                settings.recordBackup(System.currentTimeMillis(), "Google Drive")
                status = "Uploaded ${uploaded.name} — ${stats.receipts} receipt(s), " +
                    "${stats.imagesWritten} image(s)." +
                    if (pruned > 0) " Removed $pruned old backup(s)." else ""
                driveBackups = driveClient.listBackups(token, folderId)
            } finally {
                staged.delete()
            }
        }

    fun refreshDriveBackups(launchConsent: (PendingIntent) -> Unit) =
        withDriveToken(launchConsent) { token ->
            val folderId = driveClient.ensureFolder(token)
            settings.setDriveFolderId(folderId)
            driveBackups = driveClient.listBackups(token, folderId)
            driveReady = true
            if (driveBackups.isEmpty()) status = "Connected. No backups in Drive yet."
        }

    fun driveRestore(file: DriveFile, launchConsent: (PendingIntent) -> Unit) =
        withDriveToken(launchConsent) { token ->
            val local = contentIo.tempFile("drive-restore.zip")
            try {
                driveClient.download(token, file.id, local)
                applyRestore(local)
            } finally {
                local.delete()
            }
        }

    fun testDriveConnection(launchConsent: (PendingIntent) -> Unit) =
        withDriveToken(launchConsent) { token ->
            val folderId = driveClient.ensureFolder(token)
            settings.setDriveFolderId(folderId)
            driveReady = true
            status = "Connected. The \"${DriveClient.FOLDER_NAME}\" folder is ready."
        }

    /**
     * Runs [action] with an access token, routing through the consent screen when needed.
     *
     * Drive failures are reported with Google's own message rather than a generic one: an
     * unenabled API, a missing OAuth client and an unlisted test user all fail here, and each
     * needs a different fix.
     */
    private fun withDriveToken(
        launchConsent: (PendingIntent) -> Unit,
        action: suspend (String) -> Unit,
    ) = run {
        if (busy) return@run
        busy = true
        clearMessages()
        viewModelScope.launch {
            when (val auth = driveAuth.authorize()) {
                is DriveAuth.Result.Token -> runDrive(auth.accessToken, action)
                is DriveAuth.Result.NeedsConsent -> {
                    pending = action
                    launchConsent(auth.pendingIntent)
                    // busy stays true until the consent result comes back.
                }
                is DriveAuth.Result.Failed -> {
                    problem = DriveDiagnostics.forAuthFailure(context, auth.message, auth.statusCode)
                    busy = false
                }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        val action = pending
        pending = null
        viewModelScope.launch {
            when (val auth = driveAuth.tokenFromConsent(data)) {
                is DriveAuth.Result.Token ->
                    if (action != null) runDrive(auth.accessToken, action) else busy = false
                is DriveAuth.Result.Failed -> {
                    problem = DriveDiagnostics.forAuthFailure(context, auth.message, auth.statusCode)
                    busy = false
                }
                is DriveAuth.Result.NeedsConsent -> {
                    fail(
                        "Google asked for consent again; try once more.",
                        "Stage: consent result\nGoogle returned another consent request " +
                            "instead of a token.",
                    )
                    busy = false
                }
            }
        }
    }

    /**
     * The consent screen closed without a token.
     *
     * This used to assert "Google Drive access was not granted", which claimed an intent the
     * app cannot observe: a real failure — a build Google has no OAuth client for, most often —
     * arrives here identically to someone tapping Back. The result is now parsed instead.
     */
    fun onConsentCancelled(resultCode: Int, data: Intent?) {
        pending = null
        busy = false
        problem = DriveDiagnostics.forConsentResult(context, resultCode, data)
    }

    private suspend fun runDrive(token: String, action: suspend (String) -> Unit) {
        runCatching { action(token) }.onFailure { error ->
            problem = DriveDiagnostics.forDriveError(context, error)
        }
        busy = false
    }

}
