package app.penny

import app.penny.data.CurrencyRates
import app.penny.data.EcbRateSource
import app.penny.data.ExchangeRates
import app.penny.data.JsonStore
import app.penny.data.RateSeries
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.time.LocalDate

class ExchangeRatesTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val source get() = EcbRateSource(OkHttpClient(), server.url("/service/data/EXR/"))

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun day(date: String) = LocalDate.parse(date).toEpochDay()

    private fun csv(vararg rows: String) = MockResponse().setBody(
        "KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE\n" +
            rows.joinToString("\n") { row ->
                val (currency, date, value) = row.split(" ")
                "EXR.D.$currency.EUR.SP00.A,D,$currency,EUR,SP00,A,$date,$value"
            } + "\n",
    )

    @Test fun `cross rates go through the euro and weekends use Friday's`() {
        val rates = ExchangeRates(mapOf(
            "PLN" to RateSeries(listOf(day("2026-10-02"), day("2026-10-05")), listOf("4.25", "4.40")),
            "USD" to RateSeries(listOf(day("2026-10-02"), day("2026-10-05")), listOf("1.25", "1.10")),
        ))
        assertEquals(0, BigDecimal("3.4").compareTo(rates.rate("USD", "PLN", LocalDate.parse("2026-10-04"))))
        assertEquals(0, BigDecimal("4").compareTo(rates.rate("USD", "PLN", LocalDate.parse("2026-10-05"))))
        assertEquals(0, BigDecimal("4.25").compareTo(rates.rate("EUR", "PLN", LocalDate.parse("2026-10-03"))))
        // Before the first publication: the first rate.
        assertEquals(0, BigDecimal("1.25").compareTo(rates.rate("EUR", "USD", LocalDate.parse("2020-01-01"))))
        assertNull(rates.rate("GBP", "PLN", LocalDate.parse("2026-10-05")))
        assertEquals(BigDecimal.ONE, rates.rate("GBP", "GBP", LocalDate.parse("2026-10-05")))
        assertTrue(rates.covers("EUR"))
        assertFalse(rates.covers("GBP"))
        assertEquals(LocalDate.parse("2026-10-05"), rates.latestDay("PLN"))
    }

    @Test fun `parses the ECB's CSV`() = runTest {
        server.enqueue(csv("PLN 2026-10-05 4.3795", "USD 2026-10-05 1.1204", "PLN 2026-10-06 4.365"))
        val fetched = source.fetch(listOf("USD", "PLN", "EUR", "bad"), LocalDate.parse("2026-10-01"))
        assertEquals(mapOf(day("2026-10-05") to "4.3795", day("2026-10-06") to "4.365"), fetched["PLN"])
        assertEquals(setOf("PLN", "USD"), fetched.keys)
        val request = server.takeRequest()
        assertEquals("/service/data/EXR/D.PLN+USD.EUR.SP00.A?startPeriod=2026-10-01&detail=dataonly", request.path)
    }

    @Test fun `no data is not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"title":"Not Found"}"""))
        assertTrue(source.fetch(listOf("XYZ"), LocalDate.parse("2026-10-01")).isEmpty())
    }

    @Test fun `downloads only what is missing`() = runTest {
        var clock = 0L
        val rates = CurrencyRates(JsonStore(folder.root), source) { clock }
        server.enqueue(csv("PLN 2026-09-01 4.25", "PLN 2026-10-01 4.30", "USD 2026-10-01 1.10"))
        rates.update(setOf("PLN", "USD", "EUR", "RUB"), LocalDate.parse("2026-09-01"))
        assertEquals("/service/data/EXR/D.PLN+RUB+USD.EUR.SP00.A?startPeriod=2026-09-01&detail=dataonly", server.takeRequest().path)
        assertEquals(0, BigDecimal("4.30").compareTo(rates.state.value.rates.rate("EUR", "PLN", LocalDate.parse("2026-10-02"))))

        // Fresh enough, nothing new asked for (RUB isn't published, so it isn't asked for again): no request.
        clock = 60_000
        rates.update(setOf("PLN", "USD", "RUB"), LocalDate.parse("2026-09-15"))
        assertEquals(1, server.requestCount)

        // A new currency in full, then the last days of the others.
        server.enqueue(csv("GBP 2026-09-01 0.85"))
        server.enqueue(csv("PLN 2026-10-02 4.35"))
        rates.update(setOf("PLN", "USD", "GBP"), LocalDate.parse("2026-09-15"))
        assertEquals("/service/data/EXR/D.GBP.EUR.SP00.A?startPeriod=2026-09-01&detail=dataonly", server.takeRequest().path)
        assertEquals("/service/data/EXR/D.PLN+USD.EUR.SP00.A?startPeriod=2026-09-24&detail=dataonly", server.takeRequest().path)
        val state = rates.state.value
        assertEquals(LocalDate.parse("2026-10-02"), state.rates.latestDay("PLN"))
        assertTrue(state.rates.covers("GBP"))

        // Kept on disk.
        val reloaded = CurrencyRates(JsonStore(folder.root), source) { clock }
        reloaded.load()
        assertEquals(LocalDate.parse("2026-10-02"), reloaded.state.value.rates.latestDay("PLN"))
        assertEquals(0, BigDecimal("4.25").compareTo(reloaded.state.value.rates.rate("EUR", "PLN", LocalDate.parse("2026-09-01"))))

        // Earlier days: everything again. A failure keeps the old rates.
        server.enqueue(MockResponse().setResponseCode(500))
        rates.update(setOf("PLN", "USD", "GBP"), LocalDate.parse("2026-01-01"))
        assertEquals("/service/data/EXR/D.GBP+PLN+USD.EUR.SP00.A?startPeriod=2026-01-01&detail=dataonly", server.takeRequest().path)
        assertTrue(rates.state.value.failed)
        assertTrue(rates.state.value.rates.covers("GBP"))
    }
}
