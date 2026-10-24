package com.plyg.droidserver.core.files

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.i18n.tr
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Kayıtlı görselden küçük resim üretir (app: BitmapFactory). [width] veya [height] 0 ise en-boy oranı korunur. */
fun interface ImageResizer {
    /** Başarılıysa true; [target] yazılmış olur. Görseli kırpar (her iki boyut verilmişse ortadan). */
    fun resize(source: File, target: File, width: Int, height: Int): Boolean

    /**
     * Yüklenen görseli [ext] biçiminde (`webp`, `jpg`, `png`) yeniden kodlar; uzun kenarı [maxDimension]'dan büyükse
     * küçültür, EXIF yönünü uygular, meta veri yazmaz. Yapılamıyorsa veya gerek yoksa false ([target] kullanılmaz).
     */
    fun convert(source: File, target: File, ext: String, maxDimension: Int?): Boolean = false
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
class FileStore(val root: File, private val images: ImageResizer? = null) {
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

    /**
     * [temp]'i [f] alanına yüklenen dosya olarak denetler: boş olmamalı, alanın boyut sınırını aşmamalı, uzantısı
     * içerikten tespit edilen tiple çelişmemeli, tip alanın izinli tiplerinden olmalı. [head]: ilk baytlar
     * ([MimeTypes.HEAD_BYTES]). Görsel alanın ayarına göre hazırlanır ([Media]); bloklayıcıdır.
     */
    fun accept(f: FieldConfig, temp: File, size: Long, head: ByteArray, original: String): Upload {
        fun invalid(message: String): Nothing = throw ApiException(400, tr("Doğrulama hatası"), mapOf(f.name to message))
        if (size == 0L) invalid(tr("boş dosya"))
        if (size > f.maxBytes) throw ApiException(413, tr("'{0}' dosyası en çok {1} MB olabilir", f.name, f.maxBytes / 1024 / 1024))
        val kind = MimeTypes.sniff(head, original.substringAfterLast('.', "").ifEmpty { null })
        MimeTypes.extensionMismatch(original, kind)?.let(::invalid)
        if (!MimeTypes.matches(kind.mime, f.mimeTypes)) {
            invalid(tr("izin verilmeyen içerik tipi: {0} (izinli: {1})", kind.mime, f.mimeTypes!!.joinToString()))
        }
        return Media.process(this, f, Upload(f.name, temp, kind, original, size, newName(original, kind.ext)), images)
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
