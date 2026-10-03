package app.penny.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

private val Context.dataStore by preferencesDataStore("settings")

data class PairedBridge(
    /** Bonjour name, used to find the Mac again when its IP address changes. */
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    val connection get() = Connection(host, port, token)
}

class Settings(private val context: Context) {
    private object Keys {
        val name = stringPreferencesKey("bridge_name")
        val host = stringPreferencesKey("bridge_host")
        val port = intPreferencesKey("bridge_port")
        val token = stringPreferencesKey("bridge_token")
    }

    val bridge: Flow<PairedBridge?> = context.dataStore.data.map { p ->
        val host = p[Keys.host]
        val token = p[Keys.token]
        if (host == null || token == null) null
        else PairedBridge(p[Keys.name] ?: host, host, p[Keys.port] ?: 8765, token)
    }

    suspend fun current(): PairedBridge? = bridge.first()

    suspend fun save(bridge: PairedBridge) {
        context.dataStore.edit {
            it[Keys.name] = bridge.name
            it[Keys.host] = bridge.host
            it[Keys.port] = bridge.port
            it[Keys.token] = bridge.token
        }
    }

    suspend fun updateAddress(host: String, port: Int) {
        context.dataStore.edit {
            it[Keys.host] = host
            it[Keys.port] = port
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

/** JSON files in app storage: last snapshot, offline copy of transactions, pending queue. */
class JsonStore(private val dir: File) {
    private val mutex = Mutex()

    suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir, name)
            if (!file.exists()) null
            else runCatching { BridgeClient.json.decodeFromString(serializer, file.readText()) }.getOrNull()
        }
    }

    suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(BridgeClient.json.encodeToString(serializer, value))
            tmp.renameTo(File(dir, name))
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock { File(dir, name).delete() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { dir.listFiles()?.forEach { it.delete() } }
    }
}

/** Transactions waiting to be sent to the Mac. Survives app restarts. */
class PendingQueue(private val store: JsonStore) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(PendingTransaction.serializer())
    private val file = "pending.json"

    suspend fun all(): List<PendingTransaction> = store.read(file, serializer) ?: emptyList()

    suspend fun update(block: (List<PendingTransaction>) -> List<PendingTransaction>): List<PendingTransaction> =
        mutex.withLock {
            val updated = block(all())
            store.write(file, serializer, updated)
            updated
        }
}
