package dev.droidserver.core.alerts

import dev.droidserver.core.i18n.tr
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
import java.security.SecureRandom

/** Telegram komutlarının telefondaki karşılıkları (app verir). Dönen metin sohbete yazılır; bloklayıcı olabilir. */
interface TelegramActions {
    fun status(): String
    fun backup(): String
    fun logs(count: Int): List<String>
    fun start(): String
    fun stop(): String
}

/**
 * Uyarı botuna yönetim komutları: `/durum`, `/yedek`, `/loglar [n]`, `/baslat`, `/durdur` (onaylı), `/yardim`. Uzun
 * yoklamayla (`getUpdates`) çalışır, yani telefona dışarıdan bağlantı gerekmez. Yalnızca [adminChatId] sohbetinden gelen
 * komutlar işlenir; başka sohbetler yanıtsız kalır. Bot kapalıyken birikmiş [MAX_AGE_SEC]'ten eski komutlar yok sayılır
 * (örn. saatler önceki `/durdur` sonradan çalışmasın).
 */
class TelegramBot(
    private val token: String,
    private val adminChatId: String,
    private val actions: TelegramActions,
    private val log: (String) -> Unit,
    private val base: String = TelegramChannel.API,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var offset = 0L
    private val random = SecureRandom()

    /** Bekleyen `/durdur` onayı: belirteç ve istenme zamanı. */
    private var pendingStop: Pair<String, Long>? = null
    private val ignored = HashSet<String>()

    /** İptal edilene kadar komutları dinler; ağ hatasında giderek uzayan aralıkla (5 sn → 5 dk) yeniden dener. */
    suspend fun run() {
        runCatching { registerCommands() }
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                poll(POLL_SEC)
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failures == 0) log(tr("Telegram komutları alınamıyor: {0}", e.message))
                delay(minOf(300_000L, 5_000L shl minOf(failures++, 6)))
            }
        }
    }

    /** Bir yoklama turu ([timeoutSec] sn bekler); işlenen güncelleme sayısı. */
    fun poll(timeoutSec: Int): Int {
        val body = api("getUpdates", "offset=$offset&timeout=$timeoutSec&allowed_updates=${enc("[\"message\",\"callback_query\"]")}", (timeoutSec + 15) * 1000)
        val updates = (Json.parseToJsonElement(body).jsonObject["result"] as? JsonArray).orEmpty()
        for (u in updates) {
            val o = u as? JsonObject ?: continue
            (o["update_id"] as? JsonPrimitive)?.longOrNull?.let { offset = maxOf(offset, it + 1) }
            runCatching { handle(o) }.onFailure { log(tr("Telegram komutu işlenemedi: {0}", it.message)) }
        }
        return updates.size
    }

    private fun handle(update: JsonObject) {
        (update["callback_query"] as? JsonObject)?.let { return callback(it) }
        val message = update["message"] as? JsonObject ?: return
        val chat = message.chatId() ?: return
        if (chat != adminChatId) {
            if (ignored.add(chat)) log(tr("Telegram: yönetici sohbeti olmayan {0} sohbetinden gelen mesaj yok sayıldı", chat))
            return
        }
        val date = (message["date"] as? JsonPrimitive)?.longOrNull ?: 0
        if (clock() / 1000 - date > MAX_AGE_SEC) return
        val text = (message["text"] as? JsonPrimitive)?.content?.trim() ?: return
        if (!text.startsWith("/")) return send(help())
        val parts = text.split(Regex("\\s+"))
        val command = parts[0].substringBefore('@').lowercase()
        when (command) {
            "/durum" -> send(actions.status())
            "/yedek" -> {
                send(tr("Yedek alınıyor…"))
                send(runCatching { actions.backup() }.getOrElse { tr("Yedek alınamadı: {0}", it.message) })
            }
            "/loglar" -> {
                val n = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, MAX_LOGS) ?: DEFAULT_LOGS
                // Telegram mesajı en çok 4096 karakter: sondan sığanlar.
                val lines = actions.logs(n).ifEmpty { listOf(tr("Log yok.")) }
                send(lines.joinToString("\n").takeLast(MAX_TEXT))
            }
            "/baslat" -> send(actions.start())
            "/durdur" -> {
                val nonce = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
                pendingStop = nonce to clock()
                val keyboard = buildJsonObject {
                    put("inline_keyboard", buildJsonArray {
                        add(buildJsonArray {
                            add(button(tr("Evet, durdur"), "durdur:$nonce"))
                            add(button(tr("Vazgeç"), "vazgec:$nonce"))
                        })
                    })
                }
                send(tr("Sunucu durdurulsun mu? Uygulama Telegram'ı dinlemeye devam eder; /baslat ile yeniden başlatılır."), keyboard)
            }
            else -> send(help())
        }
    }

    private fun callback(q: JsonObject) {
        val id = (q["id"] as? JsonPrimitive)?.content ?: return
        val message = q["message"] as? JsonObject
        val data = (q["data"] as? JsonPrimitive)?.content.orEmpty()
        if (message?.chatId() != adminChatId) return answer(id, null)
        val (action, nonce) = data.substringBefore(':') to data.substringAfter(':')
        val pending = pendingStop
        val valid = pending != null && pending.first == nonce && clock() - pending.second <= CONFIRM_MS
        pendingStop = null
        val result = when {
            !valid -> tr("Onayın süresi doldu; yeniden /durdur yazın.")
            action == "durdur" -> actions.stop()
            else -> tr("Vazgeçildi.")
        }
        answer(id, null)
        // Onay düğmeleri kaldırılır, yerine sonuç yazılır.
        (message["message_id"] as? JsonPrimitive)?.content?.let { mid ->
            runCatching { api("editMessageText", "chat_id=${enc(adminChatId)}&message_id=$mid&text=${enc(result)}") }
        } ?: send(result)
        if (action == "durdur" && valid) log(tr("Telegram'dan sunucu durduruldu"))
    }

    private fun help(): String = buildString {
        appendLine(tr("Komutlar:"))
        COMMANDS.forEach { (c, d) -> appendLine("/$c — $d") }
    }.trimEnd()

    private fun registerCommands() {
        val list = buildJsonArray { COMMANDS.forEach { (c, d) -> add(buildJsonObject { put("command", JsonPrimitive(c)); put("description", JsonPrimitive(d)) }) } }
        // Komut menüsü yalnızca yönetici sohbetinde görünür.
        val scope = buildJsonObject { put("type", JsonPrimitive("chat")); put("chat_id", JsonPrimitive(adminChatId)) }
        api("setMyCommands", "commands=${enc(list.toString())}&scope=${enc(scope.toString())}")
    }

    private fun send(text: String, markup: JsonObject? = null) {
        api("sendMessage", "chat_id=${enc(adminChatId)}&text=${enc(text.ifBlank { "-" })}&disable_web_page_preview=true" + (markup?.let { "&reply_markup=${enc(it.toString())}" } ?: ""))
    }

    private fun answer(id: String, text: String?) {
        runCatching { api("answerCallbackQuery", "callback_query_id=${enc(id)}" + (text?.let { "&text=${enc(it)}" } ?: "")) }
    }

    private fun button(text: String, data: String) = buildJsonObject { put("text", JsonPrimitive(text)); put("callback_data", JsonPrimitive(data)) }

    private fun JsonObject.chatId(): String? = ((this["chat"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content

    private fun api(method: String, form: String, readTimeoutMs: Int = 15_000) = TelegramChannel.call(base, token, method, form, readTimeoutMs)

    private fun enc(s: String) = TelegramChannel.enc(s)

    companion object {
        const val POLL_SEC = 50
        const val MAX_AGE_SEC = 120
        const val CONFIRM_MS = 120_000L
        const val DEFAULT_LOGS = 20
        const val MAX_LOGS = 100
        private const val MAX_TEXT = 4000
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Komut ve açıklaması. */
        private val COMMANDS = listOf(
            "durum" to tr("Sunucu, pil, depolama ve son bir saatin istekleri"),
            "yedek" to tr("Şimdi yedek al"),
            "loglar" to tr("Son log satırları (/loglar 50)"),
            "baslat" to tr("Sunucuyu başlat"),
            "durdur" to tr("Sunucuyu durdur (onay ister)"),
            "yardim" to tr("Komutlar"),
        )
    }
}
