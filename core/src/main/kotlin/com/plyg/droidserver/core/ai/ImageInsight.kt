package com.plyg.droidserver.core.ai

import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Görselden etiketler ve görseldeki metin (OCR), ayarlı YZ sağlayıcısıyla (görsel destekleyen model gerekir). Yanıt
 * JSON istenir; etiketler çoklu seçim alanında yalnızca seçeneklerden seçilir.
 */
class ImageInsight(private val chat: AiChat) {
    data class Result(val tags: List<String>, val text: String)

    fun analyze(image: AiImage, options: List<String>?, tags: Boolean, text: Boolean): Result {
        val wants = buildList {
            if (tags) add(
                if (options.isNullOrEmpty()) tr("\"tags\": görseli anlatan en çok 10 kısa etiket (dizi)")
                else tr("\"tags\": yalnızca şu seçeneklerden görsele uyanlar (dizi): {0}", options.joinToString(", ")),
            )
            if (text) add(tr("\"text\": görselde okunan yazının tamamı, satır sonlarıyla; yazı yoksa boş metin"))
        }
        val system = tr("Görselleri inceleyip yalnızca bir JSON nesnesi döndürürsün; açıklama veya kod bloğu yazma.")
        val reply = chat.complete(system, listOf(AiMessage("user", tr("Bu görsel için JSON: {0}", wants.joinToString("; ")), listOf(image))))
        if (reply.refused) throw IllegalStateException(tr("YZ görseli incelemeyi reddetti"))
        val json = reply.text.let { t -> t.substring(t.indexOf('{').coerceAtLeast(0), (t.lastIndexOf('}') + 1).coerceAtLeast(0)) }
        val o = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrElse { throw IllegalStateException(tr("YZ yanıtı JSON değil")) }
        val found = (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        val picked = if (options.isNullOrEmpty()) found.distinct().take(MAX_TAGS) else found.mapNotNull { t -> options.firstOrNull { it.equals(t, ignoreCase = true) } }.distinct()
        return Result(picked, (o["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
    }

    companion object {
        const val MAX_TAGS = 10

        /** Sonucun alana yazılacak değeri: metinde virgülle, çoklu seçim ve JSON'da dizi; metin alanında uzunluk sınırına kırpılır. */
        fun tagsValue(f: FieldConfig, tags: List<String>): JsonElement = when (f.type) {
            FieldType.TEXT -> JsonPrimitive(clip(f, tags.joinToString(", ")))
            else -> JsonArray(tags.map(::JsonPrimitive))
        }

        fun textValue(f: FieldConfig, text: String): JsonElement = JsonPrimitive(clip(f, text))

        private fun clip(f: FieldConfig, s: String) = f.maxLength?.let { s.take(it) } ?: s
    }
}
