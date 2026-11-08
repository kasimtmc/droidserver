package com.plyg.droidserver.core.auth

import com.plyg.droidserver.core.alerts.TelegramChannel
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * Sunucunun kullanıcılarına Telegram botu (`auth.users.telegram`): hesap bağlama (`/start <kod>`), bağı kaldırma
 * (`/ayril`) ve kullanıcıya mesaj ([send]). Sunucu çalışırken uzun yoklamayla (`getUpdates`) dinlenir, telefona
 * dışarıdan bağlantı gerekmez; yalnızca özel sohbetler işlenir. Bot metinleri sunucunun arayüz dilindedir.
 */
class TelegramUserBot(
    private val token: String,
    val accounts: TelegramAccounts,
    private val writes: WriteGate,
    private val serverName: String,
    /** Bağlanan hesabın e-postası (bota "bağlandı" yazarken). */
    private val emailOf: (Long) -> String?,
    private val log: (String) -> Unit,
    /** Denetim kaydı: `user.telegram.link`, `user.telegram.unlink`, `user.telegram.blocked`. */
    private val audit: (action: String, user: Long) -> Unit = { _, _ -> },
    private val base: String = TelegramChannel.API,
) {
    /** Botun kullanıcı adı (`getMe`); bağlantı adresi için. Bota henüz ulaşılamadıysa null. */
    @Volatile
    var username: String? = null
        private set

    @Volatile
    private var stopped = false
    private var offset = 0L

    /** İptal edilene kadar dinler; ağ hatasında giderek uzayan aralıkla (5 sn → 5 dk) yeniden dener. */
    suspend fun run() {
        var failures = 0
        while (currentCoroutineContext().isActive && !stopped) {
            try {
                if (username == null) identify()
                poll(POLL_SEC)
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Yeniden deploy'da yeni dinleyici eskisinin isteğini keser (409); durdurulan bot bunu yazmaz.
                if (stopped || !currentCoroutineContext().isActive) break
                if (failures == 0) log(tr("Telegram kullanıcı botu: {0}", e.message))
                delay(minOf(300_000L, 5_000L shl minOf(failures++, 6)))
            }
        }
    }

    /** Sunucu duruyor: süren yoklamanın getirdikleri işlenmez (bir sonraki dinleyici yeniden alır). */
    fun stop() {
        stopped = true
    }

    /** Botun adını alır ve komut menüsünü yazar. */
    fun identify() {
        val me = Json.parseToJsonElement(api("getMe", "")).jsonObject["result"] as? JsonObject
        username = (me?.get("username") as? JsonPrimitive)?.content
        val commands = buildJsonArray {
            add(buildJsonObject { put("command", JsonPrimitive("ayril")); put("description", JsonPrimitive(tr("Telegram bağını kaldır"))) })
        }
        runCatching { api("setMyCommands", "commands=${enc(commands.toString())}") }
        log(tr("Telegram kullanıcı botu dinleniyor: @{0}", username))
    }

    /** Bir yoklama turu ([timeoutSec] sn bekler); işlenen güncelleme sayısı. */
    fun poll(timeoutSec: Int): Int {
        val body = api("getUpdates", "offset=$offset&timeout=$timeoutSec&allowed_updates=${enc("[\"message\"]")}", (timeoutSec + 15) * 1000)
        if (stopped) return 0
        val updates = (Json.parseToJsonElement(body).jsonObject["result"] as? JsonArray).orEmpty()
        for (u in updates) {
            val o = u as? JsonObject ?: continue
            (o["update_id"] as? JsonPrimitive)?.longOrNull?.let { offset = maxOf(offset, it + 1) }
            runCatching { handle(o) }.onFailure { log(tr("Telegram kullanıcı botu mesajı işleyemedi: {0}", it.message)) }
        }
        return updates.size
    }

    /** Hesabı bağlama adresi: `https://t.me/<bot>?start=<kod>` (kod 10 dakika, tek kullanımlık). */
    fun linkUrl(user: Long): String {
        val name = username ?: throw ApiException(503, tr("Telegram botuna henüz ulaşılamadı; biraz sonra yeniden deneyin"))
        return "https://t.me/$name?start=${accounts.newLinkCode(user)}"
    }

    /** Kullanıcı bağı uygulamadan kaldırır; bağlı değilse false. */
    fun unlink(user: Long): Boolean = writes.write { accounts.unlink(user) }.also { if (it) audit("user.telegram.unlink", user) }

    /**
     * [user]'ın bağlı sohbetine mesaj. Bağlı değilse false; kullanıcı botu engellediyse (Telegram 403) bağ düşer ve
     * false. Başka hatalarda [IOException].
     */
    fun send(user: Long, text: String): Boolean {
        val chat = accounts.chatOf(user) ?: return false
        return try {
            api("sendMessage", "chat_id=${enc(chat)}&text=${enc(text)}&disable_web_page_preview=true")
            true
        } catch (e: IOException) {
            if (e.message?.startsWith("Telegram 403") != true) throw e
            writes.write { accounts.unlinkChat(chat) }?.let { audit("user.telegram.blocked", it) }
            log(tr("Telegram: kullanıcı #{0} botu engellemiş; bağı kaldırıldı", user))
            false
        }
    }

    private fun handle(update: JsonObject) {
        val message = update["message"] as? JsonObject ?: return
        val chat = message["chat"] as? JsonObject ?: return
        if ((chat["type"] as? JsonPrimitive)?.content != "private") return
        val chatId = (chat["id"] as? JsonPrimitive)?.content ?: return
        val text = (message["text"] as? JsonPrimitive)?.content?.trim() ?: return reply(chatId, about())
        val parts = text.split(Regex("\\s+"), limit = 2)
        when (parts[0].substringBefore('@').lowercase()) {
            "/start" -> {
                val code = parts.getOrNull(1)?.trim()
                if (code.isNullOrEmpty()) return reply(chatId, about())
                val handle = ((message["from"] as? JsonObject)?.get("username") as? JsonPrimitive)?.content
                val user = writes.write { accounts.link(code, chatId, handle) }
                if (user == null) {
                    reply(chatId, tr("Bu bağlantının süresi dolmuş ya da daha önce kullanılmış. Uygulamadan yeni bir bağlantı alın."))
                } else {
                    audit("user.telegram.link", user)
                    reply(chatId, tr("{0}: hesabınız bağlandı ({1}). Giriş kodları ve bildirimler bu sohbete gelecek; bağı kaldırmak için /ayril yazın.", serverName, emailOf(user) ?: "#$user"))
                }
            }
            "/ayril" -> {
                val user = writes.write { accounts.unlinkChat(chatId) }
                if (user != null) audit("user.telegram.unlink", user)
                reply(chatId, if (user != null) tr("Bağ kaldırıldı; bu sohbete artık mesaj gelmeyecek.") else tr("Bu sohbet bir hesaba bağlı değil."))
            }
            else -> reply(chatId, about())
        }
    }

    private fun about() =
        tr("Bu bot {0} hesabınızı Telegram'a bağlamak içindir; bağlantıyı uygulamadan alın. Bağı kaldırmak için /ayril yazın.", serverName)

    private fun reply(chatId: String, text: String) {
        api("sendMessage", "chat_id=${enc(chatId)}&text=${enc(text)}&disable_web_page_preview=true")
    }

    private fun api(method: String, form: String, readTimeoutMs: Int = 15_000) = TelegramChannel.call(base, token, method, form, readTimeoutMs)

    private fun enc(s: String) = TelegramChannel.enc(s)

    companion object {
        const val POLL_SEC = 50
    }
}
