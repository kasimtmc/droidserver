package dev.droidserver.core

import dev.droidserver.core.api.ApiExamples
import dev.droidserver.core.api.ApiProbe
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ChangeWarnings
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.CollectionTemplate
import dev.droidserver.core.config.ComponentConfig
import dev.droidserver.core.config.ConfigDefaults
import dev.droidserver.core.config.ConfigDiff
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigSummary
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.CorsConfig
import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldDefaults
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.accessToken
import dev.droidserver.core.config.withAccess
import dev.droidserver.core.config.withAccessToken
import dev.droidserver.core.db.JdbcSqlDriver
import dev.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.JsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WizardSupportTest {

    @Test
    fun `dogrulama hatalari alan yoluna eslenir`() {
        val cfg = ServerConfig(
            name = " ",
            server = HttpConfig(port = 80, cors = CorsConfig(enabled = true, allowedHosts = emptyList())),
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("x".repeat(20), "kisa")),
            database = DatabaseConfig(file = "../disari.db"),
            collections = listOf(
                CollectionTemplate.TODOS.create(),
                CollectionConfig(
                    "notes",
                    listOf(
                        FieldConfig("body", FieldType.TEXT),
                        FieldConfig("Bad", FieldType.TEXT),
                        FieldConfig("views", FieldType.INTEGER, default = JsonPrimitive("çok")),
                    ),
                ),
            ),
            components = listOf(ComponentConfig("x y")),
        )
        val paths = ConfigValidator.validateDetailed(cfg).map { it.path }
        assertEquals(
            listOf(
                "name", "server.port", "server.cors.allowedHosts", "auth.apiKeys[1]", "database.file",
                "collections[1].fields[1].name", "collections[1].fields[2].default", "components[0].id",
            ),
            paths,
        )
        assertEquals(paths.size, ConfigValidator.validate(cfg).size)
    }

    @Test
    fun `varsayilan degerler tipe gore cozulur`() {
        assertEquals(JsonPrimitive(42), FieldDefaults.parse(FieldType.INTEGER, " 42 "))
        assertEquals(JsonPrimitive(true), FieldDefaults.parse(FieldType.BOOLEAN, "true"))
        assertEquals("2026-10-13T00:00:00Z", FieldDefaults.parse(FieldType.DATETIME, "2026-10-13T00:00:00Z")!!.let(FieldDefaults::display))
        assertNull(FieldDefaults.parse(FieldType.TEXT, ""))
        assertFailsWith<IllegalArgumentException> { FieldDefaults.parse(FieldType.REAL, "bir") }
        assertFailsWith<IllegalArgumentException> { FieldDefaults.parse(FieldType.JSON, "{bozuk") }
        assertEquals("tam sayı bekleniyor", FieldDefaults.problem(FieldType.INTEGER, JsonPrimitive("5")))
        assertNull(FieldDefaults.problem(FieldType.JSON, JsonPrimitive("her şey olur")))
    }

    @Test
    fun `sablonlar gecerli konfigurasyon uretir`() {
        for (t in CollectionTemplate.entries - CollectionTemplate.EMPTY) {
            val errors = ConfigValidator.validate(ServerConfig(collections = listOf(t.create())))
            assertTrue(errors.isEmpty(), "${t.title}: $errors")
        }
        // Boş şablon alan eklenene kadar geçersiz: sihirbaz bu adımda ilerlemeyi engeller.
        val empty = CollectionTemplate.EMPTY.create()
        assertEquals(listOf("collections[0].fields"), ConfigValidator.validateDetailed(ServerConfig(collections = listOf(empty))).map { it.path })
        val withField = empty.copy(fields = listOf(FieldConfig("title", FieldType.TEXT)))
        assertTrue(ConfigValidator.validate(ServerConfig(collections = listOf(withField))).isEmpty())

        assertTrue(ConfigDefaults.generateApiKey().length >= 16)
        assertTrue(ConfigDefaults.generateJwtSecret().length >= 32)
    }

    @Test
    fun `erisim modu bilesenlerden turetilir, token referansla saklanir`() {
        val local = ServerConfig(auth = AuthConfig(AuthMode.NONE))
        assertEquals(AccessMode.LOCAL, AccessMode.of(local))
        assertTrue(ConfigSummary.securityWarnings(local).single().startsWith("Kimlik doğrulama kapalı: yerel ağ"))

        val tunnel = local.withAccessToken(AccessMode.CLOUDFLARE, "secret:tunnel_token_1")
        assertEquals(AccessMode.CLOUDFLARE, AccessMode.of(tunnel))
        assertEquals("secret:tunnel_token_1", tunnel.accessToken(AccessMode.CLOUDFLARE))
        assertTrue(ConfigSummary.securityWarnings(tunnel).single().contains("internete açık"))

        // Yerel ağa dönünce bileşen silinmez, kapatılır; token korunur.
        val back = tunnel.withAccess(AccessMode.LOCAL)
        assertEquals(AccessMode.LOCAL, AccessMode.of(back))
        assertEquals(false, back.components.single().enabled)
        assertEquals("secret:tunnel_token_1", back.withAccess(AccessMode.CLOUDFLARE).accessToken(AccessMode.CLOUDFLARE))

        // Bileşen ortamındaki referanslar da deploy anında çözülür.
        val resolved = ConfigSecrets.resolve(tunnel, mapOf("tunnel_token_1" to "gercek-token")::get)
        assertEquals("gercek-token", resolved.components.single().env["TUNNEL_TOKEN"])

        val summary = ConfigSummary.lines(tunnel).associate { it.label to it.value }
        assertEquals("Cloudflare Tunnel", summary["Erişim"])
        assertEquals("Yok", summary["Koleksiyonlar"])
    }

    @Test
    fun `gecmis farki ve veri kaybi uyarilari`() {
        val old = ServerConfig(collections = listOf(CollectionTemplate.TODOS.create()))
        val todos = old.collections[0]
        val draftTodos = todos.copy(
            fields = todos.fields.filterNot { it.name == "tags" }
                .map { if (it.name == "due") it.copy(type = FieldType.TEXT) else it },
        )
        val new = old.copy(name = "yeni", collections = listOf(draftTodos))

        val diff = ConfigDiff.configs(old.toJson(), new.toJson())
        assertTrue(diff.any { it.kind == ConfigDiff.Kind.REMOVED && it.text.contains("\"droidserver\"") })
        assertTrue(diff.any { it.kind == ConfigDiff.Kind.ADDED && it.text.contains("\"yeni\"") })
        assertEquals(old.toJson().lines().size, diff.count { it.kind != ConfigDiff.Kind.ADDED })
        val hunks = ConfigDiff.hunks(diff)
        assertTrue(hunks.size < diff.size && null in hunks, "değişmeyen uzun bölümler atlanmalı")
        assertEquals(diff.count { it.kind != ConfigDiff.Kind.SAME }, hunks.count { it != null && it.kind != ConfigDiff.Kind.SAME })
        assertTrue(ConfigDiff.hunks(ConfigDiff.configs(old.toJson(), old.toJson())).isEmpty())

        val warnings = ChangeWarnings.forCollection(todos, draftTodos).associate { it.field to it.message }
        assertEquals(setOf("due", "tags"), warnings.keys)
        assertTrue(warnings.getValue("due").contains("datetime → text"))
        assertTrue(ChangeWarnings.forCollection(null, draftTodos).isEmpty())
        assertEquals(listOf("todos"), ChangeWarnings.removedCollections(old, new.copy(collections = emptyList())))
    }

    @Test
    fun `api ornekleri ve cihaz ici deneme istegi`() {
        val file = Files.createTempFile("ds-api", ".db").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val key = ConfigDefaults.generateApiKey()
        val posts = CollectionTemplate.BLOG.create()
        val cfg = ServerConfig(
            server = HttpConfig(host = "127.0.0.1", port = port),
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
            collections = listOf(CollectionTemplate.TODOS.create(), posts.copy(rules = posts.rules.copy(create = Rule.NOBODY, update = Rule.NOBODY, delete = Rule.NOBODY))),
        )
        val engine = ServerEngine({ JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")) }) {}
        try {
            // Sunucu çalışmıyorken plan geçici bağlantıyla çıkarılır.
            assertEquals("2 tablo oluşturulacak", engine.plan(cfg).summary())
            engine.deploy(cfg)
            assertEquals("Şema güncel", engine.plan(cfg).summary()) // canlı bağlantı
            val withField = cfg.copy(
                collections = listOf(cfg.collections[0].copy(fields = cfg.collections[0].fields + FieldConfig("note", FieldType.TEXT)), cfg.collections[1]),
            )
            assertEquals("1 sütun eklenecek", engine.plan(withField).summary())

            val todoExamples = ApiExamples.forCollection(cfg, cfg.collections[0])
            assertEquals(listOf("GET", "GET", "GET", "POST", "PATCH", "DELETE"), todoExamples.map { it.method })
            val filter = todoExamples[1]
            assertTrue(filter.path.endsWith("?title[like]=title&fields=title,done"), filter.path)
            assertTrue(filter.curl("http://h", null).startsWith("curl -g "), "curl köşeli parantezleri kalıp saymasın")
            val create = todoExamples[3].curl("http://192.168.1.50:$port", "Bearer $key")
            assertTrue(create.contains("-X POST 'http://192.168.1.50:$port/api/collections/todos/records'"), create)
            assertTrue(create.contains("Authorization: Bearer $key"))
            assertTrue(create.contains("\"title\":\"örnek title\""), create)

            val postExamples = ApiExamples.forCollection(cfg, cfg.collections[1])
            assertEquals(3, postExamples.size, "salt okunur koleksiyonda yazma örneği yok")
            assertFalse(postExamples[0].curl("http://h", "Bearer $key").contains("Authorization"), "herkese açık okuma")

            val url = "http://127.0.0.1:$port${todoExamples[0].path}"
            assertEquals(200, ApiProbe.get(url, "Bearer $key").status)
            val denied = ApiProbe.get(url, null)
            assertEquals(401, denied.status)
            // Filtre örneği cihaz içinden de denenebilir (köşeli parantezler kodlanır).
            val filtered = ApiProbe.get("http://127.0.0.1:$port${filter.path}", "Bearer $key")
            assertEquals(200, filtered.status, filtered.body)
        } finally {
            engine.stop()
            file.delete()
        }
    }
}
