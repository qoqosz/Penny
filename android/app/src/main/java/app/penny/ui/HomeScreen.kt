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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.data.Account
import app.penny.data.Repository
import app.penny.data.SyncStatus
import kotlinx.coroutines.launch
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: Repository,
    recent: TransactionsViewModel,
    onOpenAccount: (String) -> Unit,
    onAdd: () -> Unit,
    onUnpaired: () -> Unit,
) {
    val app by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val syncing = app.status == SyncStatus.Syncing

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Penny") },
                    actions = {
                        if (syncing) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { scope.launch { repository.refresh() } }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Odśwież")
                            }
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Więcej") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rozłącz z Makiem") },
                                onClick = { menu = false; confirmUnpair = true },
                            )
                        }
                    },
                )
                StatusBanner(app.status)
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Konta") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Ostatnie") })
                }
            }
        },
        floatingActionButton = {
            if (app.snapshot != null) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Dodaj") },
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
                0 -> AccountsList(app.snapshot?.accounts, app.status, onOpenAccount)
                else -> RecentList(repository, recent)
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Rozłączyć z Makiem?") },
            text = { Text("Telefon zapomni token dostępu i dane z pamięci. Oczekujące transakcje zostaną zachowane.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch { repository.unpair(); onUnpaired() }
                }) { Text("Rozłącz") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun AccountsList(accounts: List<Account>?, status: SyncStatus, onOpen: (String) -> Unit) {
    var showClosed by rememberSaveable { mutableStateOf(false) }
    if (accounts == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (status == SyncStatus.Syncing) CircularProgressIndicator()
            else EmptyState("Brak danych. Pociągnij w dół, aby pobrać konta z Maca.")
        }
        return
    }
    val open = accounts.filter { !it.closed }
    val closed = accounts.filter { it.closed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "totals") { TotalsCard(open) }
        open.groupBy { it.folder ?: "Konta" }.forEach { (folder, list) ->
            val sums = list.groupBy { it.currency }.map { (cur, a) -> Format.money(a.sumOf { it.balanceValue }, cur) }
            item(key = "f-$folder") { SectionHeader(folder, sums.joinToString(" · ")) }
            items(list, key = { it.id }) { AccountRow(it, onOpen) }
        }
        if (closed.isNotEmpty()) {
            item(key = "closed") {
                ListRow(
                    title = "Zamknięte konta (${closed.size})",
                    subtitle = "",
                    modifier = Modifier.clickable { showClosed = !showClosed },
                    leading = {
                        Icon(if (showClosed) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    },
                )
            }
            if (showClosed) items(closed, key = { it.id }) { AccountRow(it, onOpen) }
        }
    }
}

@Composable
private fun TotalsCard(accounts: List<Account>) {
    val totals = accounts.groupBy { it.currency }
        .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, a -> acc + a.balanceValue } }
        .toList().sortedByDescending { it.second.abs() }
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Razem na otwartych kontach", style = MaterialTheme.typography.labelLarge)
            totals.forEach { (currency, total) ->
                Text(Format.money(total, currency), style = MaterialTheme.typography.headlineSmall)
            }
            if (accounts.any { it.hasInvestments }) {
                Text(
                    "Konta inwestycyjne liczone bez wartości papierów",
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
                if (account.hasInvestments) Icons.Outlined.ShowChart else Icons.Outlined.AccountBalance,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = account.name,
        subtitle = if (account.hasInvestments) "Saldo gotówkowe" else "${account.transactionCount} transakcji",
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
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
        transactionItems(list.items, app.pending, app.snapshot, repository, showAccount = true)
        listFooter(list)
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.listFooter(list: TransactionListState) {
    item(key = "footer") {
        when {
            list.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            list.error != null -> EmptyState(list.error)
            list.items.isEmpty() -> EmptyState("Brak transakcji")
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
