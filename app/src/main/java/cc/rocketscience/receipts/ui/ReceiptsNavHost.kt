package cc.rocketscience.receipts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cc.rocketscience.receipts.AppContainer
import cc.rocketscience.receipts.ui.capture.CaptureScreen
import cc.rocketscience.receipts.ui.crop.CropScreen
import cc.rocketscience.receipts.ui.receipt.ReceiptEditScreen
import cc.rocketscience.receipts.ui.report.ReportDetailScreen
import cc.rocketscience.receipts.ui.reports.ReportsListScreen
import cc.rocketscience.receipts.ui.settings.DriveSetupScreen
import cc.rocketscience.receipts.ui.settings.SettingsScreen

object Routes {
    const val REPORTS = "reports"
    const val REPORT = "report/{reportId}"
    const val RECEIPT = "receipt/{reportId}/{receiptId}?image={image}"
    const val CAPTURE = "capture/{reportId}/{receiptId}"
    const val CROP = "crop/{reportId}/{receiptId}/{temp}"
    const val SETTINGS = "settings"
    const val DRIVE_SETUP = "settings/drive"

    /** Sentinel for "this receipt does not exist yet". */
    const val NEW = "new"

    fun report(reportId: String) = "report/$reportId"

    fun receipt(reportId: String, receiptId: String = NEW, image: String? = null) =
        "receipt/$reportId/$receiptId" + if (image != null) "?image=$image" else ""

    fun capture(reportId: String, receiptId: String = NEW) = "capture/$reportId/$receiptId"

    fun crop(reportId: String, receiptId: String, temp: String) =
        "crop/$reportId/$receiptId/$temp"
}

@Composable
fun ReceiptsNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val factory = remember(container) { appViewModelFactory(container) }

    NavHost(navController = navController, startDestination = Routes.REPORTS) {

        composable(Routes.REPORTS) {
            ReportsListScreen(
                factory = factory,
                onOpenReport = { navController.navigate(Routes.report(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                factory = factory,
                onBack = { navController.popBackStack() },
                onOpenDriveSetup = { navController.navigate(Routes.DRIVE_SETUP) },
            )
        }

        composable(Routes.DRIVE_SETUP) {
            DriveSetupScreen(
                factory = factory,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.REPORT,
            arguments = listOf(navArgument("reportId") { type = NavType.StringType }),
        ) {
            ReportDetailScreen(
                factory = factory,
                pipeline = container.imagePipeline,
                onBack = { navController.popBackStack() },
                onTakePhoto = { reportId -> navController.navigate(Routes.capture(reportId)) },
                onImageReady = { reportId, temp ->
                    navController.navigate(Routes.crop(reportId, Routes.NEW, temp))
                },
                onNoPhoto = { reportId -> navController.navigate(Routes.receipt(reportId)) },
                onOpenReceipt = { reportId, receiptId ->
                    navController.navigate(Routes.receipt(reportId, receiptId))
                },
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(
                navArgument("reportId") { type = NavType.StringType },
                navArgument("receiptId") { type = NavType.StringType },
            ),
        ) { entry ->
            val reportId = entry.arguments?.getString("reportId").orEmpty()
            val receiptId = entry.arguments?.getString("receiptId") ?: Routes.NEW
            CaptureScreen(
                pipeline = container.imagePipeline,
                onCaptured = { temp ->
                    navController.navigate(Routes.crop(reportId, receiptId, temp)) {
                        // The preview is finished with; don't return to it from crop's Back.
                        popUpTo(Routes.CAPTURE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.CROP,
            arguments = listOf(
                navArgument("reportId") { type = NavType.StringType },
                navArgument("receiptId") { type = NavType.StringType },
                navArgument("temp") { type = NavType.StringType },
            ),
        ) { entry ->
            val reportId = entry.arguments?.getString("reportId").orEmpty()
            val receiptId = entry.arguments?.getString("receiptId") ?: Routes.NEW
            val temp = entry.arguments?.getString("temp").orEmpty()
            CropScreen(
                pipeline = container.imagePipeline,
                tempName = temp,
                onCropped = { imageFile ->
                    navController.navigateToEntry(reportId, receiptId, imageFile)
                },
                onRetake = {
                    container.imagePipeline.discardTemp(temp)
                    navController.navigate(Routes.capture(reportId, receiptId)) {
                        popUpTo(Routes.REPORT) { inclusive = false }
                    }
                },
                onCancel = {
                    container.imagePipeline.discardTemp(temp)
                    navController.popBackStack(Routes.REPORT, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.RECEIPT,
            arguments = listOf(
                navArgument("reportId") { type = NavType.StringType },
                navArgument("receiptId") { type = NavType.StringType },
                navArgument("image") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val reportId = entry.arguments?.getString("reportId").orEmpty()
            val receiptId = entry.arguments?.getString("receiptId") ?: Routes.NEW
            ReceiptEditScreen(
                factory = factory,
                pipeline = container.imagePipeline,
                onDone = { navController.popBackStack() },
                onTakePhoto = { navController.navigate(Routes.capture(reportId, receiptId)) },
                onImageReady = { temp ->
                    navController.navigate(Routes.crop(reportId, receiptId, temp))
                },
            )
        }
    }
}

/**
 * Lands on the entry screen with the freshly stored image, replacing the capture/crop pair in
 * the back stack so Back from entry goes to the report rather than back into the camera.
 */
private fun NavHostController.navigateToEntry(
    reportId: String,
    receiptId: String,
    imageFile: String,
) {
    navigate(Routes.receipt(reportId, receiptId, imageFile)) {
        popUpTo(Routes.REPORT) { inclusive = false }
    }
}
