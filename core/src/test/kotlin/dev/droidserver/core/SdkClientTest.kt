package dev.droidserver.core

import dev.droidserver.core.api.SdkGenerator
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.sdkclient.DroidServerClient
import dev.droidserver.core.sdkclient.DroidServerException
import dev.droidserver.core.sdkclient.ListOptions
import dev.droidserver.core.sdkclient.RealtimeEvent
import dev.droidserver.core.sdkclient.TodoItemsInput
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
            TodoItemsInput(title = "Süt", qty = 2, price = 1.5, status = "açık", tags = listOf("ev"), meta = buildJsonObject { put("a", JsonPrimitive(1)) }, `in` = "x"),
        )
        assertEquals(3.0, t.total, "hesaplanan alan")
        assertEquals(myId, t.owner, "sahip alanı")
        assertNotNull(t.olusturulma, "uzman modu adı")
        assertTrue(got.await(5, TimeUnit.SECONDS), "canlı olay")
        assertEquals("create", events.first().action)
        sub.close()

        // Liste, filtre, PATCH (JVM: X-HTTP-Method-Override), alan boşaltma.
        ds.todoItems.create(TodoItemsInput(title = "Ekmek", tags = listOf("iş")))
        val page = ds.todoItems.list(ListOptions(filter = mapOf("tags" to "ev"), sort = "-id"))
        assertEquals(listOf("Süt"), page.items.map { it.title })
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
        val p = ProcessBuilder("node", "--experimental-strip-types", "--no-warnings", "deneme.mts", base).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
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
            import { DroidServer, DroidServerError, parseLink, type RealtimeEvent } from "./droidserver.ts";
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

            const t = await ds.todoItems.create({ title: "Süt", qty: 2, price: 1.5, status: "açık", tags: ["ev"] });
            if (t.total !== 3) throw new Error("total " + t.total);
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
            await ds.auth.logout();
            console.log("TAMAM");
        """.trimIndent()
    }
}
