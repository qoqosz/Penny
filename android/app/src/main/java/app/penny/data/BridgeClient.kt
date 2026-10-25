package app.penny.data

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
import java.util.concurrent.TimeUnit

/** The bridge answered with an error. [status] 4xx other than 409 means retrying won't help. */
class BridgeException(val status: Int, val code: String, message: String) : Exception(message) {
    val isPermanent: Boolean get() = status in 400..499 && status != 409 && status != 401
}

/** The Mac couldn't be reached (offline, other network, Mac asleep). */
class UnreachableException(cause: Throwable) : Exception("Mac unreachable", cause)

class NotPairedException : Exception("Not paired with a Mac")

/** The bridge answered with something this app can't read (e.g. a bridge newer or older than the app). */
class IncompatibleResponseException(cause: Throwable) : Exception("Unexpected response from the bridge", cause)

class BridgeClient(private val http: OkHttpClient = defaultHttp) {

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        private val jsonType = "application/json; charset=utf-8".toMediaType()

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Writes wait for Money to quit and relaunch on the Mac.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun ping(host: String, port: Int): Ping = call(url(host, port, "ping"), null, null)

    suspend fun pair(host: String, port: Int, code: String, deviceName: String): PairResponse =
        call(url(host, port, "pair"), null, json.encodeToString(PairRequest.serializer(), PairRequest(code, deviceName)))

    suspend fun snapshot(c: Connection): Snapshot = call(url(c.host, c.port, "snapshot"), c.token, null)

    suspend fun transactions(c: Connection, accountId: String?, offset: Int, limit: Int): TransactionPage {
        val url = url(c.host, c.port, "transactions").newBuilder()
            .apply { if (accountId != null) addQueryParameter("accountId", accountId) }
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return call(url, c.token, null)
    }

    suspend fun icons(c: Connection, ids: List<String>): IconBatch {
        val url = url(c.host, c.port, "icons").newBuilder().addQueryParameter("ids", ids.joinToString(",")).build()
        return call(url, c.token, null)
    }

    suspend fun create(c: Connection, request: NewTransactionRequest): Transaction =
        call(url(c.host, c.port, "transactions"), c.token,
            json.encodeToString(NewTransactionRequest.serializer(), request))

    /** Older bridges don't have this (404); send one by one with [create] then. */
    suspend fun createBatch(c: Connection, requests: List<NewTransactionRequest>): BatchResults =
        call(url(c.host, c.port, "transactions/batch"), c.token,
            json.encodeToString(NewTransactionBatch.serializer(), NewTransactionBatch(requests)))

    private fun url(host: String, port: Int, path: String): HttpUrl = HttpUrl.Builder()
        .scheme("http").host(host).port(port)
        .addPathSegments("api/v1/$path")
        .build()

    private suspend inline fun <reified T> call(url: HttpUrl, token: String?, body: String?): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                // The bridge writes its error messages in this language. The default locale follows Penny's language setting.
                header("Accept-Language", Locale.getDefault().toLanguageTag())
                if (token != null) header("Authorization", "Bearer $token")
                if (body != null) post(body.toRequestBody(jsonType))
            }.build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw UnreachableException(e)
            }
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val error = runCatching { json.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
                    throw BridgeException(it.code, error?.code ?: "http", error?.message ?: "HTTP ${it.code}")
                }
                try {
                    json.decodeFromString<T>(text)
                } catch (e: IllegalArgumentException) {
                    // SerializationException is an IllegalArgumentException: a missing field, a changed type, not JSON.
                    throw IncompatibleResponseException(e)
                }
            }
        }
}

data class Connection(val host: String, val port: Int, val token: String)
