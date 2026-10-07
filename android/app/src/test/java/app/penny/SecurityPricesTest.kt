package app.penny

import app.penny.data.JsonStore
import app.penny.data.SecurityPrices
import app.penny.data.YahooPriceSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SecurityPricesTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val source get() = YahooPriceSource(OkHttpClient(), server.url("/v8/finance/chart/")) { now }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun day(date: String) = LocalDate.parse(date).toEpochDay()

    /** Market open in New York on [date]. */
    private fun open(date: String) = LocalDate.parse(date).atTime(9, 30).atZone(ZoneId.of("America/New_York")).toEpochSecond()

    private fun chart(
        currency: String,
        closes: List<Pair<String, Double?>>,
        latest: Pair<String, Double>? = null,
        splits: String = "",
    ) = MockResponse().setBody(
        """{"chart":{"result":[{"meta":{"currency":"$currency","exchangeTimezoneName":"America/New_York"""" +
            (latest?.let { ""","regularMarketPrice":${it.second},"regularMarketTime":${open(it.first) + 3600}""" } ?: "") +
            """},"timestamp":[${closes.joinToString(",") { open(it.first).toString() }}],""" +
            (if (splits.isNotEmpty()) """"events":{"splits":{$splits}},""" else "") +
            """"indicators":{"quote":[{"close":[${closes.joinToString(",") { it.second?.toString() ?: "null" }}]}]}}],"error":null}}""",
    )

    @Test fun `undoes Yahoo's split adjustment and adds the latest price`() = runTest {
        // NFLX split 10-for-1 on 17 November 2025: Yahoo shows the closes before it divided by 10.
        val split = open("2025-11-17")
        server.enqueue(chart(
            "USD",
            listOf("2025-11-14" to 111.217, "2025-11-17" to 110.29, "2025-11-18" to null),
            latest = "2025-11-18" to 114.09,
            splits = """"$split":{"date":$split,"numerator":10.0,"denominator":1.0,"splitRatio":"10:1"}""",
        ))
        val history = source.fetch("NFLX", LocalDate.parse("2025-11-14"))!!
        assertEquals("USD", history.currency)
        assertEquals(
            mapOf(day("2025-11-14") to "1112.17", day("2025-11-17") to "110.29", day("2025-11-18") to "114.09"),
            history.prices.mapValues { it.value.toPlainString() },
        )
        val request = server.takeRequest()
        assertEquals("/v8/finance/chart/NFLX", request.requestUrl!!.encodedPath)
        assertEquals("1763078400", request.requestUrl!!.queryParameter("period1"))
        assertEquals("split", request.requestUrl!!.queryParameter("events"))
    }

    @Test fun `prices in pence become pounds`() = runTest {
        server.enqueue(chart("GBp", listOf("2026-10-06" to 1234.5)))
        val history = source.fetch("VUSA.L", LocalDate.parse("2026-10-01"))!!
        assertEquals("GBP", history.currency)
        assertEquals(0, BigDecimal("12.345").compareTo(history.prices[day("2026-10-06")]))
    }

    @Test fun `unknown tickers have no prices`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}"""))
        assertNull(source.fetch("JEDNOSTKA FUNDUSZU", LocalDate.parse("2026-10-01")))
        assertEquals("/v8/finance/chart/JEDNOSTKA%20FUNDUSZU", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun `downloads each ticker once, then only the last days`() = runTest {
        var clock = 0L
        val prices = SecurityPrices(JsonStore(folder.root), source) { clock }
        server.enqueue(chart("USD", listOf("2026-09-01" to 10.0, "2026-10-06" to 12.0)))
        prices.update(mapOf("AAA" to LocalDate.parse("2026-09-01")))
        assertEquals("1788220800", server.takeRequest().requestUrl!!.queryParameter("period1"))
        val series = prices.state.value.prices.series("AAA")!!
        assertEquals(0, BigDecimal("10").compareTo(series.at(LocalDate.parse("2026-09-15")).price))
        assertEquals(LocalDate.parse("2026-10-06"), series.latest.date)

        // Fresh: no request.
        clock = 60_000
        prices.update(mapOf("AAA" to LocalDate.parse("2026-09-01")))
        assertEquals(1, server.requestCount)

        // Later: the last week again, merged with what was there; a failure keeps the older prices.
        // The tickers download in parallel, so answer by ticker rather than in order.
        clock = 2 * 60 * 60 * 1000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.pathSegments.last()) {
                "AAA" -> chart("USD", listOf("2026-10-07" to 13.0))
                else -> MockResponse().setResponseCode(429)
            }
        }
        prices.update(mapOf("AAA" to LocalDate.parse("2026-09-01"), "BBB" to LocalDate.parse("2026-10-01")))
        val paths = listOf(server.takeRequest(), server.takeRequest()).associate {
            it.requestUrl!!.pathSegments.last() to it.requestUrl!!.queryParameter("period1")
        }
        assertEquals("1790640000", paths["AAA"]) // 29 September, a week before the last day
        val state = prices.state.value
        assertTrue(state.failed)
        assertEquals(0, BigDecimal("10").compareTo(state.prices.series("AAA")!!.at(LocalDate.parse("2026-09-02")).price))
        assertEquals(0, BigDecimal("13").compareTo(state.prices.series("AAA")!!.latest.price))
        assertNull(state.prices.series("BBB"))

        // Kept on disk.
        val reloaded = SecurityPrices(JsonStore(folder.root), source) { clock }
        reloaded.load()
        assertEquals(LocalDate.parse("2026-10-07"), reloaded.state.value.prices.series("AAA")!!.latest.date)
    }
}
