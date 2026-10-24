package com.plyg.droidserver.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.plyg.droidserver.core.ai.AiChat
import com.plyg.droidserver.core.ai.AiReply
import com.plyg.droidserver.core.ai.Embedder
import com.plyg.droidserver.core.ai.LocalAi
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.AiConfig
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** llama.cpp'nin JNI sarmalayıcısı (`cpp/droidserver_llama.cpp`); arm64-v8a ve x86_64 dışında yüklenemez. */
internal object LlamaNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("droidserver_llama") }.isSuccess }

    external fun load(path: String, nCtx: Int, nThreads: Int, embeddings: Boolean): Long
    external fun free(handle: Long)
    external fun cancel(handle: Long)

    /** Sonuç: [0] metin, [1] "girdi çıktı bitiş" (UTF-8). */
    external fun complete(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, maxTokens: Int, temperature: Float): Array<ByteArray>
    external fun embed(handle: Long, text: ByteArray): FloatArray
}

/**
 * İndirilebilir GGUF modelleri. Boyut ve SHA-256 sabit: indirilen dosya doğrulanır (yanlış veya değiştirilmiş dosya
 * kullanılmaz). [ram]: yüklü haldeyken gereken yaklaşık bellek (MB).
 */
enum class LocalModel(val id: String, val title: String, val description: String, val url: String, val bytes: Long, val sha256: String, val ram: Int, val embedding: Boolean, val license: String) {
    QWEN_05B(
        "qwen2.5-0.5b-instruct-q4_k_m", "Qwen2.5 0.5B Instruct", tr("En küçük ve hızlı; eski telefonlarda bile. Kısa özet ve sınıflandırma için."),
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        491_400_032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 900, false, "Apache-2.0",
    ),
    GEMMA3_1B(
        "gemma-3-1b-it-q4_k_m", "Gemma 3 1B", tr("Türkçe dahil çok dilli, dengeli. 3 GB+ bellekli telefonlar için."),
        "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        806_058_240, "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135", 1_400, false, "Gemma",
    ),
    LLAMA32_1B(
        "llama-3.2-1b-instruct-q4_k_m", "Llama 3.2 1B Instruct", tr("Genel amaçlı, İngilizcede güçlü."),
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        807_694_464, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", 1_400, false, "Llama 3.2 Community",
    ),
    QWEN_15B(
        "qwen2.5-1.5b-instruct-q4_k_m", "Qwen2.5 1.5B Instruct", tr("Daha iyi yanıtlar; 4 GB+ bellek ister, daha yavaş."),
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        1_117_320_736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 1_900, false, "Apache-2.0",
    ),
    EMBEDDING_GEMMA(
        "embeddinggemma-300m-q8_0", "EmbeddingGemma 300M", tr("Anlamsal arama için çok dilli (Türkçe dahil)."),
        "https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/resolve/main/embeddinggemma-300M-Q8_0.gguf",
        333_590_944, "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63", 600, true, "Gemma",
    ),
    BGE_M3(
        "bge-m3-q8_0", "BGE-M3", tr("Anlamsal arama için güçlü çok dilli model; büyük."),
        "https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF/resolve/main/bge-m3-q8_0.gguf",
        634_553_760, "aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173", 1_000, true, "MIT",
    ),
    MINILM(
        "all-minilm-l6-v2-q8_0", "all-MiniLM-L6-v2", tr("Çok küçük ve hızlı; yalnızca İngilizce metinlerde iyi."),
        "https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF/resolve/main/all-MiniLM-L6-v2-Q8_0.gguf",
        25_008_064, "263215c3cadd6e16740741a7624ab4cbb6c8e777688bd5331ecfbf5681c2f8ed", 150, true, "Apache-2.0",
    ),
    ;

    companion object {
        fun of(id: String): LocalModel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Telefondaki modeller: indirme (sürdürülebilir, SHA-256 doğrulamalı), yükleme ve [LocalAi]. Bir anda bir sohbet ve bir
 * gömme modeli bellekte durur; 5 dakika kullanılmayan model belleği boşaltır. Yüklemeden önce boş bellek ve telefonun
 * ısısı denetlenir; uygun değilse 503 ve [onRefused] (sağlık uyarısı).
 */
class LocalModels(private val context: Context, private val onRefused: (String) -> Unit = {}) : LocalAi {
    val dir = File(context.filesDir, "models")

    sealed interface Download {
        data class Running(val model: LocalModel, val done: Long) : Download
        data class Failed(val model: LocalModel, val message: String) : Download
    }

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()
    private val cancelled = java.util.Collections.synchronizedSet(HashSet<String>())

    private class Loaded(val model: LocalModel, val handle: Long, @Volatile var used: Long)

    private val lock = Any()
    private val chatLock = Any()
    private val embedLock = Any()
    private var chatModel: Loaded? = null
    private var embedModel: Loaded? = null
    private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "local-ai-reaper").apply { isDaemon = true } }

    init {
        reaper.scheduleWithFixedDelay({ unloadIdle() }, 1, 1, TimeUnit.MINUTES)
    }

    fun file(m: LocalModel) = File(dir, m.id + ".gguf")

    fun installed(m: LocalModel) = file(m).let { it.isFile && it.length() == m.bytes }

    val supported: Boolean get() = LlamaNative.available

    // ---- indirme ----

