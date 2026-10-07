package app.penny.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Account
import app.penny.data.HoldingValue
import app.penny.data.PricesState
import app.penny.data.Repository
import app.penny.data.SyncStatus
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant

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
    val valuation by repository.valuation.collectAsStateWithLifecycle()
    val prices by repository.prices.state.collectAsStateWithLifecycle()
    val list by transactions.state.collectAsStateWithLifecycle()
    val account = app.snapshot?.accounts?.firstOrNull { it.id == accountId }
    val pending = app.pending.filter { it.request.accountId == accountId }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var selected by rememberSelectedTransaction()
    val holdings = remember(account, valuation) { account?.let { valuation?.holdings(it) }.orEmpty() }
    val securities = holdings.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { s, h -> s + (h.value ?: BigDecimal.ZERO) }
    // Investment accounts are a portfolio and transactions, on tabs like the home screen's.
    val investment = account != null && account.hasInvestments && valuation?.knowsHoldings == true
    var tab by rememberSaveable { mutableStateOf(0) }

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
                if (investment) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.portfolio)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.transactions)) })
                    }
                }
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
            if (investment && tab == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "header") { BalanceCard(account!!, securities, list.total, cashOnly = false) }
                    if (holdings.isEmpty()) {
                        item(key = "empty") { EmptyState(stringResource(R.string.portfolio_empty)) }
                    } else {
                        items(holdings, key = { "h-" + it.security.id }) { HoldingRow(it, account!!.currency) }
                        item(key = "portfolio-note") { PortfolioNote(holdings, prices) }
                    }
                }
            } else {
                LoadMoreEffect(listState, list.canLoadMore) { transactions.loadMore() }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (account != null && !investment) {
                        item(key = "header") {
                            BalanceCard(account, securities, list.total,
                                cashOnly = account.hasInvestments && valuation?.knowsHoldings != true)
                        }
                    }
                    transactionItems(list.items, pending, app.snapshot, repository, showAccount = false) { selected = it }
                    listFooter(list)
                }
            }
        }
    }
    selected?.let { tx ->
        TransactionDetailsSheet(tx, repository, onSelect = { selected = it }, onDismiss = { selected = null })
    }
}

/** The account's value; for investment accounts, split into securities and cash. */
@Composable
private fun BalanceCard(
    account: Account,
    /** The securities at the latest prices, or null when it holds none. */
    securities: BigDecimal?,
    transactionCount: Int,
    /** The bridge doesn't send holdings, so only the cash is known. */
    cashOnly: Boolean,
) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(if (cashOnly) R.string.balance_cash else R.string.balance),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                Format.money(account.balanceValue + (securities ?: BigDecimal.ZERO), account.currency),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (securities != null) {
                Text(
                    stringResource(R.string.account_securities_cash,
                        Format.money(securities, account.currency), Format.money(account.balanceValue, account.currency)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.transaction_count, transactionCount, transactionCount),
                    stringResource(R.string.account_closed).takeIf { account.closed },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A security: shares × price on the left, its value in the account's currency on the right. */
@Composable
private fun HoldingRow(holding: HoldingValue, accountCurrency: String) {
    val security = holding.security
    val quote = holding.quote
    val details = buildList {
        add(if (quote != null) Format.shares(holding.shares) + " × " + Format.rate(quote.price, quote.currency)
            else pluralStringResource(R.plurals.shares, holding.shares.toInt(), Format.shares(holding.shares)))
        security.symbol?.let(::add)
        if (quote != null && !holding.market) add(stringResource(R.string.price_from_money))
    }
    ListRow(
        title = security.name,
        subtitle = details.joinToString(" · "),
        leading = {
            Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailing = {
            Text(
                holding.value?.let { Format.money(it, accountCurrency) } ?: "—",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
    )
}

/** Where the prices come from, and which securities couldn't be valued. */
@Composable
private fun PortfolioNote(holdings: List<HoldingValue>, prices: PricesState) {
    val lines = buildList {
        val updatedAt = prices.updatedAt
        when {
            prices.downloading -> add(stringResource(R.string.prices_downloading))
            prices.failed -> add(stringResource(R.string.prices_failed))
            updatedAt != null && holdings.any { it.market } ->
                add(stringResource(R.string.prices_updated, Format.dateTime(Instant.ofEpochMilli(updatedAt))))
        }
        holdings.filter { it.value == null }.takeIf { it.isNotEmpty() }?.let { missing ->
            add(stringResource(R.string.prices_missing, missing.joinToString(", ") { it.security.symbol ?: it.security.name }))
        }
    }
    if (lines.isEmpty()) return
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
