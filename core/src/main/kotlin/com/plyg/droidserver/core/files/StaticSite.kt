package com.plyg.droidserver.core.files

import com.plyg.droidserver.core.i18n.tr
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Statik site: `files/www/` altındaki dosyalar. Site uygulamadan zip olarak yüklenir ([install]); yükleme ayrı bir
 * klasöre açılıp tamamlanınca eskisinin yerine geçer (yarım kalmış site yayınlanmaz). Sunucu her istekte klasörden
 * okuduğu için yeniden deploy gerekmez.
 */
class StaticSite(val dir: File) {
    /**
     * İstenen yolun dosyası: klasörse içindeki `index.html`. Yol klasörün dışına çıkamaz; `.` ile başlayan parçalar
     * (gizli dosyalar) sunulmaz. Bulunamazsa ve [spaFallback] açıksa, uzantısız (sayfa) adreslerde kök `index.html`.
     */
    fun resolve(segments: List<String>, spaFallback: Boolean): File? {
        val parts = segments.filter { it.isNotEmpty() }
        if (parts.any { it.startsWith(".") || it.contains('\\') || it.contains('/') || it.contains('\u0000') }) return null
        val root = dir.canonicalFile
        val target = File(root, parts.joinToString(File.separator)).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) return null
        val file = if (target.isDirectory) File(target, "index.html") else target
        if (file.isFile) return file
        val looksLikePage = parts.lastOrNull()?.contains('.') != true
        return File(root, "index.html").takeIf { spaFallback && looksLikePage && it.isFile }
    }

    data class InstallReport(val files: Int, val bytes: Long, val hasIndex: Boolean, val strippedFolder: String?)

    companion object {
        const val MAX_BYTES = 500L * 1024 * 1024
        const val MAX_ENTRIES = 20_000

        /**
         * Zip'i [dir]'e kurar (eski site silinir). Zip'teki tüm dosyalar tek bir klasör altındaysa ve kökte
         * `index.html` yoksa o klasör kök sayılır (klasörü sağ tıklayıp sıkıştırma alışkanlığı). Klasör dışına
         * çıkan (`../`), mutlak veya gizli adlar ve sınırı aşan arşivler reddedilir; o durumda eski site yerinde kalır.
         */
        fun install(zip: InputStream, dir: File, maxBytes: Long = MAX_BYTES, maxEntries: Int = MAX_ENTRIES): InstallReport {
            val parent = dir.absoluteFile.parentFile.apply { mkdirs() }
            val staging = File(parent, "${dir.name}.yeni")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                var files = 0
                var bytes = 0L
                val names = mutableListOf<String>()
                ZipInputStream(zip).use { z ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val entry = z.nextEntry ?: break
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        val parts = name.split('/').filter { it.isNotEmpty() }
                        if (parts.any { it == ".." } || entry.name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(entry.name)) {
                            throw IOException(tr("Zip'te klasör dışına çıkan ad var: {0}", entry.name))
                        }
                        // __MACOSX ve gizli dosyalar sitenin parçası değildir.
                        if (parts.isEmpty() || parts.any { it.startsWith(".") } || parts.first() == "__MACOSX") continue
                        if (entry.isDirectory) continue
                        if (++files > maxEntries) throw IOException(tr("Zip'te çok fazla dosya var (en çok {0})", maxEntries))
                        val out = File(staging, parts.joinToString(File.separator))
                        out.parentFile.mkdirs()
                        out.outputStream().use { o ->
                            while (true) {
                                val n = z.read(buffer)
                                if (n < 0) break
                                bytes += n
                                if (bytes > maxBytes) throw IOException(tr("Site çok büyük (en çok {0} MB)", maxBytes / 1024 / 1024))
                                o.write(buffer, 0, n)
                            }
                        }
                        names += parts.joinToString("/")
                    }
                }
                if (files == 0) throw IOException(tr("Zip'te dosya yok"))

                // Tek üst klasör ve kökte index.html yoksa içeriği köke al.
                val tops = names.map { it.substringBefore('/') }.toSet()
                var stripped: String? = null
                if ("index.html" !in names && tops.size == 1 && names.all { '/' in it }) {
                    val top = File(staging, tops.single())
                    val lifted = File(parent, "${dir.name}.ic")
                    lifted.deleteRecursively()
                    if (!top.renameTo(lifted)) throw IOException(tr("Klasör taşınamadı"))
                    staging.deleteRecursively()
                    if (!lifted.renameTo(staging)) throw IOException(tr("Klasör taşınamadı"))
                    stripped = tops.single()
                }

                val old = File(parent, "${dir.name}.eski")
                old.deleteRecursively()
                if (dir.exists() && !dir.renameTo(old)) throw IOException(tr("Eski site kaldırılamadı"))
                if (!staging.renameTo(dir)) {
                    old.renameTo(dir)
                    throw IOException(tr("Yeni site yerleştirilemedi"))
                }
                old.deleteRecursively()
                return InstallReport(files, bytes, File(dir, "index.html").isFile, stripped)
            } finally {
                staging.deleteRecursively()
            }
        }
    }
}
