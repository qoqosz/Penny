package app.penny

import app.penny.data.Account
import app.penny.data.AppPreferences
import app.penny.data.HiddenAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    @Test fun `hidden folders and accounts survive a restart`() {
        val prefs = MemoryPreferences()
        AppPreferences(prefs).apply {
            setFolderHidden("archive", true)
            setFolderHidden("bank", true)
            setFolderHidden("bank", false)
            setAccountHidden("wallet", true)
            setAccountHidden("card", true)
            setAccountHidden("card", false)
        }
        assertEquals(HiddenAccounts(folders = setOf("archive"), accounts = setOf("wallet")), AppPreferences(prefs).hidden.value)
    }

    @Test fun `accounts are hidden alone or with their folder`() {
        val wallet = Account(id = "A", name = "Wallet", type = 0, currency = "PLN", balance = "0")
        val savings = wallet.copy(id = "B", name = "Savings", folder = "Archive", folderId = "archive")
        assertFalse(wallet.isHidden(HiddenAccounts(folders = setOf("archive"))))
        assertTrue(wallet.isHidden(HiddenAccounts(accounts = setOf("A"))))
        assertTrue(savings.isHidden(HiddenAccounts(folders = setOf("archive"))))
        assertTrue(savings.isHidden(HiddenAccounts(accounts = setOf("B"))))
        assertFalse(savings.isHidden(HiddenAccounts(folders = setOf("bank"), accounts = setOf("A"))))
    }
}
