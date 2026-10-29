package app.penny.data

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

// Mirrors the penny-bridge API (bridge/Sources/PennyBridge/MoneyReader.swift).

@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: Int,
    val currency: String,
    val folder: String? = null,
    val folderId: String? = null,
    val closed: Boolean = false,
    val sortOrder: Int = 0,
    val balance: String,
    val transactionCount: Int = 0,
    val lastTransactionDate: String? = null,
    val hasInvestments: Boolean = false,
    /** Securities held now. Bridges older than investment support send none. */
    val holdings: List<Holding> = emptyList(),
) {
    /** The cash; investment accounts also hold [holdings] (see [Valuation]). */
    val balanceValue: BigDecimal get() = balance.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** Accounts the user hid in settings, alone or with their folder, stay out of lists, totals and the account picker. */
    fun isHidden(hidden: HiddenAccounts): Boolean =
        id in hidden.accounts || (folderId != null && folderId in hidden.folders)

    /** The group the account is listed in: its Money folder (by name from bridges before folder IDs), "" outside folders. */
    val groupKey: String get() = folderId ?: folder ?: ""
}

@Serializable
data class Holding(
    val securityId: String,
    /**
     * Negative when only a sale was entered, e.g. a pension account topped up from outside, recorded as selling units
     * so the cash goes up. Such holdings are worth nothing (the cash already counts), never a short position.
     */
    val shares: String,
) {
    val sharesValue: BigDecimal get() = shares.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

/** A stock, fund or other asset traded in investment accounts (Money's `TradableAsset`). */
@Serializable
data class Security(
    val id: String,
    val name: String,
    /** The Yahoo Finance ticker Money downloads quotes for; null when the price is entered by hand. */
    val symbol: String? = null,
    val exchange: String? = null,
    val currency: String? = null,
    /** Money's last quote, or without one the price of the last buy or sell, in [priceCurrency]. */
    val price: String? = null,
    val priceCurrency: String? = null,
    val priceDate: String? = null,
    /** The first transaction with it (ISO instant). */
    val firstDate: String? = null,
)

/** A buy, sell, dividend or split of a security. */
@Serializable
data class Investment(
    val securityId: String,
    /** The account holding the shares (usually the transaction's own). */
    val accountId: String,
    /** "buy", "sell", "dividend", "split" or "other". */
    val type: String,
    /** Shares bought or sold, unsigned. */
    val shares: String = "0",
    /** Per share, in the transaction's currency. */
    val price: String? = null,
    /** A split turns [splitFrom] shares into [splitTo]. */
    val splitTo: Int? = null,
    val splitFrom: Int? = null,
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val fullName: String,
    val parentId: String? = null,
    val kind: String,
    val usageCount: Int = 0,
    /** A glyph from Money.app, drawn tinted (see [IconStore]). */
    val iconId: String? = null,
)

@Serializable
data class Payee(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val usageCount: Int = 0,
    /** The payee's logo in Money (see [IconStore]). */
    val iconId: String? = null,
)

@Serializable
data class Split(
    val id: String,
    val amount: String,
    val categoryId: String? = null,
    val category: String? = null,
    val note: String? = null,
    val transferAccountId: String? = null,
    /** A transfer's other side (older bridges don't send it). */
    val transferTransactionId: String? = null,
)

@Serializable
data class Tag(
    val name: String,
    /** Money's color name ("blue", "red", …), or null for no color. */
    val color: String? = null,
)

/** Where the transaction happened, as Money recorded it. */
@Serializable
data class Location(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zip: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}

@Serializable
data class Transaction(
    val id: String,
    val accountId: String,
    val date: String,
    val payee: String? = null,
    val payeeId: String? = null,
    val note: String? = null,
    val number: String? = null,
    val amount: String,
    val currency: String,
    val kind: String,
    val reconciled: Int = 0,
    val splits: List<Split> = emptyList(),
    // Not sent by bridges older than the transaction details sheet.
    val tags: List<Tag> = emptyList(),
    val location: Location? = null,
    /** Set when the transaction was in another currency: the signed amount in [originalCurrency]. */
    val originalAmount: String? = null,
    val originalCurrency: String? = null,
    /** One unit of [originalCurrency] in [currency]. */
    val exchangeRate: String? = null,
    val investment: Investment? = null,
    /**
     * The phone may change or delete it: it was added from the phone and is still a plain one (one split, no
     * security, not reconciled). Older bridges never set it.
     */
    val editable: Boolean = false,
) {
    val instant: Instant get() = runCatching { Instant.parse(date) }.getOrDefault(Instant.EPOCH)
    val amountValue: BigDecimal get() = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** This one and, for a transfer, its other side: a change or deletion on the phone covers both. */
    val sides: List<String> get() = listOf(id) + splits.mapNotNull { it.transferTransactionId }.filter { it != id }

    /**
     * As the add form would send it, to change or delete it: a transfer goes from the account it leaves, an amount in
     * another currency stays in it.
     */
    fun toRequest(clientId: String): NewTransactionRequest {
        val signed = originalAmount?.toBigDecimalOrNull() ?: amountValue
        val other = splits.singleOrNull()?.transferAccountId
        val transfer = kind == NewTransactionRequest.TRANSFER && other != null
        val outgoing = signed.signum() < 0
        return NewTransactionRequest(
            clientId = clientId,
            transactionId = id,
            accountId = if (transfer && !outgoing) other!! else accountId,
            transferAccountId = if (!transfer) null else if (outgoing) other else accountId,
            date = date,
            kind = when {
                transfer -> NewTransactionRequest.TRANSFER
                outgoing -> Kind.EXPENSE.api
                else -> Kind.INCOME.api
            },
            amount = signed.abs().toPlainString(),
            categoryId = if (transfer) null else splits.singleOrNull()?.categoryId,
            payeeName = payee,
            note = note,
            currency = originalCurrency,
            exchangeRate = exchangeRate.takeIf { originalCurrency != null },
        )
    }
}

@Serializable
data class Snapshot(
    /** Changes only when the transactions do (older bridges: whenever Money's database does). */
    val generation: String,
    /** Sent back as `If-None-Match`, so the Mac answers 304 while nothing changed. Null from older bridges. */
    val etag: String? = null,
    val bridgeVersion: String,
    val writesEnabled: Boolean,
    /** False from bridges that can't add transfers between accounts (they reject them). */
    val writesTransfers: Boolean = false,
    /** Transactions marked [Transaction.editable] can be changed and deleted (false from older bridges). */
    val writesEdits: Boolean = false,
    val defaultCurrency: String? = null,
    /**
     * The currencies set up in Money, the default one first. Null from bridges that can only add transactions in the
     * account's own currency.
     */
    val currencies: List<String>? = null,
    val accounts: List<Account>,
    val categories: List<Category>,
    val payees: List<Payee>,
    /** Securities in Money's investment accounts. Null from bridges that only know the accounts' cash. */
    val securities: List<Security>? = null,
) {
    // Delegated properties aren't serialized.
    private val payeesById by lazy { payees.associateBy { it.id } }
    private val categoriesById by lazy { categories.associateBy { it.id } }
    private val securitiesById by lazy { securities.orEmpty().associateBy { it.id } }

    /** Money's default currency, or else the one most accounts use. */
    val mainCurrency: String?
        get() = defaultCurrency ?: accounts.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key

    /** Every currency that needs exchange rates: Money's own and the accounts'. */
    val allCurrencies: Set<String>
        get() = buildSet {
            mainCurrency?.let(::add)
            currencies?.let(::addAll)
            accounts.mapTo(this) { it.currency }
        } - ""

    /** Currencies to download exchange rates for: [allCurrencies] and the ones securities are priced in. */
    val rateCurrencies: Set<String>
        get() = allCurrencies + securities.orEmpty().flatMap { listOfNotNull(it.currency, it.priceCurrency) } - ""

    fun payee(id: String?): Payee? = id?.let(payeesById::get)
    fun category(id: String?): Category? = id?.let(categoriesById::get)
    fun security(id: String?): Security? = id?.let(securitiesById::get)
}

@Serializable
data class TransactionPage(
    val generation: String,
    val total: Int,
    val offset: Int,
    val items: List<Transaction>,
)

@Serializable
data class NewTransactionRequest(
    val clientId: String,
    val accountId: String,
    /** ISO-8601 instant. */
    val date: String,
    /** "expense", "income" or [TRANSFER] (from [accountId] to [transferAccountId]). */
    val kind: String,
    /** Positive decimal with "." separator. */
    val amount: String,
    /** Transfers have none. */
    val categoryId: String? = null,
    /** Where a transfer goes; the Mac adds it to both accounts, with a minus in [accountId] and a plus in this one. */
    val transferAccountId: String? = null,
    val payeeName: String? = null,
    val note: String? = null,
    /** Set when [amount] is in another currency than the account's: one of [Snapshot.currencies]. */
    val currency: String? = null,
    /** One unit of [currency] in the account's currency, "." separator. */
    val exchangeRate: String? = null,
    /**
     * Set to change a transaction the Mac has (either side of a transfer): it gets these values. [clientId] is then the
     * change's own.
     */
    val transactionId: String? = null,
) {
    val isTransfer: Boolean get() = kind == TRANSFER

    fun involves(accountId: String): Boolean = this.accountId == accountId || transferAccountId == accountId

    /** Signed as [accountId]'s list shows it: a transfer is a plus in the account it goes to. */
    fun signedAmount(accountId: String? = null): String = when {
        isTransfer -> if (accountId != null && accountId == transferAccountId) amount else "-$amount"
        kind == Kind.EXPENSE.api -> "-$amount"
        else -> amount
    }

    companion object {
        const val TRANSFER = "transfer"
    }
}

/** `DELETE`s go in a batch's `deletes`: the transaction (a transfer loses both sides). */
@Serializable
data class DeleteTransactionRequest(val clientId: String, val transactionId: String)

enum class PendingChange { CREATE, UPDATE, DELETE }

/** A transaction entered, changed or deleted on the phone that the Mac hasn't confirmed yet. */
@Serializable
data class PendingTransaction(
    /**
     * What the Mac is to have: a new transaction; new values for one it has ([original] set, with
     * [NewTransactionRequest.transactionId]); or for [delete] the one to delete, as it is.
     */
    val request: NewTransactionRequest,
    val createdAt: Long,
    val attempts: Int = 0,
    /** Set when the bridge rejected the transaction; it won't be retried automatically. */
    val rejectedReason: String? = null,
    val lastError: String? = null,
    /** For a change or deletion: the transaction as the Mac has it, which balances leave out while this waits. */
    val original: NewTransactionRequest? = null,
    val delete: Boolean = false,
    /** For a change or deletion: the Mac's transactions it replaces (a transfer's both sides). */
    val replaces: List<String> = emptyList(),
) {
    val change: PendingChange
        get() = when {
            delete -> PendingChange.DELETE
            original != null -> PendingChange.UPDATE
            else -> PendingChange.CREATE
        }

    /** The Mac's transactions that lists leave out for this one; a rejected one leaves them shown, as they stay. */
    val hides: List<String> get() = if (rejectedReason == null) replaces else emptyList()
}

/**
 * `GET cloud`: what Money on the Mac hasn't uploaded to iCloud yet. The bridge only writes Money's database; Money
 * sends the changes on its next sync.
 */
@Serializable
data class CloudStatus(
    /** Money only uploads while it runs. */
    val moneyRunning: Boolean = false,
    /** The oldest first. */
    val waiting: List<CloudWaiting> = emptyList(),
) {
    private val byId by lazy { waiting.associateBy { it.transactionId } }

    fun waiting(transactionId: String): CloudWaiting? = byId[transactionId]

    /** Changes that should have been uploaded by [now]: Money usually sends them within a minute or two. */
    fun overdue(now: Instant): List<CloudWaiting> =
        waiting.filter { w -> w.sinceInstant?.let { it.plus(OVERDUE).isBefore(now) } ?: false }

    companion object {
        val OVERDUE: java.time.Duration = java.time.Duration.ofMinutes(10)
    }
}

@Serializable
data class CloudWaiting(
    val transactionId: String,
    /** "new", "changed" or "deleted". */
    val change: String,
    /** When it was saved on the Mac (ISO instant). */
    val since: String? = null,
) {
    val sinceInstant: Instant? get() = since?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** An image from Money, encoded as PNG or JPEG. */
@Serializable
data class IconData(val id: String, val contentType: String, /** Base64. */ val data: String)

@Serializable
data class IconBatch(val icons: List<IconData>)

@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val token: String, val name: String)

/** [tlsPort] and [fingerprint] (the certificate's SHA-256) when the bridge also serves HTTPS. */
@Serializable
data class Ping(
    val app: String,
    val name: String,
    val version: String,
    val tlsPort: Int? = null,
    val fingerprint: String? = null,
)

/** `DELETE device`. */
@Serializable
data class Revoked(val revoked: Boolean = false)

@Serializable
data class ApiErrorBody(val error: ApiErrorInner)

@Serializable
data class ApiErrorInner(val code: String, val message: String)

/**
 * `POST transactions/batch`: the queue in one write, so Money on the Mac is quit and relaunched once. Bridges older
 * than editing ignore [updates] and [deletes] (and never mark a transaction editable, so there are none).
 */
@Serializable
data class NewTransactionBatch(
    val transactions: List<NewTransactionRequest>,
    val updates: List<NewTransactionRequest> = emptyList(),
    val deletes: List<DeleteTransactionRequest> = emptyList(),
)

@Serializable
data class BatchResults(val results: List<BatchResult>)

/** One change of a batch: written ([transactionId], the transaction it added, changed or deleted) or refused ([error], with the status it would have had alone). */
@Serializable
data class BatchResult(val clientId: String, val transactionId: String? = null, val error: BatchError? = null)

@Serializable
data class BatchError(val status: Int, val code: String, val message: String)

enum class Kind(val api: String) {
    EXPENSE("expense"), INCOME("income");
}
