package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TextFormat
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlConsole
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Faz 17.10: salt okunur SQL konsolu ve örnek veri. */
class DevToolsTest {
    private val key = "yonetici-anahtari-1234567890"
    private val projects = CollectionConfig("projects", listOf(FieldConfig("name", FieldType.TEXT, required = true, unique = true)))
    private val tasks = CollectionConfig(
        "tasks",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true, maxLength = 40),
            FieldConfig("email", FieldType.TEXT, format = TextFormat.EMAIL),
            FieldConfig("status", FieldType.SELECT, required = true, options = listOf("açık", "bitti")),
            FieldConfig("tags", FieldType.MULTISELECT, options = listOf("ev", "iş", "acil")),
            FieldConfig("points", FieldType.INTEGER, min = 1.0, max = 5.0),
            FieldConfig("done", FieldType.BOOLEAN),
            FieldConfig("project", FieldType.RELATION, required = true, collection = "projects"),
        ),
    )
    private val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(projects, tasks))

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `sql denetimi`() {
        assertEquals("SELECT 1", SqlConsole.check("SELECT 1;"))
        SqlConsole.check("with x as (select 1 as a) select a from x")
        SqlConsole.check("SELECT 'drop table; delete' AS metin -- yorum; içinde\\n")
        SqlConsole.check("SELECT /* insert */ \"title\" FROM \"tasks\"")
        for ((sql, code) in listOf(
            "SELECT 1; DELETE FROM tasks" to 400, "DELETE FROM tasks" to 400, "PRAGMA table_info(tasks)" to 400,
            "WITH x AS (DELETE FROM tasks RETURNING *) SELECT * FROM x" to 400, "SELECT * INTO yeni FROM tasks" to 400,
            "SELECT * FROM _ds_meta" to 403, "SELECT \"password_hash\" FROM _users" to 403, "SELECT pg_read_file('/etc/passwd')" to 403,
            "SELECT 'kapanmamış" to 400, "" to 400,
        )) {
            val e = assertFailsWith<ApiException>(sql) { SqlConsole.check(sql) }
            assertEquals(code, e.status, sql)
        }
    }

    /** Ayrıştırıcı atlansa da veritabanı yazmayı reddeder. */
    @Test
    fun `salt okunur baglanti`() {
        for (d in Dialect.entries) {
            val db = TestDb.create(d).open()
            try {
                SchemaMigrator(db).migrate(config.schemaCollections())
                db.execute("INSERT INTO \"projects\" (\"name\", \"created_at\", \"updated_at\") VALUES ('a', '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')")
                val r = db.readOnly("SELECT \"name\" FROM \"projects\"", 10, 5)
                assertEquals(listOf("name"), r.columns, d.name)
                assertEquals(listOf(listOf<Any?>("a")), r.rows, d.name)
                runCatching { db.readOnly("DELETE FROM \"projects\"", 10, 5) }
                assertEquals(1, db.query("SELECT COUNT(*) AS n FROM \"projects\"").single()["n"].toString().toInt(), "$d: silinmemeli")
                db.execute("INSERT INTO \"projects\" (\"name\", \"created_at\", \"updated_at\") VALUES ('b', '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')")
                assertEquals(2, db.query("SELECT COUNT(*) AS n FROM \"projects\"").single()["n"].toString().toInt(), "$d: sonra yazma çalışır")
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun `sql ucu ve ornek veri`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        suspend fun post(path: String, body: String, auth: String? = key) = client.post(path) { auth?.let(::bearerAuth); contentType(ContentType.Application.Json); setBody(body) }

        // Örnek veri: zorunlu ilişkinin hedefi boşken üretilemez.
        val noTarget = post("/api/_sample/tasks", """{"count":5}""")
        assertEquals(HttpStatusCode.BadRequest, noTarget.status)
        assertTrue("projects" in noTarget.bodyAsText())
        assertEquals(HttpStatusCode.Created, post("/api/_sample/projects", """{"count":3}""").status)
        assertEquals(HttpStatusCode.Created, post("/api/_sample/projects", """{"count":3}""").status, "benzersiz alan ikinci üretimde de çakışmaz")
        assertEquals(HttpStatusCode.Created, post("/api/_sample/tasks", """{"count":40}""").status)
        val items = json(client.get("/api/collections/tasks/records?perPage=100") { bearerAuth(key) }.bodyAsText()).getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(40, items.size)
        val projectIds = json(client.get("/api/collections/projects/records?perPage=100") { bearerAuth(key) }.bodyAsText()).getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        for (t in items) {
            assertTrue(t.getValue("status").jsonPrimitive.content in setOf("açık", "bitti"))
            assertTrue(t.getValue("points").jsonPrimitive.content.toInt() in 1..5)
            assertTrue(t.getValue("title").jsonPrimitive.content.length <= 40)
            assertTrue("@" in t.getValue("email").jsonPrimitive.content)
            assertTrue(t.getValue("project").jsonPrimitive.content in projectIds)
        }
        assertEquals(HttpStatusCode.BadRequest, post("/api/_sample/tasks", """{"count":0}""").status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/_sample/tasks", """{"count":1}""", auth = null).status)

        // SQL konsolu.
        val r = json(post("/api/_sql", """{"query":"SELECT status, COUNT(*) AS n FROM tasks GROUP BY status ORDER BY status","limit":1}""").bodyAsText())
        assertEquals(listOf("status", "n"), r.getValue("columns").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, r.getValue("rows").jsonArray.size)
        assertEquals("true", r.getValue("truncated").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, post("/api/_sql", """{"query":"DELETE FROM tasks"}""").status)
        assertEquals(HttpStatusCode.Forbidden, post("/api/_sql", """{"query":"SELECT * FROM _ds_meta"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/api/_sql", """{"query":"SELECT * FROM yok"}""").status, "sorgu hatası")
        assertEquals(HttpStatusCode.Unauthorized, post("/api/_sql", """{"query":"SELECT 1"}""", auth = null).status)
        db.close()
    }
}
