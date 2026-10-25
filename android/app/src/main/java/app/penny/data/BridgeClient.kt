package app.penny.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The bridge answered with an error. [status] 4xx other than 409 means retrying won't help. */
class BridgeException(val status: Int, val code: String, message: String) : Exception(message) {
    val isPermanent: Boolean get() = status in 400..499 && status != 409 && status != 401
}

/** The Mac couldn't be reached (offline, other network, Mac asleep). */
class UnreachableException(cause: Throwable) : Exception("Mac unreachable", cause)

class NotPairedException : Exception("Not paired with a Mac")

class BridgeClient(private val http: OkHttpClient = defaultHttp) {

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        private val jsonType = "application/json; charset=utf-8".toMediaType()

        private const val REVOKE_TIMEOUT_SECONDS = 5L

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Writes wait for Money to quit and relaunch on the Mac.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** One OkHttp client per pinned certificate; plain HTTP uses [http] itself. */
    private val pinned = ConcurrentHashMap<String, OkHttpClient>()

    private fun httpFor(tls: BridgeTls?): OkHttpClient =
        tls?.let { t -> pinned.getOrPut(t.fingerprint) { http.trusting(PinnedTrustManager(t.fingerprint)) } } ?: http

    /** Over plain HTTP: tells whether the bridge also serves HTTPS ([Ping.tlsPort]). */
    suspend fun ping(host: String, port: Int): Ping = call(url(host, port, "ping"), null, null)

    /**
     * Connects to the bridge's HTTPS port trusting whatever certificate it presents (pairing is trust on first use;
     * the user compares the short fingerprint with the Mac's) and returns that certificate's fingerprint.
     */
    suspend fun certificate(host: String, tlsPort: Int): String {
        val capture = CapturingTrustManager()
        val ping: Ping = call(url(host, tlsPort, "ping", https = true), null, null, http.trusting(capture))
        val presented = capture.certificate?.let(Fingerprints::of) ?: throw UntrustedBridgeException()
        // Something in between with its own certificate that passes the bridge's answer on.
        if (ping.fingerprint != null && !Fingerprints.matches(ping.fingerprint, presented)) throw UntrustedBridgeException()
        return presented
    }

    /** With [tls], over HTTPS to the pinned certificate only. */
    suspend fun pair(host: String, port: Int, tls: BridgeTls?, code: String, deviceName: String): PairResponse =
        call(url(host, tls?.port ?: port, "pair", https = tls != null), null,
            json.encodeToString(PairRequest.serializer(), PairRequest(code, deviceName)), httpFor(tls))

    /**
     * Asks the Mac to forget this phone's token, when unpairing. Best effort: false when the Mac can't be reached or
     * is older than `DELETE device` (404), and it gives up after a few seconds.
     */
    suspend fun revokeDevice(c: Connection): Boolean = try {
        val quick = httpFor(c.tls).newBuilder().callTimeout(REVOKE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        call<Revoked>(url(c, "device"), c.token, null, quick, delete = true).revoked
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun snapshot(c: Connection): Snapshot = call(url(c, "snapshot"), c.token, null, httpFor(c.tls))

    suspend fun transactions(c: Connection, accountId: String?, offset: Int, limit: Int): TransactionPage {
        val url = url(c, "transactions").newBuilder()
            .apply { if (accountId != null) addQueryParameter("accountId", accountId) }
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return call(url, c.token, null, httpFor(c.tls))
    }

    suspend fun icons(c: Connection, ids: List<String>): IconBatch {
        val url = url(c, "icons").newBuilder().addQueryParameter("ids", ids.joinToString(",")).build()
        return call(url, c.token, null, httpFor(c.tls))
    }

    suspend fun create(c: Connection, request: NewTransactionRequest): Transaction =
        call(url(c, "transactions"), c.token,
            json.encodeToString(NewTransactionRequest.serializer(), request), httpFor(c.tls))

    /** Older bridges don't have this (404); send one by one with [create] then. */
    suspend fun createBatch(c: Connection, requests: List<NewTransactionRequest>): BatchResults =
        call(url(c, "transactions/batch"), c.token,
            json.encodeToString(NewTransactionBatch.serializer(), NewTransactionBatch(requests)), httpFor(c.tls))

    /** A pinned connection always goes over HTTPS, never plain HTTP. */
    private fun url(c: Connection, path: String): HttpUrl = url(c.host, c.tls?.port ?: c.port, path, https = c.tls != null)

    private fun url(host: String, port: Int, path: String, https: Boolean = false): HttpUrl = HttpUrl.Builder()
        .scheme(if (https) "https" else "http").host(host).port(port)
        .addPathSegments("api/v1/$path")
        .build()

    private suspend inline fun <reified T> call(
        url: HttpUrl, token: String?, body: String?, client: OkHttpClient = http, delete: Boolean = false,
    ): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                // The bridge writes its error messages in this language. The default locale follows Penny's language setting.
                header("Accept-Language", Locale.getDefault().toLanguageTag())
                if (token != null) header("Authorization", "Bearer $token")
                if (body != null) post(body.toRequestBody(jsonType))
                if (delete) delete()
            }.build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw if (isPinMismatch(e)) UntrustedBridgeException(e) else UnreachableException(e)
            }
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val error = runCatching { json.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
                    throw BridgeException(it.code, error?.code ?: "http", error?.message ?: "HTTP ${it.code}")
                }
                json.decodeFromString<T>(text)
            }
        }
}

/** [tls]: the bridge's pinned HTTPS port and certificate; without it, plain HTTP (a bridge from before HTTPS). */
data class Connection(val host: String, val port: Int, val token: String, val tls: BridgeTls? = null)
