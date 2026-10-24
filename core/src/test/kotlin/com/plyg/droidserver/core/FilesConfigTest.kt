package com.plyg.droidserver.core

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.StaticConfig
import com.plyg.droidserver.core.files.FileStore
import com.plyg.droidserver.core.files.MimeTypes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesConfigTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `icerikten tip tespiti`() {
        assertEquals("image/png", MimeTypes.sniff(FilesHttpTest.PNG).mime)
        assertEquals("image/jpeg", MimeTypes.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)).mime)
        assertEquals("image/gif", MimeTypes.sniff("GIF89a....".toByteArray()).mime)
        assertEquals("image/webp", MimeTypes.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()).mime)
        assertEquals("image/heic", MimeTypes.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray()).mime)
        assertEquals("video/mp4", MimeTypes.sniff("\u0000\u0000\u0000\u0018ftypisom".toByteArray()).mime)
        assertEquals("application/pdf", MimeTypes.sniff("%PDF-1.4".toByteArray()).mime)
        assertEquals("application/zip", MimeTypes.sniff(bytes(0x50, 0x4B, 0x03, 0x04)).mime)
        assertEquals("docx", MimeTypes.sniff(bytes(0x50, 0x4B, 0x03, 0x04), "docx").ext, "zip biçimli belge uzantısını korur")
        assertEquals("audio/mpeg", MimeTypes.sniff("ID3\u0004".toByteArray()).mime)
        assertEquals("text/plain", MimeTypes.sniff("merhaba dünya".toByteArray()).mime)
        assertEquals("text/csv", MimeTypes.sniff("a,b\n1,2".toByteArray(), "csv").mime)
        assertEquals("txt", MimeTypes.sniff("<svg onload=alert(1)>".toByteArray(), "svg").ext, "SVG/HTML metin olarak saklanır")
        assertEquals("text/plain", MimeTypes.sniff("ğ".toByteArray().copyOf(1)).mime, "yarım kalan UTF-8 karakter")
        assertEquals(MimeTypes.OCTET, MimeTypes.sniff(bytes(0x00, 0x01, 0x02)))

        assertNotNull(MimeTypes.extensionMismatch("resim.png", MimeTypes.sniff("<html>".toByteArray())))
        assertNotNull(MimeTypes.extensionMismatch("belge.PDF", MimeTypes.sniff(FilesHttpTest.PNG)))
        assertNull(MimeTypes.extensionMismatch("foto.jpeg", MimeTypes.sniff(bytes(0xFF, 0xD8, 0xFF))))
        assertNull(MimeTypes.extensionMismatch("veri.json", MimeTypes.sniff("{}".toByteArray())))
        assertNull(MimeTypes.extensionMismatch("adsiz", MimeTypes.sniff(FilesHttpTest.PNG)))
        assertNull(MimeTypes.extensionMismatch("arsiv.tar.xyz", MimeTypes.OCTET))

        assertTrue(MimeTypes.matches("image/png", listOf("image/*")))
        assertFalse(MimeTypes.matches("application/pdf", listOf("image/*", "audio/mpeg")))
        assertTrue(MimeTypes.matches("application/pdf", null))
    }

    @Test
    fun `dosya adlari guvenli`() {
        val store = FileStore(File("/tmp/yok"))
        val name = store.newName("../../Çok Güzel Foto!.PNG", "png")
        assertTrue(FileStore.NAME.matches(name), name)
        assertTrue(name.startsWith("ok_g_zel_foto_") || name.startsWith("_ok") || name.contains("foto"), name)
        assertNull(store.file("c", 1, "../x.png"))
        assertNull(store.file("c", 1, "a/b.png"))
        assertNotNull(store.file("c", 1, "a_b.png"))
    }

    @Test
    fun `dogrulama kurallari`() {
        fun paths(c: ServerConfig) = ConfigValidator.validateDetailed(c).map { it.path }.toSet()
        val ok = ServerConfig(
            collections = listOf(
                CollectionConfig("c", listOf(FieldConfig("f", FieldType.FILE, maxSizeMb = 5, mimeTypes = listOf("image/*", "application/pdf"), thumbs = listOf("200x0")))),
            ),
            static = StaticConfig(enabled = true, path = "/"),
        )
        assertEquals(emptySet(), paths(ok))
        val bad = ok.copy(
            collections = listOf(
                CollectionConfig(
                    "c",
                    listOf(
                        FieldConfig("f", FieldType.FILE, maxSizeMb = 0, mimeTypes = listOf("resim"), thumbs = listOf("0x0", "abc"), unique = true),
                        FieldConfig("t", FieldType.TEXT, multiple = true),
                    ),
                ),
            ),
            static = StaticConfig(enabled = true, path = "/api/site"),
        )
        assertEquals(
            setOf(
                "collections[0].fields[0].maxSizeMb", "collections[0].fields[0].mimeTypes[0]", "collections[0].fields[0].thumbs[0]",
                "collections[0].fields[0].thumbs[1]", "collections[0].fields[0].unique", "collections[0].fields[1].type", "static.path",
            ),
            paths(bad),
        )
        assertEquals(setOf("static.path"), paths(ok.copy(static = StaticConfig(enabled = true, path = "site/"))))
        assertEquals(emptySet(), paths(ok.copy(static = StaticConfig(enabled = false, path = "kotu"))), "kapalıyken yol denetlenmez")
        assertEquals(
            setOf("collections[0].fields[0].default"),
            paths(ok.copy(collections = listOf(CollectionConfig("c", listOf(FieldConfig("f", FieldType.FILE, default = kotlinx.serialization.json.JsonPrimitive("x"))))))),
        )
    }
}
