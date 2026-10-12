package app.penny.data

import java.math.BigDecimal

/**
 * How much each account's balance changes (by account ID, in its own currency) once the Mac saves [pending]: a minus
 * for an expense, a plus for income, and for a transfer both. Rejected transactions don't count, since they won't reach
 * Money unless retried.
 */
fun pendingBalanceChanges(pending: List<PendingTransaction>): Map<String, BigDecimal> {
    val changes = mutableMapOf<String, BigDecimal>()
    for (item in pending) {
        if (item.rejectedReason != null) continue
        val r = item.request
        // An amount in another currency is converted at the rate the form sent.
        val rate = r.exchangeRate?.toBigDecimalOrNull() ?: BigDecimal.ONE
        for (accountId in listOfNotNull(r.accountId, r.transferAccountId)) {
            val amount = r.signedAmount(accountId).toBigDecimalOrNull() ?: continue
            changes.merge(accountId, amount * rate, BigDecimal::add)
        }
    }
    return changes
}

/** The snapshot with account balances as they'll be once the Mac saves [pending] (see [pendingBalanceChanges]). */
fun Snapshot.withPending(pending: List<PendingTransaction>): Snapshot {
    val changes = pendingBalanceChanges(pending)
    if (changes.isEmpty()) return this
    return copy(accounts = accounts.map { a ->
        changes[a.id]?.let { a.copy(balance = (a.balanceValue + it).toPlainString()) } ?: a
    })
}
