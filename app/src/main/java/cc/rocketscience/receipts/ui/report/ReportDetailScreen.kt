package cc.rocketscience.receipts.ui.report

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.money.Money
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.TextPromptDialog
import cc.rocketscience.receipts.ui.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onAddReceipt: (String) -> Unit,
    onOpenReceipt: (String, String) -> Unit,
) {
    val vm: ReportDetailViewModel = viewModel(factory = factory)
    val report by vm.report.collectAsState()
    val receipts by vm.receipts.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deletingReport by remember { mutableStateOf(false) }
    var deletingReceipt by remember { mutableStateOf<Receipt?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(report?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // Export lands in phase 3.
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete report") },
                            onClick = { menuOpen = false; deletingReport = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddReceipt(vm.reportId) }) {
                Icon(Icons.Filled.Add, contentDescription = "Add receipt")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TotalCard(
                    total = Money.format(report?.totalMinor ?: 0L, vm.currency),
                    count = report?.receiptCount ?: 0,
                )
            }

            if (receipts.isEmpty()) {
                item {
                    Text(
                        "No receipts yet. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(receipts, key = { it.id }) { receipt ->
                    ReceiptRow(
                        receipt = receipt,
                        amountText = Money.format(receipt.amountMinor, vm.currency),
                        onClick = { onOpenReceipt(vm.reportId, receipt.id) },
                        onLongClick = { deletingReceipt = receipt },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (renaming) {
        TextPromptDialog(
            title = "Rename report",
            label = "Report name",
            initial = report?.name.orEmpty(),
            onConfirm = { vm.renameReport(it) },
            onDismiss = { renaming = false },
        )
    }

    if (deletingReport) {
        val count = report?.receiptCount ?: 0
        ConfirmDialog(
            title = "Delete report?",
            body = buildString {
                append("Delete \"${report?.name.orEmpty()}\"")
                if (count > 0) {
                    append(" and its $count ")
                    append(if (count == 1) "receipt" else "receipts")
                }
                append("? This cannot be undone.")
            },
            confirmLabel = "Delete",
            // Leave the screen first: its report is about to stop existing.
            onConfirm = { vm.deleteReport(); onBack() },
            onDismiss = { deletingReport = false },
        )
    }

    deletingReceipt?.let { receipt ->
        ConfirmDialog(
            title = "Delete receipt?",
            body = "Delete \"${receipt.description}\"? This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteReceipt(receipt.id) },
            onDismiss = { deletingReceipt = null },
        )
    }
}

@Composable
private fun TotalCard(total: String, count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                total,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "$count ${if (count == 1) "receipt" else "receipts"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReceiptRow(
    receipt: Receipt,
    amountText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    receipt.description.ifBlank { "(no description)" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatDate(receipt.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(amountText, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
