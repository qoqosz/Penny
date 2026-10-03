package app.penny

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.penny.ui.PennyApp
import app.penny.ui.PennyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PennyApplication
        setContent {
            PennyTheme {
                PennyApp(app)
            }
        }
    }
}
