package app.penny

import app.penny.data.Account
import app.penny.data.AccountOrder
import app.penny.data.ordered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AccountOrderTest {
    private fun account(id: String, folderId: String? = null) =
        Account(id, id, type = 0, currency = "PLN", folder = folderId?.let { "Folder $it" }, folderId = folderId, balance = "0")

    // Money's order: folder 1 (a, b), folder 2 (c, d), outside folders (e).
    private val accounts = listOf(account("a", "1"), account("b", "1"), account("c", "2"), account("d", "2"), account("e"))

    private fun List<Account>.ids() = joinToString("") { it.id }

    @Test
    fun `no order keeps Money's`() {
        assertSame(accounts, accounts.ordered(AccountOrder()))
    }

    @Test
    fun `orders groups and the accounts in them`() {
        val order = AccountOrder(groups = listOf("", "2", "1"), accounts = listOf("e", "d", "c", "b", "a"))
        assertEquals("edcba", accounts.ordered(order).ids())
    }

    @Test
    fun `unknown groups and accounts follow in Money's order`() {
        // Folder 3 and account f were added in Money after the order was saved.
        val all = accounts + account("f", "1") + account("g", "3")
        val order = AccountOrder(groups = listOf("2", "1", ""), accounts = listOf("d", "c", "b", "a", "e"))
        assertEquals("dcbafeg", all.ordered(order).ids())
    }

    @Test
    fun `old bridges group by folder name`() {
        val old = listOf(
            Account("a", "a", type = 0, currency = "PLN", folder = "Cash", balance = "0"),
            Account("b", "b", type = 0, currency = "PLN", folder = "Bank", balance = "0"),
        )
        assertEquals("ba", old.ordered(AccountOrder(groups = listOf("Bank", "Cash"))).ids())
    }
}
