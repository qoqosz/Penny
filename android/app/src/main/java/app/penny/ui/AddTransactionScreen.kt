package app.penny.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.data.Category
import app.penny.data.Kind
import app.penny.data.Repository
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(repository: Repository, vm: AddTransactionViewModel, onDone: () -> Unit) {
    val app by repository.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val snapshot = app.snapshot
    var pickCategory by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nowa transakcja") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Anuluj") } },
                actions = { TextButton(onClick = { vm.save(onDone) }, enabled = snapshot != null) { Text("Zapisz") } },
            )
        },
    ) { padding ->
        if (snapshot == null) {
            EmptyState("Najpierw połącz się z Makiem, aby pobrać konta i kategorie.", Modifier.padding(padding))
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
                Text("Zapis jest wyłączony w konfiguracji mostu na Macu.", color = MaterialTheme.colorScheme.error)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Kind.entries.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = form.kind == kind,
                        onClick = { vm.setKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, Kind.entries.size),
                    ) { Text(if (kind == Kind.EXPENSE) "Wydatek" else "Przychód") }
                }
            }

            OutlinedTextField(
                value = form.amount,
                onValueChange = { text -> vm.update { it.copy(amount = text.filter { c -> c.isDigit() || c == ',' || c == '.' }) } },
                label = { Text("Kwota") },
                suffix = { Text(account?.currency ?: snapshot.defaultCurrency ?: "") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                isError = form.amount.isNotEmpty() && form.parsedAmount == null,
                modifier = Modifier.fillMaxWidth(),
            )

            AccountPicker(
                accounts = snapshot.accounts.filter { !it.closed },
                selectedId = form.accountId,
                onSelect = { id -> vm.update { it.copy(accountId = id) } },
            )

            ClickableField(
                label = "Kategoria",
                value = category?.fullName ?: "Bez kategorii",
                onClick = { pickCategory = true },
            )

            PayeeField(
                value = form.payee,
                suggestions = snapshot.payees.map { it.name },
                onChange = { text, fromSuggestion -> vm.setPayee(text, fromSuggestion) },
            )

            ClickableField(
                label = "Data",
                value = Format.dayHeader(form.date),
                onClick = { pickDate = true },
                trailing = { Icon(Icons.Outlined.CalendarToday, contentDescription = null) },
            )

            OutlinedTextField(
                value = form.note,
                onValueChange = { text -> vm.update { it.copy(note = text) } },
                label = { Text("Notatka") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Zapisz") }
            Text(
                "Transakcja trafi do Money na Macu przy najbliższym połączeniu, a stamtąd do iCloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (pickCategory) {
            CategoryPicker(
                categories = snapshot.categories.filter { it.kind == form.kind.api },
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
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Anuluj") } },
            ) { DatePicker(state) }
        }
    }
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
            value = selected?.name ?: "Wybierz konto",
            onValueChange = {},
            readOnly = true,
            label = { Text("Konto") },
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
private fun PayeeField(value: String, suggestions: List<String>, onChange: (String, Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val matches = remember(value, suggestions) {
        if (value.length < 2) emptyList()
        else suggestions.filter { it.contains(value, ignoreCase = true) && !it.equals(value, ignoreCase = true) }.take(6)
    }
    ExposedDropdownMenuBox(expanded = focused && matches.isNotEmpty(), onExpandedChange = {}) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it, false) },
            label = { Text("Odbiorca / płatnik") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .onFocusChanged { focused = it.isFocused },
        )
        ExposedDropdownMenu(expanded = focused && matches.isNotEmpty(), onDismissRequest = { focused = false }) {
            matches.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onChange(name, true) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(categories: List<Category>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, categories) {
        if (query.isBlank()) categories else categories.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
    }
    val frequent = remember(categories) { categories.filter { it.usageCount > 0 }.sortedByDescending { it.usageCount }.take(6) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(
                    title = { Text("Kategoria") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz") }
                    },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Szukaj") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize().imePadding()) {
                    if (query.isBlank()) {
                        item { ListRow("Bez kategorii", "", Modifier.clickable { onPick(null) }) }
                        if (frequent.isNotEmpty()) {
                            item { SectionHeader("Najczęściej używane") }
                            items(frequent, key = { "f-" + it.id }) { CategoryRow(it, onPick) }
                            item { HorizontalDivider() }
                            item { SectionHeader("Wszystkie") }
                        }
                    }
                    items(filtered, key = { it.id }) { CategoryRow(it, onPick) }
                    if (filtered.isEmpty()) item { EmptyState("Brak pasujących kategorii") }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, onPick: (String?) -> Unit) {
    val parent = category.fullName.substringBeforeLast(" › ", "")
    ListRow(
        title = category.name,
        subtitle = parent,
        modifier = Modifier.clickable { onPick(category.id) },
    )
}
