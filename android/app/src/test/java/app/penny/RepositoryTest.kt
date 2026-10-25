package app.penny

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.penny.data.BridgeClient
import app.penny.data.CurrencyRates
import app.penny.data.EcbRateSource
import app.penny.data.HiddenAccounts
import app.penny.data.IconStore
import app.penny.data.IncompatibleResponseException
import app.penny.data.JsonStore
import app.penny.data.PairedBridge
import app.penny.data.PendingQueue
import app.penny.data.Repository
import app.penny.data.SecurityPrices
import app.penny.data.Settings
import app.penny.data.SyncMode
import app.penny.data.SyncStatus
import app.penny.data.TokenCipher
import app.penny.data.YahooPriceSource
import app.penny.ui.AddTransactionViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Reversible stand-in for the Android Keystore. */
private object FakeCipher : TokenCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(stored: String) = stored.removePrefix("enc:").reversed().takeIf { stored.startsWith("enc:") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun dataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }

    private fun dir(name: String) = File(folder.root, name)

    private fun TestScope.repository(settings: Settings) = Repository(
        settings = settings,
        client = BridgeClient(),
        discovery = { null },
        cache = JsonStore(dir("cache")),
        queue = PendingQueue(JsonStore(dir("queue"))),
        icons = IconStore(dir("icons")),
        rates = CurrencyRates(JsonStore(dir("rates")), EcbRateSource()),
        prices = SecurityPrices(JsonStore(dir("prices")), YahooPriceSource()),
        scope = backgroundScope,
        scheduleSync = {},
        syncMode = MutableStateFlow(SyncMode.MANUAL),
    )

    @Test fun `the token is stored encrypted, and a plain one from an older version is encrypted on first read`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[stringPreferencesKey("bridge_host")] = "10.0.0.2"
            it[intPreferencesKey("bridge_port")] = 8765
            it[stringPreferencesKey("bridge_token")] = "secret"
        }
        val settings = Settings(store, FakeCipher)
        assertEquals("secret", settings.current()?.token)
        val stored = store.data.first()
        assertNull("plain token removed", stored[stringPreferencesKey("bridge_token")])
        assertEquals("enc:terces", stored[stringPreferencesKey("bridge_token_encrypted")])

        settings.save(PairedBridge("Mac", "10.0.0.3", 8765, "other"))
        assertEquals("other", settings.current()?.token)
        assertEquals("enc:rehto", store.data.first()[stringPreferencesKey("bridge_token_encrypted")])
    }

    @Test fun `an unreadable token means not paired, not a crash`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[stringPreferencesKey("bridge_host")] = "10.0.0.2"
            it[stringPreferencesKey("bridge_token_encrypted")] = "from another key"
        }
        assertNull(Settings(store, FakeCipher).current())
    }

    @Test fun `an answer the app can't read ends the refresh with a status instead of a crash`() = runTest {
        val settings = Settings(dataStore(backgroundScope), FakeCipher)
        settings.save(PairedBridge("Mac", server.hostName, server.port, "secret"))
        server.enqueue(MockResponse().setBody("""{"generation":"g1"}"""))
        val repository = repository(settings)

        repository.refresh(push = false)

        val status = repository.state.value.status
        assertTrue("$status", status is SyncStatus.Failed && status.error is IncompatibleResponseException)
    }

    @Test fun `saving the form twice queues one transaction`() = runTest {
        val repository = repository(Settings(dataStore(backgroundScope), FakeCipher))
        advanceUntilIdle()
        val vm = AddTransactionViewModel(repository, MutableStateFlow(HiddenAccounts()), initialAccountId = "A")
        vm.setAmount("-12,50")
        var saved = 0
        val done = CompletableDeferred<Unit>()
        vm.save { saved++; done.complete(Unit) }
        vm.save { saved++ }
        // The queue is written on the IO dispatcher, outside the test's virtual time.
        done.await()

        assertEquals(1, repository.state.value.pending.size)
        assertEquals("12.50", repository.state.value.pending.single().request.amount)
        assertEquals(1, saved)
    }
}
