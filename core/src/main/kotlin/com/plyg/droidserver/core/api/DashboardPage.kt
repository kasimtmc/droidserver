package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.config.DashboardConfig
import com.plyg.droidserver.core.i18n.I18n
import com.plyg.droidserver.core.i18n.tr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * Paylaşılan pano: `GET /dashboards/<ad>?token=<shareToken>`, salt okunur HTML. Betik yok: grafikler SVG, harita
 * OpenStreetMap karolarının konumlandırılmış görselleri. Anahtarı olmayan panonun paylaşımı kapalıdır (404).
 */
internal fun Route.dashboardPages(serverName: String, dashboards: List<DashboardConfig>, service: DashboardService) {
    for (d in dashboards.filter { it.shareToken != null }) {
        get("${DashboardConfig.PATH}/${d.name}") {
            val token = call.request.queryParameters["token"].orEmpty()
            if (!MessageDigest.isEqual(token.toByteArray(), d.shareToken!!.toByteArray())) {
                return@get call.respondText(tr("Pano bulunamadı"), status = HttpStatusCode.NotFound)
            }
            val data = io { service.data(d) }
            call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append(
                "Content-Security-Policy",
                "default-src 'none'; img-src https://tile.openstreetmap.org; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
            )
            call.respondText(DashboardHtml.page(serverName, data), ContentType.Text.Html)
        }
    }
}

