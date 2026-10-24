package com.plyg.droidserver.core

import com.plyg.droidserver.core.admin.AdminSessions
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AdminPanelConfig
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.PasskeysConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 17.6: passkey ile kullanıcı ve web paneli girişi, HTTP üzerinden. */
class PasskeyHttpTest {
    private val key = "yonetici-anahtari-1234567890"
    private val rpId = "ev.duckdns.org"
    private val origin = "https://ev.duckdns.org:8443"
    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(enabled = true, sendVerification = false),
            passkeys = PasskeysConfig(enabled = true, rpId = rpId),
        ),
        adminPanel = AdminPanelConfig(enabled = true),
    )

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpResponse.obj(): JsonObject = json(bodyAsText())

    private suspend fun ApplicationTestBuilder.post(path: String, body: String = "{}", token: String? = null) =
        client.post(path) { token?.let(::bearerAuth); contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `kullanici ve panel girisi`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        val sessions = AdminSessions()
        application { droidServerModule(config, db, adminSessions = sessions) }

        assertEquals(HttpStatusCode.Created, post("/api/auth/users", """{"email":"ayse@example.com","password":"parola-12345"}""", key).status)
        val token = post("/api/auth/login", """{"email":"ayse@example.com","password":"parola-12345"}""").obj().getValue("token").jsonPrimitive.content
        val phone = TestAuthenticator.ec(rpId, origin)

        // Kayıt: seçenekler giriş yapmış kullanıcıya.
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/passkeys/register-options").status)
        val reg = post("/api/auth/passkeys/register-options", token = token).obj()
        assertEquals(rpId, reg.getValue("rp").jsonObject.getValue("id").jsonPrimitive.content)
        val added = post("/api/auth/passkeys", phone.registrationJson(reg.getValue("challenge").jsonPrimitive.content, "telefon"), token)
        assertEquals(HttpStatusCode.Created, added.status, added.bodyAsText())
        val keyId = added.obj().getValue("id").jsonPrimitive.content
        val list = client.get("/api/auth/passkeys") { bearerAuth(token) }.obj().getValue("items").jsonArray
        assertEquals("telefon", list.single().jsonObject.getValue("name").jsonPrimitive.content)
        // Aynı challenge ikinci kez kullanılamaz.
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/passkeys", phone.registrationJson(reg.getValue("challenge").jsonPrimitive.content, "yine"), token).status)

        // Giriş: e-posta yazmadan; yanıt token çifti.
        val options = post("/api/auth/passkeys/login-options").obj()
        assertTrue(options.getValue("allowCredentials").jsonArray.isEmpty(), "hesabın var olduğu sızmaz")
        val challenge = options.getValue("challenge").jsonPrimitive.content
        val body = phone.assertionJson(challenge)
        val login = post("/api/auth/passkeys/login", body)
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val passkeyToken = login.obj().getValue("token").jsonPrimitive.content
        assertEquals("ayse@example.com", client.get("/api/auth/me") { bearerAuth(passkeyToken) }.obj().getValue("email").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/passkeys/login", body).status, "aynı yanıt yeniden oynatılamaz")
        val used = client.get("/api/auth/passkeys") { bearerAuth(token) }.obj().getValue("items").jsonArray.single().jsonObject.getValue("lastUsedAt")
        assertTrue(used is JsonPrimitive && used.isString, "son kullanım yazılır: $used")

        // Kullanıcı passkey'i panele giremez (ayrı tablo).
        val panelOptions = post("/_admin/api/passkeys/login-options").obj().getValue("challenge").jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, post("/_admin/api/passkeys/login", phone.assertionJson(panelOptions)).status)

        // Silinen passkey ile giriş olmaz.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/auth/passkeys/$keyId") { bearerAuth(token) }.status)
        val again = post("/api/auth/passkeys/login-options").obj().getValue("challenge").jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/passkeys/login", phone.assertionJson(again)).status)

        // Panel: önce eşleştirme koduyla girip passkey eklenir, sonra passkey ile girilir.
        assertEquals("false", client.get("/_admin/api/login").obj().getValue("passkey").jsonPrimitive.content)
        val panel = post("/_admin/api/pair", """{"code":"${sessions.newCode().code}"}""").obj().getValue("token").jsonPrimitive.content
        val laptop = TestAuthenticator.rsa(rpId, "https://ev.duckdns.org:8443")
        val panelReg = post("/_admin/api/passkeys/register-options", token = panel).obj().getValue("challenge").jsonPrimitive.content
        assertEquals(HttpStatusCode.Created, post("/_admin/api/passkeys", laptop.registrationJson(panelReg, "dizüstü"), panel).status)
        assertEquals("true", client.get("/_admin/api/login").obj().getValue("passkey").jsonPrimitive.content)
        val c = post("/_admin/api/passkeys/login-options").obj().getValue("challenge").jsonPrimitive.content
        val session = post("/_admin/api/passkeys/login", laptop.assertionJson(c)).obj().getValue("token").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get("/_admin/api/info") { bearerAuth(session) }.status)
        assertEquals(HttpStatusCode.Unauthorized, post("/_admin/api/passkeys/register-options").status, "panel passkey'i eklemek oturum ister")
        db.close()
    }

    @Test
    fun `alan adi dogrulamasi`() {
        fun errors(rp: String, users: Boolean = true, panel: Boolean = false) = ConfigValidator.validateDetailed(
            ServerConfig(
                auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = users), passkeys = PasskeysConfig(enabled = true, rpId = rp)),
                adminPanel = AdminPanelConfig(enabled = panel),
            ),
        ).filter { it.path.startsWith("auth.passkeys") }
        assertTrue(errors("ev.duckdns.org").isEmpty())
        assertTrue(errors("localhost").isEmpty())
        assertTrue(errors("").isNotEmpty(), "boş")
        assertTrue(errors("192.168.1.20").isNotEmpty(), "IP adresi")
        assertTrue(errors("https://ev.duckdns.org").isNotEmpty(), "şema ile")
        assertTrue(errors("ev.duckdns.org:8443").isNotEmpty(), "port ile")
        assertTrue(errors("ev.duckdns.org", users = false).isNotEmpty(), "ne kullanıcılar ne panel")
        assertTrue(errors("ev.duckdns.org", users = false, panel = true).isEmpty())
    }
}
