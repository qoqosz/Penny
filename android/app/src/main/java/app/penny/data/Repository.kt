package app.penny.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Offline(val hasCachedData: Boolean) : SyncStatus
    /** [message] comes from the bridge. */
    data class Error(val message: String) : SyncStatus
    /** Something on the phone's side: an answer it can't read, storage that can't be written. */
    data class Failed(val error: Throwable) : SyncStatus
    /** The Mac's address answered with another certificate than the pinned one (see [UntrustedBridgeException]). */
    data object Untrusted : SyncStatus
}

data class AppState(
    val snapshot: Snapshot? = null,
    val pending: List<PendingTransaction> = emptyList(),
    val status: SyncStatus = SyncStatus.Idle,
    /** Bumped whenever the Mac's data changed, so open transaction lists reload. */
    val dataVersion: Int = 0,
    /** The generation of the offline copy of transactions, once there is one (see [Repository.allTransactions]). */
    val offlineGeneration: String? = null,
    /** What Money on the Mac hasn't uploaded to iCloud yet; null while unknown (or from a bridge that can't tell). */
    val cloud: CloudStatus? = null,
    /** When the phone last got the Mac's data (epoch ms), so screens can say how old the balances are. */
    val updatedAt: Long? = null,
) {
    /** [snapshot] with the balances the queued transactions will make; account lists and totals show these. */
    val snapshotWithPending: Snapshot? by lazy { snapshot?.withPending(pending) }

    /** The Mac's transactions that queued changes and deletions replace; lists show those instead. */
    val replaced: Set<String> by lazy { pending.flatMapTo(HashSet()) { it.hides } }
}

/** `freshness.json`: when the Mac's data was last downloaded or confirmed unchanged. */
@Serializable
private data class Freshness(val updatedAt: Long)

