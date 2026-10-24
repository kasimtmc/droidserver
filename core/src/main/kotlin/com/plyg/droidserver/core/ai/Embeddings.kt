package com.plyg.droidserver.core.ai

import com.plyg.droidserver.core.config.EmbeddingProvider
import com.plyg.droidserver.core.config.EmbeddingsConfig
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** Metni vektöre çeviren servis (testlerde sahte). Çağrı bloklayıcıdır. */
fun interface Embedder {
    /** [query]: arama sorgusu (true) veya dizinlenecek belge (false); bazı sağlayıcılar ikisini farklı kodlar. */
    fun embed(texts: List<String>, query: Boolean): List<FloatArray>
}

/**
 * `/embeddings` ucu: Voyage AI ve OpenAI uyumlu servisler (Ollama, LM Studio, OpenAI) aynı biçimi kullanır:
 * `{"input": [...], "model": "..."}` → `{"data": [{"index": 0, "embedding": [...]}]}`.
 */
class HttpEmbedder(private val config: EmbeddingsConfig, private val apiKey: String) : Embedder {
    override fun embed(texts: List<String>, query: Boolean): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("input", JsonArray(texts.map(::JsonPrimitive)))
            put("model", JsonPrimitive(config.effectiveModel))
            if (config.provider == EmbeddingProvider.VOYAGE) put("input_type", JsonPrimitive(if (query) "query" else "document"))
        }.toString()
        val (status, text) = postJson(
            config.effectiveUrl + "/embeddings", if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap(), body, 60_000,
        )
        if (status !in 200..299) throw IOException(tr("Gömme servisi {0} döndü: {1}", status, text.take(300)))
        val data = Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
            ?: throw IOException(tr("Gömme servisinin yanıtı anlaşılamadı"))
        val out = arrayOfNulls<FloatArray>(texts.size)
        data.forEachIndexed { i, el ->
            val o = el as JsonObject
            val index = o["index"]?.jsonPrimitive?.int ?: i
            if (index in out.indices) out[index] = o.getValue("embedding").jsonArray.let { a -> FloatArray(a.size) { a[it].jsonPrimitive.float } }
        }
        return out.map { it ?: throw IOException(tr("Gömme servisi eksik yanıt verdi")) }
    }
}
