package cc.rocketscience.receipts.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.rocketscience.receipts.backup.drive.DriveClient
import cc.rocketscience.receipts.backup.drive.DriveFile
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val lastBackupAt by vm.lastBackupAt.collectAsState()
    val lastTarget by vm.lastBackupTarget.collectAsState()

    var confirmRestore by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Path A: the system picker writes the file wherever the user chooses, Drive included.
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) vm.backupToUri(uri) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) confirmRestore = { vm.restoreFromUri(uri) }
    }

    // Path B: the Drive consent screen comes back through here.
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.onConsentResult(result.data)
        } else {
            vm.onConsentCancelled(result.resultCode, result.data)
        }
    }
    val launchConsent: (android.app.PendingIntent) -> Unit = { pendingIntent ->
        consent.launch(IntentSenderRequest.Builder(pendingIntent).build())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Currency") {
                Text(
                    "${vm.currency.currencyCode} (${vm.currency.symbol})",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Taken from the device's locale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Backup") {
                Text(
                    lastBackupAt?.let { "Last backup: ${formatDate(it)}" +
                        (lastTarget?.let { t -> " · $t" } ?: "") }
                        ?: "No backup yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A backup is a single ZIP holding every report, receipt and image, plus a " +
                        "manifest. Restoring replaces everything currently in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Google Drive") {
                Text(
                    "One-tap backup, a list of previous backups, and automatic pruning to the " +
                        "newest ${DriveClient.MAX_BACKUPS}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.driveBackup(launchConsent) },
                        enabled = !vm.busy,
                    ) { Text("Back up to Drive") }
                    OutlinedButton(
                        onClick = { vm.refreshDriveBackups(launchConsent) },
                        enabled = !vm.busy,
                    ) { Text("List backups") }
                }

                if (vm.driveBackups.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("In Drive", style = MaterialTheme.typography.labelLarge)
                    vm.driveBackups.forEach { file ->
                        DriveRow(
                            file = file,
                            enabled = !vm.busy,
                            onRestore = {
                                confirmRestore = { vm.driveRestore(file, launchConsent) }
                            },
                        )
                    }
                }
            }

            Section("Backup to a file") {
                Text(
                    "Works with no setup at all: the system picker writes the ZIP wherever you " +
                        "choose, including Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { createDocument.launch(vm.suggestedFileName()) },
                        enabled = !vm.busy,
                    ) { Text("Back up to a file") }
                    OutlinedButton(
                        onClick = { openDocument.launch(arrayOf("application/zip")) },
                        enabled = !vm.busy,
                    ) { Text("Restore from a file") }
                }
            }

            vm.status?.let { Notice(message = it, onDismiss = vm::clearMessages) }
            vm.problem?.let {
                Notice(
                    message = it.summary,
                    detail = it.detail,
                    error = true,
                    onDismiss = vm::clearMessages,
                )
            }
        }

        if (vm.busy) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }

    confirmRestore?.let { action ->
        ConfirmDialog(
            title = "Replace everything?",
            body = "Restoring replaces every report, receipt and image currently in the app " +
                "with the contents of that backup. This cannot be undone.",
            confirmLabel = "Restore",
            onConfirm = action,
            onDismiss = { confirmRestore = null },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DriveRow(file: DriveFile, enabled: Boolean, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            val size = file.size?.toLongOrNull()?.let { "%.1f MB".format(it / 1048576.0) }
            Text(
                listOfNotNull(file.createdTime?.take(10), size).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) { Text("Restore") }
    }
}

@Composable
private fun Notice(
    message: String,
    onDismiss: () -> Unit,
    detail: String? = null,
    error: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        val onContainer = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

        Column(Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = onContainer)

            if (detail != null && detail != message) {
                Spacer(Modifier.height(4.dp))
                // Collapsed by default: the summary is what to read, the detail is what to
                // send to someone who can act on it.
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(if (expanded) "Hide details" else "More info")
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }

                AnimatedVisibility(expanded) {
                    Column {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(
                                    AnnotatedString("$message\n\n$detail")
                                )
                            }) { Text("Copy details") }
                        }
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) { Text("Dismiss") }
        }
    }
}
