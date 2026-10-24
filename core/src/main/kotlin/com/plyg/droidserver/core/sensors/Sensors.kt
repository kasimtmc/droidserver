package com.plyg.droidserver.core.sensors

import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.config.SensorConfig
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/** Sensör panosu ve eşik uyarıları (bkz. [SensorConfig]). */
object Sensors {
    const val MAX_POINTS = 300
    private const val MAX_ROWS = 1_000

    /** Kayıttaki değer (sayı; evet/hayır 1/0); yoksa null. */
    fun value(s: SensorConfig, record: JsonObject): Double? {
        val p = record[s.field] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.booleanOrNull?.let { if (it) 1.0 else 0.0 }
    }

    /** Yeni değer eşiğin dışındaysa uyarı metni, değilse null. */
    fun outOfRange(s: SensorConfig, record: JsonObject): String? {
        val v = (record[s.field] as? JsonPrimitive)?.doubleOrNull ?: return null
        val name = s.name ?: s.key
        val unit = s.unit.orEmpty()
        return when {
            s.min != null && v < s.min -> tr("{0}: {1}{2}, alt eşiğin ({3}{2}) altında", name, fmt(v), unit, fmt(s.min))
            s.max != null && v > s.max -> tr("{0}: {1}{2}, üst eşiğin ({3}{2}) üstünde", name, fmt(v), unit, fmt(s.max))
            else -> null
        }
    }

    /**
     * Pano verisi: [since]'ten bu yana en çok [MAX_ROWS] son kayıt; [MAX_POINTS]'ten fazlası eşit dilimlerde ortalanır.
     * `points`: `[[epoch ms, değer], ...]` eskiden yeniye.
     */
    fun series(svc: RecordService, s: SensorConfig, since: Instant): JsonObject {
        val rows = mutableListOf<JsonObject>()
        for (page in 1..MAX_ROWS / 500) {
            val p = svc.list(page, 500, "-created_at", listOf("created_at[gte]" to since.toString()))
            rows += p.items
            if (p.items.size < 500) break
        }
        val points = rows.mapNotNull { r ->
            val at = (r["created_at"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            value(s, r)?.let { at.toEpochMilli() to it }
        }.reversed()
        val step = (points.size + MAX_POINTS - 1) / MAX_POINTS
        val reduced = if (step <= 1) points else points.chunked(step).map { c -> c.last().first to c.sumOf { it.second } / c.size }
        val last = points.lastOrNull()
        return buildJsonObject {
            put("collection", JsonPrimitive(s.collection))
            put("field", JsonPrimitive(s.field))
            put("name", JsonPrimitive(s.name ?: s.key))
            put("unit", s.unit?.let(::JsonPrimitive) ?: JsonNull)
            put("min", s.min?.let(::JsonPrimitive) ?: JsonNull)
            put("max", s.max?.let(::JsonPrimitive) ?: JsonNull)
            put("last", last?.let { (t, v) -> buildJsonObject { put("value", JsonPrimitive(v)); put("at", JsonPrimitive(Instant.ofEpochMilli(t).toString())) } } ?: JsonNull)
            put("truncated", JsonPrimitive(rows.size >= MAX_ROWS))
            put("points", JsonArray(reduced.map { (t, v) -> JsonArray(listOf(JsonPrimitive(t), JsonPrimitive(v))) }))
        }
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
