package app.penny.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/**
 * Money's icons (payee logos, category glyphs), downloaded from the Mac and kept as files named by icon ID.
 * IDs are content hashes, so a file never goes stale: a changed icon on the Mac has a new ID.
 * Icons only travel from the Mac to the phone; nothing the phone sends refers to them.
 */
class IconStore(private val dir: File) {
    private val mutex = Mutex()
    private val _available = MutableStateFlow<Set<String>>(emptySet())
    /** IDs of icons on disk. */
    val available: StateFlow<Set<String>> = _available.asStateFlow()
    /** Requested from the Mac but not sent (e.g. an image missing from Money.app); not asked for again until restart. */
    private val unavailable = mutableSetOf<String>()
    /** Bumped by [clear], so a sync started for the previous pairing stops. */
    @Volatile private var epoch = 0

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { _available.value = stored() }
    }

    fun file(id: String): File = File(dir, id)

    /**
     * Downloads the icons [snapshot] refers to that aren't on disk yet, in batches, and deletes the ones it no
     * longer refers to. Each batch shows up in [available] as soon as it's saved.
     */
    suspend fun sync(snapshot: Snapshot, fetch: suspend (List<String>) -> List<IconData>) = withContext(Dispatchers.IO) {
        val started = epoch
        mutex.withLock {
            if (epoch != started) return@withLock
            val wanted = (snapshot.payees.mapNotNull { it.iconId } + snapshot.categories.mapNotNull { it.iconId })
                .filter { it.matches(SAFE_ID) }.toSet()
            val stored = stored()
            (stored - wanted).forEach { File(dir, it).delete() }
            _available.value = stored intersect wanted
            for (batch in (wanted - stored - unavailable).sorted().chunked(BATCH_SIZE)) {
                val icons = fetch(batch).filter { it.id in batch }
                if (epoch != started) return@withLock
                dir.mkdirs()
                for (icon in icons) {
                    val bytes = runCatching { Base64.getDecoder().decode(icon.data) }.getOrNull() ?: continue
                    val tmp = File(dir, "${icon.id}.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(File(dir, icon.id))
                }
                unavailable += batch - icons.map { it.id }.toSet()
                _available.update { it + icons.map(IconData::id) }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        epoch++
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            unavailable.clear()
            _available.value = emptySet()
        }
    }

    private fun stored(): Set<String> =
        dir.listFiles()?.map { it.name }?.filterNot { it.endsWith(".tmp") }?.toSet().orEmpty()

    companion object {
        /** The bridge allows up to 200 per request. */
        const val BATCH_SIZE = 100
        /** IDs become file names. */
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
