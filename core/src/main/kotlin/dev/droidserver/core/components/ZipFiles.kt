package dev.droidserver.core.components

import dev.droidserver.core.i18n.tr
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/** Sembolik bağlantı kalıntısının içeriği: göreli yol (örn. `libfoo.so.1.2`, `../Etc/UTC`). */
private val LINK_TEXT = Regex("^[A-Za-z0-9._+@-]+(/[A-Za-z0-9._+@-]+)*$")

/**
 * Zip'i [dest]'e açar. Arşiv dışına yazan girdiler (zip-slip) reddedilir. [maxBytes] açılan toplam boyut, [maxEntries]
 * girdi sayısı sınırıdır (sıkıştırma bombasına karşı); aşılırsa açma durur.
 */
internal fun unzipSafely(input: InputStream, dest: File, maxBytes: Long = Long.MAX_VALUE, maxEntries: Int = Int.MAX_VALUE) {
    val root = dest.canonicalFile.apply { mkdirs() }
    var total = 0L
    var entries = 0
    val buffer = ByteArray(64 * 1024)
    ZipInputStream(input).use { zip ->
        generateSequence { zip.nextEntry }.forEach { e ->
            if (++entries > maxEntries) throw IllegalArgumentException(tr("Arşivde çok fazla dosya var (en çok {0})", maxEntries))
            val out = File(root, e.name).canonicalFile
            require(out.path.startsWith(root.path + File.separator)) { tr("Geçersiz arşiv girdisi: {0}", e.name) }
            if (e.isDirectory) out.mkdirs() else {
                out.parentFile.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IllegalArgumentException(tr("Arşiv açılınca çok büyük (en çok {0} MB)", maxBytes / (1024 * 1024)))
                        o.write(buffer, 0, n)
                    }
                }
            }
        }
    }
}

/**
 * Zip'te sembolik bağlantı olarak saklanmış girdiler (örn. `libfoo.so.1 -> libfoo.so.1.2`) java.util.zip ile açılınca
 * içinde hedef adı yazan küçük düz dosyalara dönüşür; dinamik bağlayıcı bunları "ELF değil" diye reddeder. Bu
 * kalıntılar gerçek bağlantıya çevrilir. Hedef klasörün içinde var olmalı; ELF dosyalarına dokunulmaz.
 * Onarılan dosya sayısını döndürür.
 */
internal fun repairZipSymlinks(dir: File): Int {
    val root = dir.canonicalFile
    if (!root.isDirectory) return 0
    var count = 0
    root.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() in 1..255 }.forEach { f ->
        val bytes = f.readBytes()
        if (bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte()) return@forEach
        val text = bytes.decodeToString()
        if (!LINK_TEXT.matches(text)) return@forEach
        val target = File(f.parentFile, text)
        val resolved = runCatching { target.canonicalFile }.getOrNull() ?: return@forEach
        if (!resolved.exists() || resolved == f.canonicalFile || !resolved.path.startsWith(root.path + File.separator)) return@forEach
        Files.delete(f.toPath())
        Files.createSymbolicLink(f.toPath(), Paths.get(text))
        count++
    }
    return count
}
