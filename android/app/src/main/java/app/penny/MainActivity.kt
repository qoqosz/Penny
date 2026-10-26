package app.penny

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// AppCompatActivity: per-app language and night mode on older Android, and a FragmentActivity for BiometricPrompt.
class MainActivity : AppCompatActivity() {
    /** The add form was asked for (widget, launcher shortcut, Quick Settings tile). */
    private val addRequests = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not again when the activity is recreated (rotation, language): the form is already open then.
        if (savedInstanceState == null) handle(intent)
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
                PennyApp(app, addRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ADD) addRequests.value = true
    }

    companion object {
        /** Opens the add form; the shortcut in res/xml/shortcuts.xml uses it too. */
        const val ACTION_ADD = "app.penny.action.ADD"

        fun addIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_ADD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
