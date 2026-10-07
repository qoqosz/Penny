package app.penny.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Account
import app.penny.data.ExchangeRates
import app.penny.data.HiddenAccounts
import app.penny.data.Repository
import app.penny.data.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: Repository,
    recent: TransactionsViewModel,
    report: ReportViewModel,
    hiddenAccounts: StateFlow<HiddenAccounts>,
    onOpenAccount: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHiddenAccounts: () -> Unit,
) {
    val app by repository.state.collectAsStateWithLifecycle()
    val hidden by hiddenAccounts.collectAsStateWithLifecycle()
    val rates by repository.rates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    val syncing = app.status == SyncStatus.Syncing

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (syncing) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { scope.launch { repository.refresh() } }) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
                StatusBanner(app.status)
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_accounts)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_recent)) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_report)) })
                }
            }
        },
        floatingActionButton = {
            // The report has no list to add to, and the button would cover its charts.
            if (app.snapshot != null && tab != 2) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add)) },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = { scope.launch { repository.refresh() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (tab) {
                0 -> AccountsList(
                    app.snapshot?.accounts, app.snapshot?.mainCurrency, rates.rates, hidden, app.status,
                    onOpenAccount, onOpenHiddenAccounts,
                )
                1 -> RecentList(repository, recent)
                else -> ReportList(repository, report)
            }
        }
    }
}

@Composable
private fun AccountsList(
    allAccounts: List<Account>?,
    mainCurrency: String?,
    rates: ExchangeRates,
    hidden: HiddenAccounts,
    status: SyncStatus,
    onOpen: (String) -> Unit,
    onOpenHiddenAccounts: () -> Unit,
) {
    var showClosed by rememberSaveable { mutableStateOf(false) }
    val accounts = allAccounts?.filterNot { it.isHidden(hidden) }
    if (accounts == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (status == SyncStatus.Syncing) CircularProgressIndicator()
            else EmptyState(stringResource(R.string.accounts_empty))
        }
        return
    }
    val open = accounts.filter { !it.closed }
    val closed = accounts.filter { it.closed }
    val defaultFolder = stringResource(R.string.accounts_default_folder)
    val closedTitle = stringResource(R.string.accounts_closed, closed.size)
    val hiddenCount = allAccounts.orEmpty().size - accounts.size
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "totals") { TotalsCard(open, mainCurrency, rates) }
        open.groupBy { it.folder ?: defaultFolder }.forEach { (folder, list) ->
            val sums = list.groupBy { it.currency }.map { (cur, a) -> Format.money(a.sumOf { it.balanceValue }, cur) }
            item(key = "f-$folder") { SectionHeader(folder, sums.joinToString(" · ")) }
            items(list, key = { it.id }) { AccountRow(it, onOpen) }
        }
        if (closed.isNotEmpty()) {
            item(key = "closed") {
                ListRow(
                    title = closedTitle,
                    subtitle = "",
                    modifier = Modifier.clickable { showClosed = !showClosed },
                    leading = {
                        Icon(if (showClosed) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    },
                )
            }
            if (showClosed) items(closed, key = { it.id }) { AccountRow(it, onOpen) }
        }
        if (hiddenCount > 0) {
            item(key = "hidden") {
                TextButton(onClick = onOpenHiddenAccounts, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(pluralStringResource(R.plurals.accounts_hidden, hiddenCount, hiddenCount))
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(accounts: List<Account>, mainCurrency: String?, rates: ExchangeRates) {
    val totals = accounts.groupBy { it.currency }
        .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, a -> acc + a.balanceValue } }
        .toList().sortedByDescending { it.second.abs() }
    // With several currencies and rates for all of them, one total in the main currency at today's rates.
    val today = LocalDate.now()
    val converted = mainCurrency?.takeIf { totals.size > 1 }?.let { main ->
        totals.map { (currency, total) ->
            total.multiply(rates.rate(currency, main, today) ?: return@let null, MathContext.DECIMAL64)
        }.fold(BigDecimal.ZERO, BigDecimal::add)
    }
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.totals_title), style = MaterialTheme.typography.labelLarge)
            if (converted != null && mainCurrency != null) {
                Text(Format.money(converted, mainCurrency), style = MaterialTheme.typography.headlineSmall)
                Text(
                    totals.joinToString(" · ") { (currency, total) -> Format.money(total, currency) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.totals_converted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            } else {
                totals.forEach { (currency, total) ->
                    Text(Format.money(total, currency), style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (accounts.any { it.hasInvestments }) {
                Text(
                    stringResource(R.string.totals_investments_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun AccountRow(account: Account, onOpen: (String) -> Unit) {
    ListRow(
        modifier = Modifier.clickable { onOpen(account.id) },
        leading = {
            Icon(
                if (account.hasInvestments) Icons.AutoMirrored.Outlined.ShowChart else Icons.Outlined.AccountBalance,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = account.name,
        subtitle = if (account.hasInvestments) stringResource(R.string.balance_cash)
        else pluralStringResource(R.plurals.transaction_count, account.transactionCount, account.transactionCount),
        trailing = {
            Text(
                Format.money(account.balanceValue, account.currency),
                style = MaterialTheme.typography.bodyLarge,
                color = if (account.balanceValue.signum() < 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@Composable
private fun RecentList(repository: Repository, vm: TransactionsViewModel) {
    val app by repository.state.collectAsStateWithLifecycle()
    val list by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, list.canLoadMore) { vm.loadMore() }
    var selected by rememberSelectedTransaction()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
        transactionItems(list.items, app.pending, app.snapshot, repository, showAccount = true) { selected = it }
        listFooter(list)
    }
    selected?.let { tx ->
        TransactionDetailsSheet(tx, repository, onSelect = { selected = it }, onDismiss = { selected = null })
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.listFooter(list: TransactionListState) {
    item(key = "footer") {
        when {
            list.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            list.error != null -> EmptyState(list.error.userMessage())
            list.items.isEmpty() -> EmptyState(stringResource(R.string.no_transactions))
        }
    }
}

/** Calls [onLoadMore] when the user scrolls near the end of the list. */
@Composable
fun LoadMoreEffect(state: LazyListState, canLoadMore: Boolean, onLoadMore: () -> Unit) {
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 10
        }
    }
    LaunchedEffect(nearEnd, canLoadMore) { if (nearEnd && canLoadMore) onLoadMore() }
}
