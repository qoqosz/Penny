package app.penny

import app.penny.data.BridgeClient
import app.penny.data.Connection
import app.penny.data.JsonStore
import app.penny.data.OfflineTransactions
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTransactionsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = BridgeClient()
    private val connection get() = Connection(server.hostName, server.port, "secret")

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun tx(id: String, account: String) =
        """{"id":"$id","accountId":"$account","date":"2026-10-0${id.last()}T08:00:00Z","amount":"-1","currency":"PLN","kind":"expense"}"""

    private fun page(generation: String, total: Int, offset: Int, vararg items: String) =
        MockResponse().setBody("""{"generation":"$generation","total":$total,"offset":$offset,"items":[${items.joinToString(",")}]}""")

    @Test fun `downloads every page and serves accounts offline after a restart`() = runTest {
        server.enqueue(page("g1", 3, 0, tx("t3", "A"), tx("t2", "B")))
        server.enqueue(page("g1", 3, 2, tx("t1", "A")))
        OfflineTransactions(client, JsonStore(folder.root)).update(connection, "g1")
        assertEquals("/api/v1/transactions?offset=0&limit=1000", server.takeRequest().path)
        assertEquals("/api/v1/transactions?offset=2&limit=1000", server.takeRequest().path)

        val restored = OfflineTransactions(client, JsonStore(folder.root)).current()!!
        assertEquals("g1", restored.generation)
        val account = restored.page("A", 0, 100)
        assertEquals(2, account.total)
        assertEquals(listOf("t3", "t1"), account.items.map { it.id })
        assertEquals(listOf("t2"), restored.page(null, 1, 1).items.map { it.id })
        assertEquals(3, restored.page(null, 1, 1).total)
    }

    @Test fun `skips the download when the copy is current`() = runTest {
        server.enqueue(page("g1", 1, 0, tx("t1", "A")))
        val offline = OfflineTransactions(client, JsonStore(folder.root))
        offline.update(connection, "g1")
        offline.update(connection, "g1")
        assertEquals(1, server.requestCount)
    }

    @Test fun `keeps the old copy when the Mac's data changes mid-download`() = runTest {
        server.enqueue(page("g2", 2, 0, tx("t2", "A")))
        server.enqueue(page("g3", 2, 1, tx("t1", "A")))
        val offline = OfflineTransactions(client, JsonStore(folder.root))
        offline.update(connection, "g2")
        assertNull(offline.current())
    }
}
