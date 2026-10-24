package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.api.issueJwt
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CRUD, kimlik doğrulama ve migration her iki lehçede aynı davranmalı: SQLite ve PostgreSQL. */
@RunWith(Parameterized::class)
class DialectContractTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val todos = CollectionConfig(
        name = "todos",
        fields = listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("done", FieldType.BOOLEAN, default = JsonPrimitive(false)),
            FieldConfig("priority", FieldType.INTEGER),
            FieldConfig("slug", FieldType.TEXT, unique = true),
            FieldConfig("meta", FieldType.JSON),
        ),
    )

    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
    }

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `migration tablo ve sutun ekler, veri silmez`() {
        val r1 = SchemaMigrator(db).migrate(listOf(todos.copy(fields = todos.fields.take(1))))
        assertEquals(listOf("todos"), r1.createdTables)
        val r2 = SchemaMigrator(db).migrate(listOf(todos))
        assertEquals(4, r2.addedColumns.size)
        val r3 = SchemaMigrator(db).migrate(listOf(todos.copy(fields = todos.fields.drop(1))))
        assertEquals(listOf("todos.title"), r3.orphanColumns)
    }

    @Test
    fun `crud uclari uctan uca calisir`() = testApplication {
        SchemaMigrator(db).migrate(listOf(todos))
        application { droidServerModule(ServerConfig(collections = listOf(todos)), db) }
        val base = "/api/collections/todos/records"

        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)

        val created = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Süt al","priority":2,"slug":"sut","meta":{"etiket":["market"]}}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val obj = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("false", obj["done"]!!.jsonPrimitive.content) // varsayılan uygulandı
        assertEquals("market", obj["meta"]!!.jsonObject["etiket"]!!.jsonArray[0].jsonPrimitive.content)

        // zorunlu alan + yanlış tip -> 400
        val invalid = client.post(base) { contentType(ContentType.Application.Json); setBody("""{"priority":"yüksek"}""") }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertTrue(invalid.bodyAsText().contains("title"))

        // benzersiz alan çakışması -> 409
        val dup = client.post(base) { contentType(ContentType.Application.Json); setBody("""{"title":"x","slug":"sut"}""") }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        client.post(base) { contentType(ContentType.Application.Json); setBody("""{"title":"Ekmek","priority":5,"done":true}""") }

        val patched = client.patch("$base/1") { contentType(ContentType.Application.Json); setBody("""{"done":true}""") }
        assertTrue(patched.bodyAsText().contains("\"done\": true"), patched.bodyAsText())

        val list = Json.parseToJsonElement(client.get("$base?sort=-priority&done=true").bodyAsText()).jsonObject
        assertEquals(2, list["totalItems"]!!.jsonPrimitive.content.toInt())
        assertEquals("Ekmek", list["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NoContent, client.delete("$base/1").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/1").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$base?sort=gizli").status)
    }

    @Test
    fun `tirnaksiz JSON degerleri 400 ile reddedilir, gecerli JSON aynen saklanir`() = testApplication {
        SchemaMigrator(db).migrate(listOf(todos))
        application { droidServerModule(ServerConfig(collections = listOf(todos)), db) }
        val base = "/api/collections/todos/records"
        suspend fun post(body: String) = client.post(base) { contentType(ContentType.Application.Json); setBody(body) }

        // kotlinx bunları değer sayar; standart JSON değiller.
        for (bad in listOf(
            """{"title":"a","meta":abc}""",
            """{"title":"a","meta":{"k":abc}}""",
            """{"title":"a","meta":[1,abc]}""",
            """{"title":"a","priority":NaN}""",
            """{"title":abc}""",
        )) {
            val r = post(bad)
            assertEquals(HttpStatusCode.BadRequest, r.status, "$bad -> ${r.bodyAsText()}")
            assertTrue(r.bodyAsText().contains("tırnak"), r.bodyAsText())
        }
        assertEquals(0, Json.parseToJsonElement(client.get(base).bodyAsText()).jsonObject["totalItems"]!!.jsonPrimitive.content.toInt())

        for (meta in listOf("""{"k":[1,2.5,-3,true,false,null,"x"]}""", "[]", "\"metin\"", "42", "true")) {
            val r = post("""{"title":"a","meta":$meta}""")
            assertEquals(HttpStatusCode.Created, r.status, "$meta -> ${r.bodyAsText()}")
            assertEquals(Json.parseToJsonElement(meta), Json.parseToJsonElement(r.bodyAsText()).jsonObject["meta"])
        }
    }

    @Test
    fun `api key ve jwt korumasi`() = testApplication {
        val publicNotes = CollectionConfig("notes", listOf(FieldConfig("body", FieldType.TEXT)), rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC))
        SchemaMigrator(db).migrate(listOf(todos, publicNotes))
        val cfg = ServerConfig(
            auth = AuthConfig(mode = AuthMode.JWT, jwtSecret = "x".repeat(40)),
            collections = listOf(todos, publicNotes),
        )
        application { droidServerModule(cfg, db) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/collections/todos/records").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records").status) // publicRead
        val write = client.post("/api/collections/notes/records") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, write.status) // yazma her zaman korumalı

        val token = issueJwt(cfg, "test-istemci")
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/todos/records") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/collections/todos/records") { bearerAuth("$token-bozuk") }.status)
    }

    @Test
    fun `tum alan tipleri gidis donus ve filtre`() = testApplication {
        val events = CollectionConfig(
            "events",
            listOf(
                FieldConfig("name", FieldType.TEXT),
                FieldConfig("count", FieldType.INTEGER),
                FieldConfig("ratio", FieldType.REAL),
                FieldConfig("active", FieldType.BOOLEAN),
                FieldConfig("at", FieldType.DATETIME),
                FieldConfig("data", FieldType.JSON),
            ),
        )
        SchemaMigrator(db).migrate(listOf(events))
        application { droidServerModule(ServerConfig(collections = listOf(events)), db) }
        val base = "/api/collections/events/records"
        val body = """{"name":"açılış","count":9007199254740993,"ratio":0.25,"active":true,"at":"2026-10-13T10:15:30Z","data":{"a":[1,2]}}"""
        val created = client.post(base) { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        client.post(base) { contentType(ContentType.Application.Json); setBody("""{"name":"boş"}""") }

        val item = Json.parseToJsonElement(client.get("$base/1").bodyAsText()).jsonObject
        assertEquals("açılış", item["name"]!!.jsonPrimitive.content)
        assertEquals("9007199254740993", item["count"]!!.jsonPrimitive.content)
        assertEquals(0.25, item["ratio"]!!.jsonPrimitive.content.toDouble())
        assertEquals("true", item["active"]!!.jsonPrimitive.content)
        assertEquals("2026-10-13T10:15:30Z", item["at"]!!.jsonPrimitive.content)
        assertEquals("2", item["data"]!!.jsonObject["a"]!!.jsonArray[1].jsonPrimitive.content)
        assertTrue(item["created_at"]!!.jsonPrimitive.content.endsWith("Z"))

        suspend fun total(query: String) =
            Json.parseToJsonElement(client.get("$base?$query").bodyAsText()).jsonObject["totalItems"]!!.jsonPrimitive.content.toInt()
        // datetime, boolean ve tam sayı filtreleri iki lehçede de aynı çalışır
        assertEquals(1, total("at=2026-10-13T10:15:30Z"))
        assertEquals(1, total("active=true"))
        assertEquals(1, total("count=9007199254740993"))
        assertEquals(0, total("active=false"))
    }

    @Test
    fun `filtre operatorleri ve alan secimi`() {
        val tasks = CollectionConfig(
            name = "tasks",
            fields = listOf(
                FieldConfig("title", FieldType.TEXT),
                FieldConfig("done", FieldType.BOOLEAN),
                FieldConfig("priority", FieldType.INTEGER),
                FieldConfig("due", FieldType.DATETIME),
            ),
        )
        SchemaMigrator(db).migrate(listOf(tasks))
        val svc = com.plyg.droidserver.core.api.RecordService(db, tasks)
        fun add(title: String, done: Boolean, priority: Int?, due: String?) = svc.create(
            kotlinx.serialization.json.buildJsonObject {
                put("title", JsonPrimitive(title)); put("done", JsonPrimitive(done))
                put("priority", priority?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                put("due", due?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            },
        )
        add("Süt al", false, 3, "2026-10-13T10:00:00Z") // 1
        add("Ekmek AL", true, 1, "2026-10-13T10:00:00.500Z") // 2: kesirli saniye
        add("%100 indirim_", false, null, null) // 3
        add("Yumurta", true, 5, "2026-10-14T08:00:00Z") // 4

        fun ids(vararg p: Pair<String, String>, sort: String? = null) =
            svc.list(1, 50, sort, p.toList()).items.map { it["id"]!!.jsonPrimitive.content.toInt() }

        assertEquals(listOf(2, 4), ids("done" to "true"))
        assertEquals(listOf(2, 4), ids("done[eq]" to "true"))
        assertEquals(listOf(1, 3), ids("done[ne]" to "true"))
        assertEquals(listOf(1, 2, 3), ids("priority[ne]" to "5"), "boş değerler ne'ye dahil")
        assertEquals(listOf(1, 4), ids("priority[gt]" to "1"))
        assertEquals(listOf(1, 2, 4), ids("priority[gte]" to "1"))
        assertEquals(listOf(2), ids("priority[lt]" to "3"))
        assertEquals(listOf(1, 2), ids("priority[lte]" to "3"))
        assertEquals(listOf(1, 4), ids("priority[in]" to "3,5,99"))
        assertEquals(listOf(3), ids("priority[null]" to "true"))
        assertEquals(listOf(1, 2, 4), ids("priority[null]" to "false"))
        assertEquals(listOf(1, 2), ids("title[like]" to "al"), "ASCII büyük/küçük harf duyarsız")
        assertEquals(listOf(1, 2), ids("title[like]" to "AL"))
        assertEquals(listOf(3), ids("title[like]" to "%100"), "% harfiyen aranır")
        assertEquals(listOf(3), ids("title[like]" to "_"), "_ harfiyen aranır")
        assertEquals(emptyList(), ids("title[like]" to "xyz"))
        assertEquals(listOf(1, 4), ids("title[in]" to "Süt al,Yumurta"))
        // Zaman: kesir hanesi farklı değerler doğru karşılaştırılır.
        assertEquals(listOf(2, 4), ids("due[gt]" to "2026-10-13T10:00:00Z"))
        assertEquals(listOf(1), ids("due[lt]" to "2026-10-13T10:00:00.250Z"))
        assertEquals(listOf(1), ids("due" to "2026-10-13T10:00:00.000Z"))
        assertEquals(listOf(4, 2, 1), ids("due[null]" to "false", sort = "-due"))
        assertEquals(listOf(4), ids("done" to "true", "priority[gt]" to "1"), "koşullar AND ile birleşir")
        assertEquals(listOf(2, 3), ids("id[in]" to "2,3"))
        assertEquals(listOf(4), ids("id[gt]" to "3"))

        val projected = svc.list(1, 50, null, listOf("id" to "1"), fields = listOf("id", "title")).items.single()
        assertEquals(setOf("id", "title"), projected.keys)
        assertEquals(setOf("id", "done", "created_at"), svc.get(2, listOf("id", "done", "created_at")).keys)
    }

    /** Sorgu sayısını sayan sürücü (expand'in kayıt sayısından bağımsız sorgu attığını doğrulamak için). */
    private class CountingDriver(private val inner: SqlDriver) : SqlDriver by inner {
        var queries = 0
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = inner.query(sql, args).also { queries++ }
    }

    @Test
    fun `iliskiler - varlik kontrolu, expand, onDelete davranislari, filtre`() {
        fun rel(name: String, target: String, onDelete: com.plyg.droidserver.core.config.OnDelete? = null, required: Boolean = false) =
            FieldConfig(name, FieldType.RELATION, required = required, collection = target, onDelete = onDelete)
        val authors = CollectionConfig("authors", listOf(FieldConfig("name", FieldType.TEXT)))
        val posts = CollectionConfig(
            "posts",
            listOf(FieldConfig("title", FieldType.TEXT), rel("author", "authors", required = true), rel("editor", "authors")),
        )
        val comments = CollectionConfig("comments", listOf(FieldConfig("text", FieldType.TEXT), rel("post", "posts", com.plyg.droidserver.core.config.OnDelete.CASCADE)))
        val cats = CollectionConfig("cats", listOf(FieldConfig("name", FieldType.TEXT), rel("parent", "cats", com.plyg.droidserver.core.config.OnDelete.CASCADE)))
        val all = listOf(authors, posts, comments, cats)
        assertTrue(com.plyg.droidserver.core.config.ConfigValidator.validate(ServerConfig(collections = all)).isEmpty())
        SchemaMigrator(db).migrate(all)
        val idx = db.dialect.listIndexes(db, "posts")
        assertTrue(SchemaMigrator.relationIndexName(com.plyg.droidserver.core.db.ColumnRef("posts", "author")) in idx, "ilişki sütununa arama indeksi: $idx")

        fun svc(c: CollectionConfig, d: SqlDriver = db) = com.plyg.droidserver.core.api.RecordService(d, c, collections = all)
        fun obj(vararg kv: Pair<String, Any?>) = kotlinx.serialization.json.buildJsonObject {
            kv.forEach { (k, v) ->
                put(k, when (v) { null -> kotlinx.serialization.json.JsonNull; is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) })
            }
        }
        fun id(o: kotlinx.serialization.json.JsonObject) = o["id"]!!.jsonPrimitive.content.toLong()

        val ali = id(svc(authors).create(obj("name" to "Ali")))
        val ayse = id(svc(authors).create(obj("name" to "Ayşe")))

        // Olmayan hedef ve yanlış tip reddedilir; hiçbir şey yazılmaz.
        val missing = kotlin.test.assertFailsWith<com.plyg.droidserver.core.api.ApiException> { svc(posts).create(obj("title" to "x", "author" to 999)) }
        assertEquals(400, missing.status)
        assertTrue(missing.details.getValue("author").contains("999 kimlikli kayıt yok"), missing.details.toString())
        val asText = kotlin.test.assertFailsWith<com.plyg.droidserver.core.api.ApiException> { svc(posts).create(obj("title" to "x", "author" to "1")) }
        assertTrue(asText.details.getValue("author").contains("kimliği"), asText.details.toString())
        assertEquals(0, svc(posts).list(1, 10, null, emptyList()).totalItems)

        val p1 = id(svc(posts).create(obj("title" to "Bir", "author" to ali, "editor" to ayse)))
        val p2 = id(svc(posts).create(obj("title" to "İki", "author" to ali)))
        val p3 = id(svc(posts).create(obj("title" to "Üç", "author" to ayse)))
        svc(comments).create(obj("text" to "güzel", "post" to p1))
        svc(comments).create(obj("text" to "katılıyorum", "post" to p1))

        // expand: hedef kayıt gömülür; boş ilişki null.
        val listed = svc(posts).list(1, 10, "id", emptyList(), expand = mapOf("author" to null, "editor" to null)).items
        val first = listed.first().getValue("expand").jsonObject
        assertEquals("Ali", first.getValue("author").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("Ayşe", first.getValue("editor").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, listed[1].getValue("expand").jsonObject.getValue("editor"))
        val one = svc(posts).get(p3, fields = listOf("id", "title"), expand = mapOf("author" to null))
        assertEquals("Ayşe", one.getValue("expand").jsonObject.getValue("author").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue("author" !in one.keys, "seçilmeyen alan yanıtta yok ama genişletilir")
        kotlin.test.assertFailsWith<com.plyg.droidserver.core.api.ApiException> { svc(posts).list(1, 10, null, emptyList(), expand = mapOf("title" to null)) }

        // Sorgu sayısı kayıt sayısına bağlı değil (N+1 yok).
        fun queriesFor(perPage: Int): Int {
            val counting = CountingDriver(db)
            svc(posts, counting).list(1, perPage, null, emptyList(), expand = mapOf("author" to null, "editor" to null))
            return counting.queries
        }
        assertEquals(queriesFor(1), queriesFor(3))
        assertEquals(4, queriesFor(3), "sayım + kayıtlar + ilişki başına bir sorgu")

        // İlişki alanında filtre.
        fun postIds(vararg p: Pair<String, String>) = svc(posts).list(1, 10, "id", p.toList()).items.map(::id)
        assertEquals(listOf(p1, p2), postIds("author" to "$ali"))
        assertEquals(listOf(p2, p3), postIds("editor[null]" to "true"))
        assertEquals(listOf(p1, p3), postIds("author[in]" to "$ali,$ayse,999", "id[ne]" to "$p2"))
        kotlin.test.assertFailsWith<com.plyg.droidserver.core.api.ApiException> { postIds("author[gt]" to "1") }

        // restrict: Ali'nin yazıları varken silinemez, hiçbir şey değişmez.
        val blocked = kotlin.test.assertFailsWith<com.plyg.droidserver.core.api.ApiException> { svc(authors).delete(ali) }
        assertEquals(409, blocked.status)
        assertTrue(blocked.message!!.contains("posts.author") && blocked.message!!.contains("2 kayıt"), blocked.message)
        assertEquals(2, svc(authors).list(1, 10, null, emptyList()).totalItems)

        // cascade: yazı silinince yorumları da silinir.
        svc(posts).delete(p1)
        assertEquals(0, svc(comments).list(1, 10, null, emptyList()).totalItems)
        // Ayşe hâlâ p3'ün yazarı (restrict): silinemez. p3 silinince silinebilir; editör olduğu yazı kalmadı.
        kotlin.test.assertFailsWith<com.plyg.droidserver.core.api.ApiException> { svc(authors).delete(ayse) }
        svc(posts).delete(p3)
        val p4 = id(svc(posts).create(obj("title" to "Dört", "author" to ali, "editor" to ayse)))
        // set_null: editör silinebilir mi? Ayşe p4'ün yalnızca editörü -> silinir, alan boşalır.
        svc(authors).delete(ayse)
        assertEquals(kotlinx.serialization.json.JsonNull, svc(posts).get(p4)["editor"])

        // Kendine bağlanan ağaç: kök silinince tüm alt dallar gider; döngü sonsuz döngüye girmez.
        val root = id(svc(cats).create(obj("name" to "kök")))
        val child = id(svc(cats).create(obj("name" to "dal", "parent" to root)))
        svc(cats).create(obj("name" to "yaprak", "parent" to child))
        val other = id(svc(cats).create(obj("name" to "başka")))
        svc(cats).delete(root)
        assertEquals(listOf(other), svc(cats).list(1, 10, null, emptyList()).items.map(::id))
        val a = id(svc(cats).create(obj("name" to "a")))
        val b = id(svc(cats).create(obj("name" to "b", "parent" to a)))
        svc(cats).update(a, obj("parent" to b))
        svc(cats).delete(a)
        assertEquals(listOf(other), svc(cats).list(1, 10, null, emptyList()).items.map(::id))
    }
}
