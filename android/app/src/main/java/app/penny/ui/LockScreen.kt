package app.penny.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.lock.AppLock
import app.penny.lock.Biometrics
import app.penny.lock.PinResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Covers the whole app while it's locked. [onReset] wipes pairing and data after a forgotten PIN. */
@Composable
fun LockScreen(lock: AppLock, onReset: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        PinVerifier(
            lock = lock,
            title = stringResource(R.string.pin_enter),
            biometricTitle = stringResource(R.string.biometric_prompt_title),
            unlock = true,
            onVerified = {},
            footer = {
                TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.pin_forgot)) }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.pin_reset_title)) },
            text = { Text(stringResource(R.string.pin_reset_text)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onReset() }) {
                    Text(stringResource(R.string.pin_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Asks for the current PIN (or biometrics) before changing lock settings. */
@Composable
fun VerifyPinDialog(lock: AppLock, onVerified: () -> Unit, onDismiss: () -> Unit) {
    FullScreenDialog(onDismiss) {
        PinVerifier(
            lock = lock,
            title = stringResource(R.string.pin_enter_current),
            biometricTitle = stringResource(R.string.settings_security),
            unlock = false,
            onVerified = onVerified,
        )
    }
}

/** Asks for a new PIN twice. */
@Composable
fun NewPinDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    FullScreenDialog(onDismiss) {
        val pending = first
        if (pending == null) {
            PinPad(
                title = stringResource(R.string.pin_create),
                message = stringResource(if (mismatch) R.string.pin_mismatch else R.string.pin_create_hint),
                isError = mismatch,
                length = null,
                onSubmit = { first = it; mismatch = false },
            )
        } else {
            PinPad(
                title = stringResource(R.string.pin_confirm),
                message = null,
                isError = false,
                length = pending.length,
                onSubmit = { if (it == pending) onDone(it) else { first = null; mismatch = true } },
            )
        }
    }
}

@Composable
private fun PinVerifier(
    lock: AppLock,
    title: String,
    biometricTitle: String,
    /** True on the lock screen (unlocks the app), false when only confirming the user before a settings change. */
    unlock: Boolean,
    onVerified: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val config by lock.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wrong by remember { mutableStateOf(false) }
    var lockedUntil by remember { mutableLongStateOf(lock.lockedOutUntil()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedOut = lockedUntil > now
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }

    val activity = context.findFragmentActivity()
    val usePin = stringResource(R.string.biometric_use_pin)
    val biometricsAvailable = remember { Biometrics.available(context) }
    val biometrics: (() -> Unit)? = if (config.biometrics && activity != null && biometricsAvailable) {
        {
            Biometrics.prompt(activity, biometricTitle, usePin) {
                if (unlock) lock.unlockWithBiometrics()
                onVerified()
            }
        }
    } else null
    // Offer biometrics right away, but only once (not again after a rotation or language change).
    var prompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!prompted && biometrics != null) {
            prompted = true
            biometrics()
        }
    }

    PinPad(
        title = title,
        message = when {
            lockedOut -> stringResource(R.string.pin_locked_out, ((lockedUntil - now + 999) / 1000).toInt())
            wrong -> stringResource(R.string.pin_wrong)
            else -> null
        },
        isError = wrong || lockedOut,
        length = config.pinLength,
        enabled = !lockedOut,
        onSubmit = { pin ->
            scope.launch {
                when (val result = if (unlock) lock.unlockWithPin(pin) else lock.verify(pin)) {
                    PinResult.Correct -> onVerified()
                    PinResult.Wrong -> wrong = true
                    is PinResult.LockedOut -> { wrong = false; lockedUntil = result.untilMillis }
                }
            }
        },
        onBiometrics = biometrics,
        footer = footer,
    )
}

/**
 * Numeric keypad with PIN dots. With a fixed [length] the PIN is submitted as soon as it's complete; otherwise
 * (choosing a new PIN) a Continue button accepts any allowed length.
 */
@Composable
private fun PinPad(
    title: String,
    message: String?,
    isError: Boolean,
    length: Int?,
    onSubmit: (String) -> Unit,
    enabled: Boolean = true,
    onBiometrics: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    // Deliberately not saveable: the PIN must not end up in the saved instance state.
    var pin by remember { mutableStateOf("") }
    val maxLength = length ?: AppLock.PIN_LENGTHS.last
    fun submit() {
        val entered = pin
        pin = ""
        onSubmit(entered)
    }
    fun type(digit: Char) {
        if (!enabled || pin.length >= maxLength) return
        pin += digit
        if (pin.length == length) submit()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        PinDots(filled = pin.length, total = length ?: maxOf(AppLock.PIN_LENGTHS.first, pin.length))
        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { digit -> KeyButton(enabled, { type(digit) }) { KeyLabel(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (onBiometrics != null) {
                    KeyButton(enabled, onBiometrics) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = stringResource(R.string.biometric_use))
                    }
                } else {
                    Spacer(Modifier.size(KeySize))
                }
                KeyButton(enabled, { type('0') }) { KeyLabel('0') }
                KeyButton(enabled && pin.isNotEmpty(), { pin = pin.dropLast(1) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = stringResource(R.string.pin_digit_delete),
                    )
                }
            }
        }
        if (length == null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::submit, enabled = enabled && pin.length in AppLock.PIN_LENGTHS) {
                Text(stringResource(R.string.action_continue))
            }
        }
        Spacer(Modifier.height(16.dp))
        footer()
    }
}

private val KeySize = 72.dp

@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(total) { index ->
            val color = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .size(14.dp)
                    .then(
                        if (index < filled) Modifier.background(color, CircleShape)
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
            )
        }
    }
}

@Composable
private fun KeyButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(KeySize),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun KeyLabel(digit: Char) {
    Text(digit.toString(), style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box {
                content()
                IconButton(onClick = onDismiss, modifier = Modifier.safeDrawingPadding().padding(4.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
