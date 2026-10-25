package app.penny

import app.penny.data.JsonStore
import app.penny.data.NewTransactionRequest
import app.penny.data.PendingQueue
import app.penny.data.PendingTransaction
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val corrupt = mutableListOf<File>()
    private fun store() = JsonStore(folder.root) { file, _ -> corrupt += file }
    private fun kept() = folder.root.listFiles().orEmpty().filter { it.name.startsWith("pending.json.corrupt-") }

    private fun request(id: String) = NewTransactionRequest(
        clientId = id, accountId = "A", date = "2026-10-25T12:00:00Z", kind = "expense", amount = "12.34",
    )

    @Test fun `writes and reads back, without leaving a temporary file`() = runTest {
        val store = store()
        store.write("list.json", ListSerializer(String.serializer()), listOf("a", "b"))
        store.write("list.json", ListSerializer(String.serializer()), listOf("c"))
        assertEquals(listOf("c"), store.read("list.json", ListSerializer(String.serializer())))
        assertEquals(listOf("list.json"), folder.root.list()!!.toList())
    }

    @Test fun `an unreadable file is moved aside and reported, not read as empty`() = runTest {
        File(folder.root, "list.json").writeText("""["a", 1""")
        assertNull(store().read("list.json", ListSerializer(String.serializer())))
        assertFalse(File(folder.root, "list.json").exists())
        assertEquals(1, corrupt.size)
        assertEquals("""["a", 1""", corrupt.single().readText())
    }

    @Test fun `a broken queue is kept when the next transaction is queued`() = runTest {
        val broken = """[{"request":{"clientId":"old","accountId":"A","date":"2026-10-24T12:00:00Z","kind":"expense","amount":"5"},"createdAt":"""
        File(folder.root, "pending.json").writeText(broken)
        val queue = PendingQueue(store())

        val updated = queue.update { it + PendingTransaction(request("new"), 1) }

        assertEquals(listOf("new"), updated.map { it.request.clientId })
        assertEquals(listOf("new"), queue.all().map { it.request.clientId })
        assertEquals("the broken queue survives for recovery", broken, kept().single().readText())
        assertTrue(corrupt.isNotEmpty())
    }
}
