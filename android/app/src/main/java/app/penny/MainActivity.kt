package app.penny

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
        // Keep transactions out of the recent-apps thumbnail while the app lock is on. Before Android 13 only
        // FLAG_SECURE does that, which also blocks screenshots.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.appLock.config.collect {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!it.enabled)
                    else if (it.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
