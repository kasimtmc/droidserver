package com.plyg.droidserver.core.auth

import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Kullanıcıların bağlı Telegram sohbetleri (`_telegram`: kullanıcı başına bir sohbet, sohbet başına bir hesap).
 * Bağlama kodu bellekte durur (10 dakika, tek kullanımlık): kullanıcı `t.me/<bot>?start=<kod>` bağlantısıyla botta
 * "Başlat"a dokununca [TelegramUserBot] kodu [link]'e getirir. Yazan işlemler çağıranın yazma kapısından geçer.
 */
class TelegramAccounts(private val db: SqlDriver, private val clock: () -> Instant = Instant::now) {
    private val t = quoteIdent(SystemCollections.TELEGRAM)
    private val dialect = db.dialect
    private val random = SecureRandom()

    private class Pending(val user: Long, val expires: Instant)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Yeni bağlama kodu; kullanıcının önceki kodu geçersizleşir. Telegram'ın `start` değeri: en çok 64 karakter, harf, rakam, `_` ve `-`. */
    fun newLinkCode(user: Long): String {
        val now = clock()
        pending.entries.removeIf { it.value.expires < now || it.value.user == user }
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        pending[code] = Pending(user, now.plus(LINK_TTL))
        return code
    }

    /**
     * [code]'un kullanıcısını [chatId] sohbetine bağlar; kod geçersiz ya da süresi dolmuşsa null. Sohbet başka bir hesaba
     * bağlıysa oradan, kullanıcının eski sohbeti de kaldırılır.
     */
    fun link(code: String, chatId: String, username: String?): Long? {
        val p = pending.remove(code)?.takeIf { !it.expires.isBefore(clock()) } ?: return null
        val user = rel(p.user)
        db.execute("DELETE FROM $t WHERE \"chat_id\" = ? OR \"user\" = ?", listOf(chatId, user))
        val now = dialect.encode(FieldType.DATETIME, clock())
        db.execute(
            "INSERT INTO $t (\"user\", \"chat_id\", \"username\", \"created_at\", \"updated_at\") VALUES (?, ?, ?, ?, ?)",
            listOf(user, chatId, username, now, now),
        )
        return p.user
    }

    /** `{linked, username}`. */
    fun status(user: Long): JsonObject {
        val row = row(user)
        return buildJsonObject {
            put("linked", JsonPrimitive(row != null))
            put("username", row?.get("username")?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    /** Kullanıcının sohbeti; bağlı değilse null. */
    fun chatOf(user: Long): String? = row(user)?.get("chat_id")?.toString()

    /** Bağı kaldırır; bağlı değilse false. */
    fun unlink(user: Long): Boolean = db.execute("DELETE FROM $t WHERE \"user\" = ?", listOf(rel(user))) > 0

    /** Sohbetin bağını kaldırır (botta `/ayril` veya kullanıcı botu engelledi); bağlı olduğu kullanıcı, yoksa null. */
    fun unlinkChat(chatId: String): Long? {
        val user = db.query("SELECT \"user\" FROM $t WHERE \"chat_id\" = ?", listOf(chatId)).firstOrNull()
            ?.let { dialect.decode(FieldType.RELATION, it["user"]) as? Long } ?: return null
        db.execute("DELETE FROM $t WHERE \"chat_id\" = ?", listOf(chatId))
        return user
    }

    /** [ids] içinden Telegram'ı bağlı olanlar (yönetici listesi). */
    fun linkedAmong(ids: List<Long>): Set<Long> = if (ids.isEmpty()) emptySet() else
        db.query("SELECT \"user\" FROM $t WHERE \"user\" IN (${ids.joinToString { "?" }})", ids.map(::rel))
            .mapNotNull { dialect.decode(FieldType.RELATION, it["user"]) as? Long }.toSet()

    private fun row(user: Long): Map<String, Any?>? = db.query("SELECT * FROM $t WHERE \"user\" = ?", listOf(rel(user))).firstOrNull()

    private fun rel(id: Long) = dialect.encode(FieldType.RELATION, id)

    companion object {
        val LINK_TTL: Duration = Duration.ofMinutes(10)
    }
}
