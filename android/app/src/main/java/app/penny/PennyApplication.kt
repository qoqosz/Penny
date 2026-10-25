package app.penny

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import app.penny.data.AppPreferences
import app.penny.data.BridgeClient
import app.penny.data.CurrencyRates
import app.penny.data.EcbRateSource
import app.penny.data.Discovery
import app.penny.data.IconStore
import app.penny.data.JsonStore
import app.penny.data.KeystoreTokenCipher
import app.penny.data.PendingQueue
import app.penny.data.Repository
import app.penny.data.SecurityPrices
import app.penny.data.Settings
import app.penny.data.YahooPriceSource
import app.penny.data.settingsDataStore
import app.penny.lock.AppLock
import app.penny.sync.SyncWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class PennyApplication : Application() {
    // A failed background job is logged, never a crash; what the user needs to see goes to the sync status.
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Penny", "Background job failed", e) },
    )

    val settings by lazy { Settings(settingsDataStore, KeystoreTokenCipher()) }
    val preferences by lazy { AppPreferences(getSharedPreferences("preferences", Context.MODE_PRIVATE)) }
    val appLock by lazy { AppLock(getSharedPreferences("app_lock", Context.MODE_PRIVATE)) }
    val discovery by lazy { Discovery(this) }
    val repository by lazy {
        Repository(
            settings = settings,
            client = BridgeClient(),
            discovery = discovery,
            cache = JsonStore(File(filesDir, "cache")),
            queue = PendingQueue(JsonStore(File(filesDir, "queue"))),
            icons = IconStore(File(filesDir, "icons")),
            // Not tied to the paired Mac, so kept when unpairing.
            rates = CurrencyRates(JsonStore(File(filesDir, "rates")), EcbRateSource()),
            prices = SecurityPrices(JsonStore(File(filesDir, "prices")), YahooPriceSource()),
            scope = appScope,
            scheduleSync = { SyncWorker.schedule(this) },
            syncMode = preferences.syncMode,
        )
    }

    override fun onCreate() {
        super.onCreate()
        preferences.applyTheme()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLock)
    }
}
