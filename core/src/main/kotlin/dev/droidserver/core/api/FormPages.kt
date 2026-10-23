package dev.droidserver.core.api

import dev.droidserver.core.automation.AutomationEngine
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FormConfig
import dev.droidserver.core.config.FormField
import dev.droidserver.core.config.TextFormat
import dev.droidserver.core.i18n.I18n
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.realtime.RecordEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Spam koruması: formu açarken verilen imzalı zaman damgası (en kısa doldurma süresi ve en uzun geçerlilik), insanların
 * görmediği tuzak alanı ve adres başına gönderim sınırı. Anahtar süreç başına rastgeledir; deploy'lar arasında geçerli kalır.
 */
internal class FormGuard(
    private val minFillMs: Long = 2_000,
    private val maxAgeMs: Long = 2 * 3_600_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun token(form: String): String {
        val ts = clock()
        return "$ts.${sign(form, ts)}"
    }

    /** Hata iletisi; geçerliyse null. */
    fun check(form: String, token: String?): String? {
        val ts = token?.substringBefore('.')?.toLongOrNull() ?: return tr("Form süresi doldu; sayfayı yenileyip yeniden gönderin")
        val ok = MessageDigest.isEqual(sign(form, ts).toByteArray(), token.substringAfter('.').toByteArray())
        val age = clock() - ts
        return when {
            !ok || age > maxAgeMs || age < 0 -> tr("Form süresi doldu; sayfayı yenileyip yeniden gönderin")
            age < minFillMs -> tr("Form çok hızlı gönderildi; birkaç saniye sonra yeniden deneyin")
            else -> null
        }
    }

    /** 10 dakikalık pencerede [max] gönderime izin verir. */
    fun allow(key: String, max: Int): Boolean {
        val now = clock()
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
            if (q.size >= max) return false
            q.addLast(now)
        }
        if (hits.size > 10_000) hits.entries.removeIf { (_, v) -> synchronized(v) { v.isEmpty() || now - v.last() > WINDOW_MS } }
        return true
    }

    private fun sign(form: String, ts: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(KEY, "HmacSHA256")) }
        return mac.doFinal("$form|$ts".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val WINDOW_MS = 600_000L
        private val KEY = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}

/** Formdaki alanlar: verilmemişse koleksiyonun yazılabilir tüm alanları. */
fun FormConfig.resolvedFields(c: CollectionConfig): List<Pair<FormField, FieldConfig>> {
    val byName = c.fields.associateBy { it.name }
    val chosen = fields.ifEmpty { c.fields.filter { formWritable(it) }.map { FormField(it.name) } }
    return chosen.mapNotNull { f -> byName[f.name]?.let { f to it } }
}

/** Formla yazılabilen alan: hesaplanan ve dosya alanı olamaz. */
fun formWritable(f: FieldConfig) = f.computed == null && f.type != FieldType.FILE

/**
 * `GET /forms/<ad>` formu gösterir, `POST` kaydı ekler. Sayfalar başka kaynak yüklemez, çerçeveye konamaz, önbelleğe
 * alınmaz; JavaScript yoktur (doğrulama tarayıcının kendi özellikleriyle ve sunucuda).
 */
