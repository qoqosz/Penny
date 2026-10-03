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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: SetupViewModel, onPaired: () -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
            val selected = state.selected
            if (selected == null) {
                Text(stringResource(R.string.setup_found), style = MaterialTheme.typography.titleMedium)
                if (discovered.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.setup_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        trailing = { TextButton(onClick = { vm.select(null) }) { Text(stringResource(R.string.setup_change)) } },
                    )
                }
                PairingCode(busy = state.busy) { code -> vm.pair(code, deviceName(), onPaired) }
            }
            state.error?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ManualAddress(busy: Boolean, onConnect: (String, Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8765") }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.setup_manual_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.setup_manual_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = { host = it.trim() }, label = { Text(stringResource(R.string.setup_ip)) },
            singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.setup_port)) },
            singleLine = true, modifier = Modifier.width(96.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    OutlinedButton(
        onClick = { onConnect(host, port.toIntOrNull() ?: 8765) },
        enabled = !busy && host.isNotBlank(),
    ) { Text(stringResource(R.string.setup_connect)) }
}

@Composable
private fun PairingCode(busy: Boolean, onPair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.setup_code_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.setup_code_instructions),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text(stringResource(R.string.setup_code)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onPair(code) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onPair(code) }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.setup_pair))
    }
}

private fun deviceName(): String =
    "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
