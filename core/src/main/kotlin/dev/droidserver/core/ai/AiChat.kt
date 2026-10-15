package dev.droidserver.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.droidserver.core.api.ApiException
import dev.droidserver.core.config.AiConfig
import dev.droidserver.core.config.AiEffort
import dev.droidserver.core.config.AiModel
import dev.droidserver.core.config.AiProvider
import dev.droidserver.core.i18n.tr
import java.io.Closeable

/** Sohbetteki bir ileti; [role] `user` veya `assistant`. */
data class AiMessage(val role: String, val content: String)

data class AiReply(
    val text: String,
    /** `end_turn`, `max_tokens`, `refusal`… */
    val stopReason: String,
    /** Yanıtı veren model (geri düşüşte istenenden farklı olabilir). */
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val refused: Boolean get() = stopReason == "refusal"
}

/** YZ sağlayıcısı (testlerde sahte). Çağrı bloklayıcıdır. [AiReply.stopReason] sağlayıcıdan bağımsız: `end_turn`, `max_tokens`, `refusal`… */
fun interface AiChat {
    fun complete(system: String, messages: List<AiMessage>): AiReply

    companion object {
        /** Ayardaki sağlayıcının istemcisi. */
        fun of(config: AiConfig): AiChat = when (config.provider) {
            AiProvider.CLAUDE -> ClaudeChat(config.apiKey, config)
            AiProvider.OPENAI -> OpenAiChat(config)
            AiProvider.GEMINI -> GeminiChat(config)
        }
    }
}

/**
 * Claude Messages API'si (resmi Java SDK'sı). Anahtar yalnızca sunucuda durur. Opus ve Sonnet'te sunucu tarafı geri
 * düşüş açıktır: güvenlik sınıflandırıcısı isteği reddederse Anthropic'in önerdiği modelle yeniden denenir.
 */
class ClaudeChat(
    apiKey: String,
    private val config: AiConfig,
    /** Testlerde sahte sunucu; null: api.anthropic.com. */
    baseUrl: String? = null,
) : AiChat, Closeable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).apply { baseUrl?.let { baseUrl(it) } }.maxRetries(2).build()

    override fun complete(system: String, messages: List<AiMessage>): AiReply {
        val params = MessageCreateParams.builder()
            .model(config.effectiveModel)
            .maxTokens(config.maxTokens.toLong())
            .outputConfig(BetaOutputConfig.builder().effort(effort(config.effort)).build())
        if (system.isNotBlank()) params.system(system)
        for (m in messages) if (m.role == "assistant") params.addAssistantMessage(m.content) else params.addUserMessage(m.content)
        if (AiModel.of(config.effectiveModel)?.fallbacks == true) {
            params.addBeta(FALLBACK_BETA)
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val response = try {
            client.beta().messages().create(params.build())
        } catch (e: AnthropicServiceException) {
            throw ApiException(502, tr("Claude API hatası ({0}): {1}", e.statusCode(), e.message ?: ""))
        }
        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return AiReply(
            text = text,
            stopReason = response.stopReason().map { it.asString() }.orElse(""),
            model = response.model().asString(),
            inputTokens = response.usage().inputTokens(),
            outputTokens = response.usage().outputTokens(),
        )
    }

    override fun close() = client.close()

    companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        private fun effort(e: AiEffort) = when (e) {
            AiEffort.LOW -> BetaOutputConfig.Effort.LOW
            AiEffort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
            AiEffort.HIGH -> BetaOutputConfig.Effort.HIGH
        }
    }
}
