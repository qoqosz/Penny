package app.penny.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** A price per share in [currency], as of [date]. */
data class Quote(val price: BigDecimal, val currency: String, val date: LocalDate)

/** Daily prices oldest first; a day without one uses the last one before it, and days before the first use the first. */
class PriceSeries(val currency: String, private val days: LongArray, private val prices: Array<BigDecimal>) {
    init { require(days.isNotEmpty() && days.size == prices.size) }

    fun at(date: LocalDate): Quote {
        val i = days.binarySearch(date.toEpochDay())
        val index = if (i >= 0) i else (-i - 2).coerceAtLeast(0)
        return Quote(prices[index], currency, LocalDate.ofEpochDay(days[index]))
    }

    val latest: Quote get() = Quote(prices.last(), currency, LocalDate.ofEpochDay(days.last()))

    companion object {
        fun of(currency: String, prices: Map<Long, BigDecimal>): PriceSeries? {
            if (prices.isEmpty()) return null
            val sorted = prices.toSortedMap()
            return PriceSeries(currency, sorted.keys.toLongArray(), sorted.values.toTypedArray())
        }
    }
}

/** Downloaded prices by ticker. */
class SecurityPriceTable(private val series: Map<String, PriceSeries>) {
    fun covers(symbol: String?): Boolean = symbol != null && symbol in series
    fun series(symbol: String?): PriceSeries? = symbol?.let(series::get)

    companion object {
        val EMPTY = SecurityPriceTable(emptyMap())
    }
}

/** One ticker's daily prices, as downloaded. */
data class PriceHistory(val currency: String, val prices: Map<Long, BigDecimal>)

/**
 * Yahoo Finance's chart API, which Money itself gets its quotes from (Money's tickers are Yahoo's). No key; daily
 * closes, split events and the latest price.
 */
