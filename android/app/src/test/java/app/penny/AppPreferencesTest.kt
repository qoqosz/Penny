package app.penny

import app.penny.data.Account
import app.penny.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppPreferencesTest {
    @Test fun `hidden folders survive a restart`() {
        val prefs = MemoryPreferences()
        AppPreferences(prefs).apply {
            setFolderHidden("archive", true)
            setFolderHidden("bank", true)
            setFolderHidden("bank", false)
        }
        assertEquals(setOf("archive"), AppPreferences(prefs).hiddenFolders.value)
    }

    @Test fun `accounts outside folders are never hidden`() {
        val account = Account(id = "A", name = "Wallet", type = 0, currency = "PLN", balance = "0")
        assertFalse(account.isHidden(setOf("archive")))
    }
}
