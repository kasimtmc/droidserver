package dev.droidserver.core

import dev.droidserver.core.config.AppConfig
import dev.droidserver.core.config.CommandLine
import dev.droidserver.core.config.ComponentConfig
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.EnvFile
import dev.droidserver.core.config.GitSource
import dev.droidserver.core.config.SecretSink
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.runtimeComponents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `.env` biçimi, ortak ortam değişkenlerinin şifreli depoya taşınması ve kullanılması. */
class EnvFileTest {
    private class MemorySink : SecretSink {
        val values = linkedMapOf<String, String>()
        override fun store(kind: String, value: String): String {
            values.entries.firstOrNull { it.key.startsWith("${kind}_") && it.value == value }?.let { return it.key }
            return "${kind}_${values.size + 1}".also { values[it] = value }
        }
    }

    @Test
    fun `ayristirma`() {
        val text = """
            # yorum
            export PORT=3000
            NAME = Ayşe Yılmaz   # satır sonu yorumu
            URL=https://example.com/a#b
            EMPTY=
            SINGLE='tek ${'$'}{tırnak} # yorum değil'
            DOUBLE="satır1\nsatır2 \"alıntı\" \\ son"
            MULTI="ilk
            ikinci"
            PEM='-----BEGIN-----
            abc
            -----END-----'
            PORT=4000
        """.trimIndent()
        val parsed = EnvFile.parse(text)
        val m = parsed.toMap()
        assertEquals("4000", m["PORT"], "sonuncusu geçerli")
        assertEquals("Ayşe Yılmaz", m["NAME"])
        assertEquals("https://example.com/a#b", m["URL"], "boşluksuz # yorum değil")
        assertEquals("", m["EMPTY"])
        assertEquals("tek \${tırnak} # yorum değil", m["SINGLE"])
        assertEquals("satır1\nsatır2 \"alıntı\" \\ son", m["DOUBLE"])
        assertEquals("ilk\nikinci", m["MULTI"])
        assertEquals("-----BEGIN-----\nabc\n-----END-----", m["PEM"])
        assertEquals(listOf("NAME", "URL", "EMPTY", "SINGLE", "DOUBLE", "MULTI", "PEM", "PORT"), parsed.entries.map { it.name })
        assertTrue(parsed.errors.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.problems.size, "tekrar eden ad uyarısı")
        assertTrue(parsed.problems.single().warning)
        assertEquals(13, parsed.problems.single().line)

        val bad = EnvFile.parse("1ABC=x\nNO_EQUALS\nOK=1\nOPEN=\"kapanmadı\n")
        assertEquals(listOf(1, 2, 4), bad.errors.map { it.line })
        assertEquals(mapOf("OK" to "1"), bad.toMap())

        // Windows satır sonları ve BOM
        assertEquals(mapOf("A" to "1", "B" to "2"), EnvFile.parse("﻿A=1\r\nB=2\r\n").toMap())
    }

    @Test
    fun `yazma ve geri okuma ayni`() {
        val values = linkedMapOf(
            "PLAIN" to "abc-123_./:@%+,=",
            "SPACE" to "iki kelime",
            "QUOTE" to "o'nun \"evi\"",
            "LINES" to "a\nb\tc\\d",
            "EMPTY" to "",
            "HASH" to "#başta",
        )
        val text = EnvFile.format(values, comments = mapOf("PLAIN" to "açıklama"))
        assertTrue(text.startsWith("# açıklama\nPLAIN=abc-123_./:@%+,=\n"), text)
        assertTrue("SPACE='iki kelime'" in text, text)
        assertEquals(values, EnvFile.parse(text).toMap())
    }

