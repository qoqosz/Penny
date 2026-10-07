package app.penny.data

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A security held in an account, valued on some day. */
data class HoldingValue(
    val security: Security,
    val shares: BigDecimal,
    /** Null when there's no price at all. */
    val quote: Quote?,
    /** The price was downloaded; otherwise it's Money's last quote or a buy or sell price. */
    val market: Boolean,
    /** In the currency asked for; null without a price or an exchange rate. */
    val value: BigDecimal?,
)

/**
 * What investment accounts hold, valued at market prices: shares × the day's price, converted at the day's exchange
 * rate. Securities without downloaded prices (prices entered by hand in Money, tickers Yahoo doesn't know) use
 * Money's last quote or, without one, their buy and sell prices.
 */
class Valuation(
    private val snapshot: Snapshot,
    private val prices: SecurityPriceTable,
    val rates: ExchangeRates,
    /** Their buy and sell prices fill in the history of securities without downloaded prices. */
    trades: List<Transaction> = emptyList(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Whether the bridge sends holdings; older ones only know the accounts' cash. */
    val knowsHoldings: Boolean get() = snapshot.securities != null

    private val fallback: Map<String, PriceSeries> = fallbackPrices(trades)

    /** The price on [date]: the downloaded one when there is one, else Money's. */
    fun price(security: Security, date: LocalDate): Pair<Quote, Boolean>? =
        prices.series(security.symbol)?.at(date)?.let { it to true }
            ?: fallback[security.id]?.at(date)?.let { it to false }

    /** [shares] of [security] in [currency] on [date]; zero for none (or fewer, see [Holding.shares]). */
    fun value(security: Security, shares: BigDecimal, date: LocalDate, currency: String): BigDecimal? {
        if (shares.signum() <= 0) return BigDecimal.ZERO
        val (quote, _) = price(security, date) ?: return null
        val rate = rates.rate(quote.currency, currency, date) ?: return null
        return shares.multiply(quote.price, MATH).multiply(rate, MATH)
    }

    /** The account's securities on [date] (by default today), largest first, valued in [currency]. */
    fun holdings(account: Account, date: LocalDate = LocalDate.now(zone), currency: String = account.currency): List<HoldingValue> =
        account.holdings.mapNotNull { h ->
            val security = snapshot.security(h.securityId) ?: return@mapNotNull null
            val shares = h.sharesValue
            if (shares.signum() <= 0) return@mapNotNull null
            val priced = price(security, date)
            HoldingValue(security, shares, priced?.first, priced?.second == true, value(security, shares, date, currency))
        }.sortedByDescending { it.value ?: BigDecimal.ZERO }

    /** The account's securities in its currency, or null when it holds none. */
    fun securitiesValue(account: Account, date: LocalDate = LocalDate.now(zone)): BigDecimal? {
        val held = holdings(account, date)
        if (held.isEmpty()) return null
        return held.fold(BigDecimal.ZERO) { sum, h -> sum + (h.value ?: BigDecimal.ZERO) }
    }

    /** Cash plus securities, in the account's currency. */
    fun total(account: Account, date: LocalDate = LocalDate.now(zone)): BigDecimal =
        account.balanceValue + (securitiesValue(account, date) ?: BigDecimal.ZERO)

    /** Some of the account's securities have no price or no exchange rate, so they count as nothing. */
    fun incomplete(account: Account, date: LocalDate = LocalDate.now(zone)): Boolean =
        holdings(account, date).any { it.value == null }

    /**
     * Money's own price for each security (its last quote, or the last buy or sell), plus earlier buy and sell
     * prices in the same currency for the past.
     */
    private fun fallbackPrices(trades: List<Transaction>): Map<String, PriceSeries> {
        val points = HashMap<String, MutableList<Pair<LocalDate, Quote>>>()
        fun day(iso: String?) = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }?.atZone(zone)?.toLocalDate()
        for (tx in trades) {
            val inv = tx.investment ?: continue
            if (inv.type != "buy" && inv.type != "sell") continue
            val price = inv.price?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            val date = tx.instant.atZone(zone).toLocalDate()
            points.getOrPut(inv.securityId) { ArrayList() } += date to Quote(price, tx.originalCurrency ?: tx.currency, date)
        }
        return snapshot.securities.orEmpty().mapNotNull { s ->
            val own = s.price?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            val ownDate = day(s.priceDate) ?: LocalDate.now(zone)
            val list = points[s.id].orEmpty().toMutableList()
            if (own != null && s.priceCurrency != null) list += ownDate to Quote(own, s.priceCurrency, ownDate)
            val currency = s.priceCurrency ?: list.maxByOrNull { it.first }?.second?.currency ?: return@mapNotNull null
            // Money's own price wins on its day; it's added last.
            val byDay = list.filter { it.second.currency == currency }.associate { it.first.toEpochDay() to it.second.price }
            PriceSeries.of(currency, byDay)?.let { s.id to it }
        }.toMap()
    }

    companion object {
        private val MATH = MathContext.DECIMAL64
    }
}
