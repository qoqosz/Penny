package app.penny.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.data.CloudStatus
import app.penny.data.PendingChange
import app.penny.data.PendingTransaction
import app.penny.data.Repository
import app.penny.data.Snapshot
import app.penny.data.SyncMode
import app.penny.data.SyncStatus
import app.penny.data.Transaction
import app.penny.data.TransactionSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** The current time, updated every minute, for texts like "3 minutes ago". */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000)
        value = System.currentTimeMillis()
    }
}

/** How long ago [millis] (epoch ms) was, e.g. "3 hours ago", kept current; null for null. */
@Composable
fun rememberAgo(millis: Long?): String? {
    val now by rememberNow()
    millis ?: return null
    val minutes = ((now - millis) / 60_000).coerceAtLeast(0).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.ago_now)
        minutes < 60 -> pluralStringResource(R.plurals.ago_minutes, minutes, minutes)
        minutes < 48 * 60 -> pluralStringResource(R.plurals.ago_hours, minutes / 60, minutes / 60)
        else -> pluralStringResource(R.plurals.ago_days, minutes / (24 * 60), minutes / (24 * 60))
    }
}

/** Why the shown data may be out of date, and how old it is ([updatedAt]: when it was last downloaded). */
@Composable
fun StatusBanner(status: SyncStatus, updatedAt: Long? = null) {
    val age = rememberAgo(updatedAt)
    val dataAge = age?.let { "\n" + stringResource(R.string.status_data_age, it) }.orEmpty()
    val (icon, text, color) = when (status) {
        is SyncStatus.Offline -> Triple(
            Icons.Outlined.CloudOff,
            when {
                status.hasCachedData && age != null -> stringResource(R.string.status_offline_age, age)
                status.hasCachedData -> stringResource(R.string.status_offline_cached)
                else -> stringResource(R.string.status_offline)
            },
            MaterialTheme.colorScheme.secondaryContainer,
        )
        is SyncStatus.Error -> Triple(Icons.Outlined.ErrorOutline, status.message + dataAge, MaterialTheme.colorScheme.errorContainer)
        is SyncStatus.Failed -> Triple(Icons.Outlined.ErrorOutline, status.error.userMessage() + dataAge, MaterialTheme.colorScheme.errorContainer)
        SyncStatus.Untrusted -> Triple(Icons.Outlined.ErrorOutline, stringResource(R.string.error_untrusted) + dataAge, MaterialTheme.colorScheme.errorContainer)
        else -> return
    }
    Banner(icon, text, color)
}