internal fun Route.formPages(
    serverName: String,
    forms: List<FormConfig>,
    collections: Map<String, CollectionConfig>,
    services: Map<String, RecordService>,
    writes: WriteGate,
    guard: FormGuard,
    changed: (String, RecordEvent.Action, JsonObject) -> Unit,
) {
    for (form in forms.filter { it.enabled }) {
        val c = collections[form.collection] ?: continue
        val fields = form.resolvedFields(c)
        get(form.path) {
            call.formPage(HttpStatusCode.OK, serverName, form, render(form, fields, guard.token(form.name), emptyMap(), emptyMap(), null))
        }
        post(form.path) {
            val p = call.receiveParameters()
            fun again(status: HttpStatusCode, error: String?, fieldErrors: Map<String, String> = emptyMap()) =
                render(form, fields, guard.token(form.name), values(p, fields), fieldErrors, error).let { status to it }
            // Tuzak alanı dolu: bot. Başarılı gibi görünür ama kayıt yazılmaz.
            if (!p[HONEYPOT].isNullOrEmpty()) return@post call.done(serverName, form)
            guard.check(form.name, p[TOKEN])?.let { err ->
                val (s, html) = again(HttpStatusCode.BadRequest, err)
                return@post call.formPage(s, serverName, form, html)
            }
            if (!guard.allow("${form.name}|${origin(call.request).key}", form.maxPerTenMinutes)) {
                return@post call.formPage(HttpStatusCode.TooManyRequests, serverName, form, "<p class=\"e\">${esc(tr("Çok fazla gönderim; biraz sonra yeniden deneyin."))}</p>")
            }
            val body = try {
                body(p, fields)
            } catch (e: IllegalArgumentException) {
                val (s, html) = again(HttpStatusCode.BadRequest, e.message)
                return@post call.formPage(s, serverName, form, html)
            }
            try {
                val record = io { writes.write { services.getValue(c.name).create(body) } }
                changed(c.name, RecordEvent.Action.CREATE, record)
            } catch (e: ApiException) {
                val (s, html) = again(HttpStatusCode.BadRequest, if (e.details.isEmpty()) e.message else tr("Lütfen işaretli alanları düzeltin."), e.details)
                return@post call.formPage(s, serverName, form, html)
            } catch (e: WritesPausedException) {
                val (_, html) = again(HttpStatusCode.ServiceUnavailable, tr("Şu an kayıt alınamıyor; biraz sonra yeniden deneyin."))
                return@post call.formPage(HttpStatusCode.ServiceUnavailable, serverName, form, html)
            }
            val redirect = form.redirect?.takeIf { it.isNotBlank() }
            if (redirect != null) call.respondRedirect(redirect, permanent = false) else call.done(serverName, form)
        }
    }
}

private const val TOKEN = "_t"
private const val HONEYPOT = "website"
private fun input(name: String) = "f.$name"

private fun values(p: Parameters, fields: List<Pair<FormField, FieldConfig>>): Map<String, List<String>> =
    fields.associate { (f, _) -> f.name to p.getAll(input(f.name)).orEmpty() }

/** Gönderilen metinleri alan tiplerine çevirir; boş bırakılan alan gönderilmez (varsayılan veya "zorunlu" hatası). */
private fun body(p: Parameters, fields: List<Pair<FormField, FieldConfig>>): JsonObject = JsonObject(buildMap<String, JsonElement> {
    for ((f, field) in fields) {
        val all = p.getAll(input(f.name)).orEmpty()
        val text = all.firstOrNull()?.trim().orEmpty()
        when (field.type) {
            FieldType.BOOLEAN -> put(f.name, JsonPrimitive(text == "true"))
            FieldType.MULTISELECT -> if (all.isNotEmpty()) put(f.name, JsonArray(all.map(::JsonPrimitive)))
            FieldType.DATETIME -> if (text.isNotEmpty()) {
                val instant = runCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant() }
                    .getOrElse { throw IllegalArgumentException(tr("{0}: tarih ve saat geçersiz", f.label ?: f.name)) }
                put(f.name, JsonPrimitive(instant.toString()))
            }
            else -> if (text.isNotEmpty()) put(f.name, AutomationEngine.typed(field.type, text))
        }
    }
})

