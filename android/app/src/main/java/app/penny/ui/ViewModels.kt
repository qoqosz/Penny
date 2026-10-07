package app.penny.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.data.DiscoveredBridge
import app.penny.data.Discovery
import app.penny.data.HiddenAccounts
import app.penny.data.Kind
import app.penny.data.NewTransactionRequest
import app.penny.data.NotPairedException
import app.penny.data.PeriodType
import app.penny.data.Report
import app.penny.data.ReportPeriod
import app.penny.data.Reports
import app.penny.data.Repository
import app.penny.data.Transaction
import app.penny.data.TransactionPage
import app.penny.data.TransactionSearch
import app.penny.data.SearchUnavailableException
import app.penny.data.UnreachableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Bridge errors carry their own message; the rest are mapped to the app's strings. */
@Composable
fun Throwable.userMessage(): String = when (this) {
    is UnreachableException -> stringResource(R.string.error_unreachable)
    is NotPairedException -> stringResource(R.string.error_not_paired)
    is SearchUnavailableException -> stringResource(R.string.search_unavailable)
    else -> message ?: toString()
}

data class TransactionListState(
    val items: List<Transaction> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: Throwable? = null,
    /** The search the list is filtered by (trimmed), or "" for every transaction. */
    val query: String = "",
) {
    val canLoadMore get() = items.size < total
    val searching get() = query.isNotEmpty()
}

/** Paged transactions for one account (or all accounts when [accountId] is null), optionally filtered by a search. */
@OptIn(FlowPreview::class)
class TransactionsViewModel(private val repository: Repository, private val accountId: String?) : ViewModel() {
    private val _state = MutableStateFlow(TransactionListState())
    val state: StateFlow<TransactionListState> = _state.asStateFlow()
    private var job: Job? = null

    /** What is typed in the search field, right away; the list follows it [SEARCH_DELAY_MS] after the last key press. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    /** Every match of the current search; pages are slices of it. */
    private var matches: List<Transaction> = emptyList()
    /** The search the shown items were loaded for. */
    private var itemsQuery = ""

    init {
        reload()
        viewModelScope.launch {
            repository.state.map { it.dataVersion }.distinctUntilChanged().drop(1).collect { reload() }
        }
        viewModelScope.launch {
            // Clearing the search shows everything again at once.
            _query.debounce { if (it.isBlank()) 0L else SEARCH_DELAY_MS }
                .map { it.trim() }.distinctUntilChanged().drop(1)
                .collect { query -> _state.update { it.copy(query = query) }; reload() }
        }
    }

    fun search(text: String) {
        _query.value = text
    }

    fun reload() = load(offset = 0)

    fun loadMore() {
        val s = _state.value
        if (!s.loading && s.canLoadMore) load(offset = s.items.size)
    }

    private fun load(offset: Int) {
        job?.cancel()
        val query = _state.value.query
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = if (query.isEmpty()) repository.transactions(accountId, offset) else searchPage(query, offset)
                _state.update {
                    it.copy(
                        // Pages can come from different sources (Mac, offline copy), so drop repeats; list keys must be unique.
                        items = if (offset == 0) page.items else (it.items + page.items).distinctBy { t -> t.id },
                        total = page.total, loading = false,
                    )
                }
                itemsQuery = query
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep what is shown, unless it was for another search.
                _state.update {
                    if (itemsQuery == query) it.copy(loading = false, error = e)
                    else it.copy(items = emptyList(), total = 0, loading = false, error = e)
                }
            }
        }
    }

    private suspend fun searchPage(query: String, offset: Int): TransactionPage {
        if (offset == 0) matches = repository.searchTransactions(accountId, TransactionSearch(query))
        return TransactionPage("", matches.size, offset, matches.drop(offset).take(Repository.PAGE_SIZE))
    }

    companion object {
        const val SEARCH_DELAY_MS = 500L
    }
}

