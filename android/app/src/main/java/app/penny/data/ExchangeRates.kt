package app.penny.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** One currency's ECB reference rates: units of it per euro on each day the ECB published them, oldest first. */
@Serializable
data class RateSeries(
    /** Epoch days. */
    val days: List<Long>,
    val perEuro: List<String>,
)

@Serializable
data class StoredRates(
    /** The first day asked for (ISO date); the series start at the first publication on or after it. */
    val from: String,
    /** When the rates were last downloaded, in epoch millis. */
    val updatedAt: Long,
    /** Currencies asked for, including ones the ECB doesn't publish (so they aren't asked for on every refresh). */
    val asked: Set<String>,
    val series: Map<String, RateSeries>,
)

/**
 * Converts between currencies with the ECB's daily euro reference rates (cross rates go through the euro).
 * A day without a publication (weekends, holidays, today before ~16:00 CET) uses the last one before it.
 */
class ExchangeRates(series: Map<String, RateSeries>) {
    private class Lookup(val days: LongArray, val perEuro: Array<BigDecimal>)

    private val lookups: Map<String, Lookup> = series
        .filterValues { it.days.isNotEmpty() && it.days.size == it.perEuro.size }
        .mapValues { (_, s) -> Lookup(s.days.toLongArray(), Array(s.perEuro.size) { BigDecimal(s.perEuro[it]) }) }

    fun covers(currency: String): Boolean = currency == EURO || currency in lookups

    /** Units of [to] for one unit of [from] on [date], or null when there are no rates for one of them. */
    fun rate(from: String, to: String, date: LocalDate): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        val perEuroFrom = perEuro(from, date) ?: return null
        val perEuroTo = perEuro(to, date) ?: return null
        return perEuroTo.divide(perEuroFrom, MathContext.DECIMAL64)
    }

    /** The last day with a published rate for [currency] (null for the euro itself, which has none). */
    fun latestDay(currency: String): LocalDate? = lookups[currency]?.days?.last()?.let(LocalDate::ofEpochDay)

    /** Before the first publication, the first rate; it's closer than nothing. */
    private fun perEuro(currency: String, date: LocalDate): BigDecimal? {
        if (currency == EURO) return BigDecimal.ONE
        val lookup = lookups[currency] ?: return null
        val i = lookup.days.binarySearch(date.toEpochDay())
        val index = if (i >= 0) i else (-i - 2).coerceAtLeast(0)
        return lookup.perEuro[index]
    }

    companion object {
        const val EURO = "EUR"
        val EMPTY = ExchangeRates(emptyMap())
    }
}

/**
 * The ECB's data API: daily series `EXR.D.<currency>.EUR.SP00.A` as CSV
 * (https://data.ecb.europa.eu/help/api/data). No key, about 30 currencies, back to 4 January 1999.
 */
