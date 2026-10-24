package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.SdkGenerator
import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.files.FileStore
import com.plyg.droidserver.core.sdkclient.DroidServerClient
import com.plyg.droidserver.core.sdkclient.DroidServerException
import com.plyg.droidserver.core.sdkclient.GeoPoint
import com.plyg.droidserver.core.sdkclient.ListOptions
import com.plyg.droidserver.core.sdkclient.RealtimeEvent
import com.plyg.droidserver.core.sdkclient.TodoItemsInput
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Faz 12.7: üretilen Kotlin istemcisi gerçek sunucuya karşı (JVM'de PATCH, dosya, canlı abonelik, token yenileme). */
class SdkClientTest {
    private lateinit var db: SqlDriver
    private lateinit var dir: File
    private lateinit var server: EmbeddedServer<*, *>
    private var base = ""

    @BeforeTest
    fun start() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(SdkConfig.config.schemaCollections())
        dir = Files.createTempDirectory("ds-sdk").toFile()
        val store = FileStore(File(dir, "storage"))
        server = embeddedServer(CIO, port = 0) { droidServerModule(SdkConfig.config, db, files = store) }.start(wait = false)
        base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}/api"
    }

    @AfterTest
    fun stop() {
        server.stop(100, 1000)
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `uretilen istemci uctan uca`() {
        val admin = DroidServerClient(base, apiKey = SdkConfig.KEY)
        assertEquals(0, admin.todoItems.list().totalItems)

        val ds = DroidServerClient(base)
        val saved = mutableListOf<String?>()
        ds.onTokens = { t, _ -> saved += t }
        val signup = ds.auth.signup(buildJsonObject { put("email", JsonPrimitive("ali@example.com")); put("password", JsonPrimitive("parola-1234")) })
        assertNotNull(signup.tokens)
        assertNotNull(ds.token)
        val me = ds.auth.me()
        val myId = me["id"].toString().toLong()

        // Canlı abonelik (arka planda) — oluşturma olayı gelir.
        val events = mutableListOf<RealtimeEvent>()
        val got = CountDownLatch(1)
        val sub = ds.subscribe(listOf("todo_items")) { e -> events += e; got.countDown() }
        Thread.sleep(300)

        val t = ds.todoItems.create(
            TodoItemsInput(title = "Süt", qty = 2, price = 1.5, status = "açık", tags = listOf("ev"), meta = buildJsonObject { put("a", JsonPrimitive(1)) }, `in` = "x", place = GeoPoint(41.0054, 28.9768)),
        )
        assertEquals(3.0, t.total, "hesaplanan alan")
        assertEquals(GeoPoint(41.0054, 28.9768), t.place, "konum")
        assertEquals(myId, t.owner, "sahip alanı")
        assertNotNull(t.olusturulma, "uzman modu adı")
        assertTrue(got.await(5, TimeUnit.SECONDS), "canlı olay")
        assertEquals("create", events.first().action)
        sub.close()

        // Liste, filtre, PATCH (JVM: X-HTTP-Method-Override), alan boşaltma.
        ds.todoItems.create(TodoItemsInput(title = "Ekmek", tags = listOf("iş")))
        val page = ds.todoItems.list(ListOptions(filter = mapOf("tags" to "ev"), sort = "-id"))
        assertEquals(listOf("Süt"), page.items.map { it.title })
        assertEquals(listOf("Süt"), ds.todoItems.list(ListOptions(filter = mapOf("place[near]" to "41.0,29.0,5"))).items.map { it.title })
        val updated = ds.todoItems.update(t.id, TodoItemsInput(done = true, qty = 4))
        assertEquals(true, updated.done)
        assertEquals(6.0, updated.total)
        assertNull(ds.todoItems.updateRaw(t.id, buildJsonObject { put("in", JsonNull) }).`in`)

        // Dosya yükleme ve adres.
        val png = File(dir, "foto.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)) }
        val withPhoto = ds.todoItems.create(TodoItemsInput(title = "Fotoğraf"), files = mapOf("photo" to listOf(png)))
        val name = assertNotNull(withPhoto.photo)
        assertTrue(ds.fileUrl("todo_items", withPhoto.id, name)!!.endsWith("/api/files/todo_items/${withPhoto.id}/$name"))

        // Doğrulama hatası alan başına; silinen kayıt 404.
        val bad = assertFailsWith<DroidServerException> { ds.todoItems.create(TodoItemsInput(status = "yok")) }
        assertEquals(400, bad.status)
        assertTrue("title" in bad.details && "status" in bad.details, bad.details.toString())
        ds.todoItems.delete(t.id)
        assertEquals(404, assertFailsWith<DroidServerException> { ds.todoItems.get(t.id) }.status)

        // Erişim token'ı geçersizse yenileme token'ıyla bir kez yenilenip tekrarlanır.
        ds.token = "bozuk"
        assertEquals(2, ds.todoItems.list().totalItems)
        assertTrue(saved.size >= 2 && saved.last() != "bozuk")
        // Takımlar: kur, adı ve üyeleri.
        val team = ds.teams.create("Mutfak")
        assertEquals("owner", team["role"].toString().trim('"'))
        val id = team["id"].toString().toLong()
        assertEquals(1, ds.teams.get(id)["members"]!!.jsonArray.size)
        assertEquals("Ev", ds.teams.rename(id, "Ev")["name"].toString().trim('"'))
        assertEquals(1, ds.teams.list()["items"]!!.jsonArray.size)
        ds.teams.delete(id)
        ds.auth.logout()
        assertNull(ds.token)
        assertEquals(401, assertFailsWith<DroidServerException> { ds.todoItems.list() }.status)
    }

    @Test
    fun `yedek adres`() {
        // Ana sunucuya ulaşılamıyor: sıradaki adres (canlı kopya veya devralan) denenir ve hatırlanır.
        val dead = "http://127.0.0.1:${java.net.ServerSocket(0).use { it.localPort }}/api"
        val ds = DroidServerClient(dead, apiKey = SdkConfig.KEY, fallbackUrls = listOf(base))
        assertEquals(0, ds.todoItems.list().totalItems)
        assertEquals(base, ds.baseUrl)
        assertEquals(1, ds.todoItems.create(TodoItemsInput(title = "Yedekte")).let { ds.todoItems.list().totalItems })
    }

    /**
     * TypeScript istemcisi Node'da (tip silme) gerçek sunucuya karşı; `tsc --strict` ile tip denetimi. Node 22.6+ veya
     * TypeScript yoksa atlanır.
     */
    @Test
    fun `typescript istemcisi nodeda calisir`() {
        val node = runCatching { ProcessBuilder("node", "--version").start().inputStream.readBytes().decodeToString().trim() }.getOrNull()
        val major = node?.removePrefix("v")?.substringBefore('.')?.toIntOrNull() ?: 0
        org.junit.Assume.assumeTrue("Node 22+ yok", major >= 22)
        File(dir, "droidserver.ts").writeText(SdkGenerator.generate(SdkConfig.config, SdkGenerator.Lang.TS))
        File(dir, "deneme.mts").writeText(TS_SCRIPT)
        val (exit, out) = withReader { reader ->
            val p = ProcessBuilder("node", "--experimental-strip-types", "--no-warnings", "deneme.mts", base, reader).directory(dir).redirectErrorStream(true).start()
            val text = p.inputStream.readBytes().decodeToString()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            p.exitValue() to text
        }
        assertEquals(0, exit, out)
        assertTrue("TAMAM" in out, out)

        val tsc = listOf("/opt/node-tools/node_modules/typescript/bin/tsc", "node_modules/typescript/bin/tsc").map(::File).firstOrNull { it.isFile }
        if (tsc != null) {
            val check = ProcessBuilder("node", tsc.path, "--noEmit", "--strict", "--target", "es2022", "--module", "nodenext", "--lib", "es2022,dom",
                "--allowImportingTsExtensions", "--erasableSyntaxOnly", "droidserver.ts", "deneme.mts").directory(dir).redirectErrorStream(true).start()
            val msg = check.inputStream.readBytes().decodeToString()
            assertTrue(check.waitFor(120, TimeUnit.SECONDS))
            assertEquals(0, check.exitValue(), msg)
        }
    }

    /** Faz 18.2: okumalar kopyaya, yazmadan hemen sonraki okumalar ana sunucuya; kopyaya ulaşılamazsa ana sunucu. */
    @Test
    fun `okuma adresleri ve kendi yazdigini oku`() {
        val reader = embeddedServer(CIO, port = 0) { droidServerModule(SdkConfig.config.copy(name = "Okuma"), db) }.start(wait = false)
        try {
            val readBase = "http://127.0.0.1:${runBlocking { reader.engine.resolvedConnectors().first().port }}/api"
            fun name(c: DroidServerClient) = c.request("GET", "/health")!!.jsonObject["name"]!!.jsonPrimitive.content
            val ds = DroidServerClient(base, apiKey = SdkConfig.KEY, readUrls = listOf(readBase), readYourWritesMs = 300)
            assertEquals("Okuma", name(ds))
            ds.todoItems.create(TodoItemsInput(title = "yazma"))
            assertEquals("SDK deneme", name(ds), "yazmadan hemen sonra ana sunucu")
            Thread.sleep(400)
            assertEquals("Okuma", name(ds))
            assertEquals("SDK deneme", name(DroidServerClient(base, apiKey = SdkConfig.KEY, readUrls = listOf(dead))), "kopyaya ulaşılamazsa ana")
        } finally {
            reader.stop(100, 1000)
        }
    }

    /** Faz 18.1: üretilen Kotlin istemcisinin çevrimdışı kopyası gerçek sunucuya karşı. */
    @Test
    fun `cevrimdisi esitleme`() {
        val ds = DroidServerClient(base)
        ds.auth.signup(buildJsonObject { put("email", JsonPrimitive("off@example.com")); put("password", JsonPrimitive("parola-1234")) })
        val other = DroidServerClient(base, token = ds.token, refreshToken = ds.refreshToken)
        val storeDir = File(dir, "offline")
        val off = ds.todoItems.offline(com.plyg.droidserver.core.sdkclient.FileOfflineStore(storeDir))
        val conflicts = mutableListOf<com.plyg.droidserver.core.sdkclient.SyncConflictInfo>()
        off.onConflict = { conflicts += it }
        off.sync()
        assertEquals(0, off.all().size)

        // Çevrimdışı yazılan iki kayıt: geçici (negatif) kimlik, eşitleyince sunucudaki kimlik.
        val a = off.create(TodoItemsInput(title = "Süt", qty = 1))
        val b = off.create(TodoItemsInput(title = "Ekmek"))
        assertTrue(a.id < 0 && b.id < 0)
        off.update(a.id, TodoItemsInput(qty = 2)) // bekleyen oluşturmaya katılır
        assertEquals(2, off.pendingCount)
        off.sync()
        assertEquals(0, off.pendingCount)
        val local = off.all().associateBy { it.title }
        assertTrue(local.values.all { it.id > 0 }, local.toString())
        assertEquals(2L, local.getValue("Süt").qty)
        assertEquals(2L, other.todoItems.list().totalItems)
        val sut = local.getValue("Süt").id

        // Başka cihazdaki değişiklik çekilir.
        other.todoItems.update(sut, TodoItemsInput(title = "Süt (2 L)"))
        off.sync()
        assertEquals("Süt (2 L)", off.get(sut)?.title)

        // Aynı alanı çevrimdışıyken iki taraf değiştirdi: sonra yazan (sunucu) kazanır; diğer alan uygulanır.
        off.update(sut, TodoItemsInput(title = "yerel", price = 3.5))
        Thread.sleep(20)
        other.todoItems.update(sut, TodoItemsInput(title = "sunucu"))
        off.sync()
        assertEquals("sunucu", off.get(sut)?.title)
        assertEquals(3.5, off.get(sut)?.price)
        assertEquals(listOf("merged"), conflicts.map { it.status })

        // Yerel silme sunucuya gider; depodan yeniden açılan kopya aynı durumda.
        off.delete(local.getValue("Ekmek").id)
        off.sync()
        assertEquals(1L, other.todoItems.list().totalItems)
        val reopened = ds.todoItems.offline(com.plyg.droidserver.core.sdkclient.FileOfflineStore(storeDir))
        assertEquals(listOf("sunucu"), reopened.all().map { it.title })

        // Ağ yokken: değişiklik bekler, hata fırlatılır.
        val offline = DroidServerClient(dead, token = ds.token).todoItems.offline(com.plyg.droidserver.core.sdkclient.MemoryOfflineStore())
        offline.create(TodoItemsInput(title = "bekliyor"))
        assertFailsWith<java.io.IOException> { offline.sync() }
        assertEquals(1, offline.pendingCount)

        // Canlı: sunucuya eklenen kayıt kısa sürede yerelde.
        val live = reopened.live()
        other.todoItems.create(TodoItemsInput(title = "canlı"))
        val deadline = System.currentTimeMillis() + 5_000
        while (reopened.all().none { it.title == "canlı" } && System.currentTimeMillis() < deadline) Thread.sleep(100)
        live.close()
        assertTrue(reopened.all().any { it.title == "canlı" }, reopened.all().toString())
    }

    /** Komut çıktısı ve çıkış kodu; araç yoksa null. */
    private fun run(dir: File, timeoutSec: Long, vararg cmd: String): Pair<Int, String>? {
        val p = runCatching { ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start() }.getOrNull() ?: return null
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("${cmd.first()} $timeoutSec sn'de bitmedi:\n$out")
        }
        reader.join(1000)
        return p.exitValue() to out.toString()
    }

    private fun tool(vararg cmd: String) = runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)

    private val dead get() = "http://127.0.0.1:${java.net.ServerSocket(0).use { it.localPort }}/api"

    /** Aynı veritabanını okuyan, adı farklı ikinci sunucu (okuma kopyası yerine). */
    private fun <R> withReader(block: (String) -> R): R {
        val reader = embeddedServer(CIO, port = 0) { droidServerModule(SdkConfig.config.copy(name = "Okuma"), db) }.start(wait = false)
        try {
            return block("http://127.0.0.1:${runBlocking { reader.engine.resolvedConnectors().first().port }}/api")
        } finally {
            reader.stop(100, 1000)
        }
    }

    /** Dart istemcisi (altın dosyayla aynı çıktı) gerçek sunucuya karşı; `dart analyze` temiz olmalı. Dart yoksa atlanır. */
    @Test
    fun `dart istemcisi calisir`() {
        org.junit.Assume.assumeTrue("Dart yok", tool("dart", "--version"))
        File(dir, "droidserver.dart").writeText(SdkGenerator.generate(SdkConfig.config, SdkGenerator.Lang.DART))
        File("src/test/sdk/deneme.dart").copyTo(File(dir, "deneme.dart"))
        val (analyzed, issues) = run(dir, 120, "dart", "analyze", "--fatal-infos", "droidserver.dart", "deneme.dart")!!
        assertEquals(0, analyzed, issues)
        val (code, out) = withReader { reader -> run(dir, 120, "dart", "run", "deneme.dart", base, SdkConfig.KEY, dead, reader)!! }
        assertEquals(0, code, out)
        assertTrue("TAMAM" in out, out)
    }

    /** Swift istemcisi Swift 6 kipinde uyarısız derlenir ve gerçek sunucuya karşı çalışır. Swift yoksa atlanır. */
    @Test
    fun `swift istemcisi calisir`() {
        org.junit.Assume.assumeTrue("Swift yok", tool("swiftc", "--version"))
        File(dir, "DroidServer.swift").writeText(SdkGenerator.generate(SdkConfig.config, SdkGenerator.Lang.SWIFT))
        File("src/test/sdk/deneme.swift").copyTo(File(dir, "deneme.swift"))
        val (built, log) = run(dir, 600, "swiftc", "-swift-version", "6", "-warnings-as-errors", "-parse-as-library", "DroidServer.swift", "deneme.swift", "-o", "deneme")!!
        assertEquals(0, built, log)
        val (code, out) = withReader { reader -> run(dir, 120, File(dir, "deneme").path, base, SdkConfig.KEY, dead, reader)!! }
        assertEquals(0, code, out)
        assertTrue("TAMAM" in out, out)
    }

    @Test
    fun `typescript ciktisi ve sdk ucu`() {
        val ts = SdkGenerator.generate(SdkConfig.config, SdkGenerator.Lang.TS)
        assertTrue("export interface TodoItems {" in ts && "\"status\"?: \"açık\" | \"kapalı\" | null;" in ts, ts.take(3000))
        assertTrue("readonly todoItems = new Collection<TodoItems, TodoItemsInput>" in ts)
        assertTrue("\"title\": string;" in ts.substringAfter("export interface TodoItemsInput"), "zorunlu alan")
        assertTrue("\"total\"" !in ts.substringAfter("export interface TodoItemsInput").substringBefore("}"), "hesaplanan alan girdide yok")

        val conn = java.net.URI("$base/_sdk/ts").toURL().openConnection() as java.net.HttpURLConnection
        assertEquals(401, conn.responseCode, "yalnızca yönetici")
        val ok = java.net.URI("$base/_sdk/kotlin?package=com.ornek.api").toURL().openConnection() as java.net.HttpURLConnection
        ok.setRequestProperty("Authorization", "Bearer ${SdkConfig.KEY}")
        assertEquals(200, ok.responseCode)
        assertTrue("attachment; filename=\"DroidServerClient.kt\"" == ok.getHeaderField("Content-Disposition"))
        assertTrue(ok.inputStream.readBytes().decodeToString().contains("package com.ornek.api"))
    }

    private companion object {
        val TS_SCRIPT = """
            import { DroidServer, DroidServerError, MemoryStore, parseLink, type RealtimeEvent } from "./droidserver.ts";
            declare const process: { argv: string[] };

            const l = parseLink("droidserver://connect?url=" + encodeURIComponent("http://x/api") + "&name=Evim&token=a.b.c&exp=1800000000");
            if (l.url !== "http://x/api" || l.name !== "Evim" || l.token !== "a.b.c" || l.expiresAt?.getTime() !== 1800000000000) throw new Error("bağlantı " + JSON.stringify(l));
            const linked = DroidServer.fromLink("droidserver://connect?url=" + encodeURIComponent(process.argv[2]) + "&token=ds_xyz");
            if (linked.apiKey !== "ds_xyz" || linked.token !== undefined || linked.baseUrl !== process.argv[2]) throw new Error("fromLink");
            if (DroidServer.fromLink("droidserver://connect?url=http%3A%2F%2Fx&token=a.b.c").token !== "a.b.c") throw new Error("fromLink jwt");

            const ds = new DroidServer(process.argv[2]);
            const signup = await ds.auth.signup({ email: "ts@example.com", password: "parola-1234" });
            if (!("token" in signup)) throw new Error("token yok");
            const team = await ds.teams.create("Mutfak");
            if (team.role !== "owner" || (await ds.teams.get(team.id)).members?.length !== 1) throw new Error("takım " + JSON.stringify(team));
            await ds.teams.delete(team.id);
            const fb = new DroidServer("http://127.0.0.1:9/api", { token: ds.token, fallbackUrls: [process.argv[2]] });
            await fb.todoItems.list();
            if (fb.baseUrl !== process.argv[2]) throw new Error("yedek adres " + fb.baseUrl);
            const events: RealtimeEvent[] = [];
            const stop = ds.subscribe(["todo_items"], (e) => { events.push(e); });
            await new Promise((r) => setTimeout(r, 300));

            const t = await ds.todoItems.create({ title: "Süt", qty: 2, price: 1.5, status: "açık", tags: ["ev"], place: { lat: 41.0054, lng: 28.9768 } });
            if (t.total !== 3) throw new Error("total " + t.total);
            if (t.place?.lat !== 41.0054) throw new Error("konum " + JSON.stringify(t.place));
            const near = await ds.todoItems.list({ filter: { place: { near: "41.0,29.0,5" } } });
            if (near.items.map((x) => x.title).join() !== "Süt") throw new Error("yakın " + JSON.stringify(near));
            await ds.todoItems.create({ title: "Ekmek", tags: ["iş"] });
            const page = await ds.todoItems.list({ filter: { tags: "ev", qty: { gte: 1 } }, sort: "-id" });
            if (page.items.length !== 1 || page.items[0].title !== "Süt") throw new Error("liste " + JSON.stringify(page));
            const u = await ds.todoItems.update(t.id, { done: true, qty: 4 });
            if (u.done !== true || u.total !== 6) throw new Error("güncelleme " + JSON.stringify(u));

            const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...new Array(64).fill(0)]);
            const f = await ds.todoItems.create({ title: "Foto" }, { photo: new Blob([png], { type: "image/png" }) });
            if (!f.photo) throw new Error("dosya yok");
            const url = ds.fileUrl("todo_items", f.id, f.photo)!;
            const img = await fetch(url, { headers: { Authorization: "Bearer " + ds.token } });
            if (img.status !== 200) throw new Error("dosya indirilemedi " + img.status);

            try {
              await ds.todoItems.create({ title: "x", status: "yok" as "açık" });
              throw new Error("hata bekleniyordu");
            } catch (e) {
              if (!(e instanceof DroidServerError) || e.status !== 400 || !e.details.status) throw e;
            }
            await new Promise((r) => setTimeout(r, 500));
            stop();
            if (!events.some((e) => e.action === "create" && e.collection === "todo_items")) throw new Error("canlı olay yok");

            ds.token = "bozuk";
            const again = await ds.todoItems.list();
            if (again.totalItems !== 3 || ds.token === "bozuk") throw new Error("yenileme çalışmadı");

            // Çevrimdışı kopya (Node: bellek deposu): yerel yazma, eşitleme, başka istemcinin değişikliği, çakışma.
            const store = new MemoryStore();
            const off = ds.todoItems.offline(store);
            const conflicts: string[] = [];
            off.onConflict = (c) => { conflicts.push(c.status); };
            await off.sync();
            const local = await off.create({ title: "Çevrimdışı", qty: 1 });
            if (local.id >= 0 || (await off.pendingCount()) !== 1) throw new Error("geçici kimlik");
            await off.update(local.id, { qty: 2 });
            await off.sync();
            const synced = (await off.all()).find((r) => r.title === "Çevrimdışı");
            if (!synced || synced.id <= 0 || synced.qty !== 2 || (await off.pendingCount()) !== 0) throw new Error("eşitleme " + JSON.stringify(synced));
            await ds.todoItems.update(synced.id, { title: "sunucuda" });
            await off.sync();
            if ((await off.get(synced.id))?.title !== "sunucuda") throw new Error("çekme");
            await off.update(synced.id, { title: "yerel", price: 9 });
            await new Promise((r) => setTimeout(r, 20));
            await ds.todoItems.update(synced.id, { title: "sunucu" });
            await off.sync();
            const merged = await off.get(synced.id);
            if (merged?.title !== "sunucu" || merged.price !== 9 || conflicts.join() !== "merged") throw new Error("çakışma " + JSON.stringify(merged) + conflicts);
            await off.delete(synced.id);
            await off.sync();
            const reopened = ds.todoItems.offline(store);
            if ((await reopened.get(synced.id)) !== undefined) throw new Error("silme");
            await ds.auth.logout();

            // Okuma kopyaya; yazmadan hemen sonra ana sunucuya.
            const rd = new DroidServer(process.argv[2], { readUrls: [process.argv[3]], readYourWritesMs: 300 });
            const health = async () => (await rd.request<{ name: string }>("GET", "/health")).name;
            if ((await health()) !== "Okuma") throw new Error("okuma kopyaya gitmedi");
            await rd.request("POST", "/auth/login", { json: { email: "yok@example.com", password: "x" } }).catch(() => undefined);
            if ((await health()) !== "SDK deneme") throw new Error("kendi yazdığını oku");
            await new Promise((r) => setTimeout(r, 400));
            if ((await health()) !== "Okuma") throw new Error("süre bitince yine kopya");
            console.log("TAMAM");
        """.trimIndent()
    }
}