private fun render(
    form: FormConfig,
    fields: List<Pair<FormField, FieldConfig>>,
    token: String,
    values: Map<String, List<String>>,
    errors: Map<String, String>,
    error: String?,
) = buildString {
    form.description?.takeIf { it.isNotBlank() }?.let { append("<p class=\"d\">${esc(it).replace("\n", "<br>")}</p>") }
    error?.let { append("<p class=\"e\" role=\"alert\">${esc(it)}</p>") }
    append("<form method=\"post\" action=\"${esc(form.path)}\">")
    append("<input type=\"hidden\" name=\"$TOKEN\" value=\"${esc(token)}\">")
    append("<div class=\"hp\" aria-hidden=\"true\"><label>Website<input name=\"$HONEYPOT\" tabindex=\"-1\" autocomplete=\"off\"></label></div>")
    for ((f, field) in fields) {
        val id = "f-${f.name}"
        val label = esc(f.label?.takeIf { it.isNotBlank() } ?: f.name)
        val required = field.required && field.default == null && field.type != FieldType.BOOLEAN
        val star = if (required) " <span class=\"r\" aria-hidden=\"true\">*</span>" else ""
        val v = values[f.name].orEmpty()
        val value = esc(v.firstOrNull() ?: field.default?.let { (it as? JsonPrimitive)?.content }.orEmpty())
        val req = if (required) " required" else ""
        val name = esc(input(f.name))
        append("<div class=\"f\">")
        when (field.type) {
            FieldType.BOOLEAN -> {
                val checked = if (v.isNotEmpty()) v.first() == "true" else (field.default as? JsonPrimitive)?.content == "true"
                append("<label class=\"c\"><input type=\"checkbox\" id=\"$id\" name=\"$name\" value=\"true\"${if (checked) " checked" else ""}> $label</label>")
            }
            FieldType.SELECT -> {
                append("<label for=\"$id\">$label$star</label><select id=\"$id\" name=\"$name\"$req>")
                if (!required) append("<option value=\"\"></option>") else append("<option value=\"\" disabled${if (v.isEmpty()) " selected" else ""}>${esc(tr("Seçin"))}</option>")
                field.options.orEmpty().forEach { o -> append("<option${if (o == v.firstOrNull()) " selected" else ""}>${esc(o)}</option>") }
                append("</select>")
            }
            FieldType.MULTISELECT -> {
                append("<fieldset><legend>$label$star</legend>")
                field.options.orEmpty().forEach { o ->
                    append("<label class=\"c\"><input type=\"checkbox\" name=\"$name\" value=\"${esc(o)}\"${if (o in v) " checked" else ""}> ${esc(o)}</label>")
                }
                append("</fieldset>")
            }
            FieldType.JSON -> append("<label for=\"$id\">$label$star</label><textarea id=\"$id\" name=\"$name\" rows=\"4\"$req>$value</textarea>")
            else -> {
                append("<label for=\"$id\">$label$star</label>")
                if (field.type == FieldType.TEXT && f.multiline) {
                    append("<textarea id=\"$id\" name=\"$name\" rows=\"5\"$req${lengths(field)}>$value</textarea>")
                } else {
                    val type = when (field.type) {
                        FieldType.GEO -> "text\" inputmode=\"decimal\" placeholder=\"41.0082,28.9784\" pattern=\"\\s*-?[0-9.]+\\s*,\\s*-?[0-9.]+\\s*"
                        FieldType.INTEGER, FieldType.RELATION -> "number\" step=\"1"
                        FieldType.REAL -> "number\" step=\"any"
                        FieldType.DATETIME -> "datetime-local"
                        else -> when (field.format) {
                            TextFormat.EMAIL -> "email"
                            TextFormat.URL -> "url"
                            null -> "text"
                        }
                    }
                    val range = listOfNotNull(field.min?.let { " min=\"${num(it)}\"" }, field.max?.let { " max=\"${num(it)}\"" }).joinToString("")
                    val pattern = field.pattern?.let { " pattern=\"${esc(it)}\"" }.orEmpty()
                    val auto = if (field.format == TextFormat.EMAIL) " autocomplete=\"email\"" else ""
                    append("<input type=\"$type\" id=\"$id\" name=\"$name\" value=\"$value\"$req$range${lengths(field)}$pattern$auto>")
                }
            }
        }
        f.help?.takeIf { it.isNotBlank() }?.let { append("<small>${esc(it)}</small>") }
        errors[f.name]?.let { append("<small class=\"e\">${esc(it)}</small>") }
        append("</div>")
    }
    append("<button type=\"submit\">${esc(form.submitLabel?.takeIf { it.isNotBlank() } ?: tr("Gönder"))}</button></form>")
}

