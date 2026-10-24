package com.plyg.droidserver.core.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Arayüz dili. Kaynak metinler Türkçedir ve katalog anahtarıdır (`tr("Kaydet")`); diğer diller
 * `i18n/<kod>.json` (Türkçe → çeviri) kataloğundan. Yeni dil: buraya bir satır ve katalog dosyası.
 */
enum class Lang(val code: String, val title: String) {
    TR("tr", "Türkçe"),
    EN("en", "English");

    companion object {
        fun of(code: String?): Lang? = entries.firstOrNull { it.code.equals(code?.substringBefore('-')?.substringBefore('_'), ignoreCase = true) }
    }
}

/**
 * Çeviri. Uygulama ve sunucu aynı süreçte çalıştığı için tek bir etkin dil vardır: uygulama arayüzü, API hata
 * iletileri, doğrulama mesajları ve e-postalar bu dilde. Katalogda olmayan metin olduğu gibi (Türkçe) kalır.
 */
object I18n {
    @Volatile
    var lang: Lang = Lang.TR

    private val catalogs = ConcurrentHashMap<Lang, Map<String, String>>()

    private val panelScripts = ConcurrentHashMap<Lang, String>()

    fun catalog(lang: Lang): Map<String, String> = if (lang == Lang.TR) emptyMap() else catalogs.getOrPut(lang) {
        val text = resource("${lang.code}.json") ?: return@getOrPut emptyMap()
        (Json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }

    /** Web panelinin kataloğu (`i18n/panel-<kod>.json`) ve dili, `window.DS_I18N` / `window.DS_LANG` olarak. */
    fun panelScript(lang: Lang = this.lang): String = panelScripts.getOrPut(lang) {
        val catalog = if (lang == Lang.TR) null else resource("panel-${lang.code}.json")?.let { Json.parseToJsonElement(it) as JsonObject }
        "window.DS_LANG=${JsonPrimitive(lang.code)};window.DS_I18N=${catalog ?: JsonObject(emptyMap())};\n"
    }

    private fun resource(name: String): String? =
        I18n::class.java.getResourceAsStream("/i18n/$name")?.use { it.readBytes().decodeToString() }

    /** [text]'in etkin dildeki karşılığı; `{0}`, `{1}` … yer tutucuları [args] ile doldurulur. */
    fun translate(text: String, args: Array<out Any?>, lang: Lang = this.lang): String {
        val out = catalog(lang)[text] ?: text
        if (args.isEmpty()) return out
        return PLACEHOLDER.replace(out) { m -> m.groupValues[1].toInt().let { i -> if (i < args.size) args[i].toString() else m.value } }
    }

    private val PLACEHOLDER = Regex("\\{(\\d)\\}")
}

/** Kısa yol: `tr(metin, argümanlar…)`; metindeki `{0}`, `{1}` … argümanlarla doldurulur. */
fun tr(text: String, vararg args: Any?): String = I18n.translate(text, args)
