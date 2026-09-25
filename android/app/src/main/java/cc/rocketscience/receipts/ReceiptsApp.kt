package cc.rocketscience.receipts

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReceiptsApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Reconcile disk against the database: collect images left behind by a delete that
        // was interrupted between removing rows and removing files.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.repository.sweepOrphanImages() }
            // Temp captures from a flow the user backed out of.
            runCatching { container.imagePipeline.clearTempDir() }
            // Exports are disposable derivatives of the database.
            runCatching { container.exporter.pruneExports() }
            // Half-finished backups or restores.
            runCatching { container.contentIo.clearTemp() }
        }
    }
}
