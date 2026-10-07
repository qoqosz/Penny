package app.penny.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.Category
import app.penny.data.HiddenAccounts
import app.penny.data.IconStore
import app.penny.data.Payee
import app.penny.data.Kind
import app.penny.data.Repository
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    repository: Repository,
    hiddenAccounts: StateFlow<HiddenAccounts>,
    vm: AddTransactionViewModel,
    onDone: () -> Unit,
) {
    val app by repository.state.collectAsStateWithLifecycle()
    val hidden by hiddenAccounts.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val snapshot = app.snapshot
    var pickCategory by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) } },
                actions = { TextButton(onClick = { vm.save(onDone) }, enabled = snapshot != null) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        if (snapshot == null) {
            EmptyState(stringResource(R.string.add_connect_first), Modifier.padding(padding))
            return@Scaffold
        }
        val account = snapshot.accounts.firstOrNull { it.id == form.accountId }
        val category = snapshot.categories.firstOrNull { it.id == form.categoryId }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!snapshot.writesEnabled) {
                Text(stringResource(R.string.add_writes_disabled), color = MaterialTheme.colorScheme.error)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Kind.entries.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = form.kind == kind,
                        onClick = { vm.setKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, Kind.entries.size),
                    ) { Text(stringResource(if (kind == Kind.EXPENSE) R.string.kind_expense else R.string.kind_income)) }
                }
            }

            val accountCurrency = account?.currency ?: snapshot.defaultCurrency ?: ""
            // Bridges that send Money's currencies can write transactions in them.
            val currencies = snapshot.currencies?.let { (listOf(accountCurrency) + it).filter(String::isNotEmpty).distinct() }.orEmpty()
            val currency = form.currency ?: accountCurrency
            OutlinedTextField(
                value = form.amount,
                onValueChange = { text -> vm.update { it.copy(amount = text.filter { c -> c.isDigit() || c == ',' || c == '.' }) } },
                label = { Text(stringResource(R.string.field_amount)) },
                suffix = if (currencies.size > 1) null else { { Text(currency) } },
                trailingIcon = if (currencies.size > 1) { { CurrencyMenu(currency, currencies, vm::setCurrency) } } else null,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                isError = form.amount.isNotEmpty() && form.parsedAmount == null,
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.currency != null) RateField(form, accountCurrency, vm)

            AccountPicker(
                // A hidden account stays listed when it's preselected (opened from its own screen).
                accounts = snapshot.accounts.filter { !it.closed && (!it.isHidden(hidden) || it.id == form.accountId) },
                selectedId = form.accountId,
                onSelect = { id -> vm.update { it.copy(accountId = id) } },
            )

            ClickableField(
                label = stringResource(R.string.field_category),
                value = category?.fullName ?: stringResource(R.string.no_category),
                onClick = { pickCategory = true },
            )

            PayeeField(
                value = form.payee,
                suggestions = snapshot.payees,
                icons = repository.icons,
                onChange = { text, fromSuggestion -> vm.setPayee(text, fromSuggestion) },
            )

            ClickableField(
                label = stringResource(R.string.field_date),
                value = Format.dayHeader(form.date),
                onClick = { pickDate = true },
                trailing = { Icon(Icons.Outlined.CalendarToday, contentDescription = null) },
            )

            OutlinedTextField(
                value = form.note,
                onValueChange = { text -> vm.update { it.copy(note = text) } },
                label = { Text(stringResource(R.string.field_note)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            form.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }

            Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
            Text(
                stringResource(R.string.add_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (pickCategory) {
            CategoryPicker(
                categories = snapshot.categories.filter { it.kind == form.kind.api },
                icons = repository.icons,
                onPick = { id -> vm.update { it.copy(categoryId = id) }; pickCategory = false },
                onDismiss = { pickCategory = false },
            )
        }
        if (pickDate) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { pickDate = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            vm.update { it.copy(date = date) }
                        }
                        pickDate = false
                    }) { Text(stringResource(R.string.action_ok)) }
                },
                dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.action_cancel)) } },
            ) { DatePicker(state) }
        }
    }
}

/** The amount's currency: the account's, or another one of Money's. */
@Composable
private fun CurrencyMenu(selected: String, currencies: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected, style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.field_currency))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            currencies.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    trailingIcon = {
                        Text(Format.currencyName(code), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = { onSelect(code); expanded = false },
                )
            }
        }
    }
}

