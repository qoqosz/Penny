package app.penny.lock

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class LockConfig(
    val enabled: Boolean = false,
    val biometrics: Boolean = false,
    val pinLength: Int = 0,
    /** How long the app may stay in the background before it locks again. */
    val timeoutMillis: Long = 0,
)

sealed interface PinResult {
    data object Correct : PinResult
    data object Wrong : PinResult
    /** Too many wrong PINs; nothing is checked until [untilMillis] (wall clock). */
    data class LockedOut(val untilMillis: Long) : PinResult
}

/**
 * PIN / biometric gate in front of the whole UI. The app starts locked when the lock is on and locks again after
 * spending [LockConfig.timeoutMillis] in the background (observe it with ProcessLifecycleOwner).
 *
 * Only a salted PBKDF2 hash of the PIN is stored. Repeated wrong PINs block entry for a growing time, persisted so
 * that restarting the app doesn't reset it.
 */
class AppLock(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {
    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<LockConfig> = _config.asStateFlow()

    private val _locked = MutableStateFlow(_config.value.enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val mutex = Mutex()
    private var backgroundedAt: Long? = null

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = elapsed()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val config = _config.value
        if (config.enabled && elapsed() - since >= config.timeoutMillis) _locked.value = true
    }

    /** Checks [pin] without changing the locked state, e.g. before turning the lock off. */
    suspend fun verify(pin: String): PinResult = mutex.withLock { check(pin) }

    private suspend fun check(pin: String): PinResult {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        if (now() < until) return PinResult.LockedOut(until)
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode)
        val stored = prefs.getString(KEY_HASH, null)?.let(::decode)
        if (salt == null || stored == null) return PinResult.Wrong
        if (MessageDigest.isEqual(hash(pin, salt), stored)) {
            prefs.edit { putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, 0) }
            return PinResult.Correct
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val lockout = lockoutAfter(failures)
        prefs.edit {
            putInt(KEY_FAILURES, failures)
            if (lockout > 0) putLong(KEY_LOCKOUT_UNTIL, now() + lockout)
        }
        return if (lockout > 0) PinResult.LockedOut(now() + lockout) else PinResult.Wrong
    }

    suspend fun unlockWithPin(pin: String): PinResult =
        verify(pin).also { if (it == PinResult.Correct) _locked.value = false }

    /** Called after a successful BiometricPrompt. */
    fun unlockWithBiometrics() {
        prefs.edit { putInt(KEY_FAILURES, 0) }
        _locked.value = false
    }

    /** Wall-clock time until which PIN entry is blocked, or 0. */
    fun lockedOutUntil(): Long = prefs.getLong(KEY_LOCKOUT_UNTIL, 0).takeIf { it > now() } ?: 0

    /** Turns the lock on, or changes the PIN when it's already on. */
    suspend fun setPin(pin: String) = mutex.withLock {
        require(pin.length in PIN_LENGTHS && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit {
            putBoolean(KEY_ENABLED, true)
            putString(KEY_SALT, encode(salt))
            putString(KEY_HASH, encode(hash))
            putInt(KEY_PIN_LENGTH, pin.length)
            putInt(KEY_FAILURES, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0)
        }
        _config.value = readConfig()
    }

    fun disable() {
        prefs.edit { clear() }
        _config.value = readConfig()
        _locked.value = false
    }

    fun setBiometrics(enabled: Boolean) = update { putBoolean(KEY_BIOMETRICS, enabled) }

    fun setTimeout(millis: Long) = update { putLong(KEY_TIMEOUT, millis) }

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block)
        _config.update { readConfig() }
    }

    private fun readConfig() = LockConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        biometrics = prefs.getBoolean(KEY_BIOMETRICS, false),
        pinLength = prefs.getInt(KEY_PIN_LENGTH, 0),
        timeoutMillis = prefs.getLong(KEY_TIMEOUT, 0),
    )

    private suspend fun hash(pin: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    companion object {
        val PIN_LENGTHS = 4..8
        val TIMEOUTS = listOf(0L, 60_000L, 5 * 60_000L, 15 * 60_000L)
        const val FREE_ATTEMPTS = 5
        private const val ITERATIONS = 120_000

        /** No delay for the first [FREE_ATTEMPTS] mistakes, then 30 s doubling with each one, up to about an hour. */
        fun lockoutAfter(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0
            val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(7)
            return 30_000L shl doublings
        }

        private const val KEY_ENABLED = "enabled"
        private const val KEY_BIOMETRICS = "biometrics"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_PIN_LENGTH = "pin_length"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }
}
