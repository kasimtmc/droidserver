package dev.droidserver.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Kayıtlı görselden küçük resim üretir (app: BitmapFactory). [width] veya [height] 0 ise en-boy oranı korunur. */
fun interface ImageResizer {
    /** Başarılıysa true; [target] yazılmış olur. Görseli kırpar (her iki boyut verilmişse ortadan). */
    fun resize(source: File, target: File, width: Int, height: Int): Boolean
}

/** Yüklenen, henüz kayda bağlanmamış dosya: geçici dosya ve tespit edilen tip. */
class Upload(
    val field: String,
    val temp: File,
    val kind: MimeTypes.Kind,
    val originalName: String,
    val size: Long,
    /** Kaydedilecek ad ([FileStore.newName]). */
    val storedName: String,
)

/**
 * Dosya alanlarının dosyaları: `<kök>/<koleksiyon>/<kayıt-id>/<ad>`. Veritabanında yalnızca ad durur. Adlar
 * `<özgün-ad>_<rastgele>.<uzantı>` biçimindedir (uzantı içerikten); yol ayracı veya `..` içeremez.
 * Küçük resimler kayıt klasörünün `.thumbs/` altında; kayıt silinince klasörle birlikte silinir.
 */
class FileStore(val root: File) {
    private val random = SecureRandom()
    private val tmp = File(root, ".tmp")

    fun newTemp(): File {
        tmp.mkdirs()
        return File.createTempFile("yukleme", ".part", tmp)
    }

    fun newName(originalName: String, ext: String): String {
        val base = originalName.substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "dosya" }
        val suffix = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "${base}_$suffix.$ext"
    }

    fun recordDir(collection: String, id: Long) = File(File(root, collection), id.toString())

    /** Adın dosyası; ad geçersizse null (yol dışına çıkılamaz). */
    fun file(collection: String, id: Long, name: String): File? =
        if (NAME.matches(name)) File(recordDir(collection, id), name) else null

    fun thumb(collection: String, id: Long, name: String, size: String): File =
        File(File(recordDir(collection, id), ".thumbs"), "${size}_$name")

    /** Geçici dosyayı kaydın klasörüne taşır. */
    fun commit(upload: Upload, collection: String, id: Long): File {
        val target = File(recordDir(collection, id).apply { mkdirs() }, upload.storedName)
        Files.move(upload.temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /** Dosyayı ve küçük resimlerini siler. */
    fun delete(collection: String, id: Long, name: String) {
        file(collection, id, name)?.delete()
        File(recordDir(collection, id), ".thumbs").listFiles { f -> f.name.endsWith("_$name") }?.forEach { it.delete() }
    }

    fun deleteRecord(collection: String, id: Long) {
        recordDir(collection, id).deleteRecursively()
    }

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        val NAME = Regex("^[a-z0-9_-]{1,60}\\.[a-z0-9]{1,8}$")
    }
}

/** Bir klasörün kapladığı yer. */
data class StorageUsage(val bytes: Long, val files: Int) {
    companion object {
        fun of(dir: File?): StorageUsage {
            if (dir == null || !dir.exists()) return StorageUsage(0, 0)
            var bytes = 0L
            var files = 0
            dir.walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); files++ }
            return StorageUsage(bytes, files)
        }
    }
}
