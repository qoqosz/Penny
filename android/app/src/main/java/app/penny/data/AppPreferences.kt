package app.penny.data

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
}

enum class AppLanguage(val tag: String) {
    SYSTEM(""), ENGLISH("en-US"), POLISH("pl");
}

/** When queued transactions go to the Mac: right after adding them, or only when the user taps "Sync now". */
enum class SyncMode { AUTOMATIC, MANUAL }

/** What the user hid in settings: whole Money folders and single accounts, by ID. */
data class HiddenAccounts(val folders: Set<String> = emptySet(), val accounts: Set<String> = emptySet())

/**
 * The user's order of account groups (Money folders, by [Account.groupKey]) and of accounts, by ID. Empty keeps Money's
 * order. Groups and accounts it doesn't list (added in Money later) follow the listed ones in Money's order.
 */
data class AccountOrder(val groups: List<String> = emptyList(), val accounts: List<String> = emptyList()) {
    val isCustom: Boolean get() = groups.isNotEmpty() || accounts.isNotEmpty()
}

/** Accounts grouped by folder, the groups and the accounts in each in [order]. */
fun List<Account>.ordered(order: AccountOrder): List<Account> {
    if (!order.isCustom) return this
    fun rank(list: List<String>, key: String) = list.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it }
    // groupBy keeps Money's order of the groups, and the sorts are stable.
    return groupBy { it.groupKey }.entries
        .sortedBy { rank(order.groups, it.key) }
        .flatMap { (_, accounts) -> accounts.sortedBy { rank(order.accounts, it.id) } }
}

/** Where the add form keeps the account it last added a transaction to. */
interface LastAccount {
    var lastAccountId: String?
}

/**
 * UI preferences. Kept in SharedPreferences rather than DataStore because the theme has to be known synchronously
 * before the first activity is created. The language is stored by AppCompat itself (or by the system on Android 13+).
 */
class AppPreferences(private val prefs: SharedPreferences) : LastAccount {
    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _syncMode = MutableStateFlow(
        runCatching { SyncMode.valueOf(prefs.getString(KEY_SYNC_MODE, null)!!) }.getOrDefault(SyncMode.AUTOMATIC)
    )
    val syncMode: StateFlow<SyncMode> = _syncMode.asStateFlow()

    fun setSyncMode(mode: SyncMode) {
        prefs.edit { putString(KEY_SYNC_MODE, mode.name) }
        _syncMode.value = mode
    }

    private val _hidden = MutableStateFlow(
        HiddenAccounts(
            folders = prefs.getStringSet(KEY_HIDDEN_FOLDERS, null).orEmpty().toSet(),
            accounts = prefs.getStringSet(KEY_HIDDEN_ACCOUNTS, null).orEmpty().toSet(),
        )
    )
    /** Accounts that aren't shown. */
    val hidden: StateFlow<HiddenAccounts> = _hidden.asStateFlow()

    fun setFolderHidden(folderId: String, hidden: Boolean) {
        val folders = _hidden.value.folders.toggled(folderId, hidden)
        prefs.edit { putStringSet(KEY_HIDDEN_FOLDERS, folders) }
        _hidden.value = _hidden.value.copy(folders = folders)
    }

    fun setAccountHidden(accountId: String, hidden: Boolean) {
        val accounts = _hidden.value.accounts.toggled(accountId, hidden)
        prefs.edit { putStringSet(KEY_HIDDEN_ACCOUNTS, accounts) }
        _hidden.value = _hidden.value.copy(accounts = accounts)
    }

    private val _order = MutableStateFlow(
        AccountOrder(groups = prefs.getString(KEY_ORDER_GROUPS, null).toIdList(), accounts = prefs.getString(KEY_ORDER_ACCOUNTS, null).toIdList())
    )
    /** How the accounts are ordered on the home screen and in the add form. */
    val accountOrder: StateFlow<AccountOrder> = _order.asStateFlow()

    fun setAccountOrder(order: AccountOrder) {
        prefs.edit {
            putString(KEY_ORDER_GROUPS, order.groups.joinToString("\n"))
            putString(KEY_ORDER_ACCOUNTS, order.accounts.joinToString("\n"))
        }
        _order.value = order
    }

    // IDs and folder names never contain line breaks. The group outside folders is "".
    private fun String?.toIdList() = this?.takeIf { it.isNotEmpty() }?.split('\n').orEmpty()

    /** The add form starts in this account. */
    override var lastAccountId: String?
        get() = prefs.getString(KEY_LAST_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_LAST_ACCOUNT, value) }

    private fun Set<String>.toggled(id: String, add: Boolean) = if (add) this + id else this - id

    /** Applies the stored theme; call before any activity is created. */
    fun applyTheme() = AppCompatDelegate.setDefaultNightMode(_theme.value.nightMode)

    /** Recreates open activities in the new theme. */
    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _theme.value = mode
        applyTheme()
    }

    val language: AppLanguage
        get() {
            val current = AppCompatDelegate.getApplicationLocales()[0] ?: return AppLanguage.SYSTEM
            return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag.startsWith(current.language) }
                ?: AppLanguage.SYSTEM
        }

    /** Recreates open activities in the new language. */
    fun setLanguage(language: AppLanguage) =
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_HIDDEN_FOLDERS = "hidden_folders"
        const val KEY_HIDDEN_ACCOUNTS = "hidden_accounts"
        const val KEY_LAST_ACCOUNT = "last_account"
        const val KEY_ORDER_GROUPS = "order_groups"
        const val KEY_ORDER_ACCOUNTS = "order_accounts"
    }
}
