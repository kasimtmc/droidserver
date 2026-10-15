package dev.droidserver.core.api

import dev.droidserver.core.config.CollectionConfig
import java.text.Normalizer
import java.util.Locale

/**
 * Aramanın metin biçimi: büyük/küçük harf, Türkçe karakterler ve aksanlardan bağımsız (İ/I/ı → i, ş → s, ğ → g,
 * ü → u, ö → o, ç → c, é → e). Kayıt yazılırken arama alanlarının bu biçimi gizli `_search` sütununa yazılır, sorgu
 * da aynı biçime çevrilip `LIKE` ile aranır; böylece SQLite ve PostgreSQL aynı sonucu verir.
 */
object SearchText {
    const val COLUMN = "_search"
    private val SPACE = Regex("\\s+")

    fun normalize(text: String): String {
        val dotless = buildString(text.length) {
            for (ch in text) append(if (ch == 'İ' || ch == 'I' || ch == 'ı') 'i' else ch)
        }
        // Ayrıştırılmış harfin işaretleri (ş'nin çengeli, ü'nün noktaları) atılır.
        val bare = Normalizer.normalize(dotless, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return bare.lowercase(Locale.ROOT).replace(SPACE, " ").trim()
    }

    /** Arama alanlarının değerlerinden `_search` değeri (boşsa boş metin; null "hesaplanmamış" demektir). */
    fun of(collection: CollectionConfig, values: (String) -> Any?): String =
        collection.search.mapNotNull { values(it)?.toString()?.takeIf(String::isNotBlank) }.joinToString(" ", transform = ::normalize)

    /** `?q=` → aranacak kelimeler (hepsi geçmeli); en çok 10. */
    fun terms(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }.distinct().take(10)

    /** Tam metin dizininde kelimenin en çok uzunluğu (uzunlar kırpılır) ve kayıt başına en çok kelime. */
    const val MAX_TERM = 64
    private const val MAX_TERMS = 2000

    /** Normalleştirilmiş metnin kelimeleri (harf ve rakam dizileri) ve geçme sayıları (tam metin dizini). */
    fun tokens(normalized: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            val t = sb.toString().take(MAX_TERM)
            sb.setLength(0)
            if (t in out || out.size < MAX_TERMS) out[t] = (out[t] ?: 0) + 1
        }
        for (ch in normalized) if (ch.isLetterOrDigit()) sb.append(ch) else flush()
        flush()
        return out
    }

    /** `?q=` → tam metin dizininde aranacak kelime başları (hepsi geçmeli); en çok 10. */
    fun queryTokens(query: String): List<String> = tokens(normalize(query)).keys.take(10).toList()
}
