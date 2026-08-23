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
