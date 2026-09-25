package cc.rocketscience.receipts.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.rocketscience.receipts.data.ReportRepository
import cc.rocketscience.receipts.data.ReportSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Currency

class ReportsListViewModel(
    private val repository: ReportRepository,
    val currency: Currency,
) : ViewModel() {

    val reports: StateFlow<List<ReportSummary>> = repository.observeReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createReport(name: String) = viewModelScope.launch {
        repository.createReport(name)
    }

    fun renameReport(id: String, name: String) = viewModelScope.launch {
        repository.renameReport(id, name)
    }

    fun deleteReport(id: String) = viewModelScope.launch {
        repository.deleteReport(id)
    }
}
