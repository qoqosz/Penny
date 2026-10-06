package app.penny.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.data.PendingTransaction
import app.penny.data.Repository
import app.penny.data.Snapshot
import app.penny.data.SyncStatus
import app.penny.data.Transaction
import kotlinx.coroutines.launch

@Composable
fun StatusBanner(status: SyncStatus) {
    val (icon, text, color) = when (status) {
        is SyncStatus.Offline -> Triple(
            Icons.Outlined.CloudOff,
            stringResource(if (status.hasCachedData) R.string.status_offline_cached else R.string.status_offline),
            MaterialTheme.colorScheme.secondaryContainer,
        )
        is SyncStatus.Error -> Triple(Icons.Outlined.ErrorOutline, status.message, MaterialTheme.colorScheme.errorContainer)
        else -> return
    }
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
        trailing = { AmountText(tx.amount, tx.currency) },
    )
}

@Composable
fun PendingRow(item: PendingTransaction, snapshot: Snapshot?, repository: Repository) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    val r = item.request
    val account = snapshot?.accounts?.firstOrNull { it.id == r.accountId }
    val category = snapshot?.categories?.firstOrNull { it.id == r.categoryId }?.fullName
    val signed = if (r.kind == "expense") "-${r.amount}" else r.amount
    val rejected = item.rejectedReason != null
    ListRow(
        modifier = Modifier.clickable { showDialog = true },
        leading = {
            Icon(
                if (rejected) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                contentDescription = stringResource(if (rejected) R.string.pending_rejected else R.string.pending_waiting),
                tint = if (rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = r.payeeName ?: category ?: r.note ?: stringResource(R.string.transaction),
        subtitle = if (rejected) stringResource(R.string.pending_rejected_reason, item.rejectedReason.orEmpty())
        else listOfNotNull(stringResource(R.string.pending_waiting_to_send), category, account?.name).joinToString(" · "),
        trailing = { AmountText(signed, account?.currency ?: snapshot?.defaultCurrency ?: "PLN") },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(stringResource(if (rejected) R.string.pending_rejected_title else R.string.pending_waiting_to_send))
            },
            text = { Text(item.rejectedReason ?: item.lastError ?: stringResource(R.string.pending_explainer)) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { repository.retryPending(r.clientId) }
                }) { Text(stringResource(R.string.pending_retry)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch { repository.discardPending(r.clientId) }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
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

/** Pending transactions first, then confirmed ones grouped by day. */
fun LazyListScope.transactionItems(
    transactions: List<Transaction>,
    pending: List<PendingTransaction>,
    snapshot: Snapshot?,
    repository: Repository,
    showAccount: Boolean,
    onOpen: (Transaction) -> Unit,
) {
    if (pending.isNotEmpty()) {
        item(key = "pending-header") { SectionHeader(stringResource(R.string.pending_header, pending.size)) }
        items(pending, key = { "p-" + it.request.clientId }) { PendingRow(it, snapshot, repository) }
    }
    transactions.groupBy { Format.localDate(it.instant) }.forEach { (day, dayItems) ->
        item(key = "d-$day") { SectionHeader(Format.dayHeader(day)) }
        items(dayItems, key = { "t-" + it.id }) { TransactionRow(it, snapshot, repository, showAccount, onOpen) }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
