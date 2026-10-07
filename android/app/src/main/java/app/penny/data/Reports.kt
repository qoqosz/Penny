package app.penny.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

enum class PeriodType { MONTH, YEAR }

/** A calendar month or year. [month] (1–12) is kept in yearly mode, so switching back returns to the same month. */
data class ReportPeriod(val type: PeriodType, val year: Int, val month: Int) {
    val start: LocalDate get() = if (type == PeriodType.MONTH) LocalDate.of(year, month, 1) else LocalDate.of(year, 1, 1)
    val endExclusive: LocalDate get() = if (type == PeriodType.MONTH) start.plusMonths(1) else start.plusYears(1)

    fun previous(): ReportPeriod = shift(-1)
    fun next(): ReportPeriod = shift(1)

    private fun shift(by: Long): ReportPeriod =
        if (type == PeriodType.YEAR) copy(year = year + by.toInt())
        else start.plusMonths(by).let { copy(year = it.year, month = it.monthValue) }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    /** The same moment seen as a month or a year; a month never lands after [today]'s. */
    fun withType(type: PeriodType, today: LocalDate): ReportPeriod {
        val month = if (year == today.year) minOf(month, today.monthValue) else month
        return ReportPeriod(type, year, month)
    }

    companion object {
        fun current(type: PeriodType, today: LocalDate) = ReportPeriod(type, today.year, today.monthValue)
    }
}

data class NetWorthPoint(val date: LocalDate, val value: BigDecimal)

/**
 * Income or expenses in one top-level category (subcategories rolled up). [amount] is positive for money earned
 * (income) or spent (expenses); refunds reduce it. [categoryId] is null for transactions without a category.
 */
data class CategoryTotal(
    val categoryId: String?,
    val name: String?,
    val amount: BigDecimal,
    /** The categories it's made of (the top-level one itself among them), when there's more than one. */
    val parts: List<CategoryTotal> = emptyList(),
)

data class Report(
    val period: ReportPeriod,
    val currency: String,
    /** End-of-day net worth for each day of the period up to today. */
    val netWorth: List<NetWorthPoint>,
    /** Net worth at the end of the day before the period. */
    val startValue: BigDecimal,
    val income: List<CategoryTotal>,
    val expenses: List<CategoryTotal>,
    /** Some accounts are investment accounts, whose securities aren't counted. */
    val hasInvestments: Boolean,
) {
    val endValue: BigDecimal get() = netWorth.lastOrNull()?.value ?: startValue
    val totalIncome: BigDecimal get() = income.fold(BigDecimal.ZERO) { s, c -> s + c.amount }
    val totalExpenses: BigDecimal get() = expenses.fold(BigDecimal.ZERO) { s, c -> s + c.amount }
}

