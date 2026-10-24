package com.plyg.droidserver.app.data

import android.content.Context
import com.plyg.droidserver.core.config.ConfigDefaults
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.config.ConfigMigrations
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ServerConfig
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant

/**
 * server.json'u saklar. Her kayıtta önceki sürüm history/ altına yedeklenir (son 20),
 * böylece hatalı bir deploy geri alınabilir. Sihirbazın yarım kalan taslağı draft.json'dadır.
 *
 * Kaydederken düz metin sırlar [SecretStore]'a taşınır, yerlerine `"secret:<ad>"` referansı konur
 * (bkz. ConfigSecrets). Eski biçimdeki (sürümdeki) dosya bellekte yükseltilerek kullanılır ama diskte
 * kendiliğinden yeniden yazılmaz: kullanıcı [upgradeFile] ile ("Sürümü yükselt") karar verir.
 */
/** Etkin dosyanın biçim sürümü güncelden eski: [changes] kullanıcıya gösterilir. */
data class ConfigUpgrade(val from: Int, val to: Int, val changes: List<String>)

class ConfigRepository(private val context: Context, private val secrets: SecretStore, root: File = context.filesDir) {
    private val dir = File(root, "config").apply { mkdirs() }
    private val file = File(dir, "server.json")
    private val draftFile = File(dir, "draft.json")
    private val historyDir = File(dir, "history").apply { mkdirs() }

    /** Etkin konfigürasyon var mı? Yoksa uygulama ilk açılışta sihirbazı gösterir. */
    fun hasConfig(): Boolean = file.exists()

    fun loadText(): String {
        ensureFile()
        return file.readText()
    }

    fun load(): ServerConfig = ServerConfig.fromJson(loadText())

    /** Etkin konfigürasyon; henüz yoksa null (varsayılanı oluşturmaz). */
    fun loadActiveOrNull(): ServerConfig? = if (hasConfig()) load() else null

    /** Metni ayrıştırır (hatalıysa istisna fırlatır), sırları depoya taşır ve kaydeder. */
    fun saveText(text: String): ServerConfig {
        ServerConfig.fromJson(text) // önce ayrıştır: hatalı metin için depoya hiçbir şey yazılmasın
        val stored = ConfigSecrets.secureText(text, secrets) ?: text
        val cfg = ServerConfig.fromJson(stored)
        if (file.exists()) {
            file.copyTo(File(historyDir, "server-${Instant.now().toString().replace(':', '-')}.json"), overwrite = true)
            historyDir.listFiles()?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
        }
        writeAtomic(file, stored)
        return cfg
    }

    fun history(): List<File> = historyDir.listFiles()?.sortedByDescending { it.name } ?: emptyList()

    /** Varsayılan konfigürasyon; yer tutucu anahtar yerine yeni üretilmiş bir anahtarla. */
    fun defaultConfigText(): String =
        context.assets.open("default-config.json").bufferedReader().use { it.readText() }
            .replace(ConfigDefaults.PLACEHOLDER_API_KEY, ConfigDefaults.generateApiKey())

    // ---- sihirbaz taslağı: uygulama kapanıp açılınca kaldığı yerden devam edilir

    fun loadDraft(): ServerConfig? =
        draftFile.takeIf { it.exists() }?.let { runCatching { ServerConfig.fromJson(it.readText()) }.getOrNull() }

    fun saveDraft(config: ServerConfig) {
        val text = config.toJson()
        writeAtomic(draftFile, ConfigSecrets.secureText(text, secrets) ?: text)
    }

    fun clearDraft() {
        draftFile.delete()
    }

    /** Etkin dosya eski biçimdeyse ne değişeceği; güncelse veya dosya yoksa null. */
    fun pendingUpgrade(): ConfigUpgrade? {
        if (!file.exists()) return null
        val from = runCatching { ConfigMigrations.versionOf(ConfigJson.parseToJsonElement(file.readText()).jsonObject) }
            .getOrNull() ?: return null
        if (from >= ConfigMigrations.CURRENT) return null
        return ConfigUpgrade(from, ConfigMigrations.CURRENT, ConfigMigrations.changes(from))
    }

    /**
     * Kullanıcı onayıyla: etkin dosyayı güncel biçime yükseltir (önceki hali Geçmiş'e yedeklenir) ve içindeki düz
     * metin sırları depoya taşır. Geçmişteki kopyalardaki düz metin sırlar da taşınır. Konfigürasyonun anlamı
     * değişmez; yeniden deploy gerekmez.
     */
    @Synchronized
    fun upgradeFile(): ServerConfig {
        val cfg = saveText(file.readText())
        for (f in history()) {
            runCatching { ConfigSecrets.secureText(f.readText(), secrets) }.getOrNull()?.let { writeAtomic(f, it) }
        }
        return cfg
    }

    /** Dosya yoksa (ilk açılış) varsayılanı güncel biçimde yazar. */
    @Synchronized
    private fun ensureFile() {
        if (!file.exists()) writeAtomic(file, defaultConfigText())
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
