package app.penny.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Repository
import app.penny.data.SyncStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    repository: Repository,
    accountId: String,
    transactions: TransactionsViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    val app by repository.state.collectAsStateWithLifecycle()
    val list by transactions.state.collectAsStateWithLifecycle()
    val account = app.snapshot?.accounts?.firstOrNull { it.id == accountId }
    val pending = app.pending.filter { it.request.accountId == accountId }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, list.canLoadMore) { transactions.loadMore() }
    var selected by rememberSelectedTransaction()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(account?.name ?: stringResource(R.string.account), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
                StatusBanner(app.status)
            }
        },
        floatingActionButton = {
            if (account != null && !account.closed) {
                FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_transaction)) }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = app.status == SyncStatus.Syncing,
            onRefresh = { scope.launch { repository.refresh(); transactions.reload() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                if (account != null) {
                    item(key = "header") {
                        Card(
                            Modifier.fillMaxWidth().padding(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(if (account.hasInvestments) R.string.balance_cash else R.string.balance),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    Format.money(account.balanceValue, account.currency),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text(
                                    listOfNotNull(
                                        pluralStringResource(R.plurals.transaction_count, list.total, list.total),
                                        stringResource(R.string.account_closed).takeIf { account.closed },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                transactionItems(list.items, pending, app.snapshot, repository, showAccount = false) { selected = it }
                listFooter(list)
            }
        }
    }
    selected?.let { tx ->
        TransactionDetailsSheet(tx, repository, onSelect = { selected = it }, onDismiss = { selected = null })
    }
}
