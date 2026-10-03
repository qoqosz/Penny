package app.penny

import android.app.Application
import app.penny.data.BridgeClient
import app.penny.data.Discovery
import app.penny.data.JsonStore
import app.penny.data.PendingQueue
import app.penny.data.Repository
import app.penny.data.Settings
import app.penny.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class PennyApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { Settings(this) }
    val discovery by lazy { Discovery(this) }
    val repository by lazy {
        Repository(
            settings = settings,
            client = BridgeClient(),
            discovery = discovery,
            cache = JsonStore(File(filesDir, "cache")),
            queue = PendingQueue(JsonStore(File(filesDir, "queue"))),
            scope = appScope,
            scheduleSync = { SyncWorker.schedule(this) },
        )
    }
}
