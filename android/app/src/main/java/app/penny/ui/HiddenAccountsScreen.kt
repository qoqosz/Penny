package app.penny.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.AppPreferences
import app.penny.data.Repository

/** Folders and accounts to leave out of the app, grouped the way the home screen shows them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAccountsScreen(preferences: AppPreferences, repository: Repository, onBack: () -> Unit) {
    val hidden by preferences.hidden.collectAsStateWithLifecycle()
    val app by repository.state.collectAsStateWithLifecycle()
    val accounts = app.snapshot?.accounts.orEmpty()
    // Accounts outside folders come last.
    val groups = accounts.groupBy { it.folderId }.toList().sortedBy { it.first == null }
    // Bridges before folder IDs send only folder names, which can't be used to hide anything.
    val oldBridge = accounts.any { it.folder != null && it.folderId == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hidden_accounts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "summary") {
                Note(stringResource(if (accounts.isEmpty()) R.string.hidden_accounts_empty else R.string.hidden_accounts_summary))
                if (oldBridge) Note(stringResource(R.string.hidden_folders_old_bridge))
            }
            groups.forEach { (folderId, folderAccounts) ->
                val folderHidden = folderId != null && folderId in hidden.folders
                if (folderId != null) {
                    item(key = "f-$folderId") {
                        CheckRow(
                            icon = Icons.Outlined.Folder,
                            title = folderAccounts.first().folder.orEmpty(),
                            subtitle = pluralStringResource(R.plurals.account_count, folderAccounts.size, folderAccounts.size),
                            checked = folderHidden,
                            onChange = { preferences.setFolderHidden(folderId, it) },
                        )
                    }
                } else if (groups.size > 1) {
                    item(key = "no-folder") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                items(folderAccounts, key = { it.id }) { account ->
                    CheckRow(
                        icon = if (account.hasInvestments) Icons.AutoMirrored.Outlined.ShowChart else Icons.Outlined.AccountBalance,
                        title = account.name,
                        subtitle = when {
                            folderHidden -> stringResource(R.string.hidden_with_folder)
                            account.closed -> stringResource(R.string.account_closed)
                            else -> null
                        },
                        checked = folderHidden || account.id in hidden.accounts,
                        enabled = !folderHidden,
                        indent = folderId != null,
                        onChange = { preferences.setAccountHidden(account.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CheckRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    indent: Boolean = false,
) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        },
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = subtitle?.let {
            { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = if (indent) 24.dp else 0.dp),
    )
}
