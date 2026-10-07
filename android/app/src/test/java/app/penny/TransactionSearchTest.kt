package app.penny

import app.penny.data.Account
import app.penny.data.Category
import app.penny.data.NewTransactionRequest
import app.penny.data.Snapshot
import app.penny.data.Split
import app.penny.data.Tag
import app.penny.data.Transaction
import app.penny.data.TransactionSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSearchTest {
    private val snapshot = Snapshot(
        generation = "g", bridgeVersion = "1", writesEnabled = true,
        accounts = listOf(
            Account("a", "Konto osobiste", type = 0, currency = "PLN", balance = "0"),
            Account("s", "Savings", type = 0, currency = "PLN", balance = "0"),
        ),
        categories = listOf(Category("food", "Groceries", "Food: Groceries", kind = "expense")),
        payees = emptyList(),
    )

    private val shopping = Transaction(
        id = "1", accountId = "a", date = "2026-10-01T10:00:00Z", payee = "Żabka", note = "Mleko i chleb",
        amount = "-12.5", currency = "PLN", kind = "expense",
        splits = listOf(Split("s1", "-12.5", categoryId = "food", category = "Groceries")),
        tags = listOf(Tag("Wakacje")),
    )
    private val transfer = Transaction(
        id = "2", accountId = "a", date = "2026-10-02T10:00:00Z", amount = "-1000", currency = "PLN", kind = "transfer",
        splits = listOf(Split("s2", "-1000", transferAccountId = "s")),
    )
    private val all = listOf(shopping, transfer)

    private fun find(query: String) = TransactionSearch(query).filter(all, snapshot).map { it.id }

    @Test
    fun `ignores case and accents`() {
        assertEquals(listOf("1"), find("zabka"))
        assertEquals(listOf("1"), find("ŻABKA"))
        assertEquals(listOf("1"), find("mleko"))
    }

    @Test
    fun `every word must match, in any field`() {
        assertEquals(listOf("1"), find("zab chleb"))
        assertEquals(listOf("1"), find("food wakacje"))
        assertEquals(emptyList<String>(), find("zabka savings"))
    }

    @Test
    fun `finds accounts, transfer accounts and amounts`() {
        assertEquals(listOf("1", "2"), find("osobiste"))
        assertEquals(listOf("2"), find("savings"))
        assertEquals(listOf("1"), find("12,50"))
        assertEquals(listOf("1"), find("12.5"))
        assertEquals(listOf("2"), find("1000.00"))
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(TransactionSearch("  ").isEmpty)
        assertEquals(listOf("1", "2"), find(" "))
    }

    @Test
    fun `matches pending transactions`() {
        val request = NewTransactionRequest(
            clientId = "c", accountId = "s", date = "2026-10-03T10:00:00Z", kind = "expense", amount = "7.99",
            categoryId = "food", payeeName = "Lidl",
        )
        assertTrue(TransactionSearch("lidl groceries").matches(request, snapshot))
        assertTrue(TransactionSearch("7,99").matches(request, snapshot))
        assertFalse(TransactionSearch("zabka").matches(request, snapshot))
    }
}
