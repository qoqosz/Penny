package app.penny.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Location
import app.penny.data.Repository
import app.penny.data.Snapshot
import app.penny.data.Split
import app.penny.data.Tag
import app.penny.data.Transaction
import kotlinx.serialization.json.Json
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.time.YearMonth

private val transactionSaver = Saver<Transaction?, String>(
    save = { it?.let { tx -> Json.encodeToString(Transaction.serializer(), tx) } },
    restore = { runCatching { Json.decodeFromString(Transaction.serializer(), it) }.getOrNull() },
)

/** The transaction whose details are open, if any. Survives rotation and process death. */
@Composable
fun rememberSelectedTransaction(): MutableState<Transaction?> =
    rememberSaveable(stateSaver = transactionSaver) { mutableStateOf(null) }

/** Everything Money knows about one transaction, in a bottom sheet. [onSelect] opens another one in its place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsSheet(
    tx: Transaction,
    repository: Repository,
    onSelect: (Transaction) -> Unit,
    onDismiss: () -> Unit,
) {
    val app by repository.state.collectAsStateWithLifecycle()
    val snapshot = app.snapshot
    val scroll = rememberScrollState()
    LaunchedEffect(tx.id) { scroll.animateScrollTo(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val history by produceState<List<Transaction>>(emptyList(), tx.payeeId, app.dataVersion) {
        value = tx.payeeId?.let { repository.payeeTransactions(it) }.orEmpty()
    }

    // Fully expanded, the sheet stops below the status bar.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = Modifier.statusBarsPadding(),
    ) {
        Column(
            Modifier
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(tx, snapshot, repository)
            DetailsCard(tx, snapshot, repository)
            if (tx.splits.size > 1) SplitsCard(tx, snapshot, repository)
            val others = history.filter { it.id != tx.id }
            if (others.isNotEmpty()) PayeeHistoryCard(tx, others, snapshot, onSelect, onShowAll = { showAll = true })
        }
    }
    if (showAll && history.isNotEmpty()) {
        PayeeTransactionsSheet(
            tx, history, snapshot, repository,
            onSelect = { showAll = false; onSelect(it) },
            onDismiss = { showAll = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(tx: Transaction, snapshot: Snapshot?, repository: Repository) {
    val info = transactionInfo(tx, snapshot)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TransactionAvatar(tx, info, snapshot, repository, size = 64)
        Spacer(Modifier.height(12.dp))
        Text(
            info.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (info.category != null && info.category != info.title) {
            Text(
                info.category, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        AmountText(tx.amount, tx.currency, style = MaterialTheme.typography.headlineMedium)
        if (tx.tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tx.tags.forEach { TagChip(it) }
            }
        }
    }
}

/** A read-only tag: Money's color as a dot, like Money's own tag tokens. */
@Composable
private fun TagChip(tag: Tag) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = TagColors.of(tag.color)
            if (color != null) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text("#" + tag.name, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DetailsCard(tx: Transaction, snapshot: Snapshot?, repository: Repository) {
    val context = LocalContext.current
    val account = snapshot?.accounts?.firstOrNull { it.id == tx.accountId }
    val split = tx.splits.singleOrNull()
    val transferAccount = tx.splits.firstNotNullOfOrNull { it.transferAccountId }
        ?.let { id -> snapshot?.accounts?.firstOrNull { it.id == id }?.name }
    val noMapApp = stringResource(R.string.details_no_map_app)

    GroupCard {
        DetailItem(Icons.Outlined.Event, stringResource(R.string.details_date), Format.dateTime(tx.instant))
        if (account != null) {
            DetailItem(Icons.Outlined.AccountBalance, stringResource(R.string.account), account.name, account.folder)
        }
        when {
            transferAccount != null -> DetailItem(
                Icons.Outlined.SwapHoriz, stringResource(R.string.transfer),
                stringResource(
                    if (tx.amountValue.signum() < 0) R.string.details_transfer_to else R.string.details_transfer_from,
                    transferAccount,
                ),
            )
            split != null -> {
                val glyph = rememberMoneyIcon(repository.icons, snapshot?.category(split.categoryId)?.iconId)
                DetailItem(
                    Icons.Outlined.Category, stringResource(R.string.field_category),
                    split.category ?: stringResource(R.string.no_category),
                    leading = glyph?.let { { CategoryGlyph(it) } },
                )
            }
        }
        if (tx.originalAmount != null && tx.originalCurrency != null) {
            val rate = tx.exchangeRate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            DetailItem(
                Icons.Outlined.CurrencyExchange, stringResource(R.string.details_original_amount),
                Format.money(tx.originalAmount, tx.originalCurrency, signed = true),
                rate?.let {
                    stringResource(
                        R.string.details_exchange_rate,
                        Format.money(BigDecimal.ONE, tx.originalCurrency), Format.rate(it, tx.currency),
                    )
                },
            )
        }
        tx.note?.takeIf { it.isNotBlank() }?.let {
            DetailItem(Icons.AutoMirrored.Outlined.Notes, stringResource(R.string.field_note), it, selectable = true)
        }
        tx.number?.takeIf { it.isNotBlank() }?.let {
            DetailItem(Icons.Outlined.Numbers, stringResource(R.string.details_number), it, selectable = true)
        }
        tx.location?.let { location ->
            val address = location.addressLines()
            DetailItem(
                Icons.Outlined.Place, stringResource(R.string.details_location),
                address.firstOrNull() ?: Format.coordinates(location),
                address.drop(1).joinToString("\n").ifEmpty { null },
                trailing = {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.details_open_map))
                },
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, location.mapUri(tx.payee)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, noMapApp, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

@Composable
private fun SplitsCard(tx: Transaction, snapshot: Snapshot?, repository: Repository) {
    Column {
        SheetSectionHeader(stringResource(R.string.details_splits))
        GroupCard {
            tx.splits.forEachIndexed { index, split ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                SplitItem(split, tx, snapshot, repository)
            }
        }
    }
}

@Composable
private fun SplitItem(split: Split, tx: Transaction, snapshot: Snapshot?, repository: Repository) {
    val transfer = split.transferAccountId?.let { id -> snapshot?.accounts?.firstOrNull { it.id == id }?.name }
    val glyph = rememberMoneyIcon(repository.icons, snapshot?.category(split.categoryId)?.iconId)
    val amount = split.amount.toBigDecimalOrNull()?.signum() ?: 0
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when {
                    transfer != null -> Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                    glyph != null -> CategoryGlyph(glyph)
                    else -> Icon(Icons.Outlined.Category, contentDescription = null)
                }
            }
        },
        headlineContent = {
            Text(
                when {
                    transfer != null -> stringResource(
                        if (amount < 0) R.string.transfer_to else R.string.transfer_from, transfer,
                    )
                    else -> split.category ?: stringResource(R.string.no_category)
                },
            )
        },
        supportingContent = split.note?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        trailingContent = { AmountText(split.amount, tx.currency, style = MaterialTheme.typography.bodyMedium) },
    )
}

