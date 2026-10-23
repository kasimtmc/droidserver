package dev.droidserver.core.mqtt

import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.MqttRoute
import dev.droidserver.core.config.MqttScene
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sunucunun MQTT durumu (uygulamadaki ekran; deploy'lar arasında sürer, sunucu başlatınca dolar). */
class MqttHost {
    @Volatile
    var broker: MqttBroker? = null
}

/** Sahnenin mesajlarını sırayla yayınlar; toplam kaç cihaza gittiği (son mesajlarda "sahne: <ad>" görünür). */
fun MqttBroker.runScene(scene: MqttScene): Int = scene.messages.sumOf { m ->
    val payload = m.payload.toByteArray()
    publish(m.topic, payload, m.retain).also { note(m.topic, payload.size, tr("sahne: {0}; {1} cihaza", scene.name, it)) }
}

/** MQTT mesajını kayıt gövdesine çevirir (bkz. [MqttRoute]). */
object MqttIngest {
    fun body(c: CollectionConfig, route: MqttRoute, topic: String, payload: ByteArray): JsonObject {
        val text = payload.decodeToString().trim()
        val fields = c.fields.associateBy { it.name }
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val out = LinkedHashMap<String, JsonElement>()
        if (parsed is JsonObject) {
            parsed.filterKeys { it in fields }.forEach { (k, v) -> out[k] = v }
            if (out.isEmpty() && route.valueField == null) throw IllegalArgumentException(tr("İçerikte '{0}' koleksiyonunun alanı yok", c.name))
        } else {
            val field = route.valueField?.let(fields::get)
                ?: throw IllegalArgumentException(tr("Düz değer için değer alanı seçin ({0})", route.topic))
            out[field.name] = value(field.type, (parsed as? JsonPrimitive)?.content ?: text)
        }
        route.topicField?.let { out[it] = JsonPrimitive(MqttBroker.captures(route.topic, topic).firstOrNull() ?: topic) }
        return JsonObject(out)
    }

    private fun value(type: FieldType, s: String): JsonPrimitive = when (type) {
        FieldType.INTEGER -> s.toLongOrNull()?.let(::JsonPrimitive) ?: s.toDoubleOrNull()?.let { JsonPrimitive(it.toLong()) } ?: JsonPrimitive(s)
        FieldType.REAL -> s.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(s)
        FieldType.BOOLEAN -> when (s.lowercase()) {
            "true", "1", "on", "açık", "acik", "yes" -> JsonPrimitive(true)
            "false", "0", "off", "kapalı", "kapali", "no" -> JsonPrimitive(false)
            else -> JsonPrimitive(s)
        }
        else -> JsonPrimitive(s)
    }
}