object DashboardHtml {
    fun page(server: String, data: JsonObject): String {
        val title = (data["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val widgets = (data["widgets"] as? JsonArray).orEmpty().map { it as JsonObject }
        return """<!doctype html><html lang="${I18n.lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            |<meta name="robots" content="noindex"><title>${esc(title)} · ${esc(server)}</title><style>
            |:root{--bg:#f4faf8;--fg:#161d1c;--muted:#5b6563;--card:#fff;--line:#d5e0dd;--accent:#006a65}
            |@media(prefers-color-scheme:dark){:root{--bg:#0f1514;--fg:#dde4e2;--muted:#9aa6a3;--card:#171d1c;--line:#2c3634;--accent:#82d5ce}}
            |*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--fg);background:var(--bg)}
            |main{max-width:1200px;margin:3vh auto;padding:16px}h1{font-size:26px;margin:0 0 16px}
            |.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}@media(max-width:800px){.grid{grid-template-columns:1fr}.w2,.w3{grid-column:auto!important}}
            |.w{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:14px 16px;min-width:0}.w2{grid-column:span 2}.w3{grid-column:span 3}
            |.w h2{font-size:14px;color:var(--muted);font-weight:600;margin:0 0 8px}.n{font-size:40px;font-weight:700}.e{color:#ba1a1a}
            |table{width:100%;border-collapse:collapse;font-size:14px}th,td{padding:6px 8px;border-bottom:1px solid var(--line);text-align:left}th{color:var(--muted)}.tw{overflow-x:auto}
            |svg{width:100%;height:auto}svg text{fill:var(--muted);font-size:11px}.bar{fill:var(--accent)}.line{stroke:var(--accent);fill:none;stroke-width:2}
            |.map{position:relative;height:300px;overflow:hidden;border-radius:12px;background:#e5e3df}.map img{position:absolute;width:256px;height:256px}
            |.pin{position:absolute;width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;background:var(--accent);border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,.4)}
            |.attr{position:absolute;right:4px;bottom:2px;font-size:10px;background:rgba(255,255,255,.8);color:#333;padding:0 4px;border-radius:4px}
            |footer{text-align:center;color:var(--muted);font-size:12px;margin:14px 0}
            |</style></head><body><main><h1>${esc(title)}</h1><div class="grid">${widgets.joinToString("") { widget(it) }}</div></main>
            |<footer>${esc(server)}</footer></body></html>""".trimMargin()
    }

    private fun widget(w: JsonObject): String {
        val width = (w["width"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 3) ?: 1
        val head = "<section class=\"w w$width\"><h2>${esc((w["title"] as? JsonPrimitive)?.contentOrNull)}</h2>"
        (w["error"] as? JsonPrimitive)?.contentOrNull?.let { return "$head<p class=\"e\">${esc(it)}</p></section>" }
        val body = when ((w["type"] as? JsonPrimitive)?.content) {
            "number" -> "<div class=\"n\">${esc(number((w["value"] as? JsonPrimitive)?.doubleOrNull))}</div>"
            "chart" -> chart(w)
            "table" -> table(w)
            "map" -> map(w)
            else -> ""
        }
        return "$head$body</section>"
    }

    private fun number(v: Double?): String = v?.let { NumberFormat.getNumberInstance(Locale.forLanguageTag(I18n.lang.code)).apply { maximumFractionDigits = 2 }.format(it) } ?: "—"

    private fun chart(w: JsonObject): String {
        val points = (w["points"] as? JsonArray).orEmpty().map { it as JsonObject }
            .map { (it["label"] as? JsonPrimitive)?.contentOrNull.orEmpty() to ((it["value"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) }
        if (points.isEmpty()) return "<p>${esc(tr("Veri yok"))}</p>"
        val line = (w["chart"] as? JsonPrimitive)?.content == "line"
        val width = 600.0
        val height = 220.0
        val top = 10.0
        val bottom = 30.0
        val max = points.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
        val step = width / points.size
        fun y(v: Double) = top + (height - top - bottom) * (1 - v / max)
        return buildString {
            append("<svg viewBox=\"0 0 600 220\" role=\"img\" aria-label=\"${esc((w["title"] as? JsonPrimitive)?.contentOrNull)}\">")
            if (line) {
                append("<polyline class=\"line\" points=\"")
                points.forEachIndexed { i, (_, v) -> append("%.1f,%.1f ".format(Locale.ROOT, step * i + step / 2, y(v))) }
                append("\"/>")
            } else {
                points.forEachIndexed { i, (_, v) ->
                    append("<rect class=\"bar\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"3\"><title>%s</title></rect>".format(Locale.ROOT, step * i + step * 0.15, y(v), step * 0.7, height - bottom - y(v), esc(number(v))))
                }
            }
            // Çok grupta etiketler seyreltilir.
            val every = ((points.size + 11) / 12).coerceAtLeast(1)
            points.forEachIndexed { i, (label, _) ->
                if (i % every == 0) append("<text x=\"%.1f\" y=\"212\" text-anchor=\"middle\">%s</text>".format(Locale.ROOT, step * i + step / 2, esc(label.take(14))))
            }
            append("<text x=\"2\" y=\"12\">${esc(number(max))}</text></svg>")
        }
    }

    private fun table(w: JsonObject): String = buildString {
        val columns = (w["columns"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
        append("<div class=\"tw\"><table><thead><tr>")
        columns.forEach { append("<th>${esc(it)}</th>") }
        append("</tr></thead><tbody>")
        (w["rows"] as? JsonArray).orEmpty().forEach { r ->
            append("<tr>")
            (r as JsonArray).forEach { append("<td>${esc((it as JsonPrimitive).content)}</td>") }
            append("</tr>")
        }
        append("</tbody></table></div>")
    }

    /** Noktaları 600×300'e sığdıran yakınlıkta karo mozaiği, kutunun ortasına hizalı (dar ekranda kenarlar kırpılır). */
    private fun map(w: JsonObject): String {
        val points = (w["points"] as? JsonArray).orEmpty().map { it as JsonObject }.mapNotNull { p ->
            val lat = (p["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lng = (p["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            Triple(lat, lng, (p["label"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        if (points.isEmpty()) return "<p>${esc(tr("Veri yok"))}</p>"
        fun x(lng: Double, z: Int) = (lng + 180) / 360 * 256 * (1 shl z)
        fun y(lat: Double, z: Int): Double {
            val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
            return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * 256 * (1 shl z)
        }
        val w0 = 600
        val h0 = 300
        var z = 16
        while (z > 1) {
            val xs = points.map { x(it.second, z) }
            val ys = points.map { y(it.first, z) }
            if (xs.max() - xs.min() < w0 - 60 && ys.max() - ys.min() < h0 - 60) break
            z--
        }
        val cx = (points.maxOf { x(it.second, z) } + points.minOf { x(it.second, z) }) / 2
        val cy = (points.maxOf { y(it.first, z) } + points.minOf { y(it.first, z) }) / 2
        val left = cx - w0 / 2
        val top = cy - h0 / 2
        val n = 1 shl z
        return buildString {
            append("<div class=\"map\">")
            for (ty in Math.floorDiv(top.toInt(), 256)..Math.floorDiv((top + h0).toInt(), 256)) {
                if (ty !in 0 until n) continue
                for (tx in Math.floorDiv(left.toInt(), 256)..Math.floorDiv((left + w0).toInt(), 256)) {
                    append("<img alt=\"\" src=\"https://tile.openstreetmap.org/$z/${Math.floorMod(tx, n)}/$ty.png\" style=\"left:calc(50%% + %.0fpx);top:%.0fpx\">".format(Locale.ROOT, tx * 256 - left - w0 / 2, ty * 256 - top))
                }
            }
            points.forEach { (lat, lng, label) ->
                append("<span class=\"pin\" title=\"${esc(label)}\" style=\"left:calc(50%% + %.0fpx);top:%.0fpx\"></span>".format(Locale.ROOT, x(lng, z) - left - w0 / 2, y(lat, z) - top))
            }
            append("<span class=\"attr\">© OpenStreetMap</span></div>")
        }
    }
}
