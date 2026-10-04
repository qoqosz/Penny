package app.penny

import app.penny.data.Category
import app.penny.data.IconData
import app.penny.data.IconStore
import app.penny.data.Payee
import app.penny.data.Snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class IconStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun snapshot(payeeIcons: List<String?>, categoryIcons: List<String?> = emptyList()) = Snapshot(
        generation = "g", bridgeVersion = "1", writesEnabled = true, accounts = emptyList(),
        categories = categoryIcons.mapIndexed { i, icon -> Category("C$i", "c", "c", kind = "expense", iconId = icon) },
        payees = payeeIcons.mapIndexed { i, icon -> Payee("P$i", "p", iconId = icon) },
    )

    private fun icon(id: String) = IconData(id, "image/png", Base64.getEncoder().encodeToString(id.toByteArray()))

    @Test fun `downloads missing icons in batches and keeps them across restarts`() = runTest {
        val ids = (1..150).map { "p%03d".format(it) }
        val requests = mutableListOf<List<String>>()
        val store = IconStore(folder.root)
        store.sync(snapshot(ids + null, listOf("c1"))) { batch -> requests += batch; batch.map(::icon) }
        assertEquals(listOf(IconStore.BATCH_SIZE, 51), requests.map { it.size })
        assertEquals(ids.toSet() + "c1", store.available.value)
        assertArrayEquals("p007".toByteArray(), store.file("p007").readBytes())

        val restarted = IconStore(folder.root).apply { load() }
        assertEquals(store.available.value, restarted.available.value)
        restarted.sync(snapshot(ids, listOf("c1"))) { error("everything is on disk already") }
    }

    @Test fun `drops icons the Mac no longer uses and doesn't retry missing ones`() = runTest {
        val store = IconStore(folder.root)
        store.sync(snapshot(listOf("pold", "pkeep"))) { batch -> batch.map(::icon) }
        var asked = 0
        // "pnone" isn't sent back (e.g. unreadable on the Mac); names that aren't plain IDs are never requested.
        val next = snapshot(listOf("pkeep", "pnew", "pnone", "../x"))
        store.sync(next) { batch -> asked++; batch.filter { it != "pnone" }.map(::icon) }
        store.sync(next) { batch -> asked++; batch.map(::icon) }
        assertEquals(1, asked)
        assertEquals(setOf("pkeep", "pnew"), store.available.value)
        assertFalse(store.file("pold").exists())
    }

    @Test fun `clearing forgets everything`() = runTest {
        val store = IconStore(folder.root)
        store.sync(snapshot(listOf("p1"))) { batch -> batch.map(::icon) }
        store.clear()
        assertEquals(emptySet<String>(), store.available.value)
        assertFalse(store.file("p1").exists())
    }
}
