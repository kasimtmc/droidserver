package com.plyg.droidserver.core

import com.plyg.droidserver.core.help.HelpDocs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 17.14: README'den uygulama içi yardım bölümleri. */
class HelpDocsTest {
    @Test
    fun `bolumler, kod blogu ve gelistirici bolumleri`() {
        val md = """
            |# DroidServer
            |
            |**Türkçe** | [English](README.en.md)
            |
            |Giriş metni.
            |
            |## Mimari
            |iç yapı
            |
            |## API
            |API metni.
            |```bash
            |## bu bir yorum, başlık değil
            |```
            |### İki adımlı doğrulama
            |Kod ister.
            |## Proje yapısı
            |### Alt
            |gizli
        """.trimMargin()
        val s = HelpDocs.sections(md, "Genel bakış")
        assertEquals(listOf("Genel bakış", "API", "İki adımlı doğrulama"), s.map { it.title })
        assertEquals("Giriş metni.", s[0].body)
        assertTrue("## bu bir yorum" in s[1].body, "kod bloğundaki # başlık sayılmaz")
        assertEquals("API", s[2].parent)
        assertEquals(listOf("İki adımlı doğrulama"), HelpDocs.search(s, "İKİ").map { it.title })
        assertEquals(listOf("API"), HelpDocs.search(s, "yorum").map { it.title }, "metinde (kod bloğunda) geçen")
    }

    @Test
    fun `gercek readme dosyalari`() {
        for (name in listOf("README.md", "README.en.md")) {
            val file = listOf(File("../$name"), File(name)).first { it.exists() }
            val s = HelpDocs.sections(file.readText(), "-")
            assertTrue(s.size > 40, "$name: ${s.size} bölüm")
            assertFalse(s.any { it.title.startsWith("Proje yapısı") || it.title.startsWith("Project structure") })
            assertTrue(s.any { it.title.startsWith("MQTT") }, name)
        }
    }
}
