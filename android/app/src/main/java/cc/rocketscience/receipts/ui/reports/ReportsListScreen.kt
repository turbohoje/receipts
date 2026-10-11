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
import androidx.compose.material.icons.filled.Share
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import cc.rocketscience.receipts.shared.SharedFileLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    var addMenuOpen by remember { mutableStateOf(false) }

    var renaming by remember { mutableStateOf<ReportSummary?>(null) }
    var deleting by remember { mutableStateOf<ReportSummary?>(null) }

    val context = LocalContext.current
    val links = remember { SharedFileLinks(context) }

    // The system picker is what reaches Google Drive, local storage, or anywhere else the
    // user has a provider for. The app never talks to any service itself.
    val createShared = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val pending = vm.pendingShare
        if (uri != null && pending != null) {
            val persisted = links.link(pending.reportId, uri)
            if (links.write(pending.reportId, pending.json)) {
                vm.notify(
                    if (persisted) {
                        "Shared to ${links.displayName(pending.reportId)}. " +
                            "This report now syncs with that file."
                    } else {
                        "Written, but this location would not grant lasting access, so you " +
                            "will be asked for the file again next time."
                    }
                )
            } else {
                vm.notify("The file could not be written.")
            }
        }
        vm.consumeShare()
    }

    // Joining a report someone else already shared.
    val openShared = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val json = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (json == null) {
                vm.notify("That file could not be read.")
            } else {
                runCatching {
                    val reportId = Json.parseToJsonElement(json)
                        .jsonObject["reportId"]?.jsonPrimitive?.content
                    if (reportId != null) links.link(reportId, uri)
                }
                vm.importShared(json)
            }
        }
    }

    // A pending export with a bound file needs no dialog: write straight back to it.
    LaunchedEffect(vm.pendingShare) {
        val pending = vm.pendingShare ?: return@LaunchedEffect
        if (pending.targetUri == null) {
            createShared.launch(pending.suggestedName)
        } else {
            val ok = links.write(pending.reportId, pending.json)
            vm.notify(
                if (ok) "Synced with ${links.displayName(pending.reportId) ?: "the shared file"}."
                else "The bound file could not be written. Use \u201cChange shared file\u2026\u201d to pick it again."
            )
            vm.consumeShare()
        }
    }

    // Checked on open: fold in anything collaborators wrote since this device last looked.
    LaunchedEffect(Unit) {
        for (reportId in links.linkedReportIds()) {
            vm.ingestOnOpen(links.read(reportId))
        }
    }

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
                // Joining someone else's shared report is also "adding a report", so it
                // belongs here rather than hidden behind a toolbar icon.
                Box {
                    FloatingActionButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a report")
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("New report") },
                            onClick = { addMenuOpen = false; showCreate = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Add a shared report\u2026") },
                            onClick = {
                                addMenuOpen = false
                                openShared.launch(arrayOf("application/json"))
                            },
                        )
                    }
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
                            onShare = {
                                vm.prepareShare(
                                    report.id, report.name,
                                    read = { links.read(report.id) },
                                    write = { json -> links.write(report.id, json) },
                                    targetUri = null,
                                )
                            },
                            onStopSharing = {
                                links.unlink(report.id)
                                vm.setShared(report.id, false)
                            },
                            onExportShared = {
                                // Merge whatever is in the bound file first, then write back.
                                vm.prepareShare(
                                    report.id, report.name,
                                    read = { links.read(report.id) },
                                    write = { json -> links.write(report.id, json) },
                                    targetUri = links.uriFor(report.id)?.toString(),
                                )
                            },
                            onChangeSharedFile = {
                                links.unlink(report.id)
                                vm.prepareShare(
                                    report.id, report.name,
                                    read = { links.read(report.id) },
                                    write = { json -> links.write(report.id, json) },
                                    targetUri = null,
                                )
                            },
                            sharedFileName = links.displayName(report.id),
                        )
                    }
                }
            }
        }
    }

    vm.sharedNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
            title = { Text("Shared report") },
            text = { Text(notice) },
        )
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
    onShare: () -> Unit = {},
    onStopSharing: () -> Unit = {},
    onExportShared: () -> Unit = {},
    onChangeSharedFile: () -> Unit = {},
    sharedFileName: String? = null,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (report.isShared) {
                            // Just a mark that it is shared; which file it goes to is in the
                            // report's own menu, where there is room to show it properly.
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = sharedFileName
                                    ?.let { "Shared with $it" }
                                    ?: "Shared, no file chosen",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(end = 0.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            report.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
                if (report.isShared) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (sharedFileName != null) "Sync with $sharedFileName"
                                else "Choose a shared file\u2026"
                            )
                        },
                        onClick = { menuOpen = false; onExportShared() },
                    )
                    if (sharedFileName != null) {
                        DropdownMenuItem(
                            text = { Text("Change shared file\u2026") },
                            onClick = { menuOpen = false; onChangeSharedFile() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Stop sharing") },
                        onClick = { menuOpen = false; onStopSharing() },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Share this report") },
                        onClick = { menuOpen = false; onShare() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
