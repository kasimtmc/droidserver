package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.auth.UserService
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Faz 17.7: açık oturumlar, tek tek / hepsini kapatma ve kapatılan oturumun erişim token'ının hemen düşmesi. */
class SessionsTest {
    private val key = "yonetici-anahtari-1234567890"
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, sendVerification = false)),
    )

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `oturumlar uzerinden`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        suspend fun login(device: String): JsonObject = json(client.post("/api/auth/login") {
            header(HttpHeaders.UserAgent, device); contentType(ContentType.Application.Json)
            setBody("""{"email":"ayse@example.com","password":"parola-12345"}""")
        }.bodyAsText())
        suspend fun sessions(token: String) = json(client.get("/api/auth/sessions") { bearerAuth(token) }.bodyAsText()).getValue("items").jsonArray.map { it.jsonObject }
        suspend fun me(token: String) = client.get("/api/auth/me") { bearerAuth(token) }.status

        assertEquals(HttpStatusCode.Created, client.post("/api/auth/users") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"email":"ayse@example.com","password":"parola-12345"}""")
        }.status)
        val phone = login("Telefon/1.0")
        val laptop = login("Dizüstü/2.0")
        val phoneToken = phone.getValue("token").jsonPrimitive.content
        val laptopToken = laptop.getValue("token").jsonPrimitive.content

        val list = sessions(phoneToken)
        assertEquals(2, list.size)
        val mine = list.single { it.getValue("current").jsonPrimitive.content == "true" }
        assertEquals("Telefon/1.0", mine.getValue("device").jsonPrimitive.content)
        val laptopId = list.single { it.getValue("device").jsonPrimitive.content == "Dizüstü/2.0" }.getValue("id").jsonPrimitive.content

        // Yenileme aynı oturumda kalır; kullanılmış yenileme token'ı geçmez.
        val oldRefresh = phone.getValue("refreshToken").jsonPrimitive.content
        val refreshed = json(client.post("/api/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$oldRefresh"}""") }.bodyAsText())
        assertEquals(2, sessions(refreshed.getValue("token").jsonPrimitive.content).size)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$oldRefresh"}""") }.status)

        // Tek oturumu kapatma: o cihazın erişim ve yenileme token'ı hemen düşer.
        assertEquals(HttpStatusCode.OK, me(laptopToken))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/auth/sessions/$laptopId") { bearerAuth(phoneToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, me(laptopToken))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${laptop.getValue("refreshToken").jsonPrimitive.content}"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, me(phoneToken))

        // Diğerlerini kapat: istek yapan oturum kalır.
        val tablet = login("Tablet/3.0").getValue("token").jsonPrimitive.content
        assertEquals("1", json(client.delete("/api/auth/sessions") { bearerAuth(phoneToken) }.bodyAsText()).getValue("closed").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, me(tablet))
        assertEquals(HttpStatusCode.OK, me(phoneToken))

        // Yönetici görür ve hepsini kapatır.
        val userId = json(client.get("/api/auth/me") { bearerAuth(phoneToken) }.bodyAsText()).getValue("id").jsonPrimitive.content
        val adminList = json(client.get("/api/auth/users/$userId/sessions") { bearerAuth(key) }.bodyAsText()).getValue("items").jsonArray
        assertEquals(1, adminList.size)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/auth/users/$userId/sessions") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.Unauthorized, me(phoneToken))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/auth/users/$userId/sessions") { bearerAuth(login("x").getValue("token").jsonPrimitive.content) }.status)
        db.close()
    }

    @Test
    fun `kapatilan oturum yeni sunucu orneginde de gecersiz`() {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        val first = UserService(db, config)
        val id = first.create(Json.parseToJsonElement("""{"email":"ali@example.com","password":"parola-12345"}""").jsonObject).getValue("id").jsonPrimitive.content.toLong()
        val tokens = first.login("test", "ali@example.com", "parola-12345") as UserService.Tokens
        val session = assertNotNull(first.resolve(tokens.token)?.session)
        first.closeSession(id, session)
        assertNull(UserService(db, config).resolve(tokens.token), "deploy'dan sonraki örnek de iptali bilir")
        db.close()
    }
}
