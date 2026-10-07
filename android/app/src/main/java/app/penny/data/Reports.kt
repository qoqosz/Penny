package app.penny.data

import java.math.BigDecimal
import java.math.MathContext
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
    /** Some accounts are investment accounts whose securities aren't counted (the bridge doesn't send holdings). */
    val hasInvestments: Boolean,
    /** Securities are included at each day's price. */
    val hasSecurities: Boolean = false,
    /** Some securities have no price or exchange rate on some days, so they count as nothing there. */
    val unvalued: Boolean = false,
    /** Accounts in other currencies were converted at each day's exchange rate. */
    val converted: Boolean = false,
    /** Currencies of visible accounts that couldn't be converted (no rates), so their accounts are left out. */
    val leftOut: List<String> = emptyList(),
) {
    val endValue: BigDecimal get() = netWorth.lastOrNull()?.value ?: startValue
    val totalIncome: BigDecimal get() = income.fold(BigDecimal.ZERO) { s, c -> s + c.amount }
    val totalExpenses: BigDecimal get() = expenses.fold(BigDecimal.ZERO) { s, c -> s + c.amount }
}

object Reports {
    /**
     * Currencies the report can be shown in: the main one first, then Money's other currencies in Money's order, then
     * the remaining account currencies by number of transactions. A currency is offered when there are rates for it
     * (every account can then be converted to it), or when visible accounts are in it (shown on their own).
     */
    fun currencies(snapshot: Snapshot, hidden: HiddenAccounts, rates: ExchangeRates): List<String> {
        val byUsage = snapshot.accounts.filterNot { it.isHidden(hidden) }
            .groupBy { it.currency }
            .entries
            .sortedByDescending { e -> e.value.sumOf { it.transactionCount } }
            .map { it.key }
        return (listOfNotNull(snapshot.mainCurrency) + snapshot.currencies.orEmpty() + byUsage)
            .distinct()
            .filter { it in byUsage || rates.covers(it) }
    }

    /** The first day with a transaction in a visible account, or null when there are none. */
    fun firstDate(snapshot: Snapshot, transactions: List<Transaction>, hidden: HiddenAccounts, zone: ZoneId): LocalDate? {
        val visible = snapshot.accounts.filterNot { it.isHidden(hidden) }.mapTo(HashSet()) { it.id }
        return transactions.filter { it.accountId in visible }.minOfOrNull { it.instant }?.atZone(zone)?.toLocalDate()
    }

