package cc.rocketscience.receipts.ui

import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import cc.rocketscience.receipts.AppContainer
import cc.rocketscience.receipts.ui.receipt.ReceiptEditViewModel
import cc.rocketscience.receipts.ui.report.ReportDetailViewModel
import cc.rocketscience.receipts.ui.reports.ReportsListViewModel

/** One factory for the whole app; keeps manual DI to a single place. */
fun appViewModelFactory(container: AppContainer) = viewModelFactory {
    initializer { ReportsListViewModel(container.repository, container.currency) }
    initializer {
        ReportDetailViewModel(
            createSavedStateHandle(),
            container.repository,
            container.exporter,
            container.currency,
        )
    }
    initializer {
        ReceiptEditViewModel(createSavedStateHandle(), container.repository, container.currency)
    }
}
