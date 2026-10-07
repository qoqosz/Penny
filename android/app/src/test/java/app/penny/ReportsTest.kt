package app.penny

import app.penny.data.Account
import app.penny.data.Category
import app.penny.data.ExchangeRates
import app.penny.data.RateSeries
import app.penny.data.HiddenAccounts
import app.penny.data.Holding
import app.penny.data.Investment
import app.penny.data.PriceSeries
import app.penny.data.Security
import app.penny.data.SecurityPriceTable
import app.penny.data.Valuation
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
    private val noRates = ExchangeRates.EMPTY

    /** 1 EUR = 4 PLN until 9 September (a Wednesday), then 5 PLN; USD is published but no account uses it. */
    private val rates = ExchangeRates(mapOf(
        "PLN" to RateSeries(listOf(day("2026-08-31"), day("2026-09-09")), listOf("4", "5")),
        "USD" to RateSeries(listOf(day("2026-08-31")), listOf("1.25")),
    ))

    private fun day(date: String) = LocalDate.parse(date).toEpochDay()

    @Test fun `net worth follows the balances day by day`() {
        val september = ReportPeriod(PeriodType.MONTH, 2026, 9)
        val report = Reports.build(snapshot, transactions, hidden, "PLN", noRates, september, today, zone)
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
        val report = Reports.build(snapshot, transactions, hidden, "PLN", noRates, october, today, zone)
        assertEquals(6, report.netWorth.size)
        assertEquals(BigDecimal("5640"), report.endValue)
        assertEquals(BigDecimal("5640"), snapshot.accounts.take(2).sumOf { it.balanceValue })
    }

    @Test fun `groups categories under their parent and leaves out transfers and system categories`() {
        val september = ReportPeriod(PeriodType.MONTH, 2026, 9)
        val report = Reports.build(snapshot, transactions, hidden, "PLN", noRates, september, today, zone)
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
        val report = Reports.build(snapshot, transactions, hidden, "EUR", noRates, year, today, zone)
        assertEquals(279, report.netWorth.size) // 1 January to 6 October
        assertEquals(BigDecimal("999"), report.totalExpenses)
        assertEquals(listOf("PLN", "EUR"), Reports.currencies(snapshot, hidden, noRates))
    }

    @Test fun `converts other currencies at each day's rate`() {
        val september = ReportPeriod(PeriodType.MONTH, 2026, 9)
        val report = Reports.build(snapshot, transactions, hidden, "PLN", rates, september, today, zone)
        assertTrue(report.converted)
        assertEquals(emptyList<String>(), report.leftOut)
        // Before 5 September the euro account was empty; then -999 EUR, at 4 PLN and from the 9th at 5 PLN.
        assertEquals(0, BigDecimal("6000").compareTo(report.netWorth[0].value))
        assertEquals(0, BigDecimal("5750").subtract(BigDecimal("3996")).compareTo(report.netWorth[4].value))
        assertEquals(0, BigDecimal("5750").subtract(BigDecimal("4995")).compareTo(report.netWorth[8].value))
        // The expense counts at the rate of its day: 230 PLN + 999 EUR × 4.
        val food = report.expenses.first { it.categoryId == "food" }
        assertEquals(0, BigDecimal("4226").compareTo(food.amount))
    }

    @Test fun `shows the report in any currency with rates`() {
        assertEquals(listOf("PLN", "EUR", "USD"), Reports.currencies(snapshot.copy(currencies = listOf("PLN", "EUR", "USD", "GBP")), hidden, rates))
        val inEuros = Reports.build(snapshot, transactions, hidden, "EUR", rates, ReportPeriod(PeriodType.MONTH, 2026, 10), today, zone)
        // 5640 PLN at 5 PLN per euro, and the euro account.
        assertEquals(0, BigDecimal("1128").subtract(BigDecimal("999")).compareTo(inEuros.endValue))
    }

    @Test fun `leaves out accounts without rates`() {
        val onlyUsd = ExchangeRates(mapOf("USD" to RateSeries(listOf(day("2026-08-31")), listOf("1.25"))))
        val report = Reports.build(snapshot, transactions, hidden, "PLN", onlyUsd, ReportPeriod(PeriodType.MONTH, 2026, 9), today, zone)
        assertEquals(listOf("EUR"), report.leftOut)
        assertFalse(report.converted)
        assertEquals(BigDecimal("5740"), report.endValue)
    }

    @Test fun `values securities at each day's price`() {
        // 10 ABC (quoted in EUR) bought on 2 September for 800 PLN, split 2-for-1 on the 15th; 2 of a fund priced by
        // hand in Money bought on the 20th at 40 PLN, which Money now prices at 50.
        val investments = listOf(
            tx("i1", "I", "2026-09-02", "-800").copy(
                originalAmount = "-200", originalCurrency = "EUR",
                investment = Investment("abc", "I", "buy", shares = "10", price = "20")),
            tx("i2", "I", "2026-09-15", "0").copy(investment = Investment("abc", "I", "split", splitTo = 2, splitFrom = 1)),
            tx("i3", "I", "2026-09-20", "-80").copy(investment = Investment("fund", "I", "buy", shares = "2", price = "40")),
            tx("i4", "I", "2026-09-25", "5").copy(investment = Investment("abc", "I", "dividend")),
        )
        val withSecurities = Snapshot(
            generation = "g", bridgeVersion = "1", writesEnabled = true, defaultCurrency = "PLN",
            accounts = listOf(account("I", "105").copy(
                hasInvestments = true, holdings = listOf(Holding("abc", "20"), Holding("fund", "2")))),
            categories = categories, payees = emptyList(),
            securities = listOf(
                Security("abc", "ABC", symbol = "ABC", currency = "EUR", price = "10", priceCurrency = "EUR"),
                Security("fund", "Fund", price = "50", priceCurrency = "PLN", priceDate = "2026-10-01T10:00:00Z"),
            ),
        )
        val prices = SecurityPriceTable(mapOf("ABC" to PriceSeries.of("EUR", mapOf(
            day("2026-09-01") to BigDecimal("20"), day("2026-09-14") to BigDecimal("22"), day("2026-09-15") to BigDecimal("11"),
        ))!!))
        val september = Reports.build(withSecurities, investments, HiddenAccounts(), "PLN", rates,
            ReportPeriod(PeriodType.MONTH, 2026, 9), today, zone, prices)
        fun on(date: String) = september.netWorth.first { it.date == LocalDate.parse(date) }.value
        assertEquals(0, BigDecimal("980").compareTo(september.startValue))
        assertEquals(0, BigDecimal("980").compareTo(on("2026-09-02")))  // 10 × 20 EUR × 4
        assertEquals(0, BigDecimal("1180").compareTo(on("2026-09-10"))) // the euro at 5
        assertEquals(0, BigDecimal("1280").compareTo(on("2026-09-14")))
        assertEquals(0, BigDecimal("1280").compareTo(on("2026-09-15"))) // 20 × 11 EUR
        assertEquals(0, BigDecimal("1280").compareTo(on("2026-09-20"))) // the fund at its buy price
        assertEquals(0, BigDecimal("1285").compareTo(on("2026-09-30")))
        assertTrue(september.hasSecurities)
        assertFalse(september.hasInvestments)
        assertFalse(september.unvalued)

        val october = Reports.build(withSecurities, investments, HiddenAccounts(), "PLN", rates,
            ReportPeriod(PeriodType.MONTH, 2026, 10), today, zone, prices)
        assertEquals(0, BigDecimal("1305").compareTo(october.endValue)) // the fund at Money's 50

        // Today in the account itself: without the trades, the fund is at Money's price.
        val valuation = Valuation(withSecurities, prices, rates, zone = zone)
        val account = withSecurities.accounts.single()
        assertEquals(0, BigDecimal("1200").compareTo(valuation.securitiesValue(account, today)))
        assertEquals(0, BigDecimal("1305").compareTo(valuation.total(account, today)))
        assertEquals(listOf("abc", "fund"), valuation.holdings(account, today).map { it.security.id })
        assertTrue(valuation.holdings(account, today).first().market)

        // Without rates the euro security can't be valued.
        val unconverted = Reports.build(withSecurities, investments, HiddenAccounts(), "PLN", noRates,
            ReportPeriod(PeriodType.MONTH, 2026, 9), today, zone, prices)
        assertTrue(unconverted.unvalued)
        assertTrue(Valuation(withSecurities, prices, noRates, zone = zone).incomplete(account, today))
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