    @Test
    fun `sirlar sifreli depoya ve geri`() {
        val sink = MemorySink()
        val entries = listOf(EnvFile.Entry("PORT", "3000"), EnvFile.Entry("API_TOKEN", "çok-gizli"), EnvFile.Entry("EMPTY_SECRET", ""))
        val env = EnvFile.toConfig(entries, secret = setOf("API_TOKEN", "EMPTY_SECRET"), sink = sink)
        assertEquals("3000", env["PORT"])
        assertEquals("secret:env_1", env["API_TOKEN"])
        assertEquals("", env["EMPTY_SECRET"], "boş değer depoya gitmez")
        assertEquals("çok-gizli", sink.values["env_1"])

        val loaded = EnvFile.fromConfig(env + ("GONE" to "secret:env_9"), sink.values::get)
        assertEquals(setOf("API_TOKEN", "GONE"), loaded.secret)
        assertEquals(setOf("GONE"), loaded.missing)
        assertEquals("çok-gizli", loaded.values.first { it.name == "API_TOKEN" }.value)

        assertTrue(EnvFile.looksSecret("stripe_secret_key"))
        assertTrue(!EnvFile.looksSecret("PORT"))
    }

    @Test
    fun `cozumleme, bilesen ve dogrulama`() {
        val cfg = ServerConfig(
            env = mapOf("DB_URL" to "secret:env_1", "MODE" to "prod"),
            components = listOf(ComponentConfig("redis", sharedEnv = true, env = mapOf("MODE" to "bilesen")), ComponentConfig("other")),
            apps = listOf(AppConfig("api", command = listOf("node", "a.js"), port = 3000, env = mapOf("K" to "secret:env_2"), git = GitSource(repo = "a/b", token = "secret:git_token_1"))),
        )
        val secrets = mapOf("env_1" to "postgres://x", "env_2" to "anahtar", "git_token_1" to "ghp_x")
        val resolved = ConfigSecrets.resolve(cfg, secrets::get)
        assertEquals("postgres://x", resolved.env["DB_URL"])
        assertEquals("anahtar", resolved.apps.single().env["K"])
        assertEquals("ghp_x", resolved.apps.single().git?.token)

        val comps = resolved.runtimeComponents().associateBy { it.id }
        assertEquals(mapOf("DB_URL" to "postgres://x", "MODE" to "bilesen"), comps.getValue("redis").env, "kendi değişkeni önceliklidir")
        assertEquals(emptyMap(), comps.getValue("other").env, "sharedEnv kapalıysa verilmez")

        // Git token'ı kaydederken depoya taşınır.
        val sink = MemorySink()
        val text = ServerConfig(apps = listOf(AppConfig("api", command = listOf("x"), port = 3000, git = GitSource(repo = "a/b", token = "ghp_duz", webhookSecret = "uzun-bir-webhook-sirri")))).toJson()
        val secured = ConfigSecrets.secureText(text, sink)!!
        assertTrue("ghp_duz" !in secured && "uzun-bir-webhook-sirri" !in secured, secured)
        assertEquals(setOf("ghp_duz", "uzun-bir-webhook-sirri"), sink.values.values.toSet())

        val errors = { c: ServerConfig -> ConfigValidator.validateDetailed(c).map { it.path }.toSet() }
        assertEquals(setOf("env"), errors(ServerConfig(env = mapOf("1BAD" to "x"))))
        assertEquals(setOf("env"), errors(ServerConfig(env = mapOf("BIG" to "x".repeat(EnvFile.MAX_VALUE + 1)))))
        assertEquals(setOf("components[0].env"), errors(ServerConfig(components = listOf(ComponentConfig("redis", env = mapOf("a-b" to "x"))))))
        assertEquals(emptySet(), errors(cfg))
    }

    @Test
    fun `komut satiri`() {
        assertEquals(listOf("node", "server.js"), CommandLine.split("  node   server.js "))
        assertEquals(listOf("java", "-Xmx256m", "-jar", "my app.jar"), CommandLine.split("java -Xmx256m -jar 'my app.jar'"))
        assertEquals(listOf("sh", "-c", "echo \"a b\" && ls"), CommandLine.split("sh -c 'echo \"a b\" && ls'"))
        assertEquals(listOf("a b", "c\\d", ""), CommandLine.split("a\\ b \"c\\\\d\" ''"))
        for (args in listOf(listOf("node", "a.js"), listOf("sh", "-c", "it's \"x\""), listOf("a b", "", "c\\d"))) {
            assertEquals(args, CommandLine.split(CommandLine.join(args)), args.toString())
        }
    }
}