    /**
     * Net worth and income/expenses by category for the visible accounts (closed ones too; they count for the past),
     * in [currency]. Accounts in other currencies are converted at each day's rate; without rates they're left out.
     * Investment accounts count their securities at each day's price (see [Valuation]).
     */
    fun build(
        snapshot: Snapshot,
        transactions: List<Transaction>,
        hidden: HiddenAccounts,
        currency: String,
        rates: ExchangeRates,
        period: ReportPeriod,
        today: LocalDate,
        zone: ZoneId,
        prices: SecurityPriceTable = SecurityPriceTable.EMPTY,
    ): Report {
        val visible = snapshot.accounts.filterNot { it.isHidden(hidden) }
        val (accounts, leftOut) = visible.partition {
            it.currency == currency || (rates.covers(it.currency) && rates.covers(currency))
        }
        val accountCurrency = accounts.associate { it.id to it.currency }
        val dated = transactions.filter { it.accountId in accountCurrency }
            .map { it.instant.atZone(zone).toLocalDate() to it }
            .sortedBy { it.first }
        fun inCurrency(amount: BigDecimal, from: String, day: LocalDate): BigDecimal =
            if (from == currency) amount else amount.multiply(rates.rate(from, currency, day)!!, MATH)

        // Net worth on a day = what each currency had before the first transaction + everything up to that day, at
        // that day's rate. The balances come from the snapshot, so if the transaction copy is older the line still
        // ends at today's balance.
        val running = HashMap<String, BigDecimal>()
        accounts.forEach { running.merge(it.currency, it.balanceValue, BigDecimal::add) }
        dated.forEach { (_, tx) -> running.merge(accountCurrency.getValue(tx.accountId), tx.amountValue.negate(), BigDecimal::add) }

        // Shares the same way: today's from the snapshot, back to before the first trade, then forward day by day.
        val valuation = Valuation(snapshot, prices, rates, transactions, zone)
        val shares = HashMap<Pair<String, String>, BigDecimal>()
        accounts.forEach { a -> a.holdings.forEach { shares[a.id to it.securityId] = it.sharesValue } }
        val trades = transactions.filter { it.investment?.accountId in accountCurrency }
            .map { it.instant.atZone(zone).toLocalDate() to it.investment!! }
            .sortedBy { it.first }
        trades.asReversed().forEach { (_, inv) -> shares.change(inv, undo = true) }
        var unvalued = false

        var i = 0
        var j = 0
        fun valueThrough(day: LocalDate): BigDecimal {
            while (i < dated.size && !dated[i].first.isAfter(day)) {
                val tx = dated[i++].second
                running.merge(accountCurrency.getValue(tx.accountId), tx.amountValue, BigDecimal::add)
            }
            while (j < trades.size && !trades[j].first.isAfter(day)) shares.change(trades[j++].second, undo = false)
            val cash = running.entries.fold(BigDecimal.ZERO) { s, (from, sum) -> s + inCurrency(sum, from, day) }
            return shares.entries.fold(cash) { s, (key, n) ->
                val security = snapshot.security(key.second) ?: return@fold s
                val value = valuation.value(security, n, day, currency)
                if (value == null) unvalued = true
                s + (value ?: BigDecimal.ZERO)
            }
        }
        val startValue = valueThrough(period.start.minusDays(1))
        val points = ArrayList<NetWorthPoint>()
        val last = minOf(period.endExclusive.minusDays(1), today)
        var day = period.start
        while (!day.isAfter(last)) {
            points += NetWorthPoint(day, valueThrough(day))
            day = day.plusDays(1)
        }

        val inPeriod = dated.filter { it.first in period }.map { (day, tx) ->
            val from = accountCurrency.getValue(tx.accountId)
            tx to if (from == currency) BigDecimal.ONE else rates.rate(from, currency, day)!!
        }
        val (income, expenses) = categoryTotals(snapshot, inPeriod)
        return Report(
            period, currency, points, startValue, income, expenses,
            hasInvestments = snapshot.securities == null && accounts.any { it.hasInvestments },
            hasSecurities = shares.isNotEmpty(),
            unvalued = unvalued,
            converted = accounts.any { it.currency != currency },
            leftOut = leftOut.map { it.currency }.distinct(),
        )
    }

    /**
     * Sums the category splits by top-level category; a category is income or an expense by the sign of its total.
     * Transfers and Money's own categories (investments, balance adjustments) aren't income or spending.
     */
    private fun categoryTotals(
        snapshot: Snapshot,
        /** Each with the rate from its account's currency to the report's. */
        transactions: List<Pair<Transaction, BigDecimal>>,
    ): Pair<List<CategoryTotal>, List<CategoryTotal>> {
        val byTop = LinkedHashMap<String, MutableMap<String, BigDecimal>>()
        var uncategorizedIn = BigDecimal.ZERO
        var uncategorizedOut = BigDecimal.ZERO
        val unknownNames = HashMap<String, String>()
        for ((tx, rate) in transactions) for (split in tx.splits) {
            if (split.transferAccountId != null) continue
            val amount = split.amount.toBigDecimalOrNull()
                ?.let { if (rate.compareTo(BigDecimal.ONE) == 0) it else it.multiply(rate, MATH) } ?: continue
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

    private val MATH = MathContext.DECIMAL64

    /** Applies a buy, sell or split to the shares held (or takes it back). */
    private fun MutableMap<Pair<String, String>, BigDecimal>.change(inv: Investment, undo: Boolean) {
        val key = inv.accountId to inv.securityId
        val held = get(key) ?: BigDecimal.ZERO
        val n = inv.shares.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val to = inv.splitTo?.takeIf { it > 0 }?.toBigDecimal()
        val from = inv.splitFrom?.takeIf { it > 0 }?.toBigDecimal()
        val updated = when (inv.type) {
            "buy" -> if (undo) held - n else held + n
            "sell" -> if (undo) held + n else held - n
            "split" -> if (to == null || from == null) held
                else if (undo) held.multiply(from).divide(to, MATH) else held.multiply(to).divide(from, MATH)
            else -> return
        }
        put(key, updated)
    }

    private fun topLevel(snapshot: Snapshot, category: Category): Category {
        var current = category
        val seen = HashSet<String>()
        while (seen.add(current.id)) current = snapshot.category(current.parentId) ?: return current
        return current
    }
}
