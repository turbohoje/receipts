package cc.rocketscience.receipts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.rocketscience.receipts.ui.ReceiptsNavHost
import cc.rocketscience.receipts.ui.theme.ReceiptsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ReceiptsApp).container
        setContent {
            ReceiptsTheme {
                ReceiptsNavHost(container = container)
            }
        }
    }
}
