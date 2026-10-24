package com.plyg.droidserver.core

import com.sun.net.httpserver.HttpServer
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.OAuthEndpoints
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.OAuthConfig
import com.plyg.droidserver.core.config.OAuthProvider
import com.plyg.droidserver.core.config.OAuthProviderConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseQueryString
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sosyal giriş: sahte Google/GitHub sağlayıcısıyla uçtan uca. */
class OAuthTest {
    private val key = "yonetici-anahtari-1234567890"
    private lateinit var db: SqlDriver
    private lateinit var provider: HttpServer
    private lateinit var base: String

    /** Sahte sağlayıcının durumu: verilen kodun PKCE meydan okuması ve döndürülecek e-posta. */
    private val challenges = mutableMapOf<String, String>()
    private var email = "ali@example.com"
    private var verified = true
    private val tokenRequests = mutableListOf<Map<String, String>>()

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config().schemaCollections())
        provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/token") { ex ->
            val form = parseQueryString(ex.requestBody.readBytes().decodeToString()).let { p -> p.names().associateWith { p[it]!! } }
            tokenRequests += form
            val challenge = challenges.remove(form["code"])
            val expected = form["code_verifier"]?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
            val body = if (challenge != null && challenge == expected && form["client_secret"] == "gizli") """{"access_token":"at-1"}""" else """{"error":"invalid_grant"}"""
            ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        provider.createContext("/userinfo") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer at-1"
            val body = if (ok) """{"email":"$email","email_verified":$verified}""" else "{}"
            ex.sendResponseHeaders(if (ok) 200 else 401, 0); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        provider.createContext("/emails") { ex ->
            val body = """[{"email":"ikincil@example.com","primary":false,"verified":true},{"email":"$email","primary":true,"verified":$verified}]"""
            ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        provider.start()
        base = "http://127.0.0.1:${provider.address.port}"
    }

    @AfterTest
    fun close() {
        provider.stop(0)
        db.close()
    }

    private fun config(allowSignup: Boolean = true) = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(
                enabled = true, allowSignup = allowSignup,
                oauth = OAuthConfig(
                    publicUrl = "https://evim.example.com",
                    providers = listOf(OAuthProviderConfig(OAuthProvider.GOOGLE, "istemci", "gizli"), OAuthProviderConfig(OAuthProvider.GITHUB, "gh-istemci", "gizli")),
                    redirectUrls = listOf("https://uygulamam.com/giris", "uygulamam://giris"),
                ),
            ),
        ),
    )

    private fun endpoints() = mapOf(
        OAuthProvider.GOOGLE to OAuthEndpoints("$base/authorize", "$base/token", "$base/userinfo", "openid email"),
        OAuthProvider.GITHUB to OAuthEndpoints("$base/authorize", "$base/token", "$base/emails", "user:email"),
    )

    private fun ApplicationTestBuilder.noRedirects() = createClient { followRedirects = false }

    /** Kullanıcının tarayıcıda yaptığı: akışı başlat, sağlayıcı izin verdi, dönüş adresine git. */
    private suspend fun HttpClient.login(provider: String, redirect: String?, cookie: Boolean = true, tamper: (String) -> String = { it }): HttpResponse {
        val start = get("/api/auth/oauth/$provider" + (redirect?.let { "?redirect=$it" } ?: ""))
        assertEquals(HttpStatusCode.Found, start.status, start.bodyAsText())
        val location = URI(start.headers[HttpHeaders.Location]!!)
        val q = parseQueryString(location.rawQuery)
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("https://evim.example.com/api/auth/oauth/$provider/callback", q["redirect_uri"])
        val state = q["state"]!!
        val setCookie = start.headers[HttpHeaders.SetCookie]!!
        assertTrue("HttpOnly" in setCookie && "SameSite=Lax" in setCookie && "Secure" in setCookie, setCookie)
        val code = "kod-" + challenges.size
        challenges[code] = q["code_challenge"]!!
        return get("/api/auth/oauth/$provider/callback?code=$code&state=${tamper(state)}") {
            if (cookie) header(HttpHeaders.Cookie, "ds_oauth=$state")
        }
    }

    private suspend fun HttpClient.exchange(code: String) = post("/api/auth/oauth/exchange") {
        contentType(ContentType.Application.Json); setBody("""{"code":"$code"}""")
    }

    @Test
    fun `google ve github ile giris, hesap eslesme, tek kullanimlik kod`() = testApplication {
        application { droidServerModule(config(), db, oauthEndpoints = endpoints()) }
        val browser = noRedirects()
        val providers = Json.parseToJsonElement(client.get("/api/auth/oauth/providers").bodyAsText()).jsonArray
        assertEquals(listOf("google", "github"), providers.map { it.jsonObject.getValue("provider").jsonPrimitive.content })

        val back = browser.login("google", "https://uygulamam.com/giris")
        assertEquals(HttpStatusCode.Found, back.status, back.bodyAsText())
        val target = URI(back.headers[HttpHeaders.Location]!!)
        assertEquals("uygulamam.com", target.host)
        val code = parseQueryString(target.rawQuery)["code"]!!
        assertFalse("token" in target.toString(), "adreste token yok")
        val tokens = Json.parseToJsonElement(client.exchange(code).bodyAsText()).jsonObject
        val user = tokens.getValue("user").jsonObject
        assertEquals("ali@example.com", user.getValue("email").jsonPrimitive.content)
        assertEquals("true", user.getValue("verified").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(tokens.getValue("token").jsonPrimitive.content) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.exchange(code).status, "kod tek kullanımlık")
        assertEquals("gizli", tokenRequests.last()["client_secret"])

        // GitHub: aynı e-posta aynı hesaba bağlanır; birincil doğrulanmış e-posta seçilir. Dönüş adresi yoksa sonuç sayfası.
        val gh = browser.login("github", null)
        assertEquals(HttpStatusCode.OK, gh.status)
        assertTrue("ali@example.com ile giriş yapıldı" in gh.bodyAsText())
        val app = browser.login("github", "uygulamam://giris")
        val appCode = parseQueryString(URI(app.headers[HttpHeaders.Location]!!).rawQuery)["code"]!!
        assertEquals(user.getValue("id"), Json.parseToJsonElement(client.exchange(appCode).bodyAsText()).jsonObject.getValue("user").jsonObject.getValue("id"))
        val users = Json.parseToJsonElement(client.get("/api/auth/users") { bearerAuth(key) }.bodyAsText()).jsonObject
        assertEquals("1", users.getValue("totalItems").jsonPrimitive.content)
    }

    @Test
    fun `saldiri ve hata durumlari`() = testApplication {
        application { droidServerModule(config(allowSignup = false), db, oauthEndpoints = endpoints()) }
        val browser = noRedirects()
        // İzinsiz dönüş adresi (açık yönlendirme) ve bilinmeyen sağlayıcı.
        assertEquals(HttpStatusCode.BadRequest, browser.get("/api/auth/oauth/google?redirect=https://kotu.example.com").status)
        assertEquals(HttpStatusCode.BadRequest, browser.get("/api/auth/oauth/google?redirect=https://uygulamam.com.kotu.example").status)
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/auth/oauth/facebook").status)

        // Çerez yok (başkasının başlattığı akış) ve değiştirilmiş state.
        val noCookie = browser.login("google", "https://uygulamam.com/giris", cookie = false)
        assertTrue("error=" in noCookie.headers[HttpHeaders.Location].orEmpty())
        assertEquals(HttpStatusCode.BadRequest, browser.login("google", null, tamper = { it + "x" }).status)

        // Kayıt kapalı ve hesap yok; doğrulanmamış e-posta.
        val closed = browser.login("google", "https://uygulamam.com/giris")
        val error = parseQueryString(URI(closed.headers[HttpHeaders.Location]!!).rawQuery)["error"]!!
        assertTrue("kayıt kapalı" in error, error)
        verified = false
        assertTrue("doğrulanmış e-posta yok" in browser.login("google", null).bodyAsText())
        // Sağlayıcıda iptal.
        val start = browser.get("/api/auth/oauth/google")
        val state = parseQueryString(URI(start.headers[HttpHeaders.Location]!!).rawQuery)["state"]!!
        val cancelled = browser.get("/api/auth/oauth/google/callback?error=access_denied&state=$state") { header(HttpHeaders.Cookie, "ds_oauth=$state") }
        assertTrue("iptal" in cancelled.bodyAsText())
    }

    @Test
    fun `dogrulama ve sir deposu`() {
        assertTrue(ConfigValidator.validate(config()).isEmpty(), ConfigValidator.validate(config()).toString())
        val oauth = config().auth.users.oauth
        fun with(o: OAuthConfig) = ConfigValidator.validate(config().copy(auth = config().auth.copy(users = config().auth.users.copy(oauth = o))))
        assertTrue(with(oauth.copy(publicUrl = "")).any { "adresi gerekli" in it })
        assertTrue(with(oauth.copy(redirectUrls = listOf("uygulamam.com"))).any { "Dönüş adresi" in it })
        assertTrue(with(oauth.copy(providers = oauth.providers + oauth.providers.first())).any { "iki kez" in it })
        assertTrue(with(oauth.copy(providers = listOf(oauth.providers.first().copy(clientSecret = "")))).any { "Client secret" in it })

        val stored = mutableMapOf<String, String>()
        val secured = ConfigSecrets.secureText(config().toJson()) { kind, value -> "${kind}_${stored.size}".also { stored[it] = value } }!!
        assertTrue("\"gizli\"" !in secured && "secret:oauth_secret_" in secured, secured)
        val resolved = ConfigSecrets.resolve(ServerConfig.fromJson(secured), stored::get)
        assertEquals(listOf("gizli", "gizli"), resolved.auth.users.oauth.providers.map { it.clientSecret })
        assertNotNull(ServerConfig.fromJson(config().toJson()).auth.users.oauth.providers.single { it.provider == OAuthProvider.GITHUB })
    }
}
