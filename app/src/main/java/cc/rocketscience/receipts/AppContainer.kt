package cc.rocketscience.receipts

import android.content.Context
import cc.rocketscience.receipts.data.ImageStore
import cc.rocketscience.receipts.data.ReceiptsDatabase
import cc.rocketscience.receipts.data.ReportRepository
import cc.rocketscience.receipts.money.Money
import java.io.File
import java.util.Currency

/**
 * Manual dependency wiring. A graph this small does not need Hilt; if it grows past a
 * screenful, revisit.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: ReceiptsDatabase by lazy { ReceiptsDatabase.build(appContext) }

    val imageStore: ImageStore by lazy { ImageStore(File(appContext.filesDir, "images")) }

    val repository: ReportRepository by lazy {
        ReportRepository(database.reportDao(), database.receiptDao(), imageStore)
    }

    /** Phase 4 replaces this with a persisted setting. */
    val currency: Currency by lazy { Money.defaultCurrency() }
}
