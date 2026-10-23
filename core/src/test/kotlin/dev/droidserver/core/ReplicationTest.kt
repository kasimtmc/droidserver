package dev.droidserver.core

import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigMigrations
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.ReplicaSource
import dev.droidserver.core.config.ReplicationConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.replication.ChangeLog
import dev.droidserver.core.replication.ReplicaStatus
import dev.droidserver.core.replication.Replicator
import dev.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Faz 13.7: canlı kopya; ana ve kopya farklı lehçelerde olabilir. */
@RunWith(Parameterized::class)
class ReplicationTest(private val primaryDialect: Dialect, private val replicaDialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}->{1}")
        fun pairs() = listOf(arrayOf(Dialect.SQLITE, Dialect.SQLITE), arrayOf(Dialect.POSTGRES, Dialect.SQLITE), arrayOf(Dialect.SQLITE, Dialect.POSTGRES))

        private fun freePort() = ServerSocket(0).use { it.localPort }
    }

    private val key = "yonetici-anahtari-1234567890"
    private val lists = CollectionConfig(
        "lists",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("done", FieldType.BOOLEAN),
            FieldConfig("due", FieldType.DATETIME),
            FieldConfig("meta", FieldType.JSON),
            FieldConfig("kind", FieldType.SELECT, options = listOf("ev", "iş")),
        ),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
        search = listOf("title"),
    )
    private val items = CollectionConfig(
        "items",
        listOf(
            FieldConfig("name", FieldType.TEXT),
            FieldConfig("list", FieldType.RELATION, collection = "lists", onDelete = OnDelete.CASCADE),
            FieldConfig("photo", FieldType.FILE, mimeTypes = listOf("image/*")),
        ),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
    )
    private val dirs = mutableListOf<File>()
    private val engines = mutableListOf<ServerEngine>()

    @After
    fun stop() {
        engines.forEach { runCatching { it.stop() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun primaryConfig(port: Int, extra: List<FieldConfig> = emptyList()) = ServerConfig(
        name = "Ana",
        server = HttpConfig(port = port, host = "127.0.0.1"),
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, sendVerification = false)),
        collections = listOf(lists.copy(fields = lists.fields + extra), items),
        replication = ReplicationConfig(source = true),
    )

    private fun engine(dialect: Dialect): Pair<ServerEngine, File> {
        val db = TestDb.create(dialect)
        val dir = Files.createTempDirectory("ds-rep").toFile().also { dirs += it }
        return ServerEngine({ db.open() }, dataDir = dir) {}.also { engines += it } to dir
    }

    private fun call(method: String, url: String, body: String? = null, auth: String? = key, multipart: Pair<String, ByteArray>? = null): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        auth?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null || multipart != null) {
            conn.doOutput = true
            if (multipart != null) {
                val b = "sinir123"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
                conn.outputStream.use { out ->
                    out.write("--$b\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n${multipart.first}\r\n".toByteArray())
                    out.write("--$b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
                    out.write(multipart.second)
                    out.write("\r\n--$b--\r\n".toByteArray())
                }
            } else {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body!!.toByteArray()) }
            }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to text
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun waitFor(timeoutMs: Long = 20_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("zaman aşımı: $what")
            Thread.sleep(100)
        }
    }

    @Test
    fun `ana sunucudan kopyaya`() {
        val (primary, _) = engine(primaryDialect)
        val pPort = freePort()
        val pCfg = primaryConfig(pPort)
        primary.deploy(pCfg)
        val p = "http://127.0.0.1:$pPort/api"

        // Kopya kurulmadan önceki veriler (ilk kopyalama).
        val listId = json(call("POST", "$p/collections/lists/records", """{"title":"Market","done":true,"due":"2026-11-01T10:00:00Z","meta":{"a":[1,2]},"kind":"ev"}""").second).getValue("id").jsonPrimitive.content
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { it.toByte() }
        val (created, itemText) = call("POST", "$p/collections/items/records", multipart = "Süt" to png)
        assertEquals(201, created, itemText)
        val item = json(itemText)
        call("PATCH", "$p/collections/items/records/${item.getValue("id").jsonPrimitive.content}", """{"list":$listId}""")
        assertEquals(201, call("POST", "$p/auth/signup", """{"email":"ali@example.com","password":"parola-1234"}""", auth = null).first)

        // Kurulum: ana sunucunun konfigürasyonu kopyanınkine dönüşür.
        val (fetched, hash) = Replicator.fetchPrimary(p, key)
        assertEquals(ChangeLog.hash(pCfg), hash)
        val (replica, rDir) = engine(replicaDialect)
        val rPort = freePort()
        val local = ServerConfig(server = HttpConfig(port = rPort, host = "127.0.0.1"), replication = ReplicationConfig(replicaOf = ReplicaSource(p, key, intervalSec = 1)))
        val rCfg = ChangeLog.merge(local, fetched)
        assertEquals(emptyList(), ConfigValidator.validateDetailed(rCfg).map { it.path })
        assertEquals(listOf("lists", "items"), rCfg.collections.map { it.name })
        assertEquals(rPort, rCfg.server.port)
        assertEquals(hash, rCfg.replication.replicaOf?.configHash)
        replica.deploy(rCfg)
        val r = "http://127.0.0.1:$rPort/api"

        waitFor(what = "ilk kopyalama") { replica.replica.value?.phase == ReplicaStatus.Phase.SYNCING }
        val copied = json(call("GET", "$r/collections/lists/records/$listId").second)
        assertEquals("Market", copied.getValue("title").jsonPrimitive.content)
        assertEquals("true", copied.getValue("done").jsonPrimitive.content)
        assertEquals("2026-11-01T10:00:00Z", copied.getValue("due").jsonPrimitive.content)
        assertEquals("""{"a":[1,2]}""", copied.getValue("meta").toString())
        val rItem = json(call("GET", "$r/collections/items/records/${item.getValue("id").jsonPrimitive.content}").second)
        val photo = rItem.getValue("photo").jsonPrimitive.content
        val file = call("GET", "$r/files/items/${rItem.getValue("id").jsonPrimitive.content}/$photo")
        assertEquals(200, file.first, "dosya kopyalandı")
        assertTrue(File(rDir, "storage/items/${rItem.getValue("id").jsonPrimitive.content}/$photo").readBytes().contentEquals(png))
        assertEquals(1, json(call("GET", "$r/collections/lists/records?q=market").second).getValue("items").jsonArray.size, "arama sütunu da kopyalandı")

        // Kopyaya gelen veri yazması ana sunucuya iletilir (kural ve doğrulama orada), yanıt döner; anahtarlar aynı.
        val (code, forwarded) = call("POST", "$r/collections/lists/records", """{"title":"kopyadan"}""")
        assertEquals(201, code, forwarded)
        val fwdId = json(forwarded).getValue("id").jsonPrimitive.content
        assertEquals("kopyadan", json(call("GET", "$p/collections/lists/records/$fwdId").second).getValue("title").jsonPrimitive.content)
        assertEquals(400, call("POST", "$r/collections/lists/records", """{"done":true}""").first, "doğrulama hatası aynen döner")
        assertEquals(204, call("DELETE", "$r/collections/lists/records/$fwdId").first)
        val (login, tokens) = call("POST", "$r/auth/login", """{"email":"ali@example.com","password":"parola-1234"}""", auth = null)
        assertEquals(200, login, tokens)
        assertTrue("token" in json(tokens))
        // Yönetim uçları (ör. SQL konsolu) kopyanın kendisinde kalır; ana sunucu kopyayı tablosunda görür.
        waitFor(what = "kopya tablosu") {
            json(call("GET", "$p/_replication/replicas").second).getValue("items").jsonArray.any { it.jsonObject["appliedSeq"]?.jsonPrimitive?.content != "null" }
        }
        val table = json(call("GET", "$p/_replication/replicas").second)
        val row = table.getValue("items").jsonArray.single().jsonObject
        assertEquals("true", row.getValue("online").jsonPrimitive.content)
        assertEquals(ConfigMigrations.CURRENT.toString(), row.getValue("version").jsonPrimitive.content)

        // Artımlı: güncelleme, yeni kayıt, silme (zincirleme) ve hesap.
        call("PATCH", "$p/collections/lists/records/$listId", """{"title":"Market 2","done":false}""")
        val second = json(call("POST", "$p/collections/lists/records", """{"title":"İş"}""").second).getValue("id").jsonPrimitive.content
        call("POST", "$p/auth/signup", """{"email":"ayse@example.com","password":"parola-1234"}""", auth = null)
        waitFor(what = "artımlı") { json(call("GET", "$r/collections/lists/records/$listId").second)["title"]?.jsonPrimitive?.content == "Market 2" }
        assertEquals(200, call("GET", "$r/collections/lists/records/$second").first)
        assertEquals(2, json(call("GET", "$r/auth/users").second).getValue("items").jsonArray.size)
        assertEquals(204, call("DELETE", "$p/collections/lists/records/$listId").first)
        waitFor(what = "silme") { call("GET", "$r/collections/lists/records/$listId").first == 404 }
        assertEquals(0, json(call("GET", "$r/collections/items/records").second).getValue("items").jsonArray.size, "zincirleme silinen")
        waitFor(what = "dosya silindi") { !File(rDir, "storage/items/${rItem.getValue("id").jsonPrimitive.content}/$photo").exists() }
        val status = assertNotNull(replica.replica.value)
        assertEquals(0, status.behind)
        assertNotNull(status.lastContact)

        // Ana sunucunun şeması değişince kopya bildirilir ve bekler.
        val notified = mutableListOf<String>()
        replica.onPrimaryConfig = { _, h -> notified += h }
        replica.deploy(rCfg) // geri çağrı yeni deploy'la bağlanır
        primary.deploy(primaryConfig(pPort, extra = listOf(FieldConfig("note", FieldType.TEXT))))
        waitFor(what = "konfigürasyon değişikliği") { notified.isNotEmpty() }
        assertEquals(ReplicaStatus.Phase.WAITING_CONFIG, replica.replica.value?.phase)

        // Devralma: kopya modundan çıkınca yazılabilir, kimlikler çakışmaz.
        replica.onPrimaryConfig = null
        replica.deploy(rCfg.copy(replication = ReplicationConfig()))
        val (c2, t2) = call("POST", "$r/collections/lists/records", """{"title":"Devraldım"}""")
        assertEquals(201, c2, t2)
        assertTrue(json(t2).getValue("id").jsonPrimitive.content.toLong() > second.toLong(), "kimlik sayacı ileri alındı")
    }

    @Test
    fun `degisiklik gunlugu`() {
        val db = TestDb.create(primaryDialect).open()
        val cfg = primaryConfig(8080)
        dev.droidserver.core.db.SchemaMigrator(db).migrate(cfg.schemaCollections())
        ChangeLog.install(db, ChangeLog.tables(cfg).map { it.name })
        ChangeLog.install(db, ChangeLog.tables(cfg).map { it.name }) // yeniden kurulum
        assertEquals(listOf("lists", "items", "_users"), ChangeLog.tables(cfg).map { it.name }.filter { it in setOf("lists", "items", "_users") })
        val t = dev.droidserver.core.api.RecordService(db, lists, collections = cfg.schemaCollections())
        val a = t.create(json("""{"title":"a"}""")).getValue("id").jsonPrimitive.content.toLong()
        t.update(a, json("""{"title":"b"}"""))
        val b = t.create(json("""{"title":"c"}""")).getValue("id").jsonPrimitive.content.toLong()
        t.delete(b)
        val batch = ChangeLog.changes(db, cfg, 0)
        val changes = batch.getValue("changes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("upsert", "delete"), changes.map { it.getValue("action").jsonPrimitive.content })
        assertEquals("b", changes[0].getValue("row").jsonObject.getValue("title").jsonPrimitive.content, "güncel hal")
        val latest = batch.getValue("latest").jsonPrimitive.content.toLong()
        assertTrue(latest >= 4, "her yazma bir satır (arama sütunu güncellemesi dahil)")
        assertEquals(latest, ChangeLog.latest(db))
        assertEquals(0, ChangeLog.changes(db, cfg, latest).getValue("changes").jsonArray.size)

        // Kısaltma: son kayıt kalır; silinmiş sıradan isteyen baştan eşitlenir.
        assertEquals((latest - 1).toInt(), ChangeLog.trim(db, 1, java.time.Instant.now().plusSeconds(3 * 86_400L)))
        assertEquals("true", ChangeLog.changes(db, cfg, 1).getValue("reset").jsonPrimitive.content)
        assertEquals("false", ChangeLog.changes(db, cfg, latest).getValue("reset").jsonPrimitive.content)
        ChangeLog.uninstall(db)
        t.create(json("""{"title":"d"}"""))
        assertEquals(latest, ChangeLog.latest(db), "tetikleyici kaldırıldı")
        assertFailsWith<IllegalArgumentException> { ChangeLog.snapshot(db, cfg, "_sessions", 0) }
        db.close()

        val bad = ConfigValidator.validateDetailed(ServerConfig(replication = ReplicationConfig(source = true, replicaOf = ReplicaSource("ftp://x", "")))).map { it.path }
        assertEquals(listOf("replication.replicaOf", "replication.replicaOf.url", "replication.replicaOf.key"), bad.filter { it.startsWith("replication") })
    }
}
