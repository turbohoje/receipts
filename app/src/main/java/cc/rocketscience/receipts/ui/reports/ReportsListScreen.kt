package cc.rocketscience.receipts.ui.reports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.rocketscience.receipts.data.ReportSummary
import cc.rocketscience.receipts.money.Money
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.TextPromptDialog
import cc.rocketscience.receipts.ui.formatDateRange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsListScreen(
    factory: ViewModelProvider.Factory,
    onOpenReport: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ReportsListViewModel = viewModel(factory = factory)
    val reports by vm.reports.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ReportSummary?>(null) }
    var deleting by remember { mutableStateOf<ReportSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New report")
            }
        },
    ) { padding ->
        if (reports.isEmpty()) {
            EmptyReports(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(reports, key = { it.id }) { report ->
                    ReportCard(
                        report = report,
                        currencyText = Money.format(report.totalMinor, vm.currency),
                        onClick = { onOpenReport(report.id) },
                        onRename = { renaming = report },
                        onDelete = { deleting = report },
                    )
                }
            }
        }
    }

    if (showCreate) {
        TextPromptDialog(
            title = "New report",
            label = "Report name",
            confirmLabel = "Create",
            onConfirm = { vm.createReport(it) },
            onDismiss = { showCreate = false },
        )
    }

    renaming?.let { report ->
        TextPromptDialog(
            title = "Rename report",
            label = "Report name",
            initial = report.name,
            onConfirm = { vm.renameReport(report.id, it) },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { report ->
        ConfirmDialog(
            title = "Delete report?",
            body = buildString {
                append("Delete \"${report.name}\"")
                if (report.receiptCount > 0) {
                    append(" and its ${report.receiptCount} ")
                    append(if (report.receiptCount == 1) "receipt" else "receipts")
                }
                append("? This cannot be undone.")
            },
            confirmLabel = "Delete",
            onConfirm = { vm.deleteReport(report.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun EmptyReports(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("No reports yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(4.dp))
            Text(
                "Tap + to start one, then add the receipts that belong to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportCard(
    report: ReportSummary,
    currencyText: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        report.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitle = listOfNotNull(
                        "${report.receiptCount} " +
                            if (report.receiptCount == 1) "receipt" else "receipts",
                        formatDateRange(report.firstDate, report.lastDate),
                    ).joinToString(" · ")
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(currencyText, style = MaterialTheme.typography.titleMedium)
            }

            // Anchored to the card; opened by the long-press above.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