/** Like Money's payee popover: the payee's latest other transactions, and a link to all of them. */
@Composable
private fun PayeeHistoryCard(
    tx: Transaction,
    others: List<Transaction>,
    snapshot: Snapshot?,
    onSelect: (Transaction) -> Unit,
    onShowAll: () -> Unit,
) {
    Column {
        SheetSectionHeader(
            stringResource(R.string.details_payee_history, tx.payee ?: stringResource(R.string.transaction)),
            pluralStringResource(R.plurals.transaction_count, others.size, others.size),
        )
        GroupCard {
            others.take(HISTORY_ROWS).forEachIndexed { index, other ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                PayeeHistoryItem(other, snapshot, Format.shortDate(other.instant), onSelect)
            }
            if (others.size > HISTORY_ROWS) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    modifier = Modifier.clickable(onClick = onShowAll),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.details_show_all), color = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PayeeHistoryItem(
    tx: Transaction,
    snapshot: Snapshot?,
    date: String,
    onSelect: (Transaction) -> Unit,
    current: Boolean = false,
) {
    val info = transactionInfo(tx, snapshot)
    val account = snapshot?.accounts?.firstOrNull { it.id == tx.accountId }?.name
    ListItem(
        modifier = Modifier.clickable { onSelect(tx) },
        colors = ListItemDefaults.colors(
            containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        headlineContent = { Text(date) },
        supportingContent = {
            Text(listOfNotNull(info.category, account).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { AmountText(tx.amount, tx.currency, style = MaterialTheme.typography.bodyMedium) },
    )
}

/** Every transaction with the payee of [tx] (which is highlighted), by month, in a sheet of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayeeTransactionsSheet(
    tx: Transaction,
    all: List<Transaction>,
    snapshot: Snapshot?,
    repository: Repository,
    onSelect: (Transaction) -> Unit,
    onDismiss: () -> Unit,
) {
    val months = remember(all) { all.groupBy { YearMonth.from(Format.localDate(it.instant)) }.toList() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TransactionAvatar(tx, transactionInfo(tx, snapshot), snapshot, repository, size = 48)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            tx.payee ?: stringResource(R.string.transaction), style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(pluralStringResource(R.plurals.transaction_count, all.size, all.size), totals(all))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(months, key = { it.first.toString() }) { (month, items) ->
                Column {
                    SheetSectionHeader(Format.month(month), totals(items))
                    GroupCard {
                        items.forEachIndexed { index, other ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            PayeeHistoryItem(
                                other, snapshot, Format.date(other.instant, "EEEEdMMMM"), onSelect, current = other.id == tx.id,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Signed sums per currency, e.g. "-$123.45 · -€67.00". */
private fun totals(transactions: List<Transaction>): String =
    transactions.groupBy { it.currency }
        .map { (currency, list) -> Format.money(list.fold(BigDecimal.ZERO) { sum, t -> sum + t.amountValue }, currency, signed = true) }
        .joinToString(" · ")

@Composable
private fun SheetSectionHeader(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun DetailItem(
    icon: ImageVector,
    label: String,
    value: String,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selectable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                leading?.invoke() ?: Icon(icon, contentDescription = null)
            }
        },
        overlineContent = { Text(label) },
        headlineContent = { if (selectable) SelectionContainer { Text(value) } else Text(value) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
    )
}

/** What a transaction is called in lists and in its details. */
data class TransactionInfo(val title: String, val category: String?, val transferTo: String?)

@Composable
fun transactionInfo(tx: Transaction, snapshot: Snapshot?): TransactionInfo {
    val transferTo = tx.splits.firstNotNullOfOrNull { it.transferAccountId }
        ?.let { id -> snapshot?.accounts?.firstOrNull { it.id == id }?.name }
    val category = when {
        transferTo != null -> stringResource(
            if (tx.amountValue.signum() < 0) R.string.transfer_to else R.string.transfer_from, transferTo,
        )
        tx.splits.size > 1 -> stringResource(R.string.split_count, tx.splits.size)
        else -> tx.splits.firstOrNull()?.category
    }
    val title = tx.payee?.takeIf { it.isNotBlank() } ?: category ?: tx.note ?: stringResource(R.string.transaction)
    return TransactionInfo(title, category, transferTo)
}

/** The payee's logo, else a transfer arrow, else the category's glyph, else a receipt; [size] in dp. */
@Composable
fun TransactionAvatar(tx: Transaction, info: TransactionInfo, snapshot: Snapshot?, repository: Repository, size: Int) {
    val logo = rememberMoneyIcon(repository.icons, snapshot?.payee(tx.payeeId)?.iconId)
    val glyph = rememberMoneyIcon(repository.icons, snapshot?.category(tx.splits.singleOrNull()?.categoryId)?.iconId)
    if (logo != null) {
        PayeeLogo(logo, size = size.dp)
        return
    }
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = (size / 2).dp
        val tint = MaterialTheme.colorScheme.onSecondaryContainer
        when {
            info.transferTo != null -> Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
            glyph != null -> Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
            else -> Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** Street, then postcode and town (with the state), then country. */
private fun Location.addressLines(): List<String> = listOfNotNull(
    street,
    listOfNotNull(listOfNotNull(zip, city).joinToString(" ").ifEmpty { null }, state).joinToString(", ").ifEmpty { null },
    country,
)

private fun Location.mapUri(label: String?): Uri {
    val query = if (hasCoordinates) {
        "$latitude,$longitude" + (label?.let { "(${it})" } ?: "")
    } else {
        addressLines().joinToString(", ")
    }
    val center = if (hasCoordinates) "$latitude,$longitude" else "0,0"
    return Uri.parse("geo:$center?q=" + Uri.encode(query))
}

private const val HISTORY_ROWS = 5
