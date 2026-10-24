package com.plyg.droidserver.core.db

import com.plyg.droidserver.core.i18n.tr
import java.io.Closeable

/**
 * Veritabanı soyutlaması. Çekirdek Android'e bağımlı olmasın diye arayüz burada,
 * Android SQLite uygulaması `app` modülünde, JDBC uygulaması ise aşağıda.
 *
 * Tüm çağrılar bloklayıcıdır; HTTP katmanı bunları Dispatchers.IO üzerinde çağırır.
 */
interface SqlDriver : Closeable {
    /** Motor-özel SQL (şema sorguları, tip eşlemesi, hata tanıma). */
    val dialect: SqlDialect

    /** DDL veya UPDATE/DELETE. Etkilenen satır sayısını döndürür (DDL için 0). */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /** INSERT; oluşan satırın id'sini döndürür. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    /** SELECT / PRAGMA. Sütun adı -> değer (Long, Double, String, ByteArray veya null). */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>

    fun <T> transaction(block: () -> T): T

    /**
     * SQL konsolu: [sql]'i ayrı, salt okunur bir bağlantıda çalıştırır (yazma veritabanı katmanında da reddedilir);
     * en çok [maxRows] satır, [timeoutSec] saniyede iptal. Önce [SqlConsole.check] çağrılmalıdır.
     */
    fun readOnly(sql: String, maxRows: Int, timeoutSec: Int): ReadOnlyResult =
        throw UnsupportedOperationException(tr("Bu veritabanında SQL konsolu desteklenmiyor"))
}

/** SQL tanımlayıcısını tırnaklar. Adlar ConfigValidator'dan geçtiği için ek güvenlik katmanıdır. */
fun quoteIdent(name: String): String {
    require(name.matches(Regex("^[a-z_][a-z0-9_]*$"))) { tr("Geçersiz tanımlayıcı: {0}", name) }
    return "\"$name\""
}
