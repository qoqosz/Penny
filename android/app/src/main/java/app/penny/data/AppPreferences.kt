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

/** What the user hid in settings: whole Money folders and single accounts, by ID. */
data class HiddenAccounts(val folders: Set<String> = emptySet(), val accounts: Set<String> = emptySet())

/**
 * UI preferences. Kept in SharedPreferences rather than DataStore because the theme has to be known synchronously
 * before the first activity is created. The language is stored by AppCompat itself (or by the system on Android 13+).
 */
class AppPreferences(private val prefs: SharedPreferences) {
    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

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
        const val KEY_HIDDEN_FOLDERS = "hidden_folders"
        const val KEY_HIDDEN_ACCOUNTS = "hidden_accounts"
    }
}
