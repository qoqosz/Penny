package app.penny.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.ExchangeRates
import app.penny.data.Repository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Money's currencies with their latest ECB rate to the main currency, and the state of the rates download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrenciesScreen(repository: Repository, onBack: () -> Unit) {
    val app by repository.state.collectAsStateWithLifecycle()
    val ratesState by repository.rates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snapshot = app.snapshot
    val main = snapshot?.mainCurrency
    val currencies = snapshot?.allCurrencies.orEmpty().toList()
    val rates = ratesState.rates

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.currencies_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (ratesState.downloading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else if (currencies.size > 1) {
                        IconButton(onClick = { scope.launch { repository.updateRates(force = true) } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.currencies_download))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "summary") {
                Note(stringResource(R.string.currencies_summary))
                when {
                    snapshot == null -> Note(stringResource(R.string.currencies_not_connected))
                    currencies.size < 2 -> Note(stringResource(R.string.currencies_single))
                    snapshot.currencies == null -> Note(stringResource(R.string.currencies_old_bridge))
                }
            }
            if (main == null) return@LazyColumn
            items(currencies, key = { it }) { code ->
                CurrencyRow(
                    code = code,
                    subtitle = when {
                        code == main -> stringResource(R.string.currencies_main)
                        ratesState.updatedAt == null -> stringResource(R.string.currencies_not_downloaded)
                        else -> latestRate(code, main, rates) ?: stringResource(R.string.currencies_no_rates)
                    },
                )
            }
            if (currencies.size > 1) {
                item(key = "status") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    val updatedAt = ratesState.updatedAt
                    when {
                        ratesState.failed -> Note(stringResource(R.string.currencies_failed), error = true)
                        updatedAt != null -> Note(stringResource(R.string.currencies_updated, Format.dateTime(Instant.ofEpochMilli(updatedAt))))
                    }
                }
            }
        }
    }
}

/** E.g. "1 EUR = 4,365 zł · 6 paź 2026", from the last day the ECB published rates for the currency. */
@Composable
private fun latestRate(code: String, main: String, rates: ExchangeRates): String? {
    // The euro has no series of its own; its rate is published with the main currency's.
    val day = rates.latestDay(code) ?: rates.latestDay(main) ?: return null
    val rate = rates.rate(code, main, day) ?: return null
    val date = Format.shortDate(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
    return stringResource(R.string.rate_prefix, code) + Format.rate(rate, main) + " · " + date
}

@Composable
private fun CurrencyRow(code: String, subtitle: String) {
    ListItem(
        leadingContent = {
            Text(code, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(44.dp))
        },
        headlineContent = { Text(Format.currencyName(code)) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