    /** Bloklayıcı; yarım kalan indirme sürer (Range), bitince SHA-256 doğrulanır. */
    fun download(m: LocalModel) {
        dir.mkdirs()
        val part = File(dir, m.id + ".part")
        cancelled.remove(m.id)
        free(dir)?.let { freeBytes -> if (freeBytes < m.bytes - part.length() + 50_000_000) throw IOException(tr("Yer yetersiz: {0} MB gerekiyor", (m.bytes - part.length()) / 1_048_576)) }
        try {
            var hops = 0
            var url = m.url
            while (true) {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "DroidServer")
                if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = c.responseCode
                if (code in 300..399 && hops++ < 5) {
                    url = URI(url).resolve(c.getHeaderField("Location")).toString()
                    c.disconnect()
                    continue
                }
                if (code != 200 && code != 206) throw IOException(tr("İndirme başarısız: HTTP {0}", code))
                val append = code == 206
                c.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = if (append) part.length() else 0L
                        var last = 0L
                        while (true) {
                            if (m.id in cancelled) throw IOException(tr("İndirme durduruldu"))
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 4_000_000) { last = done; progress(m, Download.Running(m, done)) }
                        }
                    }
                }
                c.disconnect()
                break
            }
            if (part.length() != m.bytes) throw IOException(tr("Dosya eksik indi ({0} / {1} bayt)", part.length(), m.bytes))
            progress(m, Download.Running(m, m.bytes))
            if (sha256(part) != m.sha256) {
                part.delete()
                throw IOException(tr("Dosya doğrulanamadı (SHA-256 uyuşmuyor); yeniden indirin"))
            }
            if (!part.renameTo(file(m))) throw IOException(tr("Model dosyası kaydedilemedi"))
            progress(m, null)
        } catch (e: IOException) {
            progress(m, Download.Failed(m, e.message ?: e.toString()))
            throw e
        }
    }

    fun cancel(m: LocalModel) { cancelled += m.id }

    fun delete(m: LocalModel) {
        synchronized(lock) {
            if (chatModel?.model == m) { chatModel?.let { LlamaNative.free(it.handle) }; chatModel = null }
            if (embedModel?.model == m) { embedModel?.let { LlamaNative.free(it.handle) }; embedModel = null }
        }
        file(m).delete()
        File(dir, m.id + ".part").delete()
        progress(m, null)
    }

    private fun progress(m: LocalModel, d: Download?) {
        _downloads.value = _downloads.value.toMutableMap().apply { if (d == null) remove(m.id) else put(m.id, d) }
    }

    private fun free(d: File): Long? = runCatching { d.usableSpace }.getOrNull()

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- yükleme ve çalıştırma ----

    /** Bellek ve ısı uygun mu; değilse açıklama. */
    fun problem(m: LocalModel): String? {
        if (!supported) return tr("Bu telefonun işlemcisinde yerel YZ yok (yalnızca 64 bit ARM ve x86_64)")
        if (!installed(m)) return tr("{0} indirilmedi (Yapay zekâ → Yerel modeller)", m.title)
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val loaded = synchronized(lock) { chatModel?.model == m || embedModel?.model == m }
        if (!loaded && (info.lowMemory || info.availMem / 1_048_576 < m.ram)) {
            return tr("Bellek yetersiz: {0} için yaklaşık {1} MB boş bellek gerekiyor ({2} MB var)", m.title, m.ram, info.availMem / 1_048_576)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return tr("Telefon çok ısındı; yerel YZ soğuyana kadar kullanılamaz")
        }
        return null
    }

    private fun acquire(m: LocalModel): Loaded = synchronized(lock) {
        val current = if (m.embedding) embedModel else chatModel
        if (current?.model == m) return current.also { it.used = System.currentTimeMillis() }
        problem(m)?.let { msg ->
            onRefused(msg)
            throw ApiException(503, msg)
        }
        current?.let { LlamaNative.free(it.handle) }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val handle = try {
            LlamaNative.load(file(m).path, if (m.embedding) 512 else 4096, threads, m.embedding)
        } catch (e: IOException) {
            throw ApiException(503, tr("Model yüklenemedi: {0}", e.message))
        }
        Loaded(m, handle, System.currentTimeMillis()).also { if (m.embedding) embedModel = it else chatModel = it }
    }

    private fun unloadIdle() = synchronized(lock) {
        val now = System.currentTimeMillis()
        chatModel?.takeIf { now - it.used > IDLE_MS }?.let { LlamaNative.free(it.handle); chatModel = null }
        embedModel?.takeIf { now - it.used > IDLE_MS }?.let { LlamaNative.free(it.handle); embedModel = null }
    }

    private fun model(id: String, embedding: Boolean): LocalModel =
        LocalModel.of(id)?.takeIf { it.embedding == embedding } ?: throw ApiException(503, tr("Yerel model bilinmiyor: {0}", id))

    override fun chat(model: String, config: AiConfig): AiChat = AiChat { system, messages ->
        val m = model(model, embedding = false)
        // Bir anda tek üretim (tek bağlam); diğerleri sırada bekler.
        synchronized(chatLock) {
            val loaded = acquire(m)
            val all = (if (system.isNotBlank()) listOf("system" to system) else emptyList()) + messages.map { it.role to it.content }
            val out = LlamaNative.complete(
                loaded.handle, all.map { it.first.toByteArray() }.toTypedArray(), all.map { it.second.toByteArray() }.toTypedArray(),
                config.maxTokens.coerceAtMost(2048), 0.7f,
            )
            loaded.used = System.currentTimeMillis()
            val (input, output, reason) = out[1].decodeToString().split(' ')
            AiReply(out[0].decodeToString().trim(), if (reason == "stop") "end_turn" else "max_tokens", m.id, input.toLong(), output.toLong())
        }
    }

    override fun embedder(model: String): Embedder = Embedder { texts, _ ->
        val m = model(model, embedding = true)
        synchronized(embedLock) {
            val loaded = acquire(m)
            texts.map { LlamaNative.embed(loaded.handle, it.toByteArray()) }.also { loaded.used = System.currentTimeMillis() }
        }
    }

    private companion object {
        const val IDLE_MS = 5 * 60_000L
    }
}
