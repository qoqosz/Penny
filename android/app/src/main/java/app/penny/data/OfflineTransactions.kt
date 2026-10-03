package app.penny.data

import kotlinx.serialization.Serializable

/** Every transaction as of one bridge generation, newest first (the bridge's order). */
@Serializable
data class TransactionDump(val generation: String, val items: List<Transaction>) {
    /** Same paging and filtering as the bridge's `GET transactions`. */
    fun page(accountId: String?, offset: Int, limit: Int): TransactionPage {
        val matching = if (accountId == null) items else items.filter { it.accountId == accountId }
        return TransactionPage(generation, matching.size, offset, matching.drop(offset).take(limit))
    }
}

/**
 * A full local copy of the Mac's transactions, so every account's list can be browsed without the Mac.
 * It is downloaded again whenever the bridge generation changes.
 */
class OfflineTransactions(private val client: BridgeClient, private val store: JsonStore) {
    @Volatile private var loaded: TransactionDump? = null

    suspend fun current(): TransactionDump? =
        loaded ?: store.read(FILE, TransactionDump.serializer()).also { loaded = it }

    /** Downloads everything unless the copy already matches [generation]. Keeps the old copy if the Mac's data changes mid-download. */
    suspend fun update(connection: Connection, generation: String) {
        if (current()?.generation == generation) return
        val items = ArrayList<Transaction>()
        var pageGeneration: String? = null
        do {
            val page = client.transactions(connection, null, items.size, PAGE_LIMIT)
            if (pageGeneration != null && page.generation != pageGeneration) return
            pageGeneration = page.generation
            items += page.items
        } while (page.items.isNotEmpty() && items.size < page.total)
        val dump = TransactionDump(pageGeneration ?: generation, items)
        store.write(FILE, TransactionDump.serializer(), dump)
        loaded = dump
    }

    suspend fun clear() {
        loaded = null
        store.delete(FILE)
    }

    companion object {
        /** The bridge's maximum page size. */
        private const val PAGE_LIMIT = 1000
        private const val FILE = "transactions.json"
    }
}
