package app.penny.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: SetupViewModel, onPaired: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Połącz z Makiem") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Penny pokazuje dane z Money przez most uruchomiony na Twoim Macu. " +
                    "Telefon i Mac muszą być w tej samej sieci Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val selected = state.selected
            if (selected == null) {
                Text("Znalezione Maki", style = MaterialTheme.typography.titleMedium)
                if (discovered.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Szukam w sieci…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                discovered.forEach { bridge ->
                    Card(Modifier.fillMaxWidth().clickable { vm.select(bridge) }) {
                        ListRow(
                            title = bridge.name,
                            subtitle = "${bridge.host}:${bridge.port}",
                            leading = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                        )
                    }
                }
                ManualAddress(busy = state.busy, onConnect = vm::connectManually)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    ListRow(
                        title = selected.name,
                        subtitle = "${selected.host}:${selected.port}",
                        leading = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                        trailing = { TextButton(onClick = { vm.select(null) }) { Text("Zmień") } },
                    )
                }
                PairingCode(busy = state.busy) { code -> vm.pair(code, deviceName(), onPaired) }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ManualAddress(busy: Boolean, onConnect: (String, Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8765") }
    Spacer(Modifier.height(8.dp))
    Text("Albo wpisz adres ręcznie", style = MaterialTheme.typography.titleMedium)
    Text(
        "Adres pokazuje polecenie „penny-bridge pair” na Macu.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = { host = it.trim() }, label = { Text("Adres IP") },
            singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") },
            singleLine = true, modifier = Modifier.width(96.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    OutlinedButton(
        onClick = { onConnect(host, port.toIntOrNull() ?: 8765) },
        enabled = !busy && host.isNotBlank(),
    ) { Text("Połącz") }
}

@Composable
private fun PairingCode(busy: Boolean, onPair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Text("Kod parowania", style = MaterialTheme.typography.titleMedium)
    Text(
        "Na Macu uruchom w Terminalu:  penny-bridge pair\ni wpisz pokazany 6-cyfrowy kod.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text("Kod") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onPair(code) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onPair(code) }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sparuj")
    }
}

private fun deviceName(): String =
    "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
