package cc.rocketscience.receipts.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.rocketscience.receipts.backup.SigningInfo

private const val CONSOLE_URL = "https://console.cloud.google.com/apis/credentials"
private const val DRIVE_API_URL =
    "https://console.cloud.google.com/apis/library/drive.googleapis.com"

/**
 * Walks through the one part of Drive setup that cannot live inside the app.
 *
 * An OAuth client is a *developer* registration binding a client identity to a package name and
 * a signing fingerprint. An app cannot register its own identity — so the Console visit is
 * unavoidable. What the app can do is remove the guesswork: it reads its own live package name
 * and SHA-1 (which differ between debug and release builds) and offers them for copying, then
 * tests the result and names the specific failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveSetupScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val packageName = remember { SigningInfo.packageName(context) }
    val sha1 = remember { SigningInfo.signingSha1(context) }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.onConsentResult(result.data)
        } else {
            vm.onConsentCancelled()
        }
    }
    val launchConsent: (android.app.PendingIntent) -> Unit = { pendingIntent ->
        consent.launch(IntentSenderRequest.Builder(pendingIntent).build())
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up Google Drive") },
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "You don't have to do this",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "\"Back up to a file\" already works with no setup — it writes the same " +
                            "backup ZIP anywhere you choose, Google Drive included. Doing this " +
                            "adds one-tap backup, a list of previous backups, and automatic " +
                            "pruning of old ones.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Step(1, "Create a Google Cloud project and enable the Drive API") {
                OutlinedButton(onClick = { open(DRIVE_API_URL) }) { Text("Open Drive API page") }
            }

            Step(
                2,
                "Configure the OAuth consent screen and add your own Google account as a " +
                    "test user",
            ) {}

            Step(3, "Create an OAuth client: type Android, using these exact values") {
                CopyRow("Package name", packageName, clipboard::setText)
                Spacer(Modifier.height(8.dp))
                if (sha1 != null) {
                    CopyRow("SHA-1 fingerprint", sha1, clipboard::setText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This is the fingerprint of the build you are running right now. " +
                            "Debug and release builds are signed with different keys, so if you " +
                            "later install a release build you will need to add its fingerprint " +
                            "too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Could not read this build's signing certificate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { open(CONSOLE_URL) }) { Text("Open Credentials page") }
            }

            Step(4, "Come back and test it") {
                Button(
                    onClick = { vm.testDriveConnection(launchConsent) },
                    enabled = !vm.busy,
                ) { Text(if (vm.busy) "Testing…" else "Test connection") }
            }

            if (vm.busy) CircularProgressIndicator()

            vm.status?.let {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::clearMessages) { Text("Dismiss") }
                    }
                }
            }
            vm.problem?.let {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = vm::clearMessages) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$number. $title",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: (AnnotatedString) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onCopy(AnnotatedString(value)) }) { Text("Copy") }
        }
    }
}