/** How much one unit of the chosen currency is in the account's, and what the amount comes to. */
@Composable
private fun RateField(form: AddForm, accountCurrency: String, vm: AddTransactionViewModel) {
    val suggested = vm.suggestedRate(form)
    val rate = form.parsedRate
    val amount = form.parsedAmount
    val fromEcb = !form.rateEdited && suggested != null
    OutlinedTextField(
        value = form.rate,
        onValueChange = vm::setRate,
        label = { Text(stringResource(R.string.field_rate)) },
        prefix = { Text(stringResource(R.string.rate_prefix, form.currency.orEmpty())) },
        suffix = { Text(accountCurrency) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        isError = form.rate.isNotEmpty() && rate == null,
        trailingIcon = if (form.rateEdited && suggested != null) {
            {
                IconButton(onClick = { vm.update { it.copy(rateEdited = false) } }) {
                    Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.rate_use_ecb))
                }
            }
        } else null,
        supportingText = {
            val converted = if (amount != null && rate != null) Format.money(amount.multiply(rate), accountCurrency) else null
            Text(
                when {
                    converted != null && fromEcb -> stringResource(R.string.rate_converted_ecb, converted)
                    converted != null -> stringResource(R.string.rate_converted, converted)
                    fromEcb -> stringResource(R.string.rate_ecb)
                    form.rate.isEmpty() -> stringResource(R.string.rate_missing)
                    else -> ""
                }
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ClickableField(label: String, value: String, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Box {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = trailing, modifier = Modifier.fillMaxWidth(),
        )
        // Covers the field so the tap opens the picker instead of focusing the text field.
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPicker(accounts: List<app.penny.data.Account>, selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.choose_account),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_account)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    trailingIcon = { Text(Format.money(account.balanceValue, account.currency), style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(account.id); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayeeField(value: String, suggestions: List<Payee>, icons: IconStore, onChange: (String, Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val matches = remember(value, suggestions) {
        if (value.length < 2) emptyList()
        else suggestions.filter { it.name.contains(value, ignoreCase = true) && !it.name.equals(value, ignoreCase = true) }.take(6)
    }
    ExposedDropdownMenuBox(expanded = focused && matches.isNotEmpty(), onExpandedChange = {}) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it, false) },
            label = { Text(stringResource(R.string.field_payee)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .onFocusChanged { focused = it.isFocused },
        )
        ExposedDropdownMenu(expanded = focused && matches.isNotEmpty(), onDismissRequest = { focused = false }) {
            // Names stay aligned when only some payees have a logo.
            val withLogos = matches.any { it.iconId != null }
            matches.forEach { payee ->
                val logo = rememberMoneyIcon(icons, payee.iconId)
                DropdownMenuItem(
                    text = { Text(payee.name) },
                    onClick = { onChange(payee.name, true) },
                    leadingIcon = if (!withLogos) null else {
                        { if (logo != null) PayeeLogo(logo, size = 24.dp) else Spacer(Modifier.size(24.dp)) }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(categories: List<Category>, icons: IconStore, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, categories) {
        if (query.isBlank()) categories else categories.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
    }
    val frequent = remember(categories) { categories.filter { it.usageCount > 0 }.sortedByDescending { it.usageCount }.take(6) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.field_category)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                    },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize().imePadding()) {
                    if (query.isBlank()) {
                        item { ListRow(stringResource(R.string.no_category), "", Modifier.clickable { onPick(null) }) }
                        if (frequent.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.categories_frequent)) }
                            items(frequent, key = { "f-" + it.id }) { CategoryRow(it, icons, onPick) }
                            item { HorizontalDivider() }
                            item { SectionHeader(stringResource(R.string.categories_all)) }
                        }
                    }
                    items(filtered, key = { it.id }) { CategoryRow(it, icons, onPick) }
                    if (filtered.isEmpty()) item { EmptyState(stringResource(R.string.categories_no_match)) }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, icons: IconStore, onPick: (String?) -> Unit) {
    val parent = category.fullName.substringBeforeLast(" › ", "")
    val glyph = rememberMoneyIcon(icons, category.iconId)
    ListRow(
        leading = { glyph?.let { CategoryGlyph(it) } },
        title = category.name,
        subtitle = parent,
        modifier = Modifier.clickable { onPick(category.id) },
    )
}