private fun lengths(f: FieldConfig) = listOfNotNull(f.minLength?.let { " minlength=\"$it\"" }, f.maxLength?.let { " maxlength=\"$it\"" }).joinToString("")

private fun num(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

private suspend fun RoutingCall.done(server: String, form: FormConfig) = formPage(
    HttpStatusCode.OK, server, form,
    "<p class=\"ok\">${esc(form.successMessage?.takeIf { it.isNotBlank() } ?: tr("Teşekkürler, gönderildi."))}</p>" +
        "<p><a href=\"${esc(form.path)}\">${esc(tr("Yeni gönderim"))}</a></p>",
)

private suspend fun RoutingCall.formPage(status: HttpStatusCode, server: String, form: FormConfig, body: String) {
    // Dış adrese yönlendirme formun gönderim hedefi sayılır; CSP'de izin verilir.
    val redirectOrigin = form.redirect?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?.let { runCatching { URI(it).let { u -> "${u.scheme}://${u.authority}" } }.getOrNull() }
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'${redirectOrigin?.let { " $it" }.orEmpty()}; frame-ancestors 'none'; base-uri 'none'",
    )
    val html = """<!doctype html><html lang="${I18n.lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        |<title>${esc(form.title)} · ${esc(server)}</title><style>
        |:root{--bg:#f4faf8;--fg:#161d1c;--muted:#5b6563;--card:#fff;--line:#d5e0dd;--accent:#006a65;--accent-fg:#fff;--err:#ba1a1a;--ok:#2e7d32}
        |@media(prefers-color-scheme:dark){:root{--bg:#0f1514;--fg:#dde4e2;--muted:#9aa6a3;--card:#171d1c;--line:#2c3634;--accent:#82d5ce;--accent-fg:#003734;--err:#ffb4ab;--ok:#8bd88f}}
        |*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--fg);background:var(--bg)}
        |main{max-width:560px;margin:6vh auto;padding:28px 22px;background:var(--card);border:1px solid var(--line);border-radius:20px}@media(max-width:600px){main{margin:16px}}
        |h1{font-size:24px;margin:0 0 6px}.d{color:var(--muted);margin:0 0 18px}.f{margin:14px 0}
        |label,legend{display:block;font-weight:600;font-size:14px;margin-bottom:6px}fieldset{border:0;padding:0;margin:0}
        |input,select,textarea{display:block;width:100%;padding:11px 12px;font:inherit;color:var(--fg);background:var(--bg);border:1px solid var(--line);border-radius:12px}
        |input:focus,select:focus,textarea:focus{outline:2px solid var(--accent);outline-offset:1px;border-color:transparent}
        |.c{display:flex;gap:10px;align-items:center;font-weight:400;font-size:16px}.c input{width:18px;height:18px;margin:0;accent-color:var(--accent)}
        |small{display:block;color:var(--muted);font-size:13px;margin-top:4px}.e{color:var(--err)}.ok{color:var(--ok);font-size:18px}.r{color:var(--err)}
        |button{margin-top:10px;width:100%;padding:13px;font:inherit;font-weight:600;border:0;border-radius:999px;background:var(--accent);color:var(--accent-fg);cursor:pointer}
        |a{color:var(--accent)}.hp{position:absolute;left:-10000px;width:1px;height:1px;overflow:hidden}footer{text-align:center;color:var(--muted);font-size:12px;margin:14px 0}
        |</style></head><body><main><h1>${esc(form.title)}</h1>$body</main><footer>${esc(server)}</footer></body></html>""".trimMargin()
    respondText(html, ContentType.Text.Html, status)
}
