package app.penny.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Account
import app.penny.data.AccountOrder
import app.penny.data.AppPreferences
import app.penny.data.Repository
import app.penny.data.ordered
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class AccountGroup(val key: String, val accounts: List<Account>)

private fun List<Account>.toGroups() = groupBy { it.groupKey }.map { (key, accounts) -> AccountGroup(key, accounts) }

private fun <T> List<T>.moved(from: Int, to: Int) = toMutableList().apply { add(to, removeAt(from)) }

private const val GROUP = "g:"
private const val ACCOUNT = "a:"

/**
 * The order of folders and accounts on the home screen and in the add form, changed by dragging the handles (or with
 * TalkBack's move up/down actions). While a folder is dragged its accounts are folded away, so it passes whole folders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountOrderScreen(preferences: AppPreferences, repository: Repository, onBack: () -> Unit) {
    val app by repository.state.collectAsStateWithLifecycle()
    val hidden by preferences.hidden.collectAsStateWithLifecycle()
    val order by preferences.accountOrder.collectAsStateWithLifecycle()
    // Closed accounts are listed apart on the home screen and can't be picked, so they keep Money's order.
    val accounts = app.snapshot?.accounts.orEmpty().filter { !it.closed }
    var groups by remember(accounts) { mutableStateOf(accounts.ordered(order).toGroups()) }
    var draggingGroup by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    fun save(new: List<AccountGroup>) {
        groups = new
        preferences.setAccountOrder(AccountOrder(new.map { it.key }, new.flatMap { g -> g.accounts.map { it.id } }))
    }
    fun moveGroup(from: Int, to: Int) = save(groups.moved(from, to))
    fun moveAccount(group: Int, from: Int, to: Int) =
        save(groups.toMutableList().also { it[group] = it[group].copy(accounts = it[group].accounts.moved(from, to)) })

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as String
        val toKey = to.key as String
        if (fromKey.startsWith(GROUP) && toKey.startsWith(GROUP)) {
            moveGroup(groups.indexOfFirst { GROUP + it.key == fromKey }, groups.indexOfFirst { GROUP + it.key == toKey })
        } else if (fromKey.startsWith(ACCOUNT) && toKey.startsWith(ACCOUNT)) {
            // Only the accounts of the same folder are drop targets (see ReorderableItem's enabled).
            val group = groups.indexOfFirst { g -> g.accounts.any { ACCOUNT + it.id == fromKey } }
            val list = groups[group].accounts
            moveAccount(group, list.indexOfFirst { ACCOUNT + it.id == fromKey }, list.indexOfFirst { ACCOUNT + it.id == toKey })
        } else {
            return@rememberReorderableLazyListState
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // The folder of the account being dragged; the others' accounts can't be drop targets.
    var draggingFrom by remember { mutableStateOf<String?>(null) }
    val showGroups = groups.size > 1
    val defaultFolder = stringResource(R.string.accounts_default_folder)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_order)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            preferences.setAccountOrder(AccountOrder())
                            groups = accounts.toGroups()
                        },
                        enabled = order.isCustom,
                    ) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.account_order_reset))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "summary") {
                Text(
                    stringResource(if (accounts.isEmpty()) R.string.hidden_accounts_empty else R.string.account_order_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            groups.forEachIndexed { groupIndex, group ->
                if (showGroups) {
                    item(key = GROUP + group.key) {
                        ReorderableItem(reorderState, key = GROUP + group.key, enabled = draggingGroup || draggingFrom == null) { dragging ->
                            OrderRow(
                                icon = Icons.Outlined.Folder,
                                title = group.accounts.first().folder ?: defaultFolder,
                                subtitle = pluralStringResource(R.plurals.account_count, group.accounts.size, group.accounts.size),
                                header = true,
                                dragging = dragging,
                                index = groupIndex,
                                count = groups.size,
                                onMove = { moveGroup(groupIndex, it) },
                                onDragStarted = {
                                    draggingGroup = true
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    draggingGroup = false
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            )
                        }
                    }
                }
                if (!draggingGroup) {
                    items(group.accounts, key = { ACCOUNT + it.id }) { account ->
                        val accountIndex = group.accounts.indexOf(account)
                        ReorderableItem(
                            reorderState,
                            key = ACCOUNT + account.id,
                            enabled = draggingFrom == null || draggingFrom == group.key,
                        ) { dragging ->
                            OrderRow(
                                icon = if (account.hasInvestments) Icons.AutoMirrored.Outlined.ShowChart else Icons.Outlined.AccountBalance,
                                title = account.name,
                                subtitle = if (account.isHidden(hidden)) stringResource(R.string.account_hidden) else null,
                                header = false,
                                indent = showGroups,
                                dragging = dragging,
                                index = accountIndex,
                                count = group.accounts.size,
                                onMove = { moveAccount(groupIndex, accountIndex, it) },
                                onDragStarted = {
                                    draggingFrom = group.key
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    draggingFrom = null
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.OrderRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    header: Boolean,
    dragging: Boolean,
    /** The row's place among the rows it can swap with, for the accessibility actions. */
    index: Int,
    count: Int,
    onMove: (to: Int) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    indent: Boolean = false,
) {
    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "elevation")
    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)
    Surface(
        shadowElevation = elevation,
        tonalElevation = elevation,
        modifier = Modifier.semantics {
            customActions = listOfNotNull(
                CustomAccessibilityAction(moveUp) { onMove(index - 1); true }.takeIf { index > 0 },
                CustomAccessibilityAction(moveDown) { onMove(index + 1); true }.takeIf { index < count - 1 },
            )
        },
    ) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(title, fontWeight = if (header) FontWeight.SemiBold else null) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = {
                IconButton(
                    onClick = {},
                    modifier = Modifier.draggableHandle(onDragStarted = { onDragStarted() }, onDragStopped = onDragStopped),
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = stringResource(R.string.action_reorder))
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(start = if (indent) 24.dp else 0.dp),
        )
    }
}
