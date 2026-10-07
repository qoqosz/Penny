package app.penny.data

import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/** Searching needs every transaction on the phone, and there is no offline copy yet. */
class SearchUnavailableException : Exception("No offline copy of transactions to search")

/**
 * A search box query: every word must appear somewhere in the transaction (payee, category, note, tags, account,
 * amount, …), ignoring case and accents, so "zabka" finds "Żabka". "12,5" finds an amount of 12.50.
 */
class TransactionSearch(query: String) {
    private val words = normalize(query).split(WHITESPACE).filter { it.isNotEmpty() }

    val isEmpty: Boolean get() = words.isEmpty()

    /** The matching [items], in their order. */
    fun filter(items: List<Transaction>, snapshot: Snapshot?): List<Transaction> {
        if (isEmpty) return items
        val accounts = snapshot?.accounts.orEmpty().associate { it.id to it.name }
        return items.filter { matches(fields(it, snapshot, accounts)) }
    }

    fun matches(tx: Transaction, snapshot: Snapshot?): Boolean =
        isEmpty || matches(fields(tx, snapshot, snapshot?.accounts.orEmpty().associate { it.id to it.name }))

    /** A transaction still waiting to reach the Mac. */
    fun matches(request: NewTransactionRequest, snapshot: Snapshot?): Boolean = isEmpty || matches(
        listOf(
            request.payeeName, request.note, request.currency,
            snapshot?.category(request.categoryId)?.fullName,
            snapshot?.accounts?.firstOrNull { it.id == request.accountId }?.name,
            amountText(request.amount),
        ),
    )

    private fun matches(fields: List<String?>): Boolean {
        // Fields are joined by a line break, which no word contains, so a word never spans two fields.
        val text = fields.filterNotNull().joinToString("\n", transform = ::normalize)
        return words.all { it in text }
    }

    private fun fields(tx: Transaction, snapshot: Snapshot?, accounts: Map<String, String>): List<String?> = buildList {
        add(tx.payee)
        add(tx.note)
        add(tx.number)
        add(tx.currency)
        add(tx.originalCurrency)
        add(accounts[tx.accountId])
        for (split in tx.splits) {
            add(snapshot?.category(split.categoryId)?.fullName ?: split.category)
            add(split.note)
            add(split.transferAccountId?.let(accounts::get))
        }
        tx.tags.forEach { add(it.name) }
        tx.location?.let { addAll(listOf(it.street, it.city, it.country)) }
        snapshot?.security(tx.investment?.securityId)?.let { addAll(listOf(it.name, it.symbol)) }
        add(amountText(tx.amount))
        tx.originalAmount?.let { add(amountText(it)) }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{M}+")
        private val DECIMAL_COMMA = Regex("(?<=\\d),(?=\\d)")

        /** Lowercase, without accents (ł has none to strip, so it is mapped by hand), with "." as the decimal separator. */
        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(MARKS, "")
                .replace('ł', 'l')
                .replace(DECIMAL_COMMA, ".")

        /** The unsigned amount with at least two decimals ("-12.5" → "12.50"), as people type it. */
        private fun amountText(amount: String): String? {
            val value = amount.toBigDecimalOrNull()?.abs() ?: return null
            return value.setScale(maxOf(2, value.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY).toPlainString()
        }
    }
}
