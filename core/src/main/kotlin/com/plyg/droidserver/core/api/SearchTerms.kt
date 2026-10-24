package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import java.time.Instant

/**
 * Tam metin dizini (`collections[].fullText`): kaydın arama metnindeki (`_search`) her kelime, geçme sayısıyla
 * `_search_terms` tablosunda. Arama "kelime başı" eşleşmesidir (`?q=ist` "İstanbul"u bulur, "Bistro"yu bulmaz) ve
 * kelimenin ilk 3 harfi indeksli olduğundan tablo taraması gerekmez. Sonuçlar alakaya (eşleşen kelimelerin geçme
 * sayısı) göre sıralanabilir. Dizin taşınabilir SQL'dir: SQLite ve PostgreSQL aynı sonucu verir (FTS4 ile tsvector'ün
 * kelime ayırma ve sıralama farkları yok; Android 8'in SQLite'ında da çalışır).
 */
object SearchTerms {
    const val PREFIX = 3
    private val TABLE = quoteIdent(SystemCollections.SEARCH_TERMS)
    private const val ROWS_PER_INSERT = 100 // eski SQLite'ta en çok 999 bağlanan değer

    /** Kaydın kelimelerini yeniden yazar ([text]: normalleştirilmiş arama metni; null/boş: kelime yok). */
    fun index(db: SqlDriver, collection: String, id: Long, text: String?, now: Instant) {
        delete(db, collection, id)
        val tokens = SearchText.tokens(text.orEmpty())
        if (tokens.isEmpty()) return
        val ts = db.dialect.encode(FieldType.DATETIME, now)
        for (chunk in tokens.entries.chunked(ROWS_PER_INSERT)) {
            val values = chunk.joinToString { "(?, ?, ?, ?, ?, ?, ?)" }
            val args = chunk.flatMap { (term, tf) -> listOf(collection, id, term.take(PREFIX), term, tf.toLong(), ts, ts) }
            db.execute(
                "INSERT INTO $TABLE (\"collection\", \"record\", \"prefix\", \"term\", \"tf\", \"created_at\", \"updated_at\") VALUES $values",
                args,
            )
        }
    }

    fun delete(db: SqlDriver, collection: String, id: Long) {
        db.execute("DELETE FROM $TABLE WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, id))
    }

    fun deleteAll(db: SqlDriver, collection: String) {
        db.execute("DELETE FROM $TABLE WHERE \"collection\" = ?", listOf(collection))
    }

    /** Kelime başı koşulu ([alias] tablo takma adı); bağlanacak değerler [args]'a eklenir. */
    fun match(alias: String, collection: String, token: String, args: MutableList<Any?>): String {
        args += collection
        val n = token.length
        return if (n >= PREFIX) {
            args += token.take(PREFIX)
            args += token
            "$alias.\"collection\" = ? AND $alias.\"prefix\" = ? AND substr($alias.\"term\", 1, $n) = ?"
        } else {
            args += token
            "$alias.\"collection\" = ? AND substr($alias.\"term\", 1, $n) = ?"
        }
    }

    /** Her kelime için "kayıt bu kelime başını içerir" koşulu ([idColumn] dış sorgunun kimlik sütunu). */
    fun filter(idColumn: String, collection: String, tokens: List<String>, args: MutableList<Any?>): List<String> =
        tokens.map { t -> "$idColumn IN (SELECT s.\"record\" FROM $TABLE s WHERE ${match("s", collection, t, args)})" }

    /** Alaka: eşleşen kelimelerin toplam geçme sayısı (büyük önce). */
    fun relevance(idColumn: String, collection: String, tokens: List<String>, args: MutableList<Any?>): String {
        val any = tokens.joinToString(" OR ") { t -> "(${match("r", collection, t, args)})" }
        return "(SELECT COALESCE(SUM(r.\"tf\"), 0) FROM $TABLE r WHERE r.\"record\" = $idColumn AND ($any))"
    }
}
