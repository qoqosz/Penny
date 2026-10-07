package app.penny

import app.penny.data.BridgeClient
import app.penny.data.BridgeException
import app.penny.data.Connection
import app.penny.data.HiddenAccounts
import app.penny.data.NewTransactionRequest
import app.penny.data.UnreachableException
import app.penny.ui.Format
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class BridgeClientTest {
    private lateinit var server: MockWebServer
    private val client = BridgeClient()
    private val connection get() = Connection(server.hostName, server.port, "secret")

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `parses snapshot as sent by the bridge`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"generation":"1:2-3:4","bridgeVersion":"1.0.0","writesEnabled":true,"defaultCurrency":"PLN","currencies":["PLN","EUR"],
             "accounts":[{"id":"A","name":"Konto","type":2,"currency":"PLN","folder":"Archiwum","folderId":"F1","closed":false,"sortOrder":0,
                          "balance":"83.16","transactionCount":4,"lastTransactionDate":"2026-10-02T08:30:00Z","hasInvestments":false}],
             "categories":[{"id":"C","name":"Groceries","fullName":"Food & Dining › Groceries","parentId":"P","kind":"expense","usageCount":3,"iconId":"c01"}],
             "payees":[{"id":"P1","name":"Biedronka","usageCount":2,"iconId":"p01"}],
             "futureField":42}
        """.trimIndent()))
        val snapshot = client.snapshot(connection)
        assertEquals(BigDecimal("83.16"), snapshot.accounts.single().balanceValue)
        assertEquals(listOf("PLN", "EUR"), snapshot.currencies)
        assertEquals(setOf("PLN", "EUR"), snapshot.allCurrencies)
        assertTrue(snapshot.accounts.single().isHidden(HiddenAccounts(folders = setOf("F1"))))
        assertFalse(snapshot.accounts.single().isHidden(HiddenAccounts(folders = setOf("F2"))))
        assertEquals("expense", snapshot.categories.single().kind)
        assertNull(snapshot.payees.single().categoryId)
        assertEquals("p01", snapshot.payee("P1")?.iconId)
        assertEquals("c01", snapshot.category("C")?.iconId)
        assertNull(snapshot.payee(null))
        val request = server.takeRequest()
        assertEquals("/api/v1/snapshot", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test fun `parses transaction details, with or without them`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"generation":"g","total":2,"offset":0,"items":[
              {"id":"T1","accountId":"A","date":"2026-10-02T08:30:00Z","payee":"Lidl","amount":"-449.85","currency":"PLN",
               "kind":"expense","reconciled":0,"splits":[{"id":"S1","amount":"-449.85","category":"Food"}],
               "tags":[{"name":"Vacation","color":"blue"},{"name":"Home"}],
               "location":{"street":"Puławska 2","city":"Warszawa","latitude":52.2,"longitude":21.02},
               "originalAmount":"-101","originalCurrency":"EUR","exchangeRate":"4.4540"},
              {"id":"T2","accountId":"A","date":"2026-10-01T08:30:00Z","amount":"5","currency":"PLN","kind":"income","reconciled":0,"splits":[]}
            ]}
        """.trimIndent()))
        val (full, old) = client.transactions(connection, null, 0, 10).items
        assertEquals(listOf("blue", null), full.tags.map { it.color })
        assertTrue(full.location!!.hasCoordinates)
        assertEquals("EUR", full.originalCurrency)
        assertTrue("bridges before tags send none", old.tags.isEmpty())
        assertNull(old.location)
        assertNull(old.originalAmount)
    }

    @Test fun `sends new transaction and maps bridge errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422)
            .setBody("""{"error":{"code":"invalid","message":"Nie ma takiej kategorii."}}"""))
        val request = NewTransactionRequest("id-1", "A", "2026-10-03T09:30:00Z", "expense", "12.5", payeeName = "Biedronka")
        val error = runCatching { client.create(connection, request) }.exceptionOrNull() as BridgeException
        assertEquals("Nie ma takiej kategorii.", error.message)
        assertTrue(error.isPermanent)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"amount\":\"12.5\""))
        assertFalse("nulls are omitted", body.contains("categoryId"))
        assertFalse("no currency for the account's own", body.contains("currency"))

        server.enqueue(MockResponse().setResponseCode(201).setBody("""
            {"id":"T","accountId":"A","date":"2026-10-03T09:30:00Z","amount":"-42.5","currency":"PLN","kind":"expense",
             "originalAmount":"-10","originalCurrency":"EUR","exchangeRate":"4.25"}
        """.trimIndent()))
        val created = client.create(connection, request.copy(clientId = "id-2", amount = "10", currency = "EUR", exchangeRate = "4.25"))
        assertEquals("EUR", created.originalCurrency)
        val euros = server.takeRequest().body.readUtf8()
        assertTrue(euros.contains("\"currency\":\"EUR\"") && euros.contains("\"exchangeRate\":\"4.25\""))
    }

    @Test fun `asks for messages in the app language`() = runTest {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("pl-PL"))) {
                Locale.setDefault(locale)
                server.enqueue(MockResponse().setBody("""{"app":"penny-bridge","name":"Mac","version":"1.0.0"}"""))
                client.ping(server.hostName, server.port)
                assertEquals(locale.toLanguageTag(), server.takeRequest().getHeader("Accept-Language"))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `busy Mac is retried, not rejected`() {
        assertFalse(BridgeException(409, "busy", "Money nie zamknął się").isPermanent)
        assertFalse(BridgeException(503, "setup", "Brak dostępu").isPermanent)
    }

    @Test fun `unreachable Mac is reported as such`() = runTest {
        server.shutdown()
        val error = runCatching { client.snapshot(connection) }.exceptionOrNull()
        assertTrue(error is UnreachableException)
    }

    @Test fun `parses amounts typed by the user`() {
        assertEquals(BigDecimal("12.50"), Format.parseAmount("12,50"))
        assertEquals(BigDecimal("1234.5"), Format.parseAmount("1 234,5"))
        assertEquals(BigDecimal("7"), Format.parseAmount("7"))
        assertNull(Format.parseAmount("0"))
        assertNull(Format.parseAmount("1,234"))
        assertNull(Format.parseAmount("abc"))
        assertNull(Format.parseAmount(""))
    }
}
