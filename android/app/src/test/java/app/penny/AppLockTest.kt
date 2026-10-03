package app.penny

import android.content.SharedPreferences
import androidx.lifecycle.LifecycleOwner
import app.penny.lock.AppLock
import app.penny.lock.PinResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private val prefs = MemoryPreferences()
    private var now = 1_000_000L
    private var elapsed = 0L
    private val lock = AppLock(prefs, now = { now }, elapsed = { elapsed })
    private val owner = object : LifecycleOwner {
        override val lifecycle get() = throw UnsupportedOperationException()
    }

    @Test fun `starts unlocked when no PIN is set`() {
        assertFalse(lock.locked.value)
        assertFalse(lock.config.value.enabled)
    }

    @Test fun `stores only a hash and checks the PIN`() = runTest {
        lock.setPin("4821")
        assertTrue(lock.config.value.enabled)
        assertEquals(4, lock.config.value.pinLength)
        assertFalse("PIN must not be stored in clear", prefs.values.values.any { it == "4821" })
        assertEquals(PinResult.Correct, lock.verify("4821"))
        assertEquals(PinResult.Wrong, lock.verify("1111"))
    }

    @Test fun `a new process starts locked`() = runTest {
        lock.setPin("4821")
        val restarted = AppLock(prefs, now = { now }, elapsed = { elapsed })
        assertTrue(restarted.locked.value)
        assertEquals(PinResult.Wrong, restarted.unlockWithPin("0000"))
        assertTrue(restarted.locked.value)
        assertEquals(PinResult.Correct, restarted.unlockWithPin("4821"))
        assertFalse(restarted.locked.value)
    }

    @Test fun `locks again after the timeout in background`() = runTest {
        lock.setPin("4821")
        lock.setTimeout(60_000)
        lock.onStop(owner)
        elapsed += 30_000
        lock.onStart(owner)
        assertFalse("short trip to another app", lock.locked.value)
        lock.onStop(owner)
        elapsed += 60_000
        lock.onStart(owner)
        assertTrue(lock.locked.value)
    }

    @Test fun `blocks entry after repeated wrong PINs`() = runTest {
        lock.setPin("4821")
        repeat(AppLock.FREE_ATTEMPTS - 1) { assertEquals(PinResult.Wrong, lock.verify("0000")) }
        val result = lock.verify("0000")
        assertEquals(PinResult.LockedOut(now + 30_000), result)
        assertEquals("even the right PIN waits", PinResult.LockedOut(now + 30_000), lock.verify("4821"))
        now += 30_000
        assertEquals(PinResult.LockedOut(now + 60_000), lock.verify("0000"))
        now += 60_000
        assertEquals(PinResult.Correct, lock.verify("4821"))
        assertEquals(PinResult.Wrong, lock.verify("0000"))
    }

    @Test fun `lockout grows but is capped`() {
        assertEquals(0, AppLock.lockoutAfter(AppLock.FREE_ATTEMPTS - 1))
        assertEquals(30_000, AppLock.lockoutAfter(AppLock.FREE_ATTEMPTS))
        assertEquals(60_000, AppLock.lockoutAfter(AppLock.FREE_ATTEMPTS + 1))
        assertEquals(AppLock.lockoutAfter(100), AppLock.lockoutAfter(1000))
    }

    @Test fun `turning the lock off forgets the PIN`() = runTest {
        lock.setPin("4821")
        lock.setBiometrics(true)
        lock.disable()
        assertFalse(lock.config.value.enabled)
        assertFalse(lock.config.value.biometrics)
        assertTrue(prefs.values.isEmpty())
    }
}

/** In-memory SharedPreferences for JVM tests. */
private class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }
        override fun apply() { commit() }
    }
}
