package app.penny

import app.penny.data.Account
import app.penny.data.NewTransactionRequest
import app.penny.data.PendingTransaction
import app.penny.data.Snapshot
import app.penny.data.pendingBalanceChanges
import app.penny.data.withPending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigDecimal

class PendingBalancesTest {
    private val snapshot = Snapshot(
        generation = "g", bridgeVersion = "1", writesEnabled = true,
        accounts = listOf(
            Account("a", "Konto", type = 0, currency = "PLN", balance = "1000"),
            Account("s", "Oszczędności", type = 0, currency = "PLN", balance = "500.50"),
            Account("x", "Inne", type = 0, currency = "PLN", balance = "7"),
        ),
        categories = emptyList(), payees = emptyList(),
    )

    private fun pending(kind: String, amount: String, accountId: String = "a", rejected: String? = null,
                        transferTo: String? = null, currency: String? = null, rate: String? = null) =
        PendingTransaction(
            NewTransactionRequest(
                clientId = "$kind-$amount", accountId = accountId, date = "2026-10-12T10:00:00Z", kind = kind,
                amount = amount, transferAccountId = transferTo, currency = currency, exchangeRate = rate,
            ),
            createdAt = 0, rejectedReason = rejected,
        )

    private fun balances(vararg items: PendingTransaction) =
        snapshot.withPending(items.toList()).accounts.associate { it.id to it.balanceValue }

    @Test fun `expenses and income change their account`() {
        val b = balances(pending("expense", "12.34"), pending("income", "100"))
        assertEquals(BigDecimal("1087.66"), b["a"])
        assertEquals(BigDecimal("500.50"), b["s"])
    }

    @Test fun `a transfer is a minus in one account and a plus in the other`() {
        val b = balances(pending(NewTransactionRequest.TRANSFER, "200", transferTo = "s"))
        assertEquals(BigDecimal("800"), b["a"])
        assertEquals(BigDecimal("700.50"), b["s"])
        assertEquals(BigDecimal("7"), b["x"])
    }

    @Test fun `another currency is converted at the form's rate`() {
        val b = balances(pending("expense", "10", currency = "EUR", rate = "4.25"))
        assertEquals(0, BigDecimal("957.50").compareTo(b["a"]))
    }

    @Test fun `a change counts the difference, a deletion takes the amount out`() {
        val original = NewTransactionRequest("o", "a", "2026-10-12T10:00:00Z", "expense", "10", transactionId = "T")
        val moved = PendingTransaction(original.copy(clientId = "c", accountId = "s", amount = "15"), 0, original = original)
        val b = balances(moved)
        assertEquals("the old amount goes back to its account", BigDecimal("1010"), b["a"])
        assertEquals(BigDecimal("485.50"), b["s"])
        val transfer = NewTransactionRequest("t", "a", "2026-10-12T10:00:00Z", NewTransactionRequest.TRANSFER, "200",
            transferAccountId = "s", transactionId = "T2")
        val deleted = balances(PendingTransaction(transfer, 0, original = transfer, delete = true))
        assertEquals(BigDecimal("1200"), deleted["a"])
        assertEquals(BigDecimal("300.50"), deleted["s"])
    }

    @Test fun `rejected transactions don't count`() {
        assertEquals(emptyMap<String, BigDecimal>(), pendingBalanceChanges(listOf(pending("expense", "5", rejected = "Nie"))))
        assertSame(snapshot, snapshot.withPending(listOf(pending("expense", "5", rejected = "Nie"))))
    }
}
