package com.plyg.droidserver.core

import com.plyg.droidserver.core.i18n.I18n
import com.plyg.droidserver.core.i18n.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    @Test
    fun `ceviri ve yer tutucular`() {
        assertEquals("Kaydet", I18n.translate("Kaydet", emptyArray(), Lang.TR))
        assertEquals("Save", I18n.translate("Kaydet", emptyArray(), Lang.EN))
        assertEquals("Error: disk", I18n.translate("Hata: {0}", arrayOf("disk"), Lang.EN))
        assertEquals("Hata: disk", I18n.translate("Hata: {0}", arrayOf("disk"), Lang.TR))
        assertEquals("katalogda yok {0}", I18n.translate("katalogda yok {0}", emptyArray(), Lang.EN), "bilinmeyen metin olduğu gibi")
        assertEquals(Lang.EN, Lang.of("en-US"))
        assertEquals(Lang.TR, Lang.of("tr_TR"))
        assertEquals(null, Lang.of("de"))
        val panel = I18n.panelScript(Lang.EN)
        assertTrue(panel.startsWith("window.DS_LANG=\"en\";window.DS_I18N={"))
        assertTrue("\"Kaydet\":\"Save\"" in panel)
        assertEquals("window.DS_LANG=\"tr\";window.DS_I18N={};\n", I18n.panelScript(Lang.TR))
    }

    /**
     * Kaynaktaki her `tr("…")` (uygulama + core) ve panelin `T("…")` metni katalogda olmalı, yer tutucuları ve biçim
     * belirteçleri aynı olmalı; katalogda kaynakta kalmamış metin olmamalı. Yeni metin eklenince `en.json`'a eklenir.
     */
    @Test
    fun `katalog kaynakla eslesiyor`() {
        val kotlin = (listOf("src/main/kotlin", "../app/src/main/kotlin", "../app/src/full/kotlin", "../app/src/play/kotlin").flatMap { sourceFiles(File(it)) }).flatMap { keys(it.readText(), KT_CALL, ::unescapeKotlin) }.toSet()
        val panel = keys(File("src/main/resources/admin/app.js").readText(), JS_CALL, ::unescapeJs).toSet()
        assertTrue(kotlin.size > 1000 && panel.size > 100, "kaynak taranamadı: ${kotlin.size}, ${panel.size}")
        check("en.json", kotlin)
        check("panel-en.json", panel)
    }

    private fun check(name: String, keys: Set<String>) {
        val catalog = Json.parseToJsonElement(File("src/main/resources/i18n/$name").readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        val missing = keys - catalog.keys
        assertTrue(missing.isEmpty(), "$name içinde eksik: ${missing.take(20)}")
        val stale = catalog.keys - keys
        assertTrue(stale.isEmpty(), "$name içinde kullanılmayan: ${stale.take(20)}")
        val bad = catalog.filter { (k, v) -> marks(k) != marks(v) }
        assertTrue(bad.isEmpty(), "$name yer tutucusu farklı: ${bad.entries.take(10)}")
        val templates = keys.filter { TEMPLATE.containsMatchIn(it) }
        assertTrue(templates.isEmpty(), "metinde \$ şablonu yerine {0} kullanın: $templates")
    }

    private fun sourceFiles(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun keys(src: String, call: Regex, unescape: (String) -> String): List<String> = call.findAll(src).map { m ->
        LITERAL.findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
    }.toList()

    private fun marks(s: String) = MARK.findAll(s).map { it.value }.sorted().toList()

    private fun unescapeKotlin(s: String) = ESCAPE.replace(s) { m ->
        val e = m.value.substring(1)
        when {
            e.startsWith("u") -> e.substring(1).toInt(16).toChar().toString()
            e == "n" -> "\n"
            e == "t" -> "\t"
            e == "r" -> "\r"
            else -> e
        }
    }

    private fun unescapeJs(s: String) = unescapeKotlin(s)

    private companion object {
        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'")
        private const val STR = "\"(?:[^\"\\\\]|\\\\.)*\""
        val KT_CALL = Regex("(?<![\\w.])tr\\(\\s*($STR(?:\\s*\\+\\s*$STR)*)")
        val JS_CALL = Regex("(?<![\\w.$])T\\(\\s*($STR(?:\\s*\\+\\s*$STR)*)")
        val ESCAPE = Regex("\\\\(u[0-9a-fA-F]{4}|.)")
        val MARK = Regex("\\{\\d\\}|%[-.\\d]*[sdf]")
        val TEMPLATE = Regex("\\$[{\\w]")
    }
}
