package app.penny

import app.penny.data.Account
import app.penny.data.Category
import app.penny.data.HiddenAccounts
import app.penny.data.PeriodType
import app.penny.data.ReportPeriod
import app.penny.data.Reports
import app.penny.data.Snapshot
import app.penny.data.Split
import app.penny.data.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class ReportsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 6)

    private val categories = listOf(
        Category("food", "Food", "Food", kind = "expense"),
        Category("groceries", "Groceries", "Food: Groceries", parentId = "food", kind = "expense"),
        Category("salary", "Salary", "Salary", kind = "income"),
        Category("adjust", "Balance Adjustment", "Balance Adjustment", kind = "system"),
        Category("toB", "B", "B", kind = "transfer"),
    )

    private fun account(id: String, balance: String, currency: String = "PLN", folderId: String? = null) =
        Account(id, id, 0, currency, folderId = folderId, balance = balance, transactionCount = 1)

    private fun tx(
        id: String, account: String, date: String, amount: String,
        category: String? = null, transferTo: String? = null, currency: String = "PLN",
    ) = Transaction(
        id = id, accountId = account, date = "${date}T12:00:00Z", amount = amount, currency = currency,
        kind = if (transferTo != null) "transfer" else if (amount.startsWith("-")) "expense" else "income",
        splits = listOf(Split("s$id", amount, category, null, transferAccountId = transferTo)),
    )

    private val transactions = listOf(
        tx("1", "A", "2026-08-31", "1000", "adjust"),
        tx("2", "A", "2026-09-01", "5000", "salary"),
        tx("3", "A", "2026-09-03", "-200", "groceries"),
        tx("4", "A", "2026-09-03", "-50", "food"),
        tx("5", "A", "2026-09-10", "20", "groceries"), // a refund
        tx("6", "A", "2026-09-12", "-300", transferTo = "B"),
        tx("7", "B", "2026-09-12", "300", transferTo = "A"),
        tx("8", "B", "2026-09-20", "-30"),
        tx("9", "A", "2026-10-02", "-100", "food"),
        tx("10", "E", "2026-09-05", "-999", "food", currency = "EUR"),
        tx("11", "H", "2026-09-05", "-777", "food"),
    )

    private val snapshot = Snapshot(
        generation = "g", bridgeVersion = "1", writesEnabled = true, defaultCurrency = "PLN",
        accounts = listOf(
            account("A", "5370"), account("B", "270"), account("E", "-999", "EUR"),
            account("H", "-777", folderId = "hiddenFolder"),
        ),
        categories = categories, payees = emptyList(),
    )
    private val hidden = HiddenAccounts(folders = setOf("hiddenFolder"))

    @Test fun `net worth follows the balances day by day`() {
        val september = ReportPeriod(PeriodType.MONTH, 2026, 9)
        val report = Reports.build(snapshot, transactions, hidden, "PLN", september, today, zone)
        assertEquals(30, report.netWorth.size)
        assertEquals(BigDecimal("1000"), report.startValue)
        assertEquals(BigDecimal("6000"), report.netWorth[0].value)
        assertEquals(BigDecimal("5750"), report.netWorth[2].value)
        // A transfer between two included accounts doesn't change net worth.
        assertEquals(report.netWorth[10].value, report.netWorth[11].value)
        assertEquals(BigDecimal("5740"), report.endValue)
    }

    @Test fun `a month in progress ends today`() {
        val october = ReportPeriod.current(PeriodType.MONTH, today)
        val report = Reports.build(snapshot, transactions, hidden, "PLN", october, today, zone)
        assertEquals(6, report.netWorth.size)
        assertEquals(BigDecimal("5640"), report.endValue)
        assertEquals(BigDecimal("5640"), snapshot.accounts.take(2).sumOf { it.balanceValue })
    }

    @Test fun `groups categories under their parent and leaves out transfers and system categories`() {
        val september = ReportPeriod(PeriodType.MONTH, 2026, 9)
        val report = Reports.build(snapshot, transactions, hidden, "PLN", september, today, zone)
        assertEquals(listOf("salary"), report.income.map { it.categoryId })
        assertEquals(BigDecimal("5000"), report.totalIncome)

        assertEquals(listOf("food", null), report.expenses.map { it.categoryId })
        val food = report.expenses[0]
        assertEquals(BigDecimal("230"), food.amount)
        assertEquals(listOf("groceries" to BigDecimal("180"), "food" to BigDecimal("50")), food.parts.map { it.categoryId to it.amount })
        assertEquals(BigDecimal("30"), report.expenses[1].amount)
        assertEquals(BigDecimal("260"), report.totalExpenses)
    }

    @Test fun `years and currencies`() {
        val year = ReportPeriod(PeriodType.YEAR, 2026, 9)
        val report = Reports.build(snapshot, transactions, hidden, "EUR", year, today, zone)
        assertEquals(279, report.netWorth.size) // 1 January to 6 October
        assertEquals(BigDecimal("999"), report.totalExpenses)
        assertEquals(listOf("PLN", "EUR"), Reports.currencies(snapshot, hidden))
    }

    @Test fun `periods move by month or year`() {
        val january = ReportPeriod(PeriodType.MONTH, 2026, 1)
        assertEquals(ReportPeriod(PeriodType.MONTH, 2025, 12), january.previous())
        assertEquals(LocalDate.of(2026, 2, 1), january.endExclusive)
        assertTrue(LocalDate.of(2026, 1, 31) in january)
        assertFalse(LocalDate.of(2026, 2, 1) in january)
        val year = january.withType(PeriodType.YEAR, today)
        assertEquals(ReportPeriod(PeriodType.YEAR, 2027, 1), year.next())
        // Back to months in the current year: never a month after today's.
        assertEquals(10, ReportPeriod(PeriodType.YEAR, 2026, 12).withType(PeriodType.MONTH, today).month)
    }
}
