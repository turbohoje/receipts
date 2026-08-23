package cc.rocketscience.receipts.ui.receipt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.rocketscience.receipts.data.Receipt
import cc.rocketscience.receipts.data.ReportRepository
import cc.rocketscience.receipts.money.Money
import cc.rocketscience.receipts.ui.Routes
import kotlinx.coroutines.launch
import java.util.Currency

class ReceiptEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
    val currency: Currency,
) : ViewModel() {

    val reportId: String = checkNotNull(savedStateHandle["reportId"])
    private val receiptId: String = checkNotNull(savedStateHandle["receiptId"])

    val isNew: Boolean = receiptId == Routes.NEW

    var description by mutableStateOf("")
        private set
    var amountText by mutableStateOf("")
        private set
    var date by mutableStateOf(System.currentTimeMillis())
        private set
    var amountInvalid by mutableStateOf(false)
        private set

    private var existing: Receipt? = null

    init {
        if (!isNew) {
            viewModelScope.launch {
                repository.findReceipt(receiptId)?.let { receipt ->
                    existing = receipt
                    description = receipt.description
                    amountText = Money.formatPlain(receipt.amountMinor, currency)
                    date = receipt.date
                }
            }
        }
    }

    fun onDescriptionChange(value: String) { description = value }

    fun onAmountChange(value: String) {
        amountText = value
        amountInvalid = false
    }

    fun onDateChange(value: Long) { date = value }

    /** True when there is enough to save; drives the Save button's enabled state. */
    val canSave: Boolean
        get() = Money.parse(amountText, currency) != null

    fun save(onDone: () -> Unit) {
        val amountMinor = Money.parse(amountText, currency)
        if (amountMinor == null) {
            amountInvalid = true
            return
        }
        viewModelScope.launch {
            val current = existing
            if (current == null) {
                repository.addReceipt(
                    reportId = reportId,
                    description = description,
                    amountMinor = amountMinor,
                    date = date,
                )
            } else {
                repository.updateReceipt(
                    current.copy(
                        description = description,
                        amountMinor = amountMinor,
                        date = date,
                    )
                )
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val current = existing ?: return onDone()
        viewModelScope.launch {
            repository.deleteReceipt(current.id)
            onDone()
        }
    }
}
