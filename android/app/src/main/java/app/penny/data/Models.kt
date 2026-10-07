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
) {
    val instant: Instant get() = runCatching { Instant.parse(date) }.getOrDefault(Instant.EPOCH)
    val amountValue: BigDecimal get() = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

@Serializable
data class Snapshot(
    val generation: String,
    val bridgeVersion: String,
    val writesEnabled: Boolean,
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
    /** "expense" or "income". */
    val kind: String,
    /** Positive decimal with "." separator. */
    val amount: String,
    val categoryId: String? = null,
    val payeeName: String? = null,
    val note: String? = null,
    /** Set when [amount] is in another currency than the account's: one of [Snapshot.currencies]. */
    val currency: String? = null,
    /** One unit of [currency] in the account's currency, "." separator. */
    val exchangeRate: String? = null,
)

/** A transaction entered on the phone that the Mac hasn't confirmed yet. */
@Serializable
data class PendingTransaction(
    val request: NewTransactionRequest,
    val createdAt: Long,
    val attempts: Int = 0,
    /** Set when the bridge rejected the transaction; it won't be retried automatically. */
    val rejectedReason: String? = null,
    val lastError: String? = null,
)

/** An image from Money, encoded as PNG or JPEG. */
@Serializable
data class IconData(val id: String, val contentType: String, /** Base64. */ val data: String)

@Serializable
data class IconBatch(val icons: List<IconData>)

@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val token: String, val name: String)

@Serializable
data class Ping(val app: String, val name: String, val version: String)

@Serializable
data class ApiErrorBody(val error: ApiErrorInner)

@Serializable
data class ApiErrorInner(val code: String, val message: String)

enum class Kind(val api: String) {
    EXPENSE("expense"), INCOME("income");
}