object Reports {
    /** Currencies of the visible accounts: Money's default currency first, then by number of transactions. */
    fun currencies(snapshot: Snapshot, hidden: HiddenAccounts): List<String> =
        snapshot.accounts.filterNot { it.isHidden(hidden) }
            .groupBy { it.currency }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Account>>> { it.key == snapshot.defaultCurrency }
                    .thenByDescending { e -> e.value.sumOf { it.transactionCount } },
            )
            .map { it.key }

    /** The first day with a transaction in a visible account, or null when there are none. */
    fun firstDate(snapshot: Snapshot, transactions: List<Transaction>, hidden: HiddenAccounts, zone: ZoneId): LocalDate? {
        val visible = snapshot.accounts.filterNot { it.isHidden(hidden) }.mapTo(HashSet()) { it.id }
        return transactions.filter { it.accountId in visible }.minOfOrNull { it.instant }?.atZone(zone)?.toLocalDate()
    }

    /**
     * Net worth and income/expenses by category for the visible accounts in [currency] (closed ones too; they count
     * for the past). Amounts in other currencies can't be converted without rates, so they're left out.
     */
    fun build(
        snapshot: Snapshot,
        transactions: List<Transaction>,
        hidden: HiddenAccounts,
        currency: String,
        period: ReportPeriod,
        today: LocalDate,
        zone: ZoneId,
    ): Report {
        val accounts = snapshot.accounts.filter { !it.isHidden(hidden) && it.currency == currency }
        val accountIds = accounts.mapTo(HashSet()) { it.id }
        val dated = transactions.filter { it.accountId in accountIds }
            .map { it.instant.atZone(zone).toLocalDate() to it }
            .sortedBy { it.first }

        // Net worth on a day = what there was before the first transaction + everything up to that day. The balances
        // come from the snapshot, so if the transaction copy is older the line still ends at today's balance.
        val balance = accounts.fold(BigDecimal.ZERO) { s, a -> s + a.balanceValue }
        var running = dated.fold(balance) { s, (_, tx) -> s - tx.amountValue }
        var i = 0
        fun advanceThrough(day: LocalDate) {
            while (i < dated.size && !dated[i].first.isAfter(day)) running += dated[i++].second.amountValue
        }
        advanceThrough(period.start.minusDays(1))
        val startValue = running
        val points = ArrayList<NetWorthPoint>()
        val last = minOf(period.endExclusive.minusDays(1), today)
        var day = period.start
        while (!day.isAfter(last)) {
            advanceThrough(day)
            points += NetWorthPoint(day, running)
            day = day.plusDays(1)
        }

        val (income, expenses) = categoryTotals(snapshot, dated.filter { it.first in period }.map { it.second })
        return Report(period, currency, points, startValue, income, expenses, accounts.any { it.hasInvestments })
    }

    /**
     * Sums the category splits by top-level category; a category is income or an expense by the sign of its total.
     * Transfers and Money's own categories (investments, balance adjustments) aren't income or spending.
     */
    private fun categoryTotals(snapshot: Snapshot, transactions: List<Transaction>): Pair<List<CategoryTotal>, List<CategoryTotal>> {
        val byTop = LinkedHashMap<String, MutableMap<String, BigDecimal>>()
        var uncategorizedIn = BigDecimal.ZERO
        var uncategorizedOut = BigDecimal.ZERO
        val unknownNames = HashMap<String, String>()
        for (tx in transactions) for (split in tx.splits) {
            if (split.transferAccountId != null) continue
            val amount = split.amount.toBigDecimalOrNull() ?: continue
            val category = snapshot.category(split.categoryId)
            when {
                category != null -> {
                    if (category.kind == "transfer" || category.kind == "system") continue
                    val top = topLevel(snapshot, category)
                    byTop.getOrPut(top.id) { LinkedHashMap() }.merge(category.id, amount, BigDecimal::add)
                }
                // A category the snapshot doesn't know (yet): keep it under the name the split carries.
                split.categoryId != null -> {
                    unknownNames[split.categoryId] = split.category ?: "?"
                    byTop.getOrPut(split.categoryId) { LinkedHashMap() }.merge(split.categoryId, amount, BigDecimal::add)
                }
                amount.signum() > 0 -> uncategorizedIn += amount
                else -> uncategorizedOut += amount
            }
        }

        val income = ArrayList<CategoryTotal>()
        val expenses = ArrayList<CategoryTotal>()
        for ((topId, parts) in byTop) {
            val net = parts.values.fold(BigDecimal.ZERO, BigDecimal::add)
            if (net.signum() == 0) continue
            val sign = net.signum()
            fun name(id: String) = snapshot.category(id)?.name ?: unknownNames[id]
            val breakdown = if (parts.size > 1) {
                parts.map { (id, sum) -> CategoryTotal(id, name(id), sum.multiply(BigDecimal(sign))) }
                    .sortedByDescending { it.amount }
            } else emptyList()
            val total = CategoryTotal(topId, name(topId), net.abs(), breakdown)
            if (sign > 0) income += total else expenses += total
        }
        if (uncategorizedIn.signum() != 0) income += CategoryTotal(null, null, uncategorizedIn)
        if (uncategorizedOut.signum() != 0) expenses += CategoryTotal(null, null, uncategorizedOut.negate())
        return income.sortedByDescending { it.amount } to expenses.sortedByDescending { it.amount }
    }

    private fun topLevel(snapshot: Snapshot, category: Category): Category {
        var current = category
        val seen = HashSet<String>()
        while (seen.add(current.id)) current = snapshot.category(current.parentId) ?: return current
        return current
    }
}
