package app.penny

import app.penny.data.BridgeClient
import app.penny.data.BridgeTls
import app.penny.data.Connection
import app.penny.data.Fingerprints
import app.penny.data.UntrustedBridgeException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PinningTest {
    private lateinit var server: MockWebServer
    private val client = BridgeClient()

    /** Self-signed, like the bridge's; its name doesn't match the address, which the pin makes irrelevant. */
    private val bridgeCertificate = HeldCertificate.Builder().commonName("Penny Bridge").build()
    private val otherCertificate = HeldCertificate.Builder().commonName("Penny Bridge").build()
    private val fingerprint = Fingerprints.of(bridgeCertificate.certificate)

    private val snapshotJson = """{"generation":"g","bridgeVersion":"1.0.0","writesEnabled":true,"accounts":[],"categories":[],"payees":[]}"""

    @Before fun setUp() {
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(bridgeCertificate).build().sslSocketFactory(), false)
            start()
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun pinned(fingerprint: String) = Connection(server.hostName, 1, "secret", BridgeTls(server.port, fingerprint))

    @Test fun `pinned certificate accepted, over HTTPS on the TLS port`() = runTest {
        server.enqueue(MockResponse().setBody(snapshotJson))
        assertEquals("g", client.snapshot(pinned(fingerprint)).generation)
        val request = server.takeRequest()
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertTrue("the request went over TLS", request.handshake != null)
    }

    @Test fun `another certificate is refused before the token is sent`() = runTest {
        server.enqueue(MockResponse().setBody(snapshotJson))
        try {
            client.snapshot(pinned(Fingerprints.of(otherCertificate.certificate)))
            fail("expected UntrustedBridgeException")
        } catch (_: UntrustedBridgeException) {
        }
        assertEquals("no request reached the server", 0, server.requestCount)
    }

    @Test fun `pairing trusts the presented certificate on first use`() = runTest {
        server.enqueue(MockResponse().setBody("""{"app":"penny-bridge","name":"Mac","version":"1.0.0","tlsPort":${server.port},"fingerprint":"$fingerprint"}"""))
        assertEquals(fingerprint, client.certificate(server.hostName, server.port))
        assertEquals(Fingerprints.short(fingerprint), BridgeTls(server.port, fingerprint).shortFingerprint)
        assertTrue(Regex("[0-9A-F]{4}( [0-9A-F]{4}){3}").matches(Fingerprints.short(fingerprint)))
    }

    @Test fun `a certificate other than the one the bridge reports is refused`() = runTest {
        // Something in between, with its own certificate, passing the bridge's ping on.
        val reported = Fingerprints.of(otherCertificate.certificate)
        server.enqueue(MockResponse().setBody("""{"app":"penny-bridge","name":"Mac","version":"1.0.0","fingerprint":"$reported"}"""))
        try {
            client.certificate(server.hostName, server.port)
            fail("expected UntrustedBridgeException")
        } catch (_: UntrustedBridgeException) {
        }
    }

    @Test fun `unpairing revokes the device on the Mac`() = runTest {
        server.enqueue(MockResponse().setBody("""{"revoked":true}"""))
        assertTrue(client.revokeDevice(pinned(fingerprint)))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/device", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test fun `revoking doesn't fail on an older bridge or without the Mac`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"?"}}"""))
        assertFalse(client.revokeDevice(pinned(fingerprint)))
        server.shutdown()
        assertFalse(client.revokeDevice(pinned(fingerprint)))
    }
}