class YahooPriceSource(
    private val http: OkHttpClient = defaultHttp,
    private val baseUrl: HttpUrl = "https://query1.finance.yahoo.com/v8/finance/chart/".toHttpUrl(),
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Daily prices from [from] until today, by epoch day in the exchange's time zone, or null when Yahoo doesn't
     * know the ticker. The closes are as traded, not adjusted for later splits (the shares Money counts aren't
     * either), and today's is the latest price.
     */
    suspend fun fetch(symbol: String, from: LocalDate): PriceHistory? = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addPathSegment(symbol)
            .addQueryParameter("period1", from.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toString())
            .addQueryParameter("period2", (now().epochSecond + 86_400).toString())
            .addQueryParameter("interval", "1d")
            .addQueryParameter("events", "split")
            .build()
        // Yahoo turns away clients without a browser-like user agent.
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("Yahoo: HTTP ${response.code}")
            parse(response.body?.string().orEmpty())
        }
    }

    private fun parse(body: String): PriceHistory? {
        val chart = runCatching { json.parseToJsonElement(body).jsonObject["chart"]?.jsonObject }.getOrNull()
            ?: throw IOException("Yahoo: unexpected response")
        val result = (chart["result"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val meta = result["meta"] as? JsonObject ?: return null
        val quoted = meta.string("currency") ?: return null
        // Some exchanges quote in minor units (London in pence).
        val (currency, divisor) = MINOR_UNITS[quoted] ?: (quoted.uppercase() to 1)
        val zone = meta.string("exchangeTimezoneName")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        fun day(epochSecond: Long) = Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate().toEpochDay()
        // Yahoo's prices are floats (109.84600067138672); ten digits is more than any quote has.
        fun price(value: Double) = BigDecimal(value.toString()).divide(BigDecimal(divisor), PRECISION).stripTrailingZeros()

        // Yahoo adjusts past closes for splits; undo that with the splits after each day.
        val splits = ((result["events"] as? JsonObject)?.get("splits") as? JsonObject)?.values.orEmpty().mapNotNull { e ->
            val split = e as? JsonObject ?: return@mapNotNull null
            val date = split.long("date") ?: return@mapNotNull null
            val numerator = split.double("numerator")?.takeIf { it > 0 } ?: return@mapNotNull null
            val denominator = split.double("denominator")?.takeIf { it > 0 } ?: return@mapNotNull null
            date to numerator / denominator
        }
        val timestamps = (result["timestamp"] as? JsonArray).orEmpty()
        val closes = ((result["indicators"] as? JsonObject)?.get("quote") as? JsonArray)
            ?.firstOrNull()?.jsonObject?.get("close") as? JsonArray
        val prices = HashMap<Long, BigDecimal>()
        timestamps.forEachIndexed { i, t ->
            val time = (t as? JsonPrimitive)?.longOrNull ?: return@forEachIndexed
            val close = (closes?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 && it.isFinite() } ?: return@forEachIndexed
            val factor = splits.filter { it.first > time }.fold(1.0) { f, s -> f * s.second }
            prices[day(time)] = price(close * factor)
        }
        val latest = meta.double("regularMarketPrice")?.takeIf { it > 0 && it.isFinite() }
        val latestTime = meta.long("regularMarketTime")
        if (latest != null && latestTime != null) prices[day(latestTime)] = price(latest)
        return PriceHistory(currency, prices)
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.double(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private val MINOR_UNITS = mapOf("GBp" to ("GBP" to 100), "GBX" to ("GBP" to 100), "ZAc" to ("ZAR" to 100), "ILA" to ("ILS" to 100))
        private val json = Json { ignoreUnknownKeys = true }
        private val PRECISION = MathContext(10)
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
data class StoredSymbol(
    /** The first day asked for (ISO date). */
    val from: String,
    /** When it was last downloaded, in epoch millis. */
    val checkedAt: Long,
    /** Null when Yahoo doesn't know the ticker. */
    val currency: String? = null,
    /** Epoch days, oldest first. */
    val days: List<Long> = emptyList(),
    val prices: List<String> = emptyList(),
) {
    fun series(): PriceSeries? = currency?.let { c ->
        PriceSeries.of(c, days.zip(prices).mapNotNull { (d, p) -> p.toBigDecimalOrNull()?.let { d to it } }.toMap())
    }
}

@Serializable
data class StoredPrices(val symbols: Map<String, StoredSymbol>)

data class PricesState(
    val prices: SecurityPriceTable = SecurityPriceTable.EMPTY,
    /** When prices were last downloaded (epoch millis), or null if never. */
    val updatedAt: Long? = null,
    val downloading: Boolean = false,
    /** The last download failed for some tickers; their prices are older ones (or Money's). */
    val failed: Boolean = false,
)

/**
 * Daily prices of the securities in investment accounts, downloaded by the phone (not through the Mac) and kept
 * between launches. Each ticker is downloaded back to its first transaction once, then only its last days again.
 */
class SecurityPrices(
    private val store: JsonStore,
    private val source: YahooPriceSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(PricesState())
    val state: StateFlow<PricesState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var stored: Map<String, StoredSymbol> = emptyMap()
    private var loaded = false

    private suspend fun current(): Map<String, StoredSymbol> {
        if (!loaded) {
            stored = store.read(FILE, StoredPrices.serializer())?.symbols.orEmpty()
            loaded = true
            if (stored.isNotEmpty()) _state.update { it.copy(prices = table(stored), updatedAt = stored.values.maxOf { s -> s.checkedAt }) }
        }
        return stored
    }

    suspend fun load() = mutex.withLock { current() }

    /**
     * Makes sure there are prices for each ticker in [wanted] from its date on. Unless [force], tickers downloaded
     * in the last few hours are kept as they are.
     */
    suspend fun update(wanted: Map<String, LocalDate>, force: Boolean = false) = mutex.withLock {
        val old = current()
        val jobs = wanted.mapNotNull { (symbol, from) ->
            val known = old[symbol]
            val knownFrom = known?.from?.let(LocalDate::parse)
            val start = when {
                knownFrom == null || from.isBefore(knownFrom) -> from
                force || now() - known.checkedAt > REFRESH_AFTER_MILLIS ->
                    // The last days again: the latest price, and closes that came late or were corrected.
                    known.days.lastOrNull()?.let { maxOf(knownFrom, LocalDate.ofEpochDay(it).minusDays(OVERLAP_DAYS)) } ?: knownFrom
                else -> return@mapNotNull null
            }
            symbol to start
        }
        val kept = old.filterKeys { it in wanted }
        if (jobs.isEmpty()) {
            if (kept.size != old.size) save(kept)
            return@withLock
        }

        _state.update { it.copy(downloading = true) }
        val permits = Semaphore(PARALLEL)
        val results = coroutineScope {
            jobs.map { (symbol, start) ->
                async { permits.withPermit { symbol to runCatching { source.fetch(symbol, start) } } }
            }.awaitAll()
        }
        // A ticker that failed keeps its older prices and is tried again on the next refresh.
        results.firstNotNullOfOrNull { it.second.exceptionOrNull() as? CancellationException }?.let { throw it }
        val failed = results.any { it.second.isFailure }
        val updated = HashMap(kept)
        for ((symbol, result) in results) {
            if (result.isFailure) continue
            val history = result.getOrNull()
            val known = kept[symbol]
            val start = jobs.first { it.first == symbol }.second
            val full = known == null || start.isBefore(LocalDate.parse(known.from))
            val prices = HashMap<Long, String>()
            // A currency change (rare: a ticker moving) invalidates what was there.
            if (!full && history != null && known.currency == history.currency) {
                known.days.zip(known.prices).toMap(prices)
            }
            history?.prices?.forEach { (day, price) -> prices[day] = price.toPlainString() }
            val sorted = prices.toSortedMap()
            updated[symbol] = StoredSymbol(
                from = if (full) start.toString() else known.from,
                checkedAt = now(),
                currency = history?.currency,
                days = sorted.keys.toList(),
                prices = sorted.values.toList(),
            )
        }
        save(updated)
        _state.value = PricesState(table(updated), updated.values.maxOfOrNull { it.checkedAt }, failed = failed)
    }

    private suspend fun save(symbols: Map<String, StoredSymbol>) {
        store.write(FILE, StoredPrices.serializer(), StoredPrices(symbols))
        stored = symbols
    }

    private fun table(symbols: Map<String, StoredSymbol>) =
        SecurityPriceTable(symbols.mapNotNull { (symbol, s) -> s.series()?.let { symbol to it } }.toMap())

    companion object {
        private const val FILE = "prices.json"
        /** Prices move during the trading day, so they're refreshed more often than exchange rates. */
        private const val REFRESH_AFTER_MILLIS = 30 * 60 * 1000L
        private const val OVERLAP_DAYS = 7L
        private const val PARALLEL = 4
    }
}
