package com.plyg.droidserver.core.files

import com.plyg.droidserver.core.i18n.tr

/**
 * İçerik tipi dosyanın adına değil ilk baytlarına bakılarak bulunur. Kaydedilen dosyanın uzantısı tespit edilen
 * tipten gelir; indirirken de tip bu uzantıdan verilir. Böylece `resim.png` adıyla yüklenen bir HTML dosyası
 * tarayıcıda sayfa olarak çalıştırılamaz.
 */
object MimeTypes {
    data class Kind(val mime: String, val ext: String)

    /** İlk kaç bayta bakılır. */
    const val HEAD_BYTES = 512

    val OCTET = Kind("application/octet-stream", "bin")
    private val TEXT = Kind("text/plain", "txt")

    /** Bilinen uzantıların tipleri; adı bunlardan biriyle biten dosyanın içeriği de o tipte olmalı. */
    private val BY_EXT = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heic", "avif" to "image/avif",
        "pdf" to "application/pdf", "zip" to "application/zip", "gz" to "application/gzip",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp", "webm" to "video/webm",
        "mkv" to "video/webm",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
        "wav" to "audio/wav", "flac" to "audio/flac",
        "txt" to "text/plain", "csv" to "text/csv", "json" to "application/json", "md" to "text/markdown",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "epub" to "application/epub+zip",
        "bin" to "application/octet-stream",
    )

    /** Zip biçimindeki belgeler: içerik zip, tip uzantıdan. */
    private val ZIP_DOCS = setOf("docx", "xlsx", "pptx", "odt", "ods", "epub")

    /** Metin olarak tanınan içeriğe uzantısı korunan biçimler (HTML ve SVG gibi çalıştırılabilenler hariç). */
    private val TEXT_EXTS = setOf("txt", "csv", "json", "md")

    fun forExtension(ext: String): String = BY_EXT[ext.lowercase()] ?: OCTET.mime

    /**
     * İçerikten tip. [originalExt] yalnızca aynı biçimin alt türlerini ayırmak için kullanılır (zip → docx, metin → csv).
     */
    fun sniff(head: ByteArray, originalExt: String? = null): Kind {
        val ext = originalExt?.lowercase()
        fun at(offset: Int, vararg bytes: Int) =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        fun ascii(offset: Int, s: String) = at(offset, *s.map { it.code }.toIntArray())

        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Kind("image/png", "png")
            at(0, 0xFF, 0xD8, 0xFF) -> Kind("image/jpeg", "jpg")
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> Kind("image/gif", "gif")
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Kind("image/webp", "webp")
            ascii(0, "RIFF") && ascii(8, "WAVE") -> Kind("audio/wav", "wav")
            ascii(4, "ftyp") -> ftyp(String(head.copyOfRange(8, minOf(12, head.size)), Charsets.ISO_8859_1))
            ascii(0, "%PDF-") -> Kind("application/pdf", "pdf")
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) ->
                if (ext in ZIP_DOCS) Kind(BY_EXT.getValue(ext!!), ext) else Kind("application/zip", "zip")
            at(0, 0x1F, 0x8B) -> Kind("application/gzip", "gz")
            ascii(0, "OggS") -> Kind("audio/ogg", "ogg")
            ascii(0, "fLaC") -> Kind("audio/flac", "flac")
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> Kind("video/webm", "webm")
            ascii(0, "ID3") || head.size >= 2 && head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xE0 == 0xE0 ->
                Kind("audio/mpeg", "mp3")
            isText(head) -> if (ext in TEXT_EXTS) Kind(BY_EXT.getValue(ext!!), ext) else TEXT
            else -> OCTET
        }
    }

    private fun ftyp(brand: String): Kind = when (brand.trim()) {
        "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1" -> Kind("image/heic", "heic")
        "avif", "avis" -> Kind("image/avif", "avif")
        "qt" -> Kind("video/quicktime", "mov")
        "M4A", "M4B" -> Kind("audio/mp4", "m4a")
        "3gp4", "3gp5", "3gp6", "3g2a" -> Kind("video/3gpp", "3gp")
        else -> Kind("video/mp4", "mp4")
    }

    /** NUL içermeyen geçerli UTF-8 (sonda yarım kalmış karakter olabilir). */
    private fun isText(head: ByteArray): Boolean {
        if (head.isEmpty() || head.any { it.toInt() == 0 }) return false
        var i = 0
        while (i < head.size) {
            val b = head[i].toInt() and 0xFF
            val n = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + n >= head.size) return true // yarım karakter: kesilen baş
            for (k in 1..n) if (head[i + k].toInt() and 0xC0 != 0x80) return false
            i += n + 1
        }
        return true
    }

    /**
     * Adın uzantısı bilinen bir tipe aitse içerik o tipte olmalı (sahte uzantı reddedilir). Bilinmeyen veya uzantısız
     * adlarda sorun yok. Uyuşmazlıkta açıklama, uyumluysa null.
     */
    fun extensionMismatch(originalName: String, detected: Kind): String? {
        val ext = originalName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it != originalName.lowercase() }
            ?: return null
        val expected = BY_EXT[ext] ?: return null
        if (expected == detected.mime) return null
        // Aynı biçimin yazımları: jpeg/jpg, heif/heic, mkv/webm, metin türleri.
        if (expected.startsWith("text/") && detected.mime.startsWith("text/")) return null
        if (expected == "application/json" && detected.mime.startsWith("text/")) return null
        if (expected == "application/octet-stream") return null
        return tr("dosyanın içeriği ({0}) uzantısıyla (.{1} → {2}) uyuşmuyor", detected.mime, ext, expected)
    }

    /** Kalıplardan birine uyuyor mu: tam tip veya türün tamamı (alt tür yerine yıldız). */
    fun matches(mime: String, patterns: List<String>?): Boolean = patterns == null || patterns.any { p ->
        p == mime || p.endsWith("/*") && mime.startsWith(p.dropLast(1))
    }

    /** Tarayıcıda açılabilen (gömülü gösterilecek) tipler; diğerleri indirilir. */
    fun isInline(mime: String) = mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") ||
        mime == "application/pdf" || mime == "text/plain"

    /** Küçük resmi üretilebilen görseller. */
    fun isResizable(mime: String) = mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
}
