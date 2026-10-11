package cc.rocketscience.receipts.ui.reports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /** Persists a manual ordering, top to bottom. */
    fun reorderReports(orderedIds: List<String>) = viewModelScope.launch {
        repository.reorderReports(orderedIds)
    }

    // ----- shared mode -----

    /**
     * An export waiting to be written. [targetUri] is null when the report has no file bound
     * yet, which is the only case that needs the Save-as dialog.
     */
    data class PendingShare(
        val reportId: String,
        val json: String,
        val suggestedName: String,
        val targetUri: String?,
    )

    var pendingShare by androidx.compose.runtime.mutableStateOf<PendingShare?>(null)
        private set

    var sharedNotice by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun dismissNotice() { sharedNotice = null }
    fun consumeShare() { pendingShare = null }
    fun notify(message: String) { sharedNotice = message }

    fun setShared(id: String, shared: Boolean) = viewModelScope.launch {
        repository.setShared(id, shared)
    }

    /**
     * Prepares the report's file.
     *
     * When a file is already bound, anything in it is merged in first — the other person may
     * have written since this device last looked — and the merged result goes back to the
     * same file. Otherwise this is a first publish and the caller shows a Save-as dialog.
     */
    /**
     * Prepares the report's file.
     *
     * With a file already bound this is a full sync through the repository — the same call
     * the report's own menu makes. Without one it is a first publish, and the caller shows a
     * Save-as dialog.
     */
    fun prepareShare(
        id: String,
        name: String,
        read: () -> ReportRepository.SharedFileRead,
        write: (String) -> Boolean,
        targetUri: String?,
    ) = viewModelScope.launch {
        repository.setShared(id, true)
        if (targetUri == null) {
            val json = repository.sharedStateJson(id, currency.currencyCode)
            if (json == null) {
                sharedNotice = "That report could not be read."
            } else {
                pendingShare = PendingShare(id, json, "shared-${slugifyForFile(name)}.json", null)
            }
            return@launch
        }
        sharedNotice = when (val outcome =
            repository.syncShared(id, currency.currencyCode, read, write)) {
            is ReportRepository.SyncOutcome.Synced -> outcome.summary
            is ReportRepository.SyncOutcome.Unreadable -> "Could not sync: ${outcome.detail}"
            is ReportRepository.SyncOutcome.ReadFailed ->
                // Nothing was written: overwriting a file we could not read would discard
                // whatever the other person had put in it.
                "Could not read the shared file, so nothing was written. ${outcome.detail}."
            ReportRepository.SyncOutcome.NotWritten ->
                "Merged here, but the shared file could not be written."
        }
    }

    fun importShared(json: String) = viewModelScope.launch {
        runCatching { repository.importSharedJson(json, currency.currencyCode) }
            .onSuccess { result ->
                sharedNotice = buildString {
                    append(
                        if (result.isNewReport) {
                            "Added \"${result.reportName}\" with ${result.received} receipts."
                        } else {
                            "Merged into \"${result.reportName}\": ${result.received} receipts."
                        }
                    )
                    if (result.deleted > 0) {
                        append(" ${result.deleted} were deleted by the sender.")
                    }
                }
            }
            .onFailure { sharedNotice = it.message ?: "That file could not be imported." }
    }

    /**
     * Merges a bound file without writing back — what runs when the list opens.
     *
     * Silent on failure by design: an unreachable file at launch is not worth a dialog, and
     * nothing is written, so nothing can be lost.
     */
    fun ingestOnOpen(read: ReportRepository.SharedFileRead) = viewModelScope.launch {
        val contents = read as? ReportRepository.SharedFileRead.Contents ?: return@launch
        runCatching { repository.importSharedJson(contents.json, currency.currencyCode) }
    }

    private fun slugifyForFile(name: String) =
        cc.rocketscience.receipts.export.slugify(name, fallback = "report")

    fun deleteReport(id: String) = viewModelScope.launch {
        repository.deleteReport(id)
    }
}
