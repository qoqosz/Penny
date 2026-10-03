package app.penny.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.penny.data.BridgeException
import app.penny.data.DiscoveredBridge
import app.penny.data.Discovery
import app.penny.data.Kind
import app.penny.data.NewTransactionRequest
import app.penny.data.Repository
import app.penny.data.Transaction
import app.penny.data.UnreachableException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

fun Throwable.userMessage(): String = when (this) {
    is UnreachableException -> "Brak połączenia z Makiem. Sprawdź Wi-Fi i czy most działa."
    is BridgeException -> message ?: "Błąd mostu"
    else -> message ?: toString()
}

data class TransactionListState(
    val items: List<Transaction> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore get() = items.size < total
}

/** Paged transactions for one account (or all accounts when [accountId] is null). */
class TransactionsViewModel(private val repository: Repository, private val accountId: String?) : ViewModel() {
    private val _state = MutableStateFlow(TransactionListState())
    val state: StateFlow<TransactionListState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        reload()
        viewModelScope.launch {
            repository.state.map { it.dataVersion }.distinctUntilChanged().drop(1).collect { reload() }
        }
    }

    fun reload() = load(offset = 0)

    fun loadMore() {
        val s = _state.value
        if (!s.loading && s.canLoadMore) load(offset = s.items.size)
    }

    private fun load(offset: Int) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = repository.transactions(accountId, offset)
                _state.update {
                    it.copy(
                        items = if (offset == 0) page.items else it.items + page.items,
                        total = page.total, loading = false,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }
}

data class SetupState(
    val selected: DiscoveredBridge? = null,
    val busy: Boolean = false,
    val error: String? = null,
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
                _state.update { it.copy(busy = false, error = e.userMessage()) }
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
    val error: String? = null,
) {
    val parsedAmount: BigDecimal? get() = Format.parseAmount(amount)
}

class AddTransactionViewModel(private val repository: Repository, initialAccountId: String?) : ViewModel() {
    private val _form = MutableStateFlow(AddForm(accountId = initialAccountId ?: defaultAccount()))
    val form: StateFlow<AddForm> = _form.asStateFlow()

    private fun defaultAccount(): String? {
        val snapshot = repository.state.value.snapshot ?: return null
        val lastUsed = repository.state.value.pending.lastOrNull()?.request?.accountId
        return lastUsed?.takeIf { id -> snapshot.accounts.any { it.id == id && !it.closed } }
            ?: snapshot.accounts.filter { !it.closed && !it.hasInvestments }.maxByOrNull { it.transactionCount }?.id
    }

    fun update(block: (AddForm) -> AddForm) = _form.update { block(it).copy(error = null) }

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
        val error = when {
            amount == null -> "Podaj kwotę, np. 12,50"
            form.accountId == null -> "Wybierz konto"
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
        )
        viewModelScope.launch {
            repository.add(request)
            onSaved()
        }
    }
}
