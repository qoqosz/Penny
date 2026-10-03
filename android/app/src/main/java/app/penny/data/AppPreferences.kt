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

/**
 * UI preferences. Kept in SharedPreferences rather than DataStore because the theme has to be known synchronously
 * before the first activity is created. The language is stored by AppCompat itself (or by the system on Android 13+).
 */
class AppPreferences(private val prefs: SharedPreferences) {
    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _hiddenFolders = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN_FOLDERS, null).orEmpty().toSet())
    /** IDs of Money folders whose accounts aren't shown. */
    val hiddenFolders: StateFlow<Set<String>> = _hiddenFolders.asStateFlow()

    fun setFolderHidden(folderId: String, hidden: Boolean) {
        val updated = if (hidden) _hiddenFolders.value + folderId else _hiddenFolders.value - folderId
        prefs.edit { putStringSet(KEY_HIDDEN_FOLDERS, updated) }
        _hiddenFolders.value = updated
    }

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
    }
}
