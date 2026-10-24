@file:OptIn(ExperimentalSerializationApi::class)

package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Yapay zekâ: seçilen sağlayıcıya (Claude, OpenAI, Gemini) anahtarı sunucuda tutan vekil uç (`/ai/chat`), kayıtlar
 * üzerinde soru-cevap (`/ai/ask/<koleksiyon>`) ve metin alanlarında anlama göre arama (`/ai/search/<koleksiyon>`,
 * vektör dizini). Maliyet kullanıcının kendi hesaplarına yansır; kullanıcı başına günlük sınır uygulanır.
 */
@Serializable
data class AiConfig(
    val enabled: Boolean = false,
    /** Hangi YZ sağlayıcısı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val provider: AiProvider = AiProvider.CLAUDE,
    /** Sağlayıcının API anahtarı; `secret:` referansı, kaydedince şifreli depoya taşınır. */
    val apiKey: String = "",
    /** Model kimliği; boşsa sağlayıcının varsayılanı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String = "",
    /** OpenAI: uç adresi; boşsa api.openai.com. OpenAI uyumlu servisler için (Ollama, LM Studio, OpenRouter). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String = "",
    /** Ne kadar düşünsün: düşük daha hızlı ve ucuz, yüksek daha özenli. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val effort: AiEffort = AiEffort.MEDIUM,
    /** Kim kullanabilir. Anonim istekler hiçbir zaman. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val access: AiAccess = AiAccess.USERS,
    /** Yönetici olmayan her kimliğin günlük en çok YZ isteği (sohbet ve soru); 0: sınırsız. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dailyLimit: Int = 50,
    /** Bir yanıtın en çok token'ı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxTokens: Int = 4096,
    /** Her isteğe eklenen sistem talimatı (örn. "Kısa ve Türkçe yanıtla"). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val system: String = "",
    /** Anlamsal arama için metni vektöre çeviren servis; yoksa soru-cevap anahtar kelime aramasıyla çalışır. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val embeddings: EmbeddingsConfig? = null,
    /** Anlamsal dizini tutulan koleksiyonlar ve alanları. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val semantic: List<SemanticIndexConfig> = emptyList(),
) {
    val effectiveModel: String get() = model.ifBlank { provider.defaultModel }

    /** OpenAI uyumlu başka bir servis (yerel model olabilir; anahtar zorunlu değil). */
    val customUrl: Boolean get() = provider == AiProvider.OPENAI && url.isNotBlank()
}

@Serializable
enum class AiProvider(val title: String, val defaultModel: String, val keyUrl: String) {
    @SerialName("claude") CLAUDE("Claude (Anthropic)", "claude-opus-5-5", "https://console.anthropic.com/settings/keys"),
    @SerialName("openai") OPENAI("OpenAI", "gpt-6.1-sol", "https://platform.openai.com/api-keys"),
    @SerialName("gemini") GEMINI("Google Gemini", "gemini-3.8-flash", "https://aistudio.google.com/apikey"),

    /** Telefonda çalışan model (llama.cpp); [AiConfig.model] indirilen modelin kimliği. Anahtar yok, veri telefondan çıkmaz. */
    @SerialName("local") LOCAL(tr("Telefonda (yerel model)"), "", ""),
}

/** Hazır model seçenekleri; listede olmayan bir model kimliği de yazılabilir. */
enum class AiModel(val provider: AiProvider, val id: String, val title: String, val fallbacks: Boolean = false) {
    OPUS(AiProvider.CLAUDE, "claude-opus-5-5", tr("Claude Opus 5.5 — en yetenekli"), true),
    SONNET(AiProvider.CLAUDE, "claude-sonnet-5-5", tr("Claude Sonnet 5.5 — dengeli"), true),
    HAIKU(AiProvider.CLAUDE, "claude-haiku-5-5", tr("Claude Haiku 5.5 — en hızlı ve ucuz")),
    GPT_ASTRA(AiProvider.OPENAI, "gpt-6-astra", tr("GPT-6 Astra — en yetenekli")),
    GPT_SOL(AiProvider.OPENAI, "gpt-6.1-sol", tr("GPT-6.1 Sol — dengeli")),
    GPT_LUNA(AiProvider.OPENAI, "gpt-6-luna", tr("GPT-6 Luna — en hızlı ve ucuz")),
    GEMINI_ARGON(AiProvider.GEMINI, "gemini-4-argon", tr("Gemini 4 Argon — en yetenekli")),
    GEMINI_FLASH(AiProvider.GEMINI, "gemini-3.8-flash", tr("Gemini 3.8 Flash — dengeli")),
    GEMINI_LITE(AiProvider.GEMINI, "gemini-3.5-flash-lite", tr("Gemini 3.5 Flash-Lite — en hızlı ve ucuz")),
    ;

    companion object {
        const val DEFAULT = "claude-opus-5-5"

        fun of(id: String): AiModel? = entries.firstOrNull { it.id == id }

        fun of(provider: AiProvider): List<AiModel> = entries.filter { it.provider == provider }
    }
}

@Serializable
enum class AiEffort(val title: String) {
    @SerialName("low") LOW(tr("Düşük")),
    @SerialName("medium") MEDIUM(tr("Orta")),
    @SerialName("high") HIGH(tr("Yüksek")),
}

@Serializable
enum class AiAccess(val title: String) {
    /** Giriş yapmış kullanıcılar, kapsamlı API anahtarları ve yönetici. */
    @SerialName("users") USERS(tr("Giriş yapmış kullanıcılar")),
    @SerialName("admin") ADMIN(tr("Yalnızca yönetici")),
}

@Serializable
enum class EmbeddingProvider(val title: String, val defaultUrl: String, val defaultModel: String) {
    /** Anthropic'in önerdiği gömme sağlayıcısı. */
    @SerialName("voyage") VOYAGE("Voyage AI", "https://api.voyageai.com/v1", "voyage-3.5"),

    /** OpenAI uyumlu `/embeddings` ucu: telefondaki veya ağdaki Ollama, LM Studio, OpenAI. */
    @SerialName("openai") OPENAI(tr("OpenAI uyumlu (Ollama, LM Studio…)"), "http://127.0.0.1:11434/v1", "nomic-embed-text"),

    /** Telefonda çalışan gömme modeli (llama.cpp); [EmbeddingsConfig.model] indirilen modelin kimliği. */
    @SerialName("local") LOCAL(tr("Telefonda (yerel model)"), "", ""),
}

@Serializable
data class EmbeddingsConfig(
    val provider: EmbeddingProvider = EmbeddingProvider.VOYAGE,
    /** Boşsa sağlayıcının varsayılanı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String = "",
    /** Boşsa sağlayıcının varsayılanı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String = "",
    /** Sağlayıcının API anahtarı (yerel Ollama'da boş); `secret:` referansı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apiKey: String = "",
) {
    val effectiveUrl: String get() = url.ifBlank { provider.defaultUrl }.trimEnd('/')
    val effectiveModel: String get() = model.ifBlank { provider.defaultModel }
}

/** Anlamsal dizin: kaydın bu alanları birleştirilip vektöre çevrilir (yalnızca metin ve seçim alanları). */
@Serializable
data class SemanticIndexConfig(
    val collection: String,
    val fields: List<String>,
)
