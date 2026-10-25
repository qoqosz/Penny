package app.penny.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class PairedBridge(
    /** Bonjour name, used to find the Mac again when its IP address changes. */
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    val connection get() = Connection(host, port, token)
}

/** The paired Mac. The token is stored encrypted with [cipher]; one saved in plain text by older versions is encrypted on first read. */
class Settings(private val store: DataStore<Preferences>, private val cipher: TokenCipher) {
    private object Keys {
        val name = stringPreferencesKey("bridge_name")
        val host = stringPreferencesKey("bridge_host")
        val port = intPreferencesKey("bridge_port")
        /** Plain text, from versions before the token was encrypted. */
        val token = stringPreferencesKey("bridge_token")
        val encryptedToken = stringPreferencesKey("bridge_token_encrypted")
    }

    val bridge: Flow<PairedBridge?> = store.data.onStart { encryptPlainToken() }.map { p ->
        val host = p[Keys.host]
        val token = p[Keys.encryptedToken]?.let(cipher::decrypt) ?: p[Keys.token]
        if (host == null || token == null) null
        else PairedBridge(p[Keys.name] ?: host, host, p[Keys.port] ?: 8765, token)
    }

    suspend fun current(): PairedBridge? = bridge.first()

    suspend fun save(bridge: PairedBridge) {
        val encrypted = encrypt(bridge.token)
        store.edit {
            it[Keys.name] = bridge.name
            it[Keys.host] = bridge.host
            it[Keys.port] = bridge.port
            if (encrypted != null) {
                it[Keys.encryptedToken] = encrypted
                it.remove(Keys.token)
            } else {
                it[Keys.token] = bridge.token
                it.remove(Keys.encryptedToken)
            }
        }
    }

    suspend fun updateAddress(host: String, port: Int) {
        store.edit {
            it[Keys.host] = host
            it[Keys.port] = port
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private suspend fun encryptPlainToken() {
        val plain = store.data.first()[Keys.token] ?: return
        val encrypted = encrypt(plain) ?: return
        store.edit {
            // Only if it's still the token that was read.
            if (it[Keys.token] == plain) {
                it[Keys.encryptedToken] = encrypted
                it.remove(Keys.token)
            }
        }
    }

    /** Null when the keystore can't encrypt; the token is then kept in plain text, as before, rather than lost. */
    private fun encrypt(token: String): String? = try {
        cipher.encrypt(token)
    } catch (e: Exception) {
        Log.w(TAG, "Can't encrypt the bridge token", e)
        null
    }

    private companion object {
        const val TAG = "PennySettings"
    }
}

/**
 * JSON files in app storage: last snapshot, offline copy of transactions, pending queue. A file that can't be decoded
 * is moved aside as `<name>.corrupt-<time>` (and reported to [onCorrupt]) rather than read as missing, so the next
 * write can't overwrite what may still be recovered from it.
 */
class JsonStore(
    private val dir: File,
    private val onCorrupt: (File, Throwable) -> Unit = { file, e -> Log.e("PennyStore", "Unreadable ${file.name}, kept as is", e) },
) {
    private val mutex = Mutex()

    suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir, name)
            if (!file.exists()) return@withLock null
            // An I/O error propagates: the file may be fine, and must not be mistaken for an empty one.
            val text = file.readText()
            try {
                BridgeClient.json.decodeFromString(serializer, text)
            } catch (e: IllegalArgumentException) {
                // SerializationException is one too.
                moveAside(file, e)
                null
            }
        }
    }

    suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(BridgeClient.json.encodeToString(serializer, value).toByteArray())
                // On disk before the rename, so a crash leaves the old file or the new one, never a partial one.
                out.fd.sync()
            }
            Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock { File(dir, name).delete() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun moveAside(file: File, error: Throwable) {
        val kept = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
        Files.move(file.toPath(), kept.toPath(), StandardCopyOption.ATOMIC_MOVE)
        onCorrupt(kept, error)
    }
}

/** Transactions waiting to be sent to the Mac. Survives app restarts; an unreadable file is kept aside by [JsonStore]. */
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
