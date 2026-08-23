package cc.rocketscience.receipts.ui.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.data.ReportRepository
import cc.rocketscience.receipts.data.ReportSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Currency

class ReportDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
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
}
