@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * MQTT (IoT): sensörler ve ESP32 gibi cihazlar [port]'a bağlanıp yayın yapar; [routes]'a uyan mesajlar koleksiyona
 * kayıt olarak yazılır (otomasyonlar, webhook'lar ve canlı abonelik de tetiklenir). [events]'teki koleksiyonların kayıt
 * değişiklikleri `<eventPrefix>/<koleksiyon>/<create|update|delete>` konusuna yayınlanır. Kimlik: [username] +
 * [password] (tüm cihazlar için ortak). Düz TCP: yalnızca yerel ağda kullanın.
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
