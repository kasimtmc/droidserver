package com.plyg.droidserver.core.mqtt

import com.plyg.droidserver.core.api.ApiNames
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.SensorConfig
import com.plyg.droidserver.core.config.ServerConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Home Assistant MQTT keşfi: her sensör için `<prefix>/<sensor|binary_sensor>/<kimlik>/config` konusuna tutulan keşif
 * mesajı; değerler `<eventPrefix>/<koleksiyon>/state` konusuna son kaydın JSON'u olarak (tutulan) gider. Listeden çıkan
 * sensörün keşif mesajı boş içerikle silinir (Home Assistant'ta kaybolur).
 */
object HomeAssistant {
    fun stateTopic(config: ServerConfig, collection: String) = "${config.mqtt.eventPrefix}/$collection/state"

    /** Keşif konuları ve içerikleri. */
    fun discovery(config: ServerConfig): Map<String, String> {
        val node = slug(config.name)
        val ha = config.mqtt.homeAssistant
        return config.sensors.mapNotNull { s ->
            val c = config.collections.firstOrNull { it.name == s.collection } ?: return@mapNotNull null
            val f = c.fields.firstOrNull { it.name == s.field } ?: return@mapNotNull null
            val api = ApiNames.of(c).api(s.field) ?: s.field
            val binary = f.type == FieldType.BOOLEAN
            val id = "droidserver_${node}_${slug(s.collection)}_${slug(s.field)}"
            "${ha.prefix}/${if (binary) "binary_sensor" else "sensor"}/$id/config" to payload(config, s, id, api, binary).toString()
        }.toMap()
    }

    /** Keşif mesajlarını yayınlar ve artık olmayan DroidServer keşif kayıtlarını siler. */
    fun announce(broker: MqttBroker, config: ServerConfig) {
        val current = discovery(config)
        val prefix = config.mqtt.homeAssistant.prefix + "/"
        val node = "droidserver_${slug(config.name)}_"
        // Bu sunucunun eski keşif kayıtları: <prefix>/<sensor|binary_sensor>/droidserver_<sunucu>_…/config
        fun ours(topic: String): Boolean {
            if (!topic.startsWith(prefix) || !topic.endsWith("/config")) return false
            val parts = topic.removePrefix(prefix).removeSuffix("/config").split('/')
            return parts.size == 2 && parts[0] in setOf("sensor", "binary_sensor") && parts[1].startsWith(node)
        }
        broker.retainedTopics().filter { ours(it) && it !in current }.forEach { broker.publish(it, ByteArray(0), retain = true) }
        current.forEach { (topic, payload) -> broker.publish(topic, payload.toByteArray(), retain = true) }
    }

    private fun payload(config: ServerConfig, s: SensorConfig, id: String, api: String, binary: Boolean): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(s.name ?: "${s.collection} ${s.field}"))
        put("unique_id", JsonPrimitive(id))
        put("object_id", JsonPrimitive(id))
        put("state_topic", JsonPrimitive(stateTopic(config, s.collection)))
        if (binary) {
            put("value_template", JsonPrimitive("{{ 'ON' if value_json.$api else 'OFF' }}"))
        } else {
            put("value_template", JsonPrimitive("{{ value_json.$api }}"))
            put("state_class", JsonPrimitive("measurement"))
            s.unit?.let { put("unit_of_measurement", JsonPrimitive(it)) }
        }
        s.deviceClass?.let { put("device_class", JsonPrimitive(it)) }
        put("device", buildJsonObject {
            put("identifiers", JsonArray(listOf(JsonPrimitive("droidserver_${slug(config.name)}"))))
            put("name", JsonPrimitive(config.name))
            put("manufacturer", JsonPrimitive("DroidServer"))
        })
    }

    private fun slug(s: String) = s.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").trim('_').ifEmpty { "x" }
}
