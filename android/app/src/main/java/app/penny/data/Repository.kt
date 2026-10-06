package app.penny.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Offline(val hasCachedData: Boolean) : SyncStatus
    /** [message] comes from the bridge. */
    data class Error(val message: String) : SyncStatus
}

data class AppState(
    val snapshot: Snapshot? = null,
    val pending: List<PendingTransaction> = emptyList(),
    val status: SyncStatus = SyncStatus.Idle,
    /** Bumped whenever the Mac's data changed, so open transaction lists reload. */
    val dataVersion: Int = 0,
)

class Repository(
    private val settings: Settings,
    private val client: BridgeClient,
    private val discovery: Discovery,
    private val cache: JsonStore,
    private val queue: PendingQueue,
    val icons: IconStore,
    private val scope: CoroutineScope,
    /** Schedules a background sync (WorkManager) for when the network is back. */
    private val scheduleSync: () -> Unit,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val syncMutex = Mutex()
    private val offline = OfflineTransactions(client, cache)

    init {
        scope.launch {
            val cached = cache.read(SNAPSHOT_FILE, Snapshot.serializer())
            _state.update { it.copy(snapshot = it.snapshot ?: cached, pending = queue.all()) }
            icons.load()
        }
    }

    suspend fun pair(bridge: DiscoveredBridge, code: String, deviceName: String) {
        val response = client.pair(bridge.host, bridge.port, code.filter(Char::isDigit), deviceName)
        settings.save(PairedBridge(response.name, bridge.host, bridge.port, response.token))
        cache.clear()
        offline.clear()
        icons.clear()
        _state.update { it.copy(snapshot = null) }
        refresh()
    }

    suspend fun probe(host: String, port: Int): DiscoveredBridge {
        val ping = client.ping(host, port)
        return DiscoveredBridge(ping.name, host, port)
    }

    suspend fun unpair() {
        settings.clear()
        cache.clear()
        offline.clear()
        icons.clear()
        _state.update { AppState(pending = it.pending) }
    }

    /** Sends queued transactions, then reloads accounts and categories and the offline copy of transactions. */
    suspend fun refresh() = syncMutex.withLock {
        val bridge = settings.current() ?: return@withLock
        _state.update { it.copy(status = SyncStatus.Syncing) }
        val status = try {
            val connection = withReconnect(bridge) { c -> pushPending(c); c }
            val snapshot = client.snapshot(connection)
            val changed = snapshot.generation != _state.value.snapshot?.generation
            cache.write(SNAPSHOT_FILE, Snapshot.serializer(), snapshot)
            _state.update { it.copy(snapshot = snapshot, dataVersion = it.dataVersion + if (changed) 1 else 0) }
            offline.update(connection, snapshot.generation)
            // Icons can take a while the first time; the lists show them as they arrive.
            scope.launch { syncIcons(connection, snapshot) }
            if (_state.value.pending.any { it.rejectedReason == null }) scheduleSync()
            SyncStatus.Idle
        } catch (e: UnreachableException) {
            if (_state.value.pending.isNotEmpty()) scheduleSync()
            SyncStatus.Offline(hasCachedData = _state.value.snapshot != null)
        } catch (e: BridgeException) {
            SyncStatus.Error(e.message.orEmpty())
        }
        _state.update { it.copy(status = status, pending = queue.all()) }
    }

    private suspend fun syncIcons(connection: Connection, snapshot: Snapshot) {
        // Icons are decoration: on failure the lists fall back to plain markers, and the next refresh picks up
        // where this one stopped.
        try {
            icons.sync(snapshot) { ids -> client.icons(connection, ids).icons }
        } catch (_: UnreachableException) {
        } catch (_: BridgeException) {
        }
    }

    /** Background sync entry point. Returns true when nothing is left to retry. */
    suspend fun syncInBackground(): Boolean {
        refresh()
        return _state.value.pending.none { it.rejectedReason == null }
    }

    suspend fun add(request: NewTransactionRequest) {
        val pending = queue.update { it + PendingTransaction(request, System.currentTimeMillis()) }
        _state.update { it.copy(pending = pending) }
        scope.launch { refresh() }
    }

    fun newClientId(): String = UUID.randomUUID().toString()

    suspend fun discardPending(clientId: String) {
        val pending = queue.update { list -> list.filterNot { it.request.clientId == clientId } }
        _state.update { it.copy(pending = pending) }
    }

    suspend fun retryPending(clientId: String) {
        val pending = queue.update { list ->
            list.map { if (it.request.clientId == clientId) it.copy(rejectedReason = null, lastError = null) else it }
        }
        _state.update { it.copy(pending = pending) }
        scope.launch { refresh() }
    }

    /**
     * Pages from the offline copy when it matches the shown snapshot, otherwise from the Mac.
     * Without the Mac, an older offline copy is better than nothing.
     */
    suspend fun transactions(accountId: String?, offset: Int, limit: Int = PAGE_SIZE): TransactionPage {
        val local = offline.current()
        if (local != null && local.generation == _state.value.snapshot?.generation) return local.page(accountId, offset, limit)
        val bridge = settings.current() ?: throw NotPairedException()
        return try {
            withReconnect(bridge) { client.transactions(it, accountId, offset, limit) }
        } catch (e: UnreachableException) {
            local?.page(accountId, offset, limit) ?: throw e
        }
    }

    /** Every transaction with this payee, newest first, from the offline copy (empty until it's downloaded). */
    suspend fun payeeTransactions(payeeId: String): List<Transaction> =
        offline.current()?.items?.filter { it.payeeId == payeeId }.orEmpty()

    private suspend fun pushPending(connection: Connection) {
        for (item in queue.all()) {
            if (item.rejectedReason != null) continue
            try {
                client.create(connection, item.request)
                queue.update { list -> list.filterNot { it.request.clientId == item.request.clientId } }
            } catch (e: BridgeException) {
                queue.update { list ->
                    list.map {
                        if (it.request.clientId != item.request.clientId) it
                        else it.copy(
                            attempts = it.attempts + 1,
                            rejectedReason = if (e.isPermanent) e.message else null,
                            lastError = e.message,
                        )
                    }
                }
                if (e.status == 401) throw e
            }
            _state.update { it.copy(pending = queue.all()) }
        }
    }

    /** Runs [block]; if the Mac is unreachable, looks it up again over Bonjour (new IP) and retries once. */
    private suspend fun <T> withReconnect(bridge: PairedBridge, block: suspend (Connection) -> T): T = try {
        block(bridge.connection)
    } catch (e: UnreachableException) {
        val found = discovery.find(bridge.name) ?: throw e
        if (found.host == bridge.host && found.port == bridge.port) throw e
        settings.updateAddress(found.host, found.port)
        block(bridge.copy(host = found.host, port = found.port).connection)
    }

    companion object {
        const val PAGE_SIZE = 100
        private const val SNAPSHOT_FILE = "snapshot.json"
    }
}
