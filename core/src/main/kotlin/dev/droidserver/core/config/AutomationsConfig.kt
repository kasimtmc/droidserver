@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Otomasyon kuralı: kod yazmadan "şu olunca şunu yap". Tetikleyici bir kayıt olayı ([AutomationTrigger.collection] +
 * [AutomationTrigger.events]) veya zamanlamadır ([AutomationTrigger.schedule]); [condition] liste filtresi
 * sözdizimindedir (`status=open&qty[gt]=0`) ve kaydın JSON'u üzerinde değerlendirilir. Eylemler sırayla çalışır;
 * metinlerde `{alan}` yer tutucuları kaydın değerleriyle, `{now}`, `{collection}`, `{event}`, `{count}` ve
 * `{rule}` ise çalışma bilgileriyle dolar.
 */
@Serializable
data class AutomationConfig(
    /** Kısa ad (loglarda, geçmişte ve `/_automations/{name}/run` ucunda). */
    val name: String,
    val trigger: AutomationTrigger,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val condition: String? = null,
    val actions: List<AutomationAction>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
)

@Serializable
data class AutomationTrigger(
    /** Kayıt olayı veya (zamanlamada) koşulun uygulanacağı koleksiyon. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val collection: String? = null,
    /** `create`, `update`, `delete`; [schedule] verilmişse kullanılmaz. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val events: List<String> = listOf("create"),
    /** Cron ifadesi (`0 8 * * *`: her gün 08:00, telefonun saatiyle). Verilirse kural kayıt olayında değil bu zamanlarda çalışır. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val schedule: String? = null,
    /**
     * MQTT konu süzgeci (`ev/+/hareket`): cihazdan gelen mesaj kayda yazılmadan kuralı tetikler. İçerik JSON nesnesiyse
     * alanları `{alan}`, ayrıca `{topic}` ve `{payload}` kullanılır.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mqtt: String? = null,
) {
    val scheduled: Boolean get() = !schedule.isNullOrBlank()
    val onMqtt: Boolean get() = !mqtt.isNullOrBlank()
}

@Serializable
enum class AutomationActionType(val title: String) {
    @SerialName("email") EMAIL(tr("E-posta gönder")),
    @SerialName("push") PUSH(tr("Bildirim gönder")),
    @SerialName("webhook") WEBHOOK(tr("Webhook'a gönder")),
    @SerialName("update") UPDATE(tr("Kaydı güncelle")),
    @SerialName("create") CREATE(tr("Kayıt oluştur")),
    @SerialName("log") LOG(tr("Loga yaz")),
    @SerialName("mqtt") MQTT(tr("MQTT mesajı gönder")),
}

/**
 * Bir eylem. Hangi alanların kullanıldığı türe göre değişir:
 *  - `email`: [to] (adres veya `{email}` gibi bir alan), [subject], [body].
 *  - `push`: [to] (`owner`: kaydın sahibi, ya da kullanıcı kimliği / `{alan}`), [subject] başlık, [body], [url].
 *  - `webhook`: [webhook] (tanımlı bir webhook'un adı); gövdeye kayıt ve kural adı konur.
 *  - `update`: [values] (alan → değer şablonu); olayda tetikleyen kayıt, zamanlamada koşula uyan tüm kayıtlar güncellenir.
 *  - `create`: [collection] ve [values].
 *  - `log`: [body].
 *  - `mqtt`: [topic] (joker olmadan; `{alan}` kullanılabilir), [body] içerik, [retain] tutulan mesaj.
 */
@Serializable
data class AutomationAction(
    val type: AutomationActionType,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val to: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val subject: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val body: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val url: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val webhook: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val collection: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val values: Map<String, String> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val topic: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val retain: Boolean = false,
) {
    companion object {
        /** `push` eyleminde kaydın sahibi. */
        const val OWNER = "owner"
    }
}