class Repository(
    private val settings: Settings,
    private val client: BridgeClient,
    private val discovery: BridgeFinder,
    private val cache: JsonStore,
    private val queue: PendingQueue,
    val icons: IconStore,
    /** Exchange rates for Money's currencies; they come from the ECB, not from the Mac. */
    val rates: CurrencyRates,
    /** Prices of the securities in investment accounts; they come from Yahoo Finance, not from the Mac. */
    val prices: SecurityPrices,
    private val scope: CoroutineScope,
    /** Schedules a background sync (WorkManager) for when the network is back. */
    private val scheduleSync: () -> Unit,
    /** In [SyncMode.MANUAL], queued transactions wait for [syncNow]. */
    val syncMode: StateFlow<SyncMode>,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val syncMutex = Mutex()
    private val offline = OfflineTransactions(client, cache)

    /** Investment accounts' securities at the latest prices and rates. */
    val valuation: StateFlow<Valuation?> = combine(
        state.map { it.snapshot }.distinctUntilChanged(),
        prices.state.map { it.prices }.distinctUntilChanged(),
        rates.state.map { it.rates }.distinctUntilChanged(),
    ) { snapshot, prices, rates -> snapshot?.let { Valuation(it, prices, rates) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Reads what was stored (snapshot, queue, offline copy); done before the first changes are made in tests. */
    val loaded = scope.launch {
        val cached = cache.read(SNAPSHOT_FILE, Snapshot.serializer())
        val offlineGeneration = offline.current()?.generation
        val updatedAt = cache.read(FRESHNESS_FILE, Freshness.serializer())?.updatedAt
        _state.update {
            it.copy(
                snapshot = it.snapshot ?: cached, pending = queue.all(),
                offlineGeneration = it.offlineGeneration ?: offlineGeneration, updatedAt = it.updatedAt ?: updatedAt,
            )
        }
        icons.load()
        rates.load()
        prices.load()
    }

    init {
        // Back to automatic sync: send what manual mode kept waiting.
        scope.launch {
            syncMode.drop(1).collect { mode ->
                if (mode == SyncMode.AUTOMATIC && _state.value.pending.any { it.rejectedReason == null }) refresh()
            }
        }
    }

    /** With [DiscoveredBridge.tls] (see [identify]), over HTTPS to that certificate only, which stays pinned. */
    suspend fun pair(bridge: DiscoveredBridge, code: String, deviceName: String) {
        val response = client.pair(bridge.host, bridge.port, bridge.tls, code.filter(Char::isDigit), deviceName)
        settings.save(PairedBridge(response.name, bridge.host, bridge.port, response.token, bridge.tls))
        cache.clear()
        offline.clear()
        icons.clear()
        _state.update { it.copy(snapshot = null, offlineGeneration = null, cloud = null, updatedAt = null) }
        refresh()
    }

    suspend fun probe(host: String, port: Int): DiscoveredBridge {
        val ping = client.ping(host, port)
        return DiscoveredBridge(ping.name, host, port, ping.tlsPort)
    }

    /**
     * Before pairing: whether the bridge serves HTTPS and which certificate it presents. That one is trusted on first
     * use and pinned; the user compares its short form with what `penny-bridge pair` prints on the Mac.
     */
    suspend fun identify(bridge: DiscoveredBridge): DiscoveredBridge {
        val tlsPort = bridge.tlsPort ?: client.ping(bridge.host, bridge.port).tlsPort ?: return bridge.copy(tls = null)
        return bridge.copy(tlsPort = tlsPort, tls = BridgeTls(tlsPort, client.certificate(bridge.host, tlsPort)))
    }

    suspend fun unpair() {
        // The Mac forgets the token, as far as it can be told: unreachable or too old (404), unpairing goes on anyway.
        settings.current()?.let { client.revokeDevice(it.connection) }
        settings.clear()
        cache.clear()
        offline.clear()
        icons.clear()
        _state.update { AppState(pending = it.pending) }
    }

    private val autoSync get() = syncMode.value == SyncMode.AUTOMATIC

    /**
     * Sends queued transactions (when [push]; by default only in automatic mode), then reloads accounts and categories
     * and the offline copy of transactions.
     */
    suspend fun refresh(push: Boolean = autoSync) = syncMutex.withLock {
        val bridge = settings.current()?.let { pinIfOffered(it) } ?: return@withLock
        _state.update { it.copy(status = SyncStatus.Syncing) }
        val status = try {
            val connection = withReconnect(bridge) { c -> if (push) pushPending(c); c }
            val cached = _state.value.snapshot
            // Null (304) while the Mac's snapshot is still the cached one.
            val snapshot = client.snapshotIfChanged(connection, cached?.etag) ?: cached ?: client.snapshot(connection)
            val changed = snapshot.generation != cached?.generation
            if (snapshot !== cached) cache.write(SNAPSHOT_FILE, Snapshot.serializer(), snapshot)
            val updatedAt = System.currentTimeMillis()
            cache.write(FRESHNESS_FILE, Freshness.serializer(), Freshness(updatedAt))
            // Together, so balances never count a sent transaction twice (queued and in the snapshot) or not at all.
            _state.update {
                it.copy(snapshot = snapshot, pending = queue.all(), dataVersion = it.dataVersion + if (changed) 1 else 0, updatedAt = updatedAt)
            }
            offline.update(connection, snapshot.generation)
            _state.update { it.copy(offlineGeneration = offline.current()?.generation, cloud = cloudStatus(connection)) }
            // Icons can take a while the first time; the lists show them as they arrive.
            scope.launch { syncIcons(connection, snapshot) }
            if (autoSync && _state.value.pending.any { it.rejectedReason == null }) scheduleSync()
            SyncStatus.Idle
        } catch (e: UnreachableException) {
            if (autoSync && _state.value.pending.isNotEmpty()) scheduleSync()
            SyncStatus.Offline(hasCachedData = _state.value.snapshot != null)
        } catch (e: BridgeException) {
            SyncStatus.Error(e.message.orEmpty())
        } catch (e: UntrustedBridgeException) {
            SyncStatus.Untrusted
        } catch (e: CancellationException) {
            _state.update { it.copy(status = SyncStatus.Idle) }
            throw e
        } catch (e: Exception) {
            // Never left at Syncing, and never a crash: refresh runs on every return to the app.
            SyncStatus.Failed(e)
        }
        _state.update { it.copy(status = status, pending = runCatching { queue.all() }.getOrDefault(it.pending)) }
        // Also without the Mac: rates and prices come from the internet.
        scope.launch { updateRates() }
        scope.launch { updatePrices() }
    }

    /**
     * Downloads the exchange rates between Money's currencies, back to the first transaction. Without [force],
     * a recent download is kept.
     */
    suspend fun updateRates(force: Boolean = false) = extra {
        val snapshot = _state.value.snapshot ?: return@extra
        val currencies = snapshot.rateCurrencies
        if (currencies.size < 2) return@extra
        val zone = ZoneId.systemDefault()
        val first = offline.current()?.items?.minOfOrNull { it.instant }?.atZone(zone)?.toLocalDate()
        rates.update(currencies, first ?: LocalDate.now(zone).minusYears(1), force)
    }

    /**
     * Downloads the prices of the securities in investment accounts, each back to its first transaction. Without
     * [force], recently downloaded ones are kept.
     */
    suspend fun updatePrices(force: Boolean = false) = extra {
        val securities = _state.value.snapshot?.securities ?: return@extra
        val zone = ZoneId.systemDefault()
        val fallback = LocalDate.now(zone).minusYears(1)
        val wanted = HashMap<String, LocalDate>()
        for (s in securities) {
            val symbol = s.symbol ?: continue
            val from = s.firstDate?.let { runCatching { Instant.parse(it) }.getOrNull() }?.atZone(zone)?.toLocalDate() ?: fallback
            wanted[symbol] = wanted[symbol]?.let { minOf(it, from) } ?: from
        }
        prices.update(wanted, force)
    }

    // Icons are decoration: on failure the lists fall back to plain markers, and the next refresh picks up
    // where this one stopped.
    private suspend fun syncIcons(connection: Connection, snapshot: Snapshot) = extra {
        icons.sync(snapshot) { ids -> client.icons(connection, ids).icons }
    }

    /** Asks the Mac again which changes Money hasn't uploaded to iCloud yet (cheap: no snapshot). */
    suspend fun refreshCloud() {
        val bridge = settings.current() ?: return
        val cloud = withReconnect(bridge) { cloudStatus(it) } ?: return
        _state.update { it.copy(cloud = cloud) }
    }

    /** Null when the bridge can't tell (too old, no SyncKit database) or the call fails; it's only shown, never needed. */
    private suspend fun cloudStatus(connection: Connection): CloudStatus? = try {
        client.cloud(connection)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Rates, prices and icons are extras: a failure keeps the older ones, and the next refresh tries again. */
    private suspend fun extra(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Sends queued transactions now, whatever the sync mode, then reloads. */
    suspend fun syncNow() = refresh(push = true)

    /** Background sync entry point. Returns true when nothing is left to retry. */
    suspend fun syncInBackground(): Boolean {
        // Switched to manual sync since this was scheduled: the queue waits for the user.
        if (!autoSync) return true
        refresh()
        return _state.value.pending.none { it.rejectedReason == null }
    }

    /** Queues [request]; adding the same [NewTransactionRequest.clientId] again changes nothing. */
    suspend fun add(request: NewTransactionRequest) {
        val pending = queue.update { list ->
            if (list.any { it.request.clientId == request.clientId }) list
            else list + PendingTransaction(request, System.currentTimeMillis())
        }
        publish(pending)
    }

    /**
     * Queues new values for a transaction the Mac has ([original], one that is [Transaction.editable]). A change of it
     * already queued gets them instead.
     */
    suspend fun edit(original: Transaction, request: NewTransactionRequest) {
        val pending = queue.update { list ->
            val queued = list.firstOrNull { it.change == PendingChange.UPDATE && it.request.transactionId == original.id }
            if (queued != null) {
                list.map { if (it === queued) it.withRequest(request) else it }
            } else {
                list + PendingTransaction(
                    request.copy(transactionId = original.id), System.currentTimeMillis(),
                    original = original.toRequest(request.clientId), replaces = original.sides,
                )
            }
        }
        publish(pending)
    }

    /** Queues the deletion of a transaction the Mac has (one that is [Transaction.editable]), in place of a queued change of it. */
    suspend fun delete(transaction: Transaction) {
        val pending = queue.update { list ->
            val queued = list.firstOrNull { it.change != PendingChange.CREATE && it.request.transactionId == transaction.id }
            val original = queued?.original ?: transaction.toRequest(newClientId())
            list.filterNot { it === queued } + PendingTransaction(
                original.copy(clientId = newClientId()), System.currentTimeMillis(), original = original, delete = true,
                replaces = queued?.replaces ?: transaction.sides,
            )
        }
        publish(pending)
    }

    /**
     * New values for a queued transaction or change. It keeps its clientId: should the Mac already have the old values
     * (sent, but the answer lost), it takes these as a change of what it saved.
     */
    suspend fun updatePending(clientId: String, request: NewTransactionRequest) {
        val pending = queue.update { list ->
            list.map { if (it.request.clientId == clientId && !it.delete) it.withRequest(request) else it }
        }
        publish(pending)
    }

    private fun PendingTransaction.withRequest(request: NewTransactionRequest) = copy(
        request = request.copy(clientId = this.request.clientId, transactionId = this.request.transactionId),
        attempts = 0, rejectedReason = null, lastError = null,
    )

    private fun publish(pending: List<PendingTransaction>) {
        _state.update { it.copy(pending = pending) }
        if (autoSync) scope.launch { refresh() }
    }

    /** Transactions added on this phone and saved on the Mac since the app started: clientId → its ID and request. */
    private val sent = ConcurrentHashMap<String, Pair<String, NewTransactionRequest>>()

    /**
     * Takes back a transaction just added: still queued, it leaves the queue; already on the Mac, its deletion is
     * queued. A send in progress is waited for, so it's one or the other.
     */
    suspend fun undoAdd(clientId: String) {
        val deleting = syncMutex.withLock {
            if (queue.all().any { it.request.clientId == clientId }) {
                val pending = queue.update { list -> list.filterNot { it.request.clientId == clientId } }
                _state.update { it.copy(pending = pending) }
                false
            } else {
                val (id, request) = sent[clientId] ?: return
                val original = request.copy(transactionId = id)
                val sides = offline.current()?.items?.firstOrNull { it.id == id }?.sides ?: listOf(id)
                val item = PendingTransaction(
                    original.copy(clientId = newClientId()), System.currentTimeMillis(), original = original, delete = true,
                    replaces = sides,
                )
                val pending = queue.update { it + item }
                _state.update { it.copy(pending = pending) }
                true
            }
        }
        if (deleting && autoSync) refresh()
    }

    /** Transactions opened from a list, for [findTransaction] when the offline copy doesn't have them. */
    private val opened = ConcurrentHashMap<String, Transaction>()

    fun keep(transaction: Transaction) {
        opened[transaction.id] = transaction
    }

    /** A transaction of the Mac's by ID: one opened from a list ([keep]), else from the offline copy. */
    suspend fun findTransaction(id: String): Transaction? = opened[id] ?: offline.current()?.items?.firstOrNull { it.id == id }

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
        // "Send again" is an explicit request, so it sends in manual mode too.
        scope.launch { syncNow() }
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

    /**
     * The transactions matching [search] (in one account, or all when [accountId] is null), newest first. The Mac can't
     * search, so this always uses the offline copy, even an older one.
     */
    suspend fun searchTransactions(accountId: String?, search: TransactionSearch): List<Transaction> {
        val local = offline.current() ?: throw SearchUnavailableException()
        val snapshot = _state.value.snapshot
        return withContext(Dispatchers.Default) {
            search.filter(if (accountId == null) local.items else local.items.filter { it.accountId == accountId }, snapshot)
        }
    }

    /** Every transaction with this payee, newest first, from the offline copy (empty until it's downloaded). */
    suspend fun payeeTransactions(payeeId: String): List<Transaction> =
        offline.current()?.items?.filter { it.payeeId == payeeId }.orEmpty()

    /** Every transaction, newest first, from the offline copy (empty until it's downloaded). */
    suspend fun allTransactions(): List<Transaction> = offline.current()?.items.orEmpty()

    /** Sends the queue in batches, so the Mac quits and relaunches Money once rather than for every transaction. */
    private suspend fun pushPending(connection: Connection) {
        val waiting = queue.all().filter { it.rejectedReason == null }
        for (chunk in waiting.chunked(BATCH_SIZE)) {
            fun of(change: PendingChange) = chunk.filter { it.change == change }
            val batch = NewTransactionBatch(
                transactions = of(PendingChange.CREATE).map { it.request },
                updates = of(PendingChange.UPDATE).map { it.request },
                deletes = of(PendingChange.DELETE).map { DeleteTransactionRequest(it.request.clientId, it.request.transactionId.orEmpty()) },
            )
            val results = try {
                client.createBatch(connection, batch).results
            } catch (e: BridgeException) {
                // A bridge that old can't change transactions (and never offers to).
                if (e.status == 404) return pushOneByOne(connection, of(PendingChange.CREATE))
                // Nothing was written (Money in use, a problem on the Mac): every item gets the error.
                chunk.forEach { record(it, e) }
                if (e.status == 401) throw e
                continue
            }
            val byId = results.associateBy { it.clientId }
            for (item in chunk) {
                // Missing from the answer: left as it was, for the next sync.
                val result = byId[item.request.clientId] ?: continue
                record(item, result.error?.let { BridgeException(it.status, it.code, it.message) }, result.transactionId)
            }
        }
    }

    /** For bridges without `transactions/batch`. */
    private suspend fun pushOneByOne(connection: Connection, items: List<PendingTransaction>) {
        for (item in items) {
            try {
                record(item, null, client.create(connection, item.request).id)
            } catch (e: BridgeException) {
                record(item, e)
                if (e.status == 401) throw e
            }
        }
    }

    /** Takes a sent transaction off the queue ([error] null) or notes why it wasn't written. */
    private suspend fun record(item: PendingTransaction, error: BridgeException?, transactionId: String? = null) {
        val id = item.request.clientId
        if (error == null) {
            if (item.change == PendingChange.CREATE && transactionId != null) sent[id] = transactionId to item.request
            // It leaves the shown queue with the next snapshot, which has it (see refresh).
            queue.update { list -> list.filterNot { it.request.clientId == id } }
            return
        }
        val failed = item.copy(
            attempts = item.attempts + 1,
            rejectedReason = if (error.isPermanent) error.message else null,
            lastError = error.message,
        )
        queue.update { list -> list.map { if (it.request.clientId == id) failed else it } }
        // Only this one: the ones already sent stay shown until the snapshot has them.
        _state.update { s -> s.copy(pending = s.pending.map { if (it.request.clientId == id) failed else it }) }
    }

    /** When the last check that a pairing from before HTTPS could move to it ran (see [pinIfOffered]). */
    private var tlsCheckedAt = 0L

    /**
     * A pairing with a bridge from before HTTPS moves to it once the bridge offers it: the certificate the Mac presents
     * then is trusted on first use and pinned, and from then on the phone never talks to it over plain HTTP. Checked at
     * most hourly; on any failure it stays as it was until the next check.
     */
    private suspend fun pinIfOffered(bridge: PairedBridge): PairedBridge {
        if (bridge.tls != null || System.currentTimeMillis() - tlsCheckedAt < TLS_CHECK_INTERVAL_MS) return bridge
        tlsCheckedAt = System.currentTimeMillis()
        return try {
            val tlsPort = client.ping(bridge.host, bridge.port).tlsPort ?: return bridge
            val tls = BridgeTls(tlsPort, client.certificate(bridge.host, tlsPort))
            settings.pin(tls)
            bridge.copy(tls = tls)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bridge
        }
    }

    /**
     * Runs [block]; if the Mac is unreachable, looks it up again over Bonjour (new IP) and retries once. The new address
     * is kept only when it works: for a pinned pairing that means it presented the pinned certificate, so a device
     * posing as the Mac under its Bonjour name never gets the token.
     */
    private suspend fun <T> withReconnect(bridge: PairedBridge, block: suspend (Connection) -> T): T = try {
        block(bridge.connection)
    } catch (e: UnreachableException) {
        val found = discovery.find(bridge.name) ?: throw e
        val moved = bridge.copy(host = found.host, port = found.port,
            tls = bridge.tls?.let { it.copy(port = found.tlsPort ?: it.port) })
        if (moved == bridge) throw e
        block(moved.connection).also { settings.updateAddress(moved.host, moved.port, moved.tls?.port) }
    }

    companion object {
        const val PAGE_SIZE = 100
        /** The bridge takes at most 100 transactions per batch. */
        private const val BATCH_SIZE = 100
        private const val SNAPSHOT_FILE = "snapshot.json"
        private const val FRESHNESS_FILE = "freshness.json"
        private const val TLS_CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
