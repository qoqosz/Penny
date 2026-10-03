package app.penny.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Account
import app.penny.data.AppLanguage
import app.penny.data.AppPreferences
import app.penny.data.Repository
import app.penny.data.Settings
import app.penny.data.ThemeMode
import app.penny.lock.AppLock
import app.penny.lock.Biometrics
import kotlinx.coroutines.launch

private enum class PinFlow { ENABLE, DISABLE, CHANGE_VERIFY, CHANGE_NEW }

private enum class Picker { THEME, LANGUAGE, TIMEOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    lock: AppLock,
    settings: Settings,
    repository: Repository,
    onBack: () -> Unit,
    onUnpaired: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by preferences.theme.collectAsStateWithLifecycle()
    val hiddenFolders by preferences.hiddenFolders.collectAsStateWithLifecycle()
    val appState by repository.state.collectAsStateWithLifecycle()
    val lockConfig by lock.config.collectAsStateWithLifecycle()
    val bridge by settings.bridge.collectAsStateWithLifecycle(initialValue = null)
    var biometricsAvailable by remember { mutableStateOf(Biometrics.available(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { biometricsAvailable = Biometrics.available(context) }

    var picker by remember { mutableStateOf<Picker?>(null) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))
            SettingRow(
                title = stringResource(R.string.settings_theme),
                subtitle = themeLabel(theme),
                onClick = { picker = Picker.THEME },
            )
            SettingRow(
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(preferences.language),
                onClick = { picker = Picker.LANGUAGE },
            )

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader(stringResource(R.string.settings_accounts))
            HiddenFolders(appState.snapshot?.accounts, hiddenFolders, preferences::setFolderHidden)

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader(stringResource(R.string.settings_security))
            SettingRow(
                title = stringResource(R.string.lock_title),
                subtitle = stringResource(R.string.lock_summary),
                onClick = { pinFlow = if (lockConfig.enabled) PinFlow.DISABLE else PinFlow.ENABLE },
                trailing = { Switch(checked = lockConfig.enabled, onCheckedChange = null) },
            )
            val biometricsEnabled = lockConfig.enabled && biometricsAvailable
            SettingRow(
                title = stringResource(R.string.biometric_title),
                subtitle = stringResource(
                    if (biometricsAvailable) R.string.biometric_summary else R.string.biometric_unavailable
                ),
                enabled = biometricsEnabled,
                onClick = {
                    if (lockConfig.biometrics) {
                        lock.setBiometrics(false)
                    } else {
                        // Make sure the sensor works for this user before relying on it.
                        val activity = context.findFragmentActivity() ?: return@SettingRow
                        Biometrics.prompt(
                            activity,
                            context.getString(R.string.biometric_confirm_title),
                            context.getString(R.string.action_cancel),
                        ) { lock.setBiometrics(true) }
                    }
                },
                trailing = {
                    Switch(
                        checked = lockConfig.biometrics && biometricsEnabled,
                        onCheckedChange = null,
                        enabled = biometricsEnabled,
                    )
                },
            )
            SettingRow(
                title = stringResource(R.string.lock_after),
                subtitle = timeoutLabel(lockConfig.timeoutMillis),
                enabled = lockConfig.enabled,
                onClick = { picker = Picker.TIMEOUT },
            )
            SettingRow(
                title = stringResource(R.string.change_pin),
                enabled = lockConfig.enabled,
                onClick = { pinFlow = PinFlow.CHANGE_VERIFY },
            )

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader(stringResource(R.string.settings_mac))
            val paired = bridge
            SettingRow(
                title = paired?.name ?: stringResource(R.string.mac_not_paired),
                subtitle = paired?.let { "${it.host}:${it.port}" },
            )
            if (paired != null) {
                SettingRow(
                    title = stringResource(R.string.unpair),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmUnpair = true },
                )
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader(stringResource(R.string.settings_about))
            SettingRow(title = stringResource(R.string.version), subtitle = appVersion())
        }
    }

    when (picker) {
        Picker.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = theme,
            onSelect = { preferences.setTheme(it); picker = null },
            onDismiss = { picker = null },
        )
        Picker.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries.map { it to languageLabel(it) },
            selected = preferences.language,
            onSelect = { picker = null; preferences.setLanguage(it) },
            onDismiss = { picker = null },
        )
        Picker.TIMEOUT -> ChoiceDialog(
            title = stringResource(R.string.lock_after),
            options = AppLock.TIMEOUTS.map { it to timeoutLabel(it) },
            selected = lockConfig.timeoutMillis,
            onSelect = { lock.setTimeout(it); picker = null },
            onDismiss = { picker = null },
        )
        null -> {}
    }

    when (pinFlow) {
        PinFlow.ENABLE, PinFlow.CHANGE_NEW -> NewPinDialog(
            onDone = { pin -> pinFlow = null; scope.launch { lock.setPin(pin) } },
            onDismiss = { pinFlow = null },
        )
        PinFlow.DISABLE -> VerifyPinDialog(
            lock = lock,
            onVerified = { pinFlow = null; lock.disable() },
            onDismiss = { pinFlow = null },
        )
        PinFlow.CHANGE_VERIFY -> VerifyPinDialog(
            lock = lock,
            onVerified = { pinFlow = PinFlow.CHANGE_NEW },
            onDismiss = { pinFlow = null },
        )
        null -> {}
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.unpair_title)) },
            text = { Text(stringResource(R.string.unpair_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch { repository.unpair(); onUnpaired() }
                }) { Text(stringResource(R.string.unpair_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** One checkbox per Money folder, in the order the home screen shows them. */
@Composable
private fun HiddenFolders(
    accounts: List<Account>?,
    hidden: Set<String>,
    onChange: (folderId: String, hidden: Boolean) -> Unit,
) {
    val folders = accounts.orEmpty().filter { it.folderId != null }.groupBy { it.folderId!! }
    Text(
        stringResource(if (folders.isEmpty()) R.string.hidden_folders_empty else R.string.hidden_folders_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    folders.forEach { (id, folderAccounts) ->
        val isHidden = id in hidden
        SettingRow(
            title = folderAccounts.first().folder.orEmpty(),
            subtitle = pluralStringResource(R.plurals.account_count, folderAccounts.size, folderAccounts.size),
            onClick = { onChange(id, !isHidden) },
            trailing = { Checkbox(checked = isHidden, onCheckedChange = null) },
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = { Text(title, color = titleColor.copy(alpha = alpha)) },
        supportingContent = subtitle?.let {
            { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun themeLabel(mode: ThemeMode) = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
private fun languageLabel(language: AppLanguage) = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.POLISH -> R.string.language_polish
    }
)

@Composable
private fun timeoutLabel(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return if (minutes == 0) stringResource(R.string.lock_after_immediately)
    else pluralStringResource(R.plurals.lock_after_minutes, minutes, minutes)
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}