data class ReportState(
    val period: ReportPeriod,
    /** Null until the snapshot and the offline copy of transactions are there. */
    val report: Report? = null,
    /** Currencies the report can be shown in. */
    val currencies: List<String> = emptyList(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/** Net worth and income/expenses by category for a month or a year, from the offline copy of transactions. */
class ReportViewModel(private val repository: Repository, hiddenAccounts: StateFlow<HiddenAccounts>) : ViewModel() {
    private data class Selection(val period: ReportPeriod, val currency: String? = null)

    private val selection = MutableStateFlow(Selection(ReportPeriod.current(PeriodType.MONTH, LocalDate.now())))

    val state: StateFlow<ReportState> = combine(
        repository.state.map { it.snapshot to it.offlineGeneration }.distinctUntilChanged(),
        hiddenAccounts,
        repository.rates.state.map { it.rates }.distinctUntilChanged(),
        repository.prices.state.map { it.prices }.distinctUntilChanged(),
        selection,
    ) { (snapshot, offlineGeneration), hidden, rates, prices, (period, chosenCurrency) ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val current = ReportPeriod.current(period.type, today)
        val transactions = repository.allTransactions()
        val first = snapshot?.let { Reports.firstDate(it, transactions, hidden, zone) }
        val base = ReportState(
            period = period,
            canGoBack = first != null && period.start.isAfter(first),
            canGoForward = period.start.isBefore(current.start),
        )
        val currencies = snapshot?.let { Reports.currencies(it, hidden, rates) }.orEmpty()
        val currency = chosenCurrency?.takeIf { it in currencies } ?: currencies.firstOrNull()
        if (snapshot == null || currency == null || offlineGeneration == null) return@combine base
        base.copy(
            report = Reports.build(snapshot, transactions, hidden, currency, rates, period, today, zone, prices),
            currencies = currencies,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportState(selection.value.period))

    fun setType(type: PeriodType) = selection.update { it.copy(period = it.period.withType(type, LocalDate.now())) }
    fun previous() = selection.update { it.copy(period = it.period.previous()) }
    fun next() = selection.update { it.copy(period = it.period.next()) }
    fun setCurrency(currency: String) = selection.update { it.copy(currency = currency) }
}

data class SetupState(
    val selected: DiscoveredBridge? = null,
    val busy: Boolean = false,
    val error: Throwable? = null,
)

class SetupViewModel(private val repository: Repository, discovery: Discovery) : ViewModel() {
    val discovered: StateFlow<List<DiscoveredBridge>> =
        discovery.browse().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun select(bridge: DiscoveredBridge?) = _state.update { it.copy(selected = bridge, error = null) }

    fun connectManually(host: String, port: Int) = launchBusy {
        repository.probe(host.trim(), port).also { b -> _state.update { it.copy(selected = b) } }
    }

    fun pair(code: String, deviceName: String, onDone: () -> Unit) = launchBusy {
        val bridge = _state.value.selected ?: return@launchBusy
        repository.pair(bridge, code, deviceName)
        onDone()
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
                _state.update { it.copy(busy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e) }
            }
        }
    }
}

data class AddForm(
    val kind: Kind = Kind.EXPENSE,
    val amount: String = "",
    val accountId: String? = null,
    val categoryId: String? = null,
    val payee: String = "",
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    /** The currency [amount] is in, when it isn't the account's. */
    val currency: String? = null,
    /** One unit of [currency] in the account's currency. Filled in with the ECB rate until the user types one. */
    val rate: String = "",
    val rateEdited: Boolean = false,
    @StringRes val error: Int? = null,
) {
    val parsedAmount: BigDecimal? get() = Format.parseAmount(amount)
    val parsedRate: BigDecimal? get() = Format.parseRate(rate)
}

class AddTransactionViewModel(
    private val repository: Repository,
    private val hiddenAccounts: StateFlow<HiddenAccounts>,
    initialAccountId: String?,
) : ViewModel() {
    private val _form = MutableStateFlow(AddForm(accountId = initialAccountId ?: defaultAccount()))
    val form: StateFlow<AddForm> = _form.asStateFlow()

    init {
        // Rates downloaded while the form is open fill in the rate.
        viewModelScope.launch { repository.rates.state.collect { _form.update(::withSuggestedRate) } }
    }

    fun accountCurrency(form: AddForm): String? =
        repository.state.value.snapshot?.accounts?.firstOrNull { it.id == form.accountId }?.currency

    /** The ECB rate for the form's day, from its currency to the account's. */
    fun suggestedRate(form: AddForm): BigDecimal? {
        val from = form.currency ?: return null
        val to = accountCurrency(form) ?: return null
        return repository.rates.state.value.rates.rate(from, to, form.date)
    }

    /** Drops a currency that is the account's own, and keeps an untouched rate at the ECB's for the day. */
    private fun withSuggestedRate(form: AddForm): AddForm {
        if (form.currency == null || form.currency == accountCurrency(form)) return form.copy(currency = null, rate = "", rateEdited = false)
        if (form.rateEdited) return form
        return form.copy(rate = suggestedRate(form)?.let(Format::rateInput).orEmpty())
    }

    private fun defaultAccount(): String? {
        val snapshot = repository.state.value.snapshot ?: return null
        val candidates = snapshot.accounts.filter { !it.closed && !it.isHidden(hiddenAccounts.value) }
        val lastUsed = repository.state.value.pending.lastOrNull()?.request?.accountId
        return lastUsed?.takeIf { id -> candidates.any { it.id == id } }
            ?: candidates.filter { !it.hasInvestments }.maxByOrNull { it.transactionCount }?.id
    }

    fun update(block: (AddForm) -> AddForm) = _form.update { withSuggestedRate(block(it)).copy(error = null) }

    fun setCurrency(currency: String) = update { it.copy(currency = currency, rateEdited = false) }

    fun setRate(text: String) = update {
        it.copy(rate = text.filter { c -> c.isDigit() || c == ',' || c == '.' }, rateEdited = true)
    }

    fun setKind(kind: Kind) = update {
        val category = repository.state.value.snapshot?.categories?.firstOrNull { c -> c.id == it.categoryId }
        it.copy(kind = kind, categoryId = it.categoryId.takeIf { category?.kind == kind.api })
    }

    /** Picking a known payee fills in its usual category. */
    fun setPayee(name: String, fromSuggestion: Boolean) = update { form ->
        val payee = repository.state.value.snapshot?.payees?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val category = repository.state.value.snapshot?.categories?.firstOrNull { it.id == payee?.categoryId }
        if (fromSuggestion && form.categoryId == null && category != null && category.kind == form.kind.api) {
            form.copy(payee = name, categoryId = category.id)
        } else {
            form.copy(payee = name)
        }
    }

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        val amount = form.parsedAmount
        val rate = form.parsedRate
        val error = when {
            amount == null -> R.string.error_amount
            form.accountId == null -> R.string.choose_account
            form.currency != null && rate == null -> R.string.error_rate
            else -> null
        }
        if (error != null) {
            _form.update { it.copy(error = error) }
            return
        }
        val zone = ZoneId.systemDefault()
        // Today keeps the current time (as Money does); other days get noon so time zones can't shift the date.
        val time = if (form.date == LocalDate.now(zone)) LocalTime.now(zone) else LocalTime.NOON
        val request = NewTransactionRequest(
            clientId = repository.newClientId(),
            accountId = form.accountId!!,
            date = ZonedDateTime.of(form.date, time, zone).toInstant().toString(),
            kind = form.kind.api,
            amount = amount!!.toPlainString(),
            categoryId = form.categoryId,
            payeeName = form.payee.trim().ifEmpty { null },
            note = form.note.trim().ifEmpty { null },
            currency = form.currency,
            exchangeRate = if (form.currency != null) rate?.toPlainString() else null,
        )
        viewModelScope.launch {
            repository.add(request)
            onSaved()
        }
    }
}