/** Changes on the Mac that Money hasn't uploaded to iCloud for longer than it should take. */
@Composable
fun CloudBanner(cloud: CloudStatus?) {
    val now by rememberNow()
    val overdue = remember(cloud, now) { cloud?.overdue(Instant.ofEpochMilli(now)).orEmpty() }
    if (cloud == null || overdue.isEmpty()) return
    val text = pluralStringResource(R.plurals.cloud_overdue, overdue.size, overdue.size) +
        if (cloud.moneyRunning) "" else " " + stringResource(R.string.cloud_money_stopped)
    Banner(Icons.Outlined.CloudUpload, text, MaterialTheme.colorScheme.tertiaryContainer)
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionHeader(text: String, trailing: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AmountText(amount: String, currency: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val positive = (amount.toBigDecimalOrNull()?.signum() ?: 0) > 0
    Text(
        Format.money(amount, currency, signed = true),
        style = style,
        fontWeight = FontWeight.Medium,
        color = if (positive) AmountColors.income else AmountColors.expense,
        maxLines = 1,
    )
}

@Composable
fun TransactionRow(
    tx: Transaction,
    snapshot: Snapshot?,
    repository: Repository,
    showAccount: Boolean,
    /** Shows a mark while Money on the Mac hasn't uploaded the transaction to iCloud. */
    cloud: CloudStatus? = null,
    onOpen: (Transaction) -> Unit,
) {
    val info = transactionInfo(tx, snapshot)
    val accountName = snapshot?.accounts?.firstOrNull { it.id == tx.accountId }?.name
    val details = listOfNotNull(
        info.category.takeIf { info.title != it },
        accountName.takeIf { showAccount },
        tx.note.takeIf { !it.isNullOrBlank() && info.title != it },
    ).joinToString(" · ")
    // Like Money: colored dots for the tags, then their names.
    val tagColors = tx.tags.map { TagColors.of(it.color) }
    val subtitle = buildAnnotatedString {
        append(details)
        if (tx.tags.isNotEmpty()) {
            if (details.isNotEmpty()) append(" · ")
            tagColors.filterNotNull().forEach { withStyle(SpanStyle(color = it)) { append("●") } }
            if (tagColors.any { it != null }) append(" ")
            append(tx.tags.joinToString(" ") { "#" + it.name })
        }
    }
    ListRow(
        modifier = Modifier.clickable { onOpen(tx) },
        leadingSize = 40.dp,
        leading = { TransactionAvatar(tx, info, snapshot, repository, size = 40) },
        title = info.title,
        subtitle = subtitle,
        trailing = {
            if (cloud?.waiting(tx.id) != null) {
                Icon(
                    Icons.Outlined.CloudUpload, contentDescription = stringResource(R.string.cloud_waiting),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            AmountText(tx.amount, tx.currency)
        },
    )
}

/**
 * A queued transaction, change or deletion. [accountId] is the account whose list shows the row, which decides a
 * transfer's sign (null: the one it leaves).
 */
@Composable
fun PendingRow(
    item: PendingTransaction,
    snapshot: Snapshot?,
    repository: Repository,
    accountId: String? = null,
    onEdit: (EditTarget) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    val syncMode by repository.syncMode.collectAsStateWithLifecycle()
    val r = item.request
    val account = snapshot?.accounts?.firstOrNull { it.id == (accountId ?: r.accountId) }
    val signed = r.signedAmount(accountId)
    val category = if (r.isTransfer) {
        // As the account's own list shows it: to the other account, or from it.
        val incoming = accountId != null && accountId == r.transferAccountId
        val other = snapshot?.accounts?.firstOrNull { it.id == if (incoming) r.accountId else r.transferAccountId }
        stringResource(if (incoming) R.string.transfer_from else R.string.transfer_to, other?.name ?: "?")
    } else {
        snapshot?.categories?.firstOrNull { it.id == r.categoryId }?.fullName
    }
    val rejected = item.rejectedReason != null
    val change = item.change
    val waiting = stringResource(
        when (change) {
            PendingChange.CREATE -> R.string.pending_waiting_to_send
            PendingChange.UPDATE -> R.string.pending_change_waiting
            PendingChange.DELETE -> R.string.pending_delete_waiting
        }
    )
    ListRow(
        modifier = Modifier.clickable { showDialog = true },
        leading = {
            Icon(
                when {
                    rejected -> Icons.Outlined.ErrorOutline
                    change == PendingChange.UPDATE -> Icons.Outlined.Edit
                    change == PendingChange.DELETE -> Icons.Outlined.Delete
                    else -> Icons.Outlined.Schedule
                },
                contentDescription = stringResource(if (rejected) R.string.pending_rejected else R.string.pending_waiting),
                tint = if (rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = r.payeeName ?: category ?: r.note ?: stringResource(R.string.transaction),
        subtitle = if (rejected) stringResource(R.string.pending_rejected_reason, item.rejectedReason.orEmpty())
        else listOfNotNull(waiting, category, account?.name).joinToString(" · "),
        trailing = {
            // A deletion shows the amount it takes away, struck through.
            AmountText(
                signed, r.currency ?: account?.currency ?: snapshot?.defaultCurrency ?: "PLN",
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (change == PendingChange.DELETE) TextDecoration.LineThrough else null,
                ),
            )
        },
    )
    if (showDialog) {
        val manual = syncMode == SyncMode.MANUAL
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    when {
                        !rejected -> waiting
                        change == PendingChange.UPDATE -> stringResource(R.string.pending_change_rejected_title)
                        change == PendingChange.DELETE -> stringResource(R.string.pending_delete_rejected_title)
                        else -> stringResource(R.string.pending_rejected_title)
                    }
                )
            },
            text = {
                val explainer = when (change) {
                    PendingChange.CREATE -> if (manual) R.string.pending_explainer_manual else R.string.pending_explainer
                    PendingChange.UPDATE -> if (manual) R.string.pending_explainer_change_manual else R.string.pending_explainer_change
                    PendingChange.DELETE -> if (manual) R.string.pending_explainer_delete_manual else R.string.pending_explainer_delete
                }
                Text(item.rejectedReason ?: item.lastError ?: stringResource(explainer))
            },
            confirmButton = {
                Row {
                    if (change != PendingChange.DELETE) {
                        TextButton(onClick = {
                            showDialog = false
                            onEdit(EditTarget.Queued(r.clientId))
                        }) { Text(stringResource(R.string.action_edit)) }
                    }
                    TextButton(onClick = {
                        showDialog = false
                        scope.launch { repository.retryPending(r.clientId) }
                    }) { Text(stringResource(R.string.pending_retry)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { repository.discardPending(r.clientId) }
                }) {
                    Text(
                        stringResource(
                            when (change) {
                                PendingChange.CREATE -> R.string.action_delete
                                PendingChange.UPDATE -> R.string.pending_discard_change
                                PendingChange.DELETE -> R.string.pending_keep
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) = ListRow(title, AnnotatedString(subtitle), modifier, 32.dp, leading, trailing)

@Composable
fun ListRow(
    title: String,
    subtitle: AnnotatedString,
    modifier: Modifier = Modifier,
    leadingSize: Dp = 32.dp,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(leadingSize), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/**
 * Pending transactions first, then confirmed ones grouped by day. Those that a queued change or deletion replaces
 * ([AppState.replaced]) are left out; the pending ones show instead.
 */
fun LazyListScope.transactionItems(
    transactions: List<Transaction>,
    pending: List<PendingTransaction>,
    snapshot: Snapshot?,
    repository: Repository,
    showAccount: Boolean,
    /** The account whose transactions these are, if any. */
    accountId: String? = null,
    replaced: Set<String> = emptySet(),
    cloud: CloudStatus? = null,
    onEdit: (EditTarget) -> Unit = {},
    onOpen: (Transaction) -> Unit,
) {
    if (pending.isNotEmpty()) {
        item(key = "pending-header") { SectionHeader(stringResource(R.string.pending_header, pending.size)) }
        items(pending, key = { "p-" + it.request.clientId }) { PendingRow(it, snapshot, repository, accountId, onEdit) }
    }
    val shown = if (replaced.isEmpty()) transactions else transactions.filter { it.id !in replaced }
    shown.groupBy { Format.localDate(it.instant) }.forEach { (day, dayItems) ->
        item(key = "d-$day") { SectionHeader(Format.dayHeader(day)) }
        items(dayItems, key = { "t-" + it.id }) { TransactionRow(it, snapshot, repository, showAccount, cloud, onOpen) }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The search button of a top bar with a transaction list. */
@Composable
fun SearchAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search)) }
}

/** The top bar while searching: a search field in place of the title. Back (or the system back) closes the search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopAppBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onClose)
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close_search))
            }
        },
        title = { SearchField(query, onQueryChange, Modifier.fillMaxWidth().focusRequester(focus), onSearch = { keyboard?.hide() }) },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange(""); focus.requestFocus() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                }
            }
        },
    )
}

/** A borderless single-line field for a search; [onSearch] runs on the keyboard's search key. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier, onSearch: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** The pending transactions a search finds (all of them without one), newest first like the bridge's list: by date, then the last added. */
@Composable
fun matchingPending(pending: List<PendingTransaction>, query: String, snapshot: Snapshot?): List<PendingTransaction> =
    remember(pending, query, snapshot) {
        val search = TransactionSearch(query)
        val found = if (search.isEmpty) pending else pending.filter { search.matches(it.request, snapshot) }
        found.sortedWith(
            compareByDescending<PendingTransaction> { runCatching { Instant.parse(it.request.date) }.getOrDefault(Instant.EPOCH) }
                .thenByDescending { it.createdAt }
        )
    }

/** How many transactions a search found, above the results. */
fun LazyListScope.searchSummary(list: TransactionListState, pendingCount: Int) {
    val count = list.total + pendingCount
    if (!list.searching || count == 0) return
    item(key = "search-summary") {
        Text(
            pluralStringResource(R.plurals.search_results, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
