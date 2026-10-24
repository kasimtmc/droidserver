package com.plyg.droidserver.core.ai

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.AiConfig
import com.plyg.droidserver.core.config.AiEffort
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** JSON POST; (durum kodu, gövde). Anahtar yalnızca başlıkta gider, adreste değil (loglara düşmesin). */
internal fun postJson(url: String, headers: Map<String, String>, body: String, readTimeoutMs: Int = 120_000): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return status to text
    } finally {
        conn.disconnect()
    }
}

/** Sağlayıcı yanıtını ayrıştırır; HTTP hatasında anlaşılır 502. */
private fun call(provider: String, url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
    val (status, text) = try {
        postJson(url, headers, body.toString())
    } catch (e: IOException) {
        throw ApiException(502, tr("{0} API'sine ulaşılamadı: {1}", provider, e.message ?: e.toString()))
    }
    val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    if (status !in 200..299) {
        val err = json?.get("error")
        val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (err as? JsonPrimitive)?.contentOrNull ?: text.take(300)
        throw ApiException(502, tr("{0} API hatası ({1}): {2}", provider, status, msg))
    }
    return json ?: throw ApiException(502, tr("{0} API'sinin yanıtı anlaşılamadı", provider))
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.long(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0

/**
 * OpenAI Chat Completions (`/chat/completions`). Adres değiştirilirse OpenAI uyumlu servisler (Ollama, LM Studio,
 * OpenRouter) de kullanılabilir; o zaman yalnızca ortak parametreler gönderilir.
 */
class OpenAiChat(
    private val config: AiConfig,
    /** Testlerde sahte sunucu; normalde ayardaki adres veya api.openai.com. */
    private val base: String = config.url.ifBlank { DEFAULT_URL }.trimEnd('/'),
) : AiChat {
    override fun complete(system: String, messages: List<AiMessage>): AiReply {
        val model = config.effectiveModel
        val official = !config.customUrl
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", JsonArray(buildList {
                // Akıl yürüten modellerde talimat rolü "developer"; uyumlu servisler "system" bekler.
                if (system.isNotBlank()) add(message(if (official) "developer" else "system", system))
                messages.forEach { add(message(it.role, it.content, it.images)) }
            }))
            if (official) {
                put("max_completion_tokens", JsonPrimitive(config.maxTokens))
                if (REASONING.containsMatchIn(model)) put("reasoning_effort", JsonPrimitive(config.effort.name.lowercase()))
            } else {
                put("max_tokens", JsonPrimitive(config.maxTokens))
            }
        }
        val headers = if (config.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${config.apiKey}")
        val r = call("OpenAI", "$base/chat/completions", headers, body)
        val choice = r["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ApiException(502, tr("{0} API'sinin yanıtı anlaşılamadı", "OpenAI"))
        val msg = choice["message"] as? JsonObject
        val refusal = msg?.get("refusal").str()
        val finish = choice["finish_reason"].str()
        val usage = r["usage"] as? JsonObject
        return AiReply(
            text = msg?.get("content").str() ?: refusal.orEmpty(),
            stopReason = when {
                refusal != null || finish == "content_filter" -> "refusal"
                finish == "length" -> "max_tokens"
                finish == "stop" -> "end_turn"
                else -> finish.orEmpty()
            },
            model = r["model"].str() ?: model,
            inputTokens = usage?.get("prompt_tokens").long(),
            outputTokens = usage?.get("completion_tokens").long(),
        )
    }

    private fun message(role: String, content: String, images: List<AiImage> = emptyList()) = buildJsonObject {
        put("role", JsonPrimitive(role))
        if (images.isEmpty()) {
            put("content", JsonPrimitive(content))
        } else {
            put("content", JsonArray(images.map { img ->
                buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject { put("url", JsonPrimitive("data:${img.mime};base64,${img.base64}")) })
                }
            } + buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(content)) }))
        }
    }

    companion object {
        const val DEFAULT_URL = "https://api.openai.com/v1"

        /** `reasoning_effort` kabul eden model aileleri (akıl yürüten GPT-5 ve sonrası, o-serisi). */
        private val REASONING = Regex("^(o\\d|gpt-([5-9]|\\d{2}))")
    }
}

/** Google Gemini `generateContent`. Anahtar `x-goog-api-key` başlığında. */
class GeminiChat(private val config: AiConfig, private val base: String = DEFAULT_URL) : AiChat {
    override fun complete(system: String, messages: List<AiMessage>): AiReply {
        val model = config.effectiveModel
        val body = buildJsonObject {
            if (system.isNotBlank()) put("systemInstruction", buildJsonObject { put("parts", JsonArray(listOf(text(system)))) })
            put("contents", JsonArray(messages.map { m ->
                buildJsonObject {
                    put("role", JsonPrimitive(if (m.role == "assistant") "model" else "user"))
                    put("parts", JsonArray(m.images.map { img ->
                        buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", JsonPrimitive(img.mime)); put("data", JsonPrimitive(img.base64)) }) }
                    } + text(m.content)))
                }
            }))
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", JsonPrimitive(config.maxTokens))
                // Düşünme düzeyi Gemini 3 ve sonrasında; eski modeller bu alanı tanımaz.
                if (THINKING.containsMatchIn(model)) put("thinkingConfig", buildJsonObject { put("thinkingLevel", JsonPrimitive(level(config.effort))) })
            })
        }
        val r = call("Gemini", "${base.trimEnd('/')}/models/$model:generateContent", mapOf("x-goog-api-key" to config.apiKey), body)
        val usage = r["usageMetadata"] as? JsonObject
        val input = usage?.get("promptTokenCount").long()
        val output = usage?.get("candidatesTokenCount").long() + usage?.get("thoughtsTokenCount").long()
        val blocked = (r["promptFeedback"] as? JsonObject)?.get("blockReason").str()
        val candidate = r["candidates"]?.jsonArray?.firstOrNull() as? JsonObject
        if (blocked != null || candidate == null) return AiReply("", "refusal", model, input, output)
        val text = (candidate["content"] as? JsonObject)?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["thought"].str() != "true" }
            .mapNotNull { it["text"].str() }
            .joinToString("")
        val finish = candidate["finishReason"].str()
        return AiReply(
            text = text,
            stopReason = when (finish) {
                "STOP", null -> "end_turn"
                "MAX_TOKENS" -> "max_tokens"
                in REFUSALS -> "refusal"
                else -> finish.lowercase()
            },
            model = r["modelVersion"].str() ?: model,
            inputTokens = input,
            outputTokens = output,
        )
    }

    private fun text(t: String) = buildJsonObject { put("text", JsonPrimitive(t)) }

    companion object {
        const val DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val THINKING = Regex("^gemini-([3-9]|\\d{2})")
        private val REFUSALS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

        private fun level(e: AiEffort) = e.name.lowercase()
    }
}
