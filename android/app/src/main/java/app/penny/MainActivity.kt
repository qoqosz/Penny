package app.penny

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.penny.ui.PennyApp
import app.penny.ui.PennyTheme
import kotlinx.coroutines.launch

// AppCompatActivity: per-app language and night mode on older Android, and a FragmentActivity for BiometricPrompt.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PennyApplication
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keep transactions out of the recent-apps thumbnail while the app lock is on.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    app.appLock.config.collect { setRecentsScreenshotEnabled(!it.enabled) }
                }
            }
        }
        setContent {
            PennyTheme {
                PennyApp(app)
            }
        }
    }
}
