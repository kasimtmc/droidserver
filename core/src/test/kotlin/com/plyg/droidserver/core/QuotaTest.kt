package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.auth.DailyCounter
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.QuotaConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.files.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 12.3: kullanıcı başına kayıt, depolama ve günlük istek kotaları, iki lehçede. */
@RunWith(Parameterized::class)
class QuotaTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val owner = FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE)
    private val notes = CollectionConfig(
        "notes", listOf(FieldConfig("text", FieldType.TEXT), owner),
        rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER), ownerField = "owner",
    )
    private val photos = CollectionConfig(
        "photos", listOf(FieldConfig("img", FieldType.FILE, multiple = true), owner),
        rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER), ownerField = "owner",
    )
    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(enabled = true, quotas = QuotaConfig(records = 2, collections = mapOf("photos" to 5), storageMb = 1, dailyRequests = 12)),
        ),
        collections = listOf(notes, photos),
    )
    private lateinit var db: SqlDriver
    private lateinit var dir: File
    private lateinit var store: FileStore

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        dir = Files.createTempDirectory("ds-quota").toFile()
        store = FileStore(File(dir, "storage"))
    }

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.signup(email: String): String {
        val r = post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"$email","password":"parola-1234"}""") }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return json(r.bodyAsText()).getValue("token").jsonPrimitive.content
    }
    private suspend fun HttpClient.note(token: String): HttpResponse = post("/api/collections/notes/records") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"text":"x"}""")
    }
    private suspend fun HttpClient.photo(token: String, bytes: Int): HttpResponse = post("/api/collections/photos/records") {
        bearerAuth(token)
        setBody(MultiPartFormDataContent(formData {
            append("img", ByteArray(bytes) { 1 }, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.bin\"") })
        }))
    }
    private suspend fun HttpClient.usage(token: String): JsonObject = json(get("/api/auth/me/usage") { bearerAuth(token) }.bodyAsText())

    @Test
    fun `kayit, depolama ve gunluk istek kotalari`() = testApplication {
        // Gün dönümünü denemek için sayaç saati elde.
        var now = Instant.parse("2026-10-14T23:00:00Z")
        val counts = DailyCounter { now }
        application { droidServerModule(config, db, files = store, requestCounts = counts) }
        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString())
        val ali = client.signup("ali@example.com")
        val ayse = client.signup("ayse@example.com")

        // Kayıt: koleksiyon başına 2; kullanıcıya göre ayrı sayılır; yönetici anahtarı muaf.
        repeat(2) { assertEquals(HttpStatusCode.Created, client.note(ali).status) }
        val full = client.note(ali)
        assertEquals(HttpStatusCode.Forbidden, full.status)
        assertEquals("records", json(full.bodyAsText()).getValue("details").jsonObject.getValue("quota").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, client.note(ayse).status)
        assertEquals(HttpStatusCode.Created, client.note(key).status)

        // Depolama: toplam 1 MB (koleksiyona özel kayıt sınırı 5).
        assertEquals(HttpStatusCode.Created, client.photo(ali, 700_000).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.photo(ali, 400_000).status)
        assertEquals(HttpStatusCode.Created, client.photo(ayse, 400_000).status)

        val usage = client.usage(ali)
        assertEquals("2", usage.getValue("records").jsonObject.getValue("notes").jsonObject.getValue("used").jsonPrimitive.content)
        assertEquals("5", usage.getValue("records").jsonObject.getValue("photos").jsonObject.getValue("limit").jsonPrimitive.content)
        assertEquals(700_000L, usage.getValue("storage").jsonObject.getValue("usedBytes").jsonPrimitive.content.toLong())
        assertEquals((1024 * 1024).toString(), usage.getValue("storage").jsonObject.getValue("limitBytes").jsonPrimitive.content)

        // Günlük istek: 12; aşılınca 429, ertesi gün (UTC) sıfırlanır. Token'sız istek sayılmaz.
        val today = usage.getValue("requests").jsonObject.getValue("today").jsonPrimitive.content.toInt()
        assertEquals(6, today, "ali'nin bugünkü istekleri (signup token'sız)")
        repeat(12 - today) { client.get("/api/collections/notes/records") { bearerAuth(ali) } }
        val limited = client.get("/api/collections/notes/records") { bearerAuth(ali) }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status, limited.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records") { bearerAuth(ayse) }.status)
        now = Instant.parse("2026-10-15T00:00:01Z")
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records") { bearerAuth(ali) }.status)
    }

    @Test
    fun `kotasiz kullanim ozeti ve dogrulama`() = testApplication {
        val open = config.copy(auth = config.auth.copy(users = config.auth.users.copy(quotas = QuotaConfig())))
        application { droidServerModule(open, db, files = store) }
        val ali = client.signup("ali@example.com")
        repeat(3) { assertEquals(HttpStatusCode.Created, client.note(ali).status) }
        val usage = client.usage(ali)
        assertEquals(JsonNull, usage.getValue("records").jsonObject.getValue("notes").jsonObject.getValue("limit"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/auth/me/usage") { bearerAuth(key) }.status, "anahtarın hesabı yok")

        fun errors(q: QuotaConfig) = ConfigValidator.validate(config.copy(auth = config.auth.copy(users = config.auth.users.copy(quotas = q))))
        assertTrue(errors(QuotaConfig(collections = mapOf("yok" to 1))).any { "koleksiyonu yok" in it })
        assertTrue(errors(QuotaConfig(storageMb = 0)).any { "en az 1 MB" in it })
        val noOwner = config.copy(collections = listOf(notes.copy(ownerField = null, rules = Rules())))
        assertTrue(ConfigValidator.validate(noOwner.copy(auth = noOwner.auth.copy(users = noOwner.auth.users.copy(quotas = QuotaConfig(collections = mapOf("notes" to 1)))))).any { "sahip alanı yok" in it })
        assertEquals(config, ServerConfig.fromJson(config.toJson()))
    }
}
