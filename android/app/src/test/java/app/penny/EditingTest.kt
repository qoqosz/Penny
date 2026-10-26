package app.penny

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.penny.data.BridgeClient
import app.penny.data.CloudStatus
import app.penny.data.CloudWaiting
import app.penny.data.CurrencyRates
import app.penny.data.EcbRateSource
import app.penny.data.HiddenAccounts
import app.penny.data.IconStore
import app.penny.data.JsonStore
import app.penny.data.LastAccount
import app.penny.data.NewTransactionRequest
import app.penny.data.PairedBridge
import app.penny.data.PendingChange
import app.penny.data.PendingQueue
import app.penny.data.Repository
import app.penny.data.SecurityPrices
import app.penny.data.Settings
import app.penny.data.Split
import app.penny.data.SyncMode
import app.penny.data.SyncStatus
import app.penny.data.TokenCipher
import app.penny.data.Transaction
import app.penny.data.YahooPriceSource
import app.penny.ui.AddTransactionViewModel
import app.penny.ui.EditTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** Changing, deleting and taking back transactions; made-up data against a fake bridge. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditingTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    /** The batches the fake bridge got. */
    private val batches = mutableListOf<JsonObject>()

    private val groceries = Transaction(
        id = "T1", accountId = "A", date = "2026-10-01T12:00:00Z", payee = "Lidl", note = "z telefonu",
        amount = "-12.34", currency = "PLN", kind = "expense",
        splits = listOf(Split("S1", "-12.34", categoryId = "C")), editable = true,
    )
    /** The incoming side of a transfer of 25 from A to B. */
    private val transferIn = Transaction(
        id = "T2b", accountId = "B", date = "2026-10-04T09:00:00Z", amount = "25", currency = "PLN", kind = "transfer",
        splits = listOf(Split("S2b", "25", transferAccountId = "A", transferTransactionId = "T2a")), editable = true,
    )

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = bridge(request)
            }
            start()
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun bridge(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val body = when (path) {
            "/api/v1/ping" -> """{"app":"penny-bridge","name":"Mac","version":"1.0.0"}"""
            "/api/v1/transactions/batch" -> {
                val batch = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                batches += batch
                fun ids(key: String, id: (JsonObject) -> String) = batch[key]?.jsonArray.orEmpty().map {
                    val o = it.jsonObject
                    """{"clientId":"${o["clientId"]!!.jsonPrimitive.content}","transactionId":"${id(o)}"}"""
                }
                val results = ids("transactions") { "T-" + it["clientId"]!!.jsonPrimitive.content } +
                    ids("updates") { it["transactionId"]!!.jsonPrimitive.content } +
                    ids("deletes") { it["transactionId"]!!.jsonPrimitive.content }
                """{"results":[${results.joinToString(",")}]}"""
            }
            "/api/v1/snapshot" -> """
                {"generation":"g2","bridgeVersion":"1.0.0","writesEnabled":true,"writesTransfers":true,"writesEdits":true,
                 "defaultCurrency":"PLN","currencies":["PLN"],
                 "accounts":[{"id":"A","name":"Konto","type":0,"currency":"PLN","balance":"1000"},
                             {"id":"B","name":"Oszczędności","type":0,"currency":"PLN","balance":"500"}],
                 "categories":[],"payees":[]}
            """.trimIndent()
            "/api/v1/transactions" -> """{"generation":"g2","total":0,"offset":0,"items":[]}"""
            "/api/v1/cloud" -> """{"moneyRunning":false,"waiting":[{"transactionId":"T1","change":"changed","since":"2026-10-25T10:00:00Z"}]}"""
            else -> return MockResponse().setResponseCode(404)
        }
        return MockResponse().setBody(body)
    }

    private fun TestScope.repository(): Repository {
        val settings = Settings(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "s.preferences_pb") },
            object : TokenCipher {
                override fun encrypt(plain: String) = plain
                override fun decrypt(stored: String) = stored
            })
        return Repository(
            settings = settings,
            client = BridgeClient(),
            discovery = { null },
            cache = JsonStore(File(folder.root, "cache")),
            queue = PendingQueue(JsonStore(File(folder.root, "queue"))),
            icons = IconStore(File(folder.root, "icons")),
            rates = CurrencyRates(JsonStore(File(folder.root, "rates")), EcbRateSource()),
            prices = SecurityPrices(JsonStore(File(folder.root, "prices")), YahooPriceSource()),
            scope = backgroundScope,
            scheduleSync = {},
            syncMode = MutableStateFlow(SyncMode.MANUAL),
        ).also { this@EditingTest.settings = settings }
    }

    private lateinit var settings: Settings

    private suspend fun pair() = settings.save(PairedBridge("Mac", server.hostName, server.port, "secret"))

    private fun request(clientId: String, amount: String = "5") = NewTransactionRequest(
        clientId = clientId, accountId = "A", date = "2026-10-05T12:00:00Z", kind = "expense", amount = amount,
    )

    @Test fun `a transaction becomes the request that would make it`() {
        val expense = groceries.toRequest("x")
        assertEquals(listOf("T1", "A", null, "expense", "12.34", "C", "Lidl"),
            listOf(expense.transactionId, expense.accountId, expense.transferAccountId, expense.kind, expense.amount, expense.categoryId, expense.payeeName))
        val transfer = transferIn.toRequest("y")
        assertEquals("a transfer goes from the account it leaves", listOf("A", "B", "transfer", "25", null),
            listOf(transfer.accountId, transfer.transferAccountId, transfer.kind, transfer.amount, transfer.categoryId))
        assertEquals(listOf("T2b", "T2a"), transferIn.sides)
        val euros = groceries.copy(amount = "-42.5", originalAmount = "-10", originalCurrency = "EUR", exchangeRate = "4.25").toRequest("z")
        assertEquals(listOf("10", "EUR", "4.25"), listOf(euros.amount, euros.currency, euros.exchangeRate))
    }

    @Test fun `a change and a deletion wait in the queue, then go to the Mac in one batch`() = runTest {
        val repository = repository()
        repository.loaded.join()
        pair()
        repository.edit(groceries, groceries.toRequest("edit-form").copy(amount = "20", note = "poprawione"))
        repository.delete(transferIn)
        val pending = repository.state.value.pending
        assertEquals(listOf(PendingChange.UPDATE, PendingChange.DELETE), pending.map { it.change })
        assertEquals(setOf("T1", "T2b", "T2a"), repository.state.value.replaced)
        assertEquals("12.34", pending[0].original?.amount)

        repository.refresh(push = true)

        val batch = batches.single()
        assertEquals("[]", batch["transactions"].toString())
        val update = batch["updates"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("T1", "20", "poprawione"), listOf("transactionId", "amount", "note").map { update[it]!!.jsonPrimitive.content })
        assertEquals("T2b", batch["deletes"]!!.jsonArray.single().jsonObject["transactionId"]!!.jsonPrimitive.content)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(SyncStatus.Idle, repository.state.value.status)
    }

    @Test fun `a queued change keeps its clientId when edited, and a deletion takes its place`() = runTest {
        val repository = repository()
        repository.loaded.join()
        repository.edit(groceries, groceries.toRequest("form-1").copy(amount = "20"))
        val clientId = repository.state.value.pending.single().request.clientId
        repository.updatePending(clientId, groceries.toRequest("form-2").copy(amount = "25"))
        val edited = repository.state.value.pending.single()
        assertEquals(listOf(clientId, "T1", "25", "12.34"),
            listOf(edited.request.clientId, edited.request.transactionId, edited.request.amount, edited.original?.amount))
        repository.edit(groceries, groceries.toRequest("form-3").copy(amount = "30"))
        assertEquals("another edit of the same transaction changes the queued one",
            listOf(clientId to "30"), repository.state.value.pending.map { it.request.clientId to it.request.amount })

        repository.delete(groceries)
        val deletion = repository.state.value.pending.single()
        assertEquals(PendingChange.DELETE, deletion.change)
        assertEquals("the Mac's version is the one deleted", "12.34", deletion.request.amount)
    }

    @Test fun `undo drops a queued transaction, and deletes one the Mac already has`() = runTest {
        val repository = repository()
        repository.loaded.join()
        pair()
        repository.add(request("queued"))
        repository.undoAdd("queued")
        assertTrue(repository.state.value.pending.isEmpty())

        repository.add(request("sent", amount = "7"))
        repository.refresh(push = true)
        assertTrue(repository.state.value.pending.isEmpty())
        repository.undoAdd("sent")
        val deletion = repository.state.value.pending.single()
        assertEquals(listOf(PendingChange.DELETE, "T-sent", "7"),
            listOf(deletion.change, deletion.request.transactionId, deletion.original?.amount))
    }

    @Test fun `the form edits a transfer from either side, keeping its time`() = runTest {
        val repository = repository()
        repository.loaded.join()
        repository.keep(transferIn)
        val vm = AddTransactionViewModel(repository, MutableStateFlow(HiddenAccounts()), null, editing = EditTarget.Saved("T2b"))
        val form = vm.form.value
        assertTrue(form.ready)
        assertEquals(listOf("-25", "A", "B", true), listOf(form.amount, form.accountId, form.transferAccountId, form.transferLock))
        vm.setAmount("-30")
        val done = CompletableDeferred<String?>()
        vm.save { done.complete(it) }
        assertNull("no undo for an edit", done.await())
        val change = repository.state.value.pending.single()
        assertEquals(listOf(PendingChange.UPDATE, "T2b", "A", "B", "30", "2026-10-04T09:00:00Z"),
            listOf(change.change, change.request.transactionId, change.request.accountId, change.request.transferAccountId,
                change.request.amount, change.request.date))
    }

    @Test fun `a new transaction starts in the account last added to`() = runTest {
        val repository = repository()
        repository.loaded.join()
        val memory = object : LastAccount { override var lastAccountId: String? = null }
        val vm = AddTransactionViewModel(repository, MutableStateFlow(HiddenAccounts()), initialAccountId = "B", lastAccount = memory)
        vm.setAmount("-3")
        val done = CompletableDeferred<String?>()
        vm.save { done.complete(it) }
        val clientId = done.await()
        assertEquals("the clientId comes back, to undo it", repository.state.value.pending.single().request.clientId, clientId)
        assertEquals("B", memory.lastAccountId)
    }

    @Test fun `a refresh notes when the data was downloaded and what Money hasn't uploaded`() = runTest {
        val repository = repository()
        repository.loaded.join()
        pair()
        assertNull(repository.state.value.updatedAt)
        repository.refresh(push = false)
        assertNotNull(repository.state.value.updatedAt)
        val cloud = repository.state.value.cloud
        assertEquals("changed", cloud?.waiting("T1")?.change)
        assertEquals(false, cloud?.moneyRunning)
    }

    @Test fun `changes are overdue after ten minutes`() {
        val cloud = CloudStatus(waiting = listOf(
            CloudWaiting("old", "new", "2026-10-25T10:00:00Z"), CloudWaiting("recent", "new", "2026-10-25T10:08:00Z"),
            CloudWaiting("undated", "new"),
        ))
        assertEquals(listOf("old"), cloud.overdue(Instant.parse("2026-10-25T10:12:00Z")).map { it.transactionId })
    }
}
