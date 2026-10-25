package app.penny.data

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The bridge's HTTPS port and the SHA-256 of its self-signed certificate, pinned when pairing. */
data class BridgeTls(val port: Int, val fingerprint: String) {
    /** As `penny-bridge pair` prints it, for the user to compare. */
    val shortFingerprint: String get() = Fingerprints.short(fingerprint)
}

/** The bridge presented another certificate than the pinned one: not the paired Mac (or its certificate was replaced). */
class UntrustedBridgeException(cause: Throwable? = null) : Exception("Untrusted certificate", cause)

/** Thrown by [PinnedTrustManager]; OkHttp wraps it in an SSLHandshakeException. */
class PinMismatchException : CertificateException("Certificate doesn't match the pinned fingerprint")

object Fingerprints {
    /** SHA-256 of the DER certificate, lowercase hex (as the bridge's ping reports it). */
    fun of(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /** The first 8 bytes as "A1B2 C3D4 E5F6 0718", the same as on the Mac. */
    fun short(fingerprint: String): String = fingerprint.take(16).uppercase().chunked(4).joinToString(" ")

    fun matches(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())
}

/**
 * Trusts exactly one certificate, the bridge's own. Hostnames aren't checked (the bridge is reached by IP address and
 * its certificate names no host): the pin already says which server this is.
 */
@SuppressLint("CustomX509TrustManager") // Stricter than the platform's: one certificate, by its hash.
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw PinMismatchException()
        if (!Fingerprints.matches(Fingerprints.of(leaf), fingerprint)) throw PinMismatchException()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Trust on first use, only while pairing (or moving a pairing to HTTPS): accepts any certificate and remembers it. */
@SuppressLint("CustomX509TrustManager") // Used once, to learn the certificate that is then pinned.
class CapturingTrustManager : X509TrustManager {
    @Volatile var certificate: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        certificate = chain?.firstOrNull() ?: throw CertificateException("No certificate")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** [base] talking only to servers that [trustManager] accepts, whatever their hostname. */
fun OkHttpClient.trusting(trustManager: X509TrustManager): OkHttpClient {
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return newBuilder()
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
}

/** Whether [e] (or what caused it) is a [PinMismatchException]. */
internal fun isPinMismatch(e: Throwable): Boolean =
    generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(10).any { it is PinMismatchException }
