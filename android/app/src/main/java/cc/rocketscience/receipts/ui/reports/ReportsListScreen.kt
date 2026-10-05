package cc.rocketscience.receipts.ui.reports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.rocketscience.receipts.R
import cc.rocketscience.receipts.data.ReportSummary
import cc.rocketscience.receipts.money.Money
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.TextPromptDialog
import cc.rocketscience.receipts.ui.formatDateRange

/**
 * Row height while reordering. Fixed, and shared by the card and the drag maths, because the
 * drop target is computed as a multiple of it.
 */
private val REORDER_ROW_HEIGHT = 88.dp

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
    var reordering by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ReportSummary?>(null) }
    var deleting by remember { mutableStateOf<ReportSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_brand_mark),
                            // Decorative: the adjacent title already names the app.
                            contentDescription = null,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("RS Receipts")
                    }
                },
                actions = {
                    if (reports.size > 1) {
                        IconButton(onClick = { reordering = !reordering }) {
                            if (reordering) {
                                Icon(Icons.Filled.Check, contentDescription = "Done reordering")
                            } else {
                                Icon(Icons.Filled.Edit, contentDescription = "Reorder reports")
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!reordering) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New report")
                }
            }
        },
    ) { padding ->
        if (reports.isEmpty()) {
            EmptyReports(Modifier.fillMaxSize().padding(padding))
        } else {
            if (reordering) {
                // A plain scrolling column while rearranging: every row is the same height, so
                // the drop target is arithmetic rather than a lazy-layout measurement.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ReorderableColumn(
                        items = reports,
                        keyOf = { it.id },
                        rowHeight = REORDER_ROW_HEIGHT,
                        onReordered = vm::reorderReports,
                    ) { report, isDragging, handle ->
                        ReportCard(
                            report = report,
                            currencyText = Money.format(report.totalMinor, vm.currency),
                            onClick = {},
                            onRename = { renaming = report },
                            onDelete = { deleting = report },
                            reordering = true,
                            isDragging = isDragging,
                            dragHandle = handle,
                        )
                    }
                }
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
    reordering: Boolean = false,
    isDragging: Boolean = false,
    dragHandle: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Fixed while reordering so the drop target stays pure arithmetic.
            .then(if (reordering) Modifier.height(REORDER_ROW_HEIGHT) else Modifier),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Opening a report or its menu mid-rearrange is never what was meant, so
                    // while reordering the row carries no click handling at all.
                    .then(
                        if (reordering) {
                            Modifier
                        } else {
                            Modifier.combinedClickable(
                                onClick = onClick,
                                onLongClick = { menuOpen = true },
                            )
                        }
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reordering) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Drag to reorder ${report.name}",
                        modifier = dragHandle.padding(end = 12.dp),
                    )
                }
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
