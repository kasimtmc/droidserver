package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.backup.BackupArchive
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HistoryConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.TrashConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.security.FieldCipher
import dev.droidserver.core.security.FieldCipherException
import dev.droidserver.core.security.FieldEncryption
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Şifreli alanlar: veritabanında, geçmişte ve çöp kutusunda şifreli; API'de açık; iki lehçede. */
@RunWith(Parameterized::class)
class FieldEncryptionTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val apiKey = "yonetici-anahtari-1234567890"
    private val fieldKey = FieldCipher.generateKey()
    private val patients = CollectionConfig(
        "patients",
        listOf(
            FieldConfig("name", FieldType.TEXT, required = true),
            FieldConfig("tc", FieldType.TEXT, encrypted = true),
            FieldConfig("notes", FieldType.JSON, encrypted = true),
        ),
        history = HistoryConfig(),
        trash = TrashConfig(),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(apiKey)),
        database = DatabaseConfig(fieldKey = fieldKey),
        collections = listOf(patients),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.send(method: String, path: String, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(apiKey)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, build)
            "POST" -> post(path, build)
            "PATCH" -> patch(path, build)
            else -> delete(path, build)
        }
    }

    private fun raw(table: String, column: String): List<String> =
        db.query("SELECT \"$column\" AS v FROM \"$table\" ORDER BY \"id\"", emptyList()).mapNotNull { it["v"]?.toString() }

    @Test
    fun `sifreleme ve acma`() {
        val c = FieldCipher.of(fieldKey)
        val sealed = c.encrypt("12345678901")
        assertTrue(sealed.startsWith("enc:v1:${c.keyId}:"))
        assertNotEquals(sealed, c.encrypt("12345678901"), "her seferinde farklı IV")
        assertEquals("12345678901", c.decrypt(sealed))
        assertFailsWith<FieldCipherException> { FieldCipher.of(FieldCipher.generateKey()).decrypt(sealed) }
        val tampered = sealed.dropLast(2) + (if (sealed.last() == 'A') "BB" else "AA")
        assertFailsWith<FieldCipherException> { c.decrypt(tampered) }
        assertFailsWith<IllegalArgumentException> { FieldCipher.of("kisa") }
    }

    @Test
    fun `api acik, veritabani gecmis ve cop kutusu sifreli`() = testApplication {
        application { droidServerModule(config, db) }
        val base = "/api/collections/patients/records"
        val created = client.send("POST", base, """{"name":"Ayşe","tc":"12345678901","notes":{"alerji":["penisilin"]}}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val rec = json(created.bodyAsText())
        assertEquals("12345678901", rec.getValue("tc").jsonPrimitive.content)
        assertEquals("penisilin", rec.getValue("notes").jsonObject.getValue("alerji").jsonArray.single().jsonPrimitive.content)
        val id = rec.getValue("id").jsonPrimitive.content

        // Ham satırda okunabilir değer yok.
        val tc = raw("patients", "tc").single()
        assertTrue(tc.startsWith(FieldCipher.PREFIX), tc)
        assertFalse("penisilin" in raw("patients", "notes").single())
        assertEquals("Ayşe", raw("patients", "name").single(), "şifresiz alan olduğu gibi")

        assertEquals("12345678901", json(client.send("GET", "$base/$id").bodyAsText()).getValue("tc").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "$base?tc=12345678901").status, "şifreli alana göre filtre yok")
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "$base?sort=tc").status)
        assertTrue("şifreli" in client.send("GET", "/api/collections/patients/aggregate?max=tc").bodyAsText())

        client.send("PATCH", "$base/$id", """{"tc":"10987654321"}""")
        assertFalse(raw("_history", "data").any { "12345678901" in it || "10987654321" in it || "penisilin" in it }, "geçmişte de şifreli")
        val history = json(client.send("GET", "$base/$id/history").bodyAsText()).getValue("items").jsonArray.map { it.jsonObject.getValue("data").jsonObject }
        assertEquals(listOf("10987654321", "12345678901"), history.map { it.getValue("tc").jsonPrimitive.content }, "geçmiş API'de açık")
        val reverted = client.send("POST", "$base/$id/history/1/revert")
        assertEquals(HttpStatusCode.OK, reverted.status, reverted.bodyAsText())
        assertEquals("12345678901", json(reverted.bodyAsText()).getValue("tc").jsonPrimitive.content)
        assertTrue(raw("patients", "tc").single().startsWith(FieldCipher.PREFIX), "geri dönülen değer yeniden şifreli")

        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "$base/$id").status)
        assertFalse(raw("_trash", "data").any { "12345678901" in it }, "çöp kutusunda da şifreli")
        val trash = json(client.send("GET", "/api/collections/patients/trash").bodyAsText()).getValue("items").jsonArray.single().jsonObject
        assertEquals("12345678901", trash.getValue("data").jsonObject.getValue("tc").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/collections/patients/trash/$id/restore").status)
        assertEquals("12345678901", json(client.send("GET", "$base/$id").bodyAsText()).getValue("tc").jsonPrimitive.content)
        assertTrue(raw("patients", "tc").single().startsWith(FieldCipher.PREFIX))
    }

    @Test
    fun `sifreleme acilip kapatilinca mevcut degerler donusur`() = testApplication {
        val plain = config.copy(collections = listOf(patients.copy(fields = patients.fields.map { it.copy(encrypted = false) })))
        application { droidServerModule(plain, db) }
        client.send("POST", "/api/collections/patients/records", """{"name":"Ali","tc":"11111111111","notes":{"a":1}}""")
        client.send("PATCH", "/api/collections/patients/records/1", """{"tc":"22222222222"}""")
        assertEquals("22222222222", raw("patients", "tc").single())

        // Açılınca: satır ve geçmişteki kopyalar şifrelenir; tekrar çalıştırmak bir şey değiştirmez.
        val changed = FieldEncryption.sync(db, config, plain)
        assertTrue(changed >= 4, "satırda 2, geçmişte en az 2 değer: $changed")
        assertTrue(raw("patients", "tc").single().startsWith(FieldCipher.PREFIX))
        assertFalse(raw("_history", "data").any { "11111111111" in it || "22222222222" in it })
        assertEquals(0, FieldEncryption.sync(db, config, config))
        assertEquals(0, FieldEncryption.sync(db, config, null), "zaten şifreli değerler yeniden şifrelenmez")

        // Kapatılınca açılır.
        assertTrue(FieldEncryption.sync(db, plain, config) >= 4)
        assertEquals("22222222222", raw("patients", "tc").single())
        assertTrue(raw("_history", "data").any { "11111111111" in it })
        assertEquals("""{"a":1}""", raw("patients", "notes").single().replace(" ", ""))
    }

    @Test
    fun `dogrulama, anahtar uretimi ve yedek anahtar kimligi`() {
        val errors = { c: ServerConfig -> ConfigValidator.validateDetailed(c).map { it.path }.toSet() }
        assertEquals(emptySet(), errors(config))
        val bad = patients.copy(
            fields = listOf(
                FieldConfig("name", FieldType.TEXT),
                FieldConfig("age", FieldType.INTEGER, encrypted = true),
                FieldConfig("tc", FieldType.TEXT, encrypted = true, unique = true),
                FieldConfig("code", FieldType.TEXT, encrypted = true),
                FieldConfig("label", FieldType.TEXT, computed = "upper(code)"),
            ),
            search = listOf("code"),
        )
        assertEquals(
            setOf(
                "collections[0].fields[1].encrypted", "collections[0].fields[2].encrypted",
                "collections[0].fields[4].computed", "collections[0].search[0]",
            ),
            errors(config.copy(collections = listOf(bad))),
        )
        assertEquals(setOf("database.fieldKey"), errors(config.copy(database = DatabaseConfig())))
        assertEquals(setOf("database.fieldKey"), errors(config.copy(database = DatabaseConfig(fieldKey = "kisa"))))

        // Panelden şifreli alan eklenince anahtar üretilir; varsa dokunulmaz.
        val text = ConfigJson.encodeToString(ServerConfig.serializer(), config.copy(database = DatabaseConfig()))
        val withKey = ServerConfig.fromJson(FieldEncryption.ensureKey(text))
        assertTrue(runCatching { FieldCipher.of(withKey.database.fieldKey!!) }.isSuccess)
        val again = FieldEncryption.ensureKey(ConfigJson.encodeToString(ServerConfig.serializer(), withKey))
        assertEquals(withKey.database.fieldKey, ServerConfig.fromJson(again).database.fieldKey)

        val manifest = BackupArchive.create(ByteArrayOutputStream(), db, config, "{}", emptyMap())
        assertEquals(FieldCipher.of(fieldKey).keyId, manifest.fieldKeyId)
        val noEncryption = config.copy(collections = listOf(patients.copy(fields = patients.fields.map { it.copy(encrypted = false) })))
        assertEquals(null, BackupArchive.create(ByteArrayOutputStream(), db, noEncryption, "{}", emptyMap()).fieldKeyId)
    }
}
