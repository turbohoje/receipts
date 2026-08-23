package cc.rocketscience.receipts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cc.rocketscience.receipts.AppContainer
import cc.rocketscience.receipts.ui.receipt.ReceiptEditScreen
import cc.rocketscience.receipts.ui.report.ReportDetailScreen
import cc.rocketscience.receipts.ui.reports.ReportsListScreen

object Routes {
    const val REPORTS = "reports"
    const val REPORT = "report/{reportId}"
    const val RECEIPT = "receipt/{reportId}/{receiptId}"

    /** Sentinel for "this receipt does not exist yet". */
    const val NEW = "new"

    fun report(reportId: String) = "report/$reportId"
    fun receipt(reportId: String, receiptId: String = NEW) = "receipt/$reportId/$receiptId"
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
            )
        }

        composable(
            route = Routes.REPORT,
            arguments = listOf(navArgument("reportId") { type = NavType.StringType }),
        ) {
            ReportDetailScreen(
                factory = factory,
                onBack = { navController.popBackStack() },
                onAddReceipt = { reportId ->
                    navController.navigate(Routes.receipt(reportId))
                },
                onOpenReceipt = { reportId, receiptId ->
                    navController.navigate(Routes.receipt(reportId, receiptId))
                },
            )
        }

        composable(
            route = Routes.RECEIPT,
            arguments = listOf(
                navArgument("reportId") { type = NavType.StringType },
                navArgument("receiptId") { type = NavType.StringType },
            ),
        ) {
            ReceiptEditScreen(
                factory = factory,
                onDone = { navController.popBackStack() },
            )
        }
    }
}
