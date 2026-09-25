package cc.rocketscience.receipts.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Small enough not to need a repository of its own. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val lastBackupTarget = stringPreferencesKey("last_backup_target")
        val driveFolderId = stringPreferencesKey("drive_folder_id")
        val driveAccount = stringPreferencesKey("drive_account")
    }

    val lastBackupAt: Flow<Long?> =
        context.dataStore.data.map { it[Keys.lastBackupAt] }

    val lastBackupTarget: Flow<String?> =
        context.dataStore.data.map { it[Keys.lastBackupTarget] }

    val driveFolderId: Flow<String?> =
        context.dataStore.data.map { it[Keys.driveFolderId] }

    val driveAccount: Flow<String?> =
        context.dataStore.data.map { it[Keys.driveAccount] }

    suspend fun recordBackup(at: Long, target: String) {
        context.dataStore.edit {
            it[Keys.lastBackupAt] = at
            it[Keys.lastBackupTarget] = target
        }
    }

    suspend fun setDriveFolderId(id: String?) {
        context.dataStore.edit {
            if (id == null) it.remove(Keys.driveFolderId) else it[Keys.driveFolderId] = id
        }
    }

    suspend fun setDriveAccount(account: String?) {
        context.dataStore.edit {
            if (account == null) it.remove(Keys.driveAccount) else it[Keys.driveAccount] = account
        }
    }
}
