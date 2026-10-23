package dev.droidserver.core.ai

import dev.droidserver.core.config.AiConfig

/**
 * Telefonda çalışan modeller (uygulama sağlar: llama.cpp ve indirilen GGUF dosyaları). Model indirilmemişse, cihaz
 * desteklemiyorsa veya bellek/ısı uygun değilse çağrı `ApiException` (503) fırlatır.
 */
interface LocalAi {
    /** [model]: indirilmiş sohbet modelinin kimliği. */
    fun chat(model: String, config: AiConfig): AiChat

    /** [model]: indirilmiş gömme modelinin kimliği. */
    fun embedder(model: String): Embedder
}
