@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * MQTT (IoT): sensörler ve ESP32 gibi cihazlar [port]'a bağlanıp yayın yapar; [routes]'a uyan mesajlar koleksiyona
 * kayıt olarak yazılır (otomasyonlar, webhook'lar ve canlı abonelik de tetiklenir). [events]'teki koleksiyonların kayıt
 * değişiklikleri `<eventPrefix>/<koleksiyon>/<create|update|delete>` konusuna yayınlanır. Kimlik: [username] +
 * [password] (tüm cihazlar için ortak; [devices] varken boş bırakılırsa kapalı) ve/veya [devices] (cihaz başına).
 * Düz TCP: yalnızca yerel ağda çalışır.
 */
@Serializable
data class MqttConfig(
    val enabled: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val port: Int = 1883,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val username: String = "device",
    /** `secret:` referansı; kaydedince şifreli depoya taşınır. En az 8 karakter. */
    val password: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val routes: List<MqttRoute> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val events: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val eventPrefix: String = "droidserver",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxClients: Int = 50,
    /** Cihaz başına kimlik; birini silmek veya kapatmak diğerlerini etkilemez. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val devices: List<MqttDevice> = emptyList(),
    /** Sahneler: tek dokunuşla gönderilen adlı mesaj grupları (uygulama, widget, panel, `POST /_mqtt/scenes/{ad}`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scenes: List<MqttScene> = emptyList(),
    /** Home Assistant MQTT keşfi: [ServerConfig.sensors] Home Assistant'ta kendiliğinden görünür. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val homeAssistant: HomeAssistantConfig = HomeAssistantConfig(),
)

/** [prefix]: Home Assistant'ın keşif öneki (varsayılan `homeassistant`). */
@Serializable
data class HomeAssistantConfig(
    val enabled: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val prefix: String = "homeassistant",
)

/**
 * Sensör: koleksiyondaki bir alan (sayı veya evet/hayır). Sensör panosunda zaman grafiği; [min]/[max] dışındaki yeni değer
 * sağlık uyarısı verir; Home Assistant keşfi açıksa orada da görünür ([unit], [deviceClass]: `temperature`, `humidity`…).
 */
@Serializable
data class SensorConfig(
    val collection: String,
    val field: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unit: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deviceClass: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val min: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val max: Double? = null,
) {
    val key: String get() = "$collection.${this.field}"
}

@Serializable
data class MqttScene(val name: String, val messages: List<MqttMessage>)

/** Sahnedeki tek mesaj: [topic] joker olmadan; [retain] ise sonradan bağlanan cihaz da alır. */
@Serializable
data class MqttMessage(
    val topic: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val payload: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retain: Boolean = false,
)

/** Cihaz kimliği: [name] MQTT kullanıcı adıdır; [password] `secret:` referansı (kaydedince şifreli depoya taşınır). */
@Serializable
data class MqttDevice(
    val name: String,
    val password: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
)

/**
 * Konu süzgecine ([topic], `+` ve `#` jokerleriyle) uyan mesaj [collection]'a eklenir. İçerik JSON nesnesiyse alanları
 * koleksiyonun alanlarına yazılır (bilinmeyenler atlanır); düz değerse (`23.5`, `açık`) [valueField]'a. [topicField]:
 * konunun `+`/`#` bölümü (örn. `sensors/+/temp` ← `sensors/salon/temp` → `salon`; joker yoksa konunun tamamı).
 */
@Serializable
data class MqttRoute(
    val topic: String,
    val collection: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val valueField: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val topicField: String? = null,
)
