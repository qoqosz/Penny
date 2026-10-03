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
) {
    val balanceValue: BigDecimal get() = balance.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** Accounts in folders the user hid in settings stay out of lists, totals and the account picker. */
    fun isHidden(hiddenFolders: Set<String>): Boolean = folderId != null && folderId in hiddenFolders
}

@Serializable
data class Category(
    val id: String,
    val name: String,
    val fullName: String,
    val parentId: String? = null,
    val kind: String,
    val usageCount: Int = 0,
)

@Serializable
data class Payee(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val usageCount: Int = 0,
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
data class Transaction(
    val id: String,
    val accountId: String,
    val date: String,
    val payee: String? = null,
    val note: String? = null,
    val number: String? = null,
    val amount: String,
    val currency: String,
    val kind: String,
    val reconciled: Int = 0,
    val splits: List<Split> = emptyList(),
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
    val accounts: List<Account>,
    val categories: List<Category>,
    val payees: List<Payee>,
)

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
