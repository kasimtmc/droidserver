package dev.droidserver.core.api

import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.DashboardConfig
import dev.droidserver.core.config.GeoPoint
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.WidgetConfig
import dev.droidserver.core.config.WidgetType
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * Panoların verisi (panel, uygulama ve paylaşılan sayfa aynı biçimi kullanır). Kutular görünümleri yönetici olarak
 * çalıştırır; kutu hatası panoyu düşürmez, kutuda `error` olarak döner.
 *
 * `{name, title, widgets: [{type, title, width, …}]}`: `number` → `value`; `chart` → `chart`, `points: [{label, value}]`;
 * `table` → `columns`, `rows` (hücreler metin); `map` → `points: [{lat, lng, label}]`.
 */
class DashboardService(private val config: ServerConfig, private val views: ViewService) {
    fun find(name: String): DashboardConfig = config.dashboards.firstOrNull { it.name == name } ?: throw ApiException(404, tr("'{0}' panosu yok", name))

    fun data(d: DashboardConfig): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(d.name))
        put("title", JsonPrimitive(d.title))
        put("widgets", JsonArray(d.widgets.map(::widget)))
    }

    private fun widget(w: WidgetConfig): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(w.type.name.lowercase()))
        val view = runCatching { views.find(w.view) }.getOrNull()
        put("title", JsonPrimitive(w.title?.takeIf { it.isNotBlank() } ?: view?.label ?: w.view))
        put("width", JsonPrimitive(w.width))
        try {
            view ?: throw ApiException(404, tr("'{0}' görünümü yok", w.view))
            when (w.type) {
                WidgetType.NUMBER -> put("value", views.run(view, Caller.Admin, emptyList())[w.metric] ?: JsonNull)
                WidgetType.CHART -> {
                    put("chart", JsonPrimitive(w.chart.name.lowercase()))
                    val groups = (views.run(view, Caller.Admin, emptyList())["groups"] as? JsonArray).orEmpty()
                    put("points", JsonArray(groups.mapNotNull { g ->
                        val o = g as? JsonObject ?: return@mapNotNull null
                        buildJsonObject {
                            put("label", JsonPrimitive(text(o["key"]) ?: tr("(boş)")))
                            put("value", JsonPrimitive((o[w.metric] as? JsonPrimitive)?.doubleOrNull ?: 0.0))
                        }
                    }))
                }
                WidgetType.TABLE -> {
                    val columns = views.columns(view)
                    val items = (views.run(view, Caller.Admin, listOf("perPage" to w.limit.toString()))["items"] as? JsonArray).orEmpty()
                    put("columns", JsonArray(columns.map(::JsonPrimitive)))
                    put("rows", JsonArray(items.map { r -> JsonArray(columns.map { c -> JsonPrimitive(text((r as JsonObject)[c]).orEmpty()) }) }))
                }
                WidgetType.MAP -> {
                    val field = w.field.orEmpty()
                    val label = views.columns(view).firstOrNull { it != field && '.' !in it }
                    val items = (views.run(view, Caller.Admin, listOf("perPage" to "500", "$field[null]" to "false"))["items"] as? JsonArray).orEmpty()
                    put("points", JsonArray(items.mapNotNull { r ->
                        val o = r as JsonObject
                        val p = o[field]?.let { runCatching { GeoPoint.of(it) }.getOrNull() } ?: return@mapNotNull null
                        buildJsonObject {
                            put("lat", JsonPrimitive(p.lat))
                            put("lng", JsonPrimitive(p.lng))
                            put("label", JsonPrimitive(label?.let { text(o[it]) }.orEmpty()))
                        }
                    }))
                }
            }
        } catch (e: ApiException) {
            put("error", JsonPrimitive(e.message ?: tr("Kutu yüklenemedi")))
        }
    }

    private fun text(v: JsonElement?): String? = when (v) {
        null, JsonNull -> null
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
}
