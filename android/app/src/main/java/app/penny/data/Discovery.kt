package app.penny.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

data class DiscoveredBridge(val name: String, val host: String, val port: Int)

/** Finds penny-bridge instances advertised over Bonjour (`_penny._tcp`) on the local network. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    // Older Android versions allow only one resolve at a time.
    private val resolveMutex = Mutex()

    fun browse(): Flow<List<DiscoveredBridge>> = callbackFlow {
        val found = linkedMapOf<String, DiscoveredBridge>()
        val lock = wifi?.createMulticastLock("penny-discovery")?.apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val resolved = resolve(info) ?: return@launch
                    found[resolved.name] = resolved
                    trySend(found.values.toList())
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                trySend(found.values.toList())
            }
        }
        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock?.release()
        }
    }

    /** Looks for a bridge with the given Bonjour name, e.g. after the Mac got a new IP address. */
    suspend fun find(name: String, timeoutMs: Long = 4000): DiscoveredBridge? = withTimeoutOrNull(timeoutMs) {
        browse().first { list -> list.any { it.name == name } }.first { it.name == name }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredBridge? = resolveMutex.withLock {
        val result = CompletableDeferred<NsdServiceInfo?>()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                result.complete(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                result.complete(serviceInfo)
            }
        })
        val resolved = withTimeoutOrNull(5000) { result.await() } ?: return null
        val address = resolved.host ?: return null
        val host = if (address is Inet4Address) address.hostAddress else "[${address.hostAddress}]"
        DiscoveredBridge(resolved.serviceName, host ?: return null, resolved.port)
    }

    companion object {
        const val SERVICE_TYPE = "_penny._tcp."
    }
}
