package dev.droidserver.core

import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.ConfigJson
import dev.droidserver.core.config.ConfigMigrations
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.MissingSecretsException
import dev.droidserver.core.config.SecretSink
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.runtimeComponents
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMigrationsTest {
    /** Sırları bellekte tutan sahte depo: api_key_1, api_key_2, jwt_3 ... */
    private class MapSink : SecretSink {
        val values = linkedMapOf<String, String>()
        override fun store(kind: String, value: String) = "${kind}_${values.size + 1}".also { values[it] = value }
    }

    private val v1 = """
        {
          "version": 1,
          "name": "eski",
          "auth": {
            "mode": "api_key",
            "apiKeys": ["ilk-anahtar-0123456789", "secret:aslinda-duz-metin"],
            "jwtSecret": "jwt-sirri-en-az-otuz-iki-karakter-uzunlukta"
          },
          "collections": [ { "name": "todos", "fields": [ { "name": "title", "type": "text" } ] } ],
          "gelecekAlani": true
        }
    """.trimIndent()

    @Test
    fun `v1 konfigurasyonu v2ye donusur, sirlar depoya tasinir`() {
        val sink = MapSink()
        val secured = ConfigSecrets.secureText(v1, sink)!!
        val cfg = ServerConfig.fromJson(secured)

        assertEquals(ConfigMigrations.CURRENT, cfg.version)
        assertEquals(listOf("secret:api_key_1", "secret:api_key_2"), cfg.auth.apiKeys)
        assertEquals("secret:jwt_3", cfg.auth.jwtSecret)
        assertEquals("secret:aslinda-duz-metin", sink.values["api_key_2"], "v1'de 'secret:' öneki referans değildir")
        assertTrue(secured.contains("gelecekAlani"), "bilinmeyen anahtarlar korunmalı")
        assertTrue(!secured.contains("ilk-anahtar"), "düz metin sır dosyada kalmamalı")

        val resolved = ConfigSecrets.resolve(cfg, sink.values::get)
        assertEquals(listOf("ilk-anahtar-0123456789", "secret:aslinda-duz-metin"), resolved.auth.apiKeys)
        assertEquals("jwt-sirri-en-az-otuz-iki-karakter-uzunlukta", resolved.auth.jwtSecret)

        assertNull(ConfigSecrets.secureText(secured, sink), "güncel dosyada değişiklik olmamalı")
        assertEquals(3, sink.values.size)
    }

    @Test
    fun `yukseltmede neyin degisecegi kullaniciya anlatilir`() {
        assertEquals(21, ConfigMigrations.CURRENT)
        val changes = ConfigMigrations.changes(1)
        assertEquals(20, changes.size)
        assertTrue(changes[0].startsWith("v1 → v2") && changes[19].startsWith("v20 → v21"), changes.joinToString())
        assertTrue(ConfigMigrations.changes(ConfigMigrations.CURRENT).isEmpty())
        // v2 dosyası yeni alanlar olmadan aynen okunur, yalnızca sürüm numarası değişir.
        val v2 = """{ "version": 2, "name": "iki" }"""
        assertEquals(2, ConfigMigrations.versionOf(ConfigJson.parseToJsonElement(v2).jsonObject))
        assertEquals("iki", ServerConfig.fromJson(v2).name)
    }

    @Test
    fun `v3 erisim bilesenleri access bolumune tasinir`() {
        fun v3(components: String, auth: String = "none") = ServerConfig.fromJson(
            """{ "version": 3, "auth": { "mode": "$auth", "apiKeys": ["0123456789abcdef"] }, "components": [$components] }""",
        )
        val named = v3("""{ "id": "cloudflared", "env": { "TUNNEL_TOKEN": "secret:t" } }""", auth = "api_key")
        assertEquals(AccessMode.CLOUDFLARE, named.access.mode)
        assertEquals(CloudflareTunnel.NAMED, named.access.cloudflare.tunnel)
        assertFalse(named.access.allowNoAuth)
        assertEquals("secret:t", named.components.single().env["TUNNEL_TOKEN"])

        val byArgs = v3("""{ "id": "cloudflared", "args": ["tunnel", "run", "--token", "abc"] }""", auth = "api_key")
        assertEquals(CloudflareTunnel.NAMED, byArgs.access.cloudflare.tunnel, "elle yazılmış argümanlar korunur")
        assertEquals(listOf("tunnel", "run", "--token", "abc"), byArgs.runtimeComponents().single().args)

        val quickNoAuth = v3("""{ "id": "cloudflared" }""")
        assertEquals(CloudflareTunnel.QUICK, quickNoAuth.access.cloudflare.tunnel)
        assertTrue(quickNoAuth.access.allowNoAuth, "önceden çalışan kurulum yükseltmeden sonra da açılmalı")
        assertTrue(ConfigValidator.validate(quickNoAuth).isEmpty(), ConfigValidator.validate(quickNoAuth).joinToString())

        val ts = v3("""{ "id": "tailscale", "env": { "TS_AUTHKEY": "x" } }, { "id": "postgres", "enabled": false }""")
        assertEquals(AccessMode.TAILSCALE, ts.access.mode)
        assertEquals(listOf("postgres"), ts.components.map { it.id }, "paketi olmayan tailscale bileşeni kaldırılır")

        val local = v3("")
        assertEquals(AccessMode.LOCAL, local.access.mode)
        assertFalse(local.toJson().contains("\"access\""), "varsayılan erişim dosyaya yazılmaz")
    }

    @Test
    fun `depo olmadan eski surum okunur, sirlar yerinde kalir`() {
        val cfg = ServerConfig.fromJson(v1)
        assertEquals(ConfigMigrations.CURRENT, cfg.version)
        assertEquals("ilk-anahtar-0123456789", cfg.auth.apiKeys[0])

        assertEquals(ConfigMigrations.CURRENT, ServerConfig.fromJson("""{ "name": "surumsuz" }""").version) // v1 sayılır
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromJson("""{ "version": 99 }""") }
    }

    @Test
    fun `v2de duz metin sir kaydederken tasinir, referanslar korunur`() {
        val sink = MapSink()
        val text = ServerConfig(
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("secret:mevcut", "yeni-anahtar-0123456789")),
        ).toJson()

        val cfg = ServerConfig.fromJson(ConfigSecrets.secureText(text, sink)!!)
        assertEquals(listOf("secret:mevcut", "secret:api_key_1"), cfg.auth.apiKeys)
        assertEquals(mapOf("api_key_1" to "yeni-anahtar-0123456789"), sink.values)

        val e = assertFailsWith<MissingSecretsException> { ConfigSecrets.resolve(cfg, sink.values::get) }
        assertEquals(listOf("mevcut"), e.names)
    }

    @Test
    fun `v5 publicRead ve readOnly kurallara donusur`() {
        val v5 = """{ "version": 5, "collections": [
            { "name": "a", "fields": [{ "name": "x", "type": "text" }], "publicRead": true },
            { "name": "b", "fields": [{ "name": "x", "type": "text" }], "readOnly": true },
            { "name": "c", "fields": [{ "name": "x", "type": "text" }], "publicRead": true, "readOnly": true },
            { "name": "d", "fields": [{ "name": "x", "type": "text" }] }
        ] }"""
        val cfg = ServerConfig.fromJson(v5)
        val (a, b, c, d) = cfg.collections.map { it.rules }
        assertEquals(Rules(list = Rule.PUBLIC, view = Rule.PUBLIC), a)
        assertEquals(Rules(create = Rule.NOBODY, update = Rule.NOBODY, delete = Rule.NOBODY), b)
        assertEquals(Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.NOBODY, Rule.NOBODY, Rule.NOBODY), c)
        assertEquals(Rules(), d)
        val written = cfg.toJson()
        assertFalse("publicRead" in written || "readOnly" in written, written)
        assertFalse("\"users\"" in written, "kapalı kullanıcı ayarı dosyaya yazılmaz")
        assertEquals(cfg, ServerConfig.fromJson(written))
    }
}
