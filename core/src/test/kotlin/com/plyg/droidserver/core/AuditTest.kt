package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.config.ApiKeyConfig
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TrashConfig
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 13.1: denetim kaydı. */
class AuditTest {
    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig("notes", listOf(FieldConfig("text", FieldType.TEXT)), trash = TrashConfig())
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, sendVerification = false)),
        collections = listOf(notes),
    )
    private lateinit var db: SqlDriver
    private val dir: File = Files.createTempDirectory("ds-audit").toFile()

    @BeforeTest
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @AfterTest
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `yonetici islemleri kaydedilir`() = testApplication {
        val audit = AuditLog(File(dir, "audit.jsonl"))
        application { droidServerModule(config, db, audit = audit) }
        suspend fun post(path: String, body: String) = client.post(path) { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body) }

        val user = Json.parseToJsonElement(post("/api/auth/users", """{"email":"ali@example.com","password":"parola-1234"}""").bodyAsText()).jsonObject
        val id = user.getValue("id").jsonPrimitive.content
        client.patch("/api/auth/users/$id") {
            bearerAuth(key); header("X-Forwarded-For", "203.0.113.9"); contentType(ContentType.Application.Json); setBody("""{"role":"admin","password":"yeni-parola-99"}""")
        }
        client.delete("/api/auth/users/$id") { bearerAuth(key) }
        val note = Json.parseToJsonElement(post("/api/collections/notes/records", """{"text":"x"}""").bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
        client.delete("/api/collections/notes/records/$note") { bearerAuth(key) }
        client.delete("/api/collections/notes/trash/$note") { bearerAuth(key) }

        val items = Json.parseToJsonElement(client.get("/api/_audit") { bearerAuth(key) }.bodyAsText()).jsonObject.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("record.purge", "user.delete", "user.update", "user.create"), items.map { it.getValue("action").jsonPrimitive.content })
        val update = items[2]
        assertEquals("admin", update.getValue("actor").jsonPrimitive.content)
        assertEquals("ali@example.com", update.getValue("target").jsonPrimitive.content)
        assertEquals("rol: admin; parola sıfırlandı", update.getValue("detail").jsonPrimitive.content, "parolanın kendisi yazılmaz")
        assertEquals("notes#$note", items[0].getValue("target").jsonPrimitive.content)
        assertTrue("yeni-parola" !in File(dir, "audit.jsonl").readText())

        val filtered = Json.parseToJsonElement(client.get("/api/_audit?action=user.&q=ali") { bearerAuth(key) }.bodyAsText()).jsonObject.getValue("items").jsonArray
        assertEquals(3, filtered.size)
        val csv = client.get("/api/_audit?format=csv") { bearerAuth(key) }.bodyAsText()
        assertTrue(csv.startsWith("zaman,kim,islem,hedef,ayrinti,ip") && "user.create" in csv, csv)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_audit").status)
        // Dosyada kalır: yeni bir nesne aynı kaydı okur.
        assertEquals(4, AuditLog(File(dir, "audit.jsonl")).list().size)
    }

    @Test
    fun `saklama suresi, csv guvenligi ve degisiklik ozeti`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val log = AuditLog(File(dir, "a.jsonl")) { now }
        log.keepDays = 30
        log.record("admin", "user.create", "=HYPERLINK(\"x\")", "a,b")
        now = now.plus(Duration.ofDays(40))
        log.record("admin", "deploy")
        log.trim()
        assertEquals(listOf("deploy"), log.list().map { it.action })
        val csv = AuditLog.csv(listOf(com.plyg.droidserver.core.audit.AuditEntry("t", "admin", "x", "=SUM(1)", "a,\"b\"")))
        assertTrue("'=SUM(1)" in csv && "\"a,\"\"b\"\"\"" in csv, csv)

        val next = config.copy(
            collections = listOf(notes.copy(fields = notes.fields + FieldConfig("n", FieldType.INTEGER)), CollectionConfig("tags", listOf(FieldConfig("t", FieldType.TEXT)))),
            auth = config.auth.copy(keys = listOf(ApiKeyConfig("rapor", "a".repeat(64), "dsk_abcd1234", readOnly = true))),
        )
        val changes = AuditLog.changes(config, next)
        assertTrue("koleksiyon eklendi: tags" in changes && "notes: alan eklendi: n" in changes && "anahtar oluşturuldu: rapor" in changes, changes.toString())
        assertTrue(AuditLog.changes(config, config).isEmpty())
        assertTrue(ConfigValidator.validate(config.copy(audit = com.plyg.droidserver.core.config.AuditConfig(1))).any { "Denetim" in it })
    }
}
