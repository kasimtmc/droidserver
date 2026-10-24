package com.plyg.droidserver.app.data

import android.content.Context
import androidx.core.content.edit
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** Bağımsız bir sunucu: kendi konfigürasyonu, veritabanı, dosyaları, script'leri ve yedekleri. */
@Serializable
data class Profile(val id: String, val name: String) {
    val isDefault: Boolean get() = id == ProfileStore.DEFAULT
}

/**
 * Sunucu profilleri. Aynı anda biri çalışır; geçişte çalışan sunucu durur, uygulama seçilen profille yeniden kurulur.
 * Varsayılan profil önceki sürümlerin klasör düzenini kullanır (`files/`), diğerleri `files/profiles/<id>/` altında,
 * SQLite dosyaları da profilin `databases/` klasöründe. Yüklü bileşenler (PostgreSQL, cloudflared), HTTPS sertifikası
 * ve şifreli sır deposu profiller arasında ortaktır (sır adları benzersizdir).
 */
class ProfileStore(private val context: Context) {
    private val dir = File(context.filesDir, "profiles")
    private val file = File(dir, "profiles.json")
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<Profile> {
        val others = runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), file.readText()) }.getOrDefault(emptyList())
        return listOf(Profile(DEFAULT, tr("Varsayılan"))) + others.filter { it.id != DEFAULT }
    }

    fun active(): Profile = list().firstOrNull { it.id == prefs.getString(KEY_ACTIVE, DEFAULT) } ?: list().first()

    fun setActive(id: String) {
        require(list().any { it.id == id }) { tr("Profil yok: {0}", id) }
        prefs.edit(commit = true) { putString(KEY_ACTIVE, id) }
    }

    /** Profilin veri klasörü (konfigürasyon, dosyalar, script'ler, yedekler). */
    fun root(id: String): File = if (id == DEFAULT) context.filesDir else File(dir, id)

    /** SQLite dosyalarının klasörü; varsayılan profilde null (uygulamanın veritabanı klasörü). */
    fun databaseDir(id: String): File? = if (id == DEFAULT) null else File(root(id), "databases")

    fun create(name: String): Profile {
        val clean = name.trim().take(40).ifEmpty { tr("Sunucu") }
        val id = "p" + ByteArray(5).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val profile = Profile(id, clean)
        root(id).mkdirs()
        save(list().drop(1) + profile)
        return profile
    }

    fun rename(id: String, name: String) {
        if (id == DEFAULT) return
        save(list().drop(1).map { if (it.id == id) it.copy(name = name.trim().take(40).ifEmpty { it.name }) else it })
    }

    /** Profili ve tüm verisini siler; varsayılan ve etkin profil silinemez. */
    fun delete(id: String) {
        require(id != DEFAULT) { tr("Varsayılan profil silinemez") }
        require(id != active().id) { tr("Etkin profil silinemez; önce başka profile geçin") }
        save(list().drop(1).filter { it.id != id })
        root(id).deleteRecursively()
    }

    private fun save(profiles: List<Profile>) {
        dir.mkdirs()
        val tmp = File(dir, "profiles.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Profile.serializer()), profiles))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val DEFAULT = "default"
        private const val KEY_ACTIVE = "active"
    }
}
