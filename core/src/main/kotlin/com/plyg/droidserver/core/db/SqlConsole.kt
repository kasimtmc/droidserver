package com.plyg.droidserver.core.db

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.i18n.tr

/** Salt okunur sorgunun sonucu: sütunlar sırasıyla, satırlar ve [maxRows]'u aştığı için kesildi mi. */
data class ReadOnlyResult(val columns: List<String>, val rows: List<List<Any?>>, val truncated: Boolean)

/**
 * Paneldeki SQL konsolunun ilk denetimi (asıl güvence salt okunur bağlantıdır, bkz. [SqlDriver.readOnly]): tek ifade,
 * `SELECT` / `WITH` / `VALUES` ile başlar, yazma ve yönetim komutları yoktur. Yönetici olsa da imza anahtarının durduğu
 * `_ds_meta`, parola ve oturum özetleri ve Postgres'in parola katalogları ile sunucu dosyalarını okuyan işlevler
 * sorgulanamaz (sızarsa kalıcı token sahteciliğine veya telefondaki dosyalara yol açar).
 */
object SqlConsole {
    const val DEFAULT_LIMIT = 200
    const val MAX_LIMIT = 1_000
    const val TIMEOUT_SEC = 5

    private val WRITE = setOf(
        "insert", "update", "delete", "drop", "alter", "create", "attach", "detach", "pragma", "vacuum", "replace", "truncate",
        "grant", "revoke", "copy", "call", "do", "lock", "reindex", "analyze", "set", "reset", "listen", "notify", "begin",
        "commit", "rollback", "savepoint", "release", "merge", "upsert", "into",
    )
    private val BLOCKED = setOf(
        "_ds_meta", "password_hash", "token_hash", "_two_factor", "pg_authid", "pg_shadow", "pg_user_mapping",
        "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file", "lo_import", "lo_export", "lo_get", "dblink",
        "load_extension", "readfile", "writefile",
    )
    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    /** Sorguyu denetler; sonundaki `;` atılmış hali. */
    fun check(sql: String): String {
        val text = sql.trim().trimEnd(';').trim()
        if (text.isEmpty()) throw ApiException(400, tr("Sorgu boş"))
        if (text.length > 20_000) throw ApiException(400, tr("Sorgu çok uzun"))
        val code = stripLiterals(text)
        if (';' in code) throw ApiException(400, tr("Tek bir sorgu yazın (; ile birden çok ifade olmaz)"))
        val words = WORD.findAll(code).map { it.value.lowercase() }.toList()
        if (words.firstOrNull() !in setOf("select", "with", "values")) throw ApiException(400, tr("Yalnızca SELECT sorguları çalıştırılabilir"))
        words.firstOrNull { it in WRITE }?.let { throw ApiException(400, tr("Salt okunur konsol: '{0}' kullanılamaz", it.uppercase())) }
        val quoted = Regex("\"([^\"]*)\"").findAll(code).map { it.groupValues[1].lowercase() }
        (words + quoted).firstOrNull { it in BLOCKED }?.let { throw ApiException(403, tr("'{0}' konsoldan sorgulanamaz", it)) }
        return text
    }

    /** Dize sabitlerini ve yorumları boşlukla değiştirir (içlerindeki ; ve anahtar kelimeler sayılmasın). */
    private fun stripLiterals(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' -> {
                    i++
                    while (i < s.length) {
                        if (s[i] == '\'' && s.getOrNull(i + 1) == '\'') i += 2
                        else if (s[i] == '\'') break
                        else i++
                    }
                    if (i >= s.length) throw ApiException(400, tr("Kapanmamış dize"))
                    out.append(' ')
                    i++
                }
                c == '-' && s.getOrNull(i + 1) == '-' -> {
                    while (i < s.length && s[i] != '\n') i++
                    out.append(' ')
                }
                c == '/' && s.getOrNull(i + 1) == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) throw ApiException(400, tr("Kapanmamış yorum"))
                    out.append(' ')
                    i = end + 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
