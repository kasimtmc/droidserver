package dev.droidserver.core

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.auth.WebAuthn
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 17.6: passkey kaydı ve girişinin doğrulanması; tarayıcı ve doğrulayıcı [TestAuthenticator] ile taklit edilir. */
class WebAuthnTest {
    private val rpId = "ev.duckdns.org"
    private val origin = "https://ev.duckdns.org:8443"

    private fun register(a: TestAuthenticator, challenge: String, rp: String = rpId, origin: String = this.origin, fmt: String = "none"): WebAuthn.Registration {
        val c = a.create(challenge, rp, origin, fmt)
        return WebAuthn.verifyRegistration(c.clientData, c.attestationObject, challenge, rpId)
    }

    private fun login(a: TestAuthenticator, reg: WebAuthn.Registration, challenge: String, stored: Long, signed: String = challenge, tamper: Boolean = false): WebAuthn.Assertion {
        val r = a.get(signed, tamper)
        return WebAuthn.verifyAssertion(r.clientData, r.authenticatorData, r.signature, challenge, rpId, reg.publicKey, reg.algorithm, stored)
    }

    @Test
    fun `kayit ve giris, ES256 ve RS256`() {
        for (a in listOf(TestAuthenticator.ec(rpId, origin), TestAuthenticator.rsa(rpId, origin))) {
            val reg = register(a, WebAuthn.challenge())
            assertContentEquals(a.id, reg.credentialId)
            assertEquals(a.alg, reg.algorithm)
            assertTrue(reg.userVerified)
            assertEquals(1, login(a, reg, WebAuthn.challenge(), stored = 0).signCount)
            assertEquals(2, login(a, reg, WebAuthn.challenge(), stored = 1).signCount)
        }
    }

    @Test
    fun `reddedilenler`() {
        val a = TestAuthenticator.ec(rpId, origin)
        val c = WebAuthn.challenge()
        assertFailsWith<ApiException>("başka köken") { register(a, c, origin = "https://kotu.example.com") }
        assertFailsWith<ApiException>("başka RP için anahtar") { register(a, c, rp = "kotu.example.com") }
        assertFailsWith<ApiException>("düz http") { register(a, c, origin = "http://ev.duckdns.org:8080") }
        val reg = register(a, c, fmt = "packed") // attestation biçimi denetlenmez
        assertFailsWith<ApiException>("başka isteğin challenge'ı") { login(a, reg, WebAuthn.challenge(), stored = 0, signed = WebAuthn.challenge()) }
        assertFailsWith<ApiException>("bozuk imza") { login(a, reg, WebAuthn.challenge(), stored = 0, tamper = true) }
        assertFailsWith<ApiException>("sayaç gerilemesi (kopya anahtar)") { login(a, reg, WebAuthn.challenge(), stored = 50) }
        val client = a.create(c).clientData
        assertFailsWith<ApiException>("bozuk CBOR") { WebAuthn.verifyRegistration(client, byteArrayOf(0xA1.toByte(), 0x61), c, rpId) }
    }

    @Test
    fun `koken kurallari`() {
        assertTrue(WebAuthn.originAllowed("https://ev.duckdns.org", rpId))
        assertTrue(WebAuthn.originAllowed("https://panel.ev.duckdns.org:8443", rpId))
        assertFalse(WebAuthn.originAllowed("https://kotuev.duckdns.org", rpId))
        assertFalse(WebAuthn.originAllowed("http://ev.duckdns.org", rpId))
        assertTrue(WebAuthn.originAllowed("http://localhost:8080", "localhost"))
    }
}
