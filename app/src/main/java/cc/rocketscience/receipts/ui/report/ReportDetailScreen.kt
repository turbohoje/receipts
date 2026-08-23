package cc.rocketscience.receipts.ui.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Spacer
import coil3.compose.AsyncImage
import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.export.ExportResult
import cc.rocketscience.receipts.export.shareExport
import cc.rocketscience.receipts.image.ImagePipeline
import cc.rocketscience.receipts.money.Money
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.TextPromptDialog
import cc.rocketscience.receipts.ui.ImageSourceSheet
import cc.rocketscience.receipts.ui.formatDate
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    factory: ViewModelProvider.Factory,
    pipeline: ImagePipeline,
    onBack: () -> Unit,
    onTakePhoto: (String) -> Unit,
    onImageReady: (String, String) -> Unit,
    onNoPhoto: (String) -> Unit,
    onOpenReceipt: (String, String) -> Unit,
) {
    val vm: ReportDetailViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val report by vm.report.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val scope = rememberCoroutineScope()

    var showSourceSheet by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // The system photo picker: no permission, and the app only ever sees what was selected.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val temp = pipeline.importFromUri(uri)
            importing = false
            if (temp != null) onImageReady(vm.reportId, temp)
        }
    }
    var renaming by remember { mutableStateOf(false) }
    var deletingReport by remember { mutableStateOf(false) }
    var deletingReceipt by remember { mutableStateOf<Receipt?>(null) }

    // Export failures are reported, never swallowed: a silent no-op here looks like a
    // successful export that produced nothing.
    LaunchedEffect(vm.exportError) {
        vm.exportError?.let {
            snackbar.showSnackbar("Export failed: $it")
            vm.clearExportError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                        val hasReceipts = (report?.receiptCount ?: 0) > 0
                        DropdownMenuItem(
                            text = { Text("Export PDF") },
                            enabled = hasReceipts && !vm.exporting,
                            onClick = {
                                menuOpen = false
                                vm.exportPdf { share(context, it, report?.name) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export ZIP") },
                            enabled = hasReceipts && !vm.exporting,
                            onClick = {
                                menuOpen = false
                                vm.exportZip { share(context, it, report?.name) }
                            },
                        )
                        HorizontalDivider()
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
            FloatingActionButton(onClick = { showSourceSheet = true }) {
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
                        imageFile = receipt.imageFile?.let { pipeline.imageFileOrNull(it) },
                        onClick = { onOpenReceipt(vm.reportId, receipt.id) },
                        onLongClick = { deletingReceipt = receipt },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (vm.exporting) {
        // A long report with many images takes a moment; the tap must not look ignored.
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    if (showSourceSheet) {
        ImageSourceSheet(
            onTakePhoto = { onTakePhoto(vm.reportId) },
            onChooseFromPhotos = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onNoPhoto = { onNoPhoto(vm.reportId) },
            onDismiss = { showSourceSheet = false },
        )
    }

    if (importing) {
        // Copying a picked image can take a moment for a large photo.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
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
    imageFile: File?,
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
            Thumbnail(
                file = imageFile,
                // A row that references an image the file for which has vanished must say so,
                // not render blank and hide the loss until export time.
                missing = receipt.imageFile != null && imageFile == null,
            )
            Spacer(Modifier.width(14.dp))
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

@Composable
private fun Thumbnail(file: File?, missing: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            file != null -> AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            missing -> Icon(
                Icons.Filled.Warning,
                contentDescription = "Image missing",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            else -> Icon(
                Icons.Filled.Edit,
                contentDescription = "No photo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun share(context: android.content.Context, result: ExportResult.Success, reportName: String?) {
    shareExport(
        context = context,
        file = result.file,
        mimeType = result.mimeType,
        subject = reportName ?: "RS Receipts export",
    )
}
