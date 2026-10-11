package cc.rocketscience.receipts.ui.report

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.data.ReportRepository
import cc.rocketscience.receipts.data.ReportSummary
import cc.rocketscience.receipts.export.ExportResult
import cc.rocketscience.receipts.export.Exporter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Currency

class ReportDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
    private val exporter: Exporter,
    val currency: Currency,
) : ViewModel() {

    val reportId: String = checkNotNull(savedStateHandle["reportId"])

    var syncNotice by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun dismissSyncNotice() { syncNotice = null }

    /** The way out of a tombstone written in error; see the repository. */
    fun forgetDeletions() = viewModelScope.launch {
        val dropped = repository.forgetDeletions(reportId)
        syncNotice = if (dropped == 0) {
            "There were no deletions recorded here."
        } else {
            "Forgot $dropped deletion${if (dropped == 1) "" else "s"}. Sync now to take " +
                "whatever the shared file holds."
        }
    }

    /**
     * Merges the bound file and hands back what should be written to it.
     *
     * The view model never touches the file itself — reading and writing a content URI needs
     * a ContentResolver, which belongs to the screen.
     */
    /** Delegates to the repository, so this behaves exactly as the reports list does. */
    fun syncShared(read: () -> cc.rocketscience.receipts.data.ReportRepository.SharedFileRead, write: (String) -> Boolean) = viewModelScope.launch {
        syncNotice = when (val outcome =
            repository.syncShared(reportId, currency.currencyCode, read, write)) {
            is cc.rocketscience.receipts.data.ReportRepository.SyncOutcome.Synced -> outcome.summary
            is cc.rocketscience.receipts.data.ReportRepository.SyncOutcome.Unreadable ->
                "Could not sync: ${outcome.detail}"
            is cc.rocketscience.receipts.data.ReportRepository.SyncOutcome.ReadFailed ->
                // Nothing was written: overwriting a file we could not read would discard
                // whatever the other person had put in it.
                "Could not read the shared file, so nothing was written. ${outcome.detail}."
            cc.rocketscience.receipts.data.ReportRepository.SyncOutcome.NotWritten ->
                "Merged here, but the shared file could not be written."
        }
    }

    val report: StateFlow<ReportSummary?> = repository.observeReport(reportId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val receipts: StateFlow<List<Receipt>> = repository.observeReceipts(reportId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun renameReport(name: String) = viewModelScope.launch {
        repository.renameReport(reportId, name)
    }

    fun deleteReport() = viewModelScope.launch {
        repository.deleteReport(reportId)
    }

    fun deleteReceipt(receiptId: String) = viewModelScope.launch {
        repository.deleteReceipt(receiptId)
    }

    // ----- export -----

    var exporting by mutableStateOf(false)
        private set

    /** Set when an export fails; the screen shows it and clears it. */
    var exportError by mutableStateOf<String?>(null)
        private set

    fun clearExportError() { exportError = null }

    fun exportPdf(onReady: (ExportResult.Success) -> Unit) =
        export(onReady) { name, receipts -> exporter.exportPdf(name, receipts, currency) }

    fun exportZip(onReady: (ExportResult.Success) -> Unit) =
        export(onReady) { name, receipts -> exporter.exportZip(name, receipts, currency) }

    private fun export(
        onReady: (ExportResult.Success) -> Unit,
        build: suspend (String, List<Receipt>) -> ExportResult,
    ) = viewModelScope.launch {
        if (exporting) return@launch
        exporting = true
        // Read the rows once, here, rather than from the UI's snapshot: the export must match
        // what is in the database at the moment it runs.
        val name = report.value?.name ?: "Report"
        val rows = repository.receiptsOnce(reportId)
        when (val result = build(name, rows)) {
            is ExportResult.Success -> onReady(result)
            is ExportResult.Failure -> exportError = result.message
        }
        exporting = false
    }
}
