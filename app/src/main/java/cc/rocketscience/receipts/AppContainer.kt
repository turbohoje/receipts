package cc.rocketscience.receipts

import android.content.Context
import cc.rocketscience.receipts.data.ImageStore
import cc.rocketscience.receipts.data.ReceiptsDatabase
import cc.rocketscience.receipts.data.ReportRepository
import cc.rocketscience.receipts.backup.BackupManager
import cc.rocketscience.receipts.backup.ContentIo
import cc.rocketscience.receipts.backup.SigningInfo
import cc.rocketscience.receipts.backup.drive.DriveAuth
import cc.rocketscience.receipts.backup.drive.DriveClient
import cc.rocketscience.receipts.data.SettingsStore
import cc.rocketscience.receipts.export.Exporter
import cc.rocketscience.receipts.image.ImagePipeline
import cc.rocketscience.receipts.money.Money
import java.io.File
import java.util.Currency

/**
 * Manual dependency wiring. A graph this small does not need Hilt; if it grows past a
 * screenful, revisit.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: ReceiptsDatabase by lazy { ReceiptsDatabase.build(appContext) }

    val imageStore: ImageStore by lazy { ImageStore(File(appContext.filesDir, "images")) }

    val imagePipeline: ImagePipeline by lazy { ImagePipeline(appContext, imageStore) }

    val exporter: Exporter by lazy { Exporter(appContext, imageStore) }

    val repository: ReportRepository by lazy {
        ReportRepository(database.reportDao(), database.receiptDao(), imageStore)
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val contentIo: ContentIo by lazy { ContentIo(appContext) }

    val driveAuth: DriveAuth by lazy { DriveAuth(appContext) }

    val driveClient: DriveClient by lazy { DriveClient() }

    val backupManager: BackupManager by lazy {
        BackupManager(
            database = database,
            imageStore = imageStore,
            appVersion = SigningInfo.versionName(appContext),
            currency = { currency },
        )
    }

    /** Device locale for now; a per-app override would live in [settings]. */
    val currency: Currency by lazy { Money.defaultCurrency() }
}