class EcbRateSource(
    private val http: OkHttpClient = defaultHttp,
    private val baseUrl: HttpUrl = "https://data-api.ecb.europa.eu/service/data/EXR/".toHttpUrl(),
) {
    /** Rates per euro from [from] on, by currency and epoch day. Currencies the ECB doesn't publish are left out. */
    suspend fun fetch(currencies: Collection<String>, from: LocalDate): Map<String, Map<Long, String>> =
        withContext(Dispatchers.IO) {
            val codes = currencies.filter { it.matches(CODE) && it != ExchangeRates.EURO }.sorted()
            if (codes.isEmpty()) return@withContext emptyMap()
            val url = baseUrl.newBuilder()
                .addPathSegment("D.${codes.joinToString("+")}.EUR.SP00.A")
                .addQueryParameter("startPeriod", from.toString())
                .addQueryParameter("detail", "dataonly")
                .build()
            val request = Request.Builder().url(url).header("Accept", "text/csv").build()
            http.newCall(request).execute().use { response ->
                // 404 means no data: none of the currencies is published, or nothing since [from].
                if (response.code == 404) return@withContext emptyMap()
                if (!response.isSuccessful) throw IOException("ECB: HTTP ${response.code}")
                parse(response.body?.string().orEmpty())
            }
        }

    private fun parse(csv: String): Map<String, Map<Long, String>> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyMap()
        val header = lines.next().split(',')
        val currencyColumn = header.indexOf("CURRENCY")
        val dayColumn = header.indexOf("TIME_PERIOD")
        val valueColumn = header.indexOf("OBS_VALUE")
        if (currencyColumn < 0 || dayColumn < 0 || valueColumn < 0) throw IOException("ECB: unexpected CSV header")
        val result = HashMap<String, MutableMap<Long, String>>()
        for (line in lines) {
            val fields = line.split(',')
            val currency = fields.getOrNull(currencyColumn) ?: continue
            val day = fields.getOrNull(dayColumn)?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() } ?: continue
            // Missing observations come as empty values.
            val value = fields.getOrNull(valueColumn)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            result.getOrPut(currency) { HashMap() }[day] = value.toPlainString()
        }
        return result
    }

    companion object {
        private val CODE = Regex("[A-Z]{3}")
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

data class RatesState(
    val rates: ExchangeRates = ExchangeRates.EMPTY,
    /** When the rates were last downloaded (epoch millis), or null if never. */
    val updatedAt: Long? = null,
    val downloading: Boolean = false,
    /** The last download failed; [rates] are the older ones. */
    val failed: Boolean = false,
)

/**
 * Daily exchange rates on the phone, downloaded from the ECB (not through the Mac) and kept between launches.
 * Only what's missing is downloaded: new currencies and earlier days in full, otherwise the last days again.
 */
class CurrencyRates(
    private val store: JsonStore,
    private val source: EcbRateSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RatesState())
    val state: StateFlow<RatesState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var stored: StoredRates? = null
    private var loaded = false

    private suspend fun current(): StoredRates? {
        if (!loaded) {
            stored = store.read(FILE, StoredRates.serializer())
            loaded = true
            stored?.let { s -> _state.update { it.copy(rates = ExchangeRates(s.series), updatedAt = s.updatedAt) } }
        }
        return stored
    }

    suspend fun load() = mutex.withLock { current() }

    /**
     * Makes sure there are rates for [currencies] from [from] on. Unless [force], a copy younger than a few hours
     * is kept as it is (the ECB publishes once a working day).
     */
    suspend fun update(currencies: Set<String>, from: LocalDate, force: Boolean = false) = mutex.withLock {
        val wanted = currencies.filter { it != ExchangeRates.EURO }.toSet()
        if (wanted.isEmpty()) return@withLock
        val old = current()
        val start = maxOf(from, FIRST_DAY)
        val oldFrom = old?.from?.let(LocalDate::parse)
        val earlier = old == null || oldFrom == null || start.isBefore(oldFrom)
        val missing = if (old == null) wanted else wanted - old.asked
        val stale = old == null || now() - old.updatedAt > REFRESH_AFTER_MILLIS
        if (!force && !earlier && missing.isEmpty() && !stale) return@withLock

        _state.update { it.copy(downloading = true) }
        try {
            val series = HashMap<String, MutableMap<Long, String>>()
            val updated = if (earlier) {
                merge(series, source.fetch(wanted, start))
                StoredRates(start.toString(), now(), wanted, emptyMap())
            } else {
                old!!.series.filterKeys { it in wanted }.forEach { (code, s) ->
                    series[code] = s.days.zip(s.perEuro).toMap(HashMap())
                }
                if (missing.isNotEmpty()) merge(series, source.fetch(missing, oldFrom!!))
                // Publications from the last days may have been missing or corrected.
                val known = wanted - missing
                if (known.isNotEmpty()) {
                    val last = known.mapNotNull { series[it]?.keys?.maxOrNull() }.minOrNull()?.let(LocalDate::ofEpochDay) ?: oldFrom!!
                    merge(series, source.fetch(known, maxOf(oldFrom!!, last.minusDays(OVERLAP_DAYS))))
                }
                StoredRates(old.from, now(), wanted, emptyMap())
            }.copy(series = series.mapValues { (_, days) ->
                val sorted = days.toSortedMap()
                RateSeries(sorted.keys.toList(), sorted.values.toList())
            })
            store.write(FILE, StoredRates.serializer(), updated)
            stored = updated
            _state.value = RatesState(ExchangeRates(updated.series), updated.updatedAt)
        } catch (e: IOException) {
            _state.update { it.copy(downloading = false, failed = true) }
        }
    }

    private fun merge(into: MutableMap<String, MutableMap<Long, String>>, fetched: Map<String, Map<Long, String>>) {
        fetched.forEach { (code, days) -> into.getOrPut(code) { HashMap() }.putAll(days) }
    }

    companion object {
        private const val FILE = "rates.json"
        /** The ECB's first reference rates. */
        val FIRST_DAY: LocalDate = LocalDate.of(1999, 1, 4)
        private const val REFRESH_AFTER_MILLIS = 6 * 60 * 60 * 1000L
        private const val OVERLAP_DAYS = 7L
    }
}
