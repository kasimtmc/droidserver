package com.plyg.droidserver.core.files

import com.plyg.droidserver.core.config.FieldConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Yüklenen görselin hazırlanması (kayda bağlanmadan önce): istenirse biçim dönüşümü ve küçültme (telefonda,
 * [ImageResizer.convert]; yeniden kodlanan görselde meta veri kalmaz), değilse konum bilgisinin silinmesi ([ExifGps]).
 */
object Media {
    private val CONVERTIBLE = setOf("image/jpeg", "image/png", "image/webp")

    fun process(store: FileStore, f: FieldConfig, u: Upload, images: ImageResizer?): Upload {
        if (u.kind.mime !in CONVERTIBLE) return u
        val format = f.convert?.takeIf { it.mime != u.kind.mime }
        if ((format != null || f.maxDimension != null) && images != null) {
            val out = store.newTemp()
            val ext = format?.ext ?: u.kind.ext
            val converted = runCatching { images.convert(u.temp, out, ext, f.maxDimension) }.getOrDefault(false)
            if (converted && out.length() > 0) {
                u.temp.delete()
                val kind = MimeTypes.Kind(format?.mime ?: u.kind.mime, ext)
                return Upload(u.field, out, kind, u.originalName, out.length(), store.newName(u.originalName, ext))
            }
            out.delete()
        }
        if (!f.keepLocation) runCatching { ExifGps.strip(u.temp, u.kind.mime) }
        return u
    }
}

/**
 * Görseldeki konum bilgisini dosyanın boyutunu değiştirmeden siler: EXIF'teki GPS bölümü boşaltılır (yön, tarih gibi
 * diğer bilgiler kalır), konum içeren XMP paketi boşluklarla silinir. JPEG, PNG (`eXIf`, XMP `iTXt`) ve WebP (`EXIF`,
 * `XMP `). Bozuk yapıda dokunulmaz.
 */
object ExifGps {
    /** Bir şey silindiyse true. */
    fun strip(file: File, mime: String): Boolean = RandomAccessFile(file, "rw").use { raf ->
        when (mime) {
            "image/jpeg" -> jpeg(raf)
            "image/png" -> png(raf)
            "image/webp" -> webp(raf)
            else -> false
        }
    }

    private fun jpeg(raf: RandomAccessFile): Boolean {
        if (raf.length() < 4 || raf.readUnsignedShort() != 0xFFD8) return false
        var changed = false
        var pos = 2L
        while (pos + 4 <= raf.length()) {
            raf.seek(pos)
            if (raf.readUnsignedByte() != 0xFF) return changed
            val marker = raf.readUnsignedByte()
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { pos += 2; continue }
            if (marker == 0xDA || marker == 0xD9) return changed // görüntü verisi başladı
            val len = raf.readUnsignedShort()
            if (len < 2 || pos + 2 + len > raf.length()) return changed
            if (marker == 0xE1) {
                val data = ByteArray(len - 2)
                raf.readFully(data)
                if (edit(data)) {
                    raf.seek(pos + 4)
                    raf.write(data)
                    changed = true
                }
            }
            pos += 2 + len
        }
        return changed
    }

    /** APP1 / EXIF yükü: `Exif\0\0` + TIFF ya da XMP. */
    private fun edit(data: ByteArray): Boolean = when {
        startsWith(data, EXIF_HEADER) -> tiff(data, EXIF_HEADER.size)
        startsWith(data, TIFF_LE) || startsWith(data, TIFF_BE) -> tiff(data, 0)
        startsWith(data, XMP_HEADER) || startsWith(data, XMP_KEY) -> xmp(data)
        else -> false
    }

    private fun png(raf: RandomAccessFile): Boolean {
        val sig = ByteArray(8)
        if (raf.length() < 8) return false
        raf.readFully(sig)
        if (!sig.contentEquals(PNG_SIGNATURE)) return false
        var changed = false
        var pos = 8L
        while (pos + 12 <= raf.length()) {
            raf.seek(pos)
            val len = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }
            val name = String(type, Charsets.ISO_8859_1)
            if (pos + 12 + len > raf.length()) return changed
            if ((name == "eXIf" || name == "iTXt" || name == "tEXt") && len in 1..16_000_000) {
                val data = ByteArray(len.toInt())
                raf.readFully(data)
                val edited = if (name == "eXIf") tiff(data, 0) else xmp(data)
                if (edited) {
                    raf.seek(pos + 8)
                    raf.write(data)
                    val crc = CRC32().apply { update(type); update(data) }.value
                    raf.writeInt(crc.toInt())
                    changed = true
                }
            }
            if (name == "IEND") return changed
            pos += 12 + len
        }
        return changed
    }

    private fun webp(raf: RandomAccessFile): Boolean {
        val head = ByteArray(12)
        if (raf.length() < 12) return false
        raf.readFully(head)
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(head, 8, 4, Charsets.ISO_8859_1) != "WEBP") return false
        var changed = false
        var pos = 12L
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            val fourcc = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            val b = ByteArray(4).also { raf.readFully(it) }
            val len = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            if (pos + 8 + len > raf.length()) return changed
            if ((fourcc == "EXIF" || fourcc == "XMP ") && len in 1..16_000_000) {
                val data = ByteArray(len.toInt())
                raf.readFully(data)
                if (if (fourcc == "EXIF") edit(data) else xmp(data)) {
                    raf.seek(pos + 8)
                    raf.write(data)
                    changed = true
                }
            }
            pos += 8 + len + (len and 1)
        }
        return changed
    }

    /** XMP konum içeriyorsa paket boşluklarla değiştirilir (uzunluk aynı kalır). */
    private fun xmp(data: ByteArray): Boolean {
        val text = String(data, Charsets.ISO_8859_1)
        if (!text.contains("GPS", ignoreCase = false)) return false
        val start = text.indexOf("<x:xmpmeta").takeIf { it >= 0 } ?: text.indexOf("<?xpacket").takeIf { it >= 0 } ?: return false
        for (i in start until data.size) data[i] = ' '.code.toByte()
        return true
    }

    /** TIFF yapısında IFD0'daki GPS bölümünü (0x8825) boşaltır: değerler ve girdiler sıfırlanır, girdi sayısı 0 olur. */
    private fun tiff(d: ByteArray, base: Int): Boolean {
        if (d.size < base + 8) return false
        val le = d[base] == 'I'.code.toByte() && d[base + 1] == 'I'.code.toByte()
        if (!le && !(d[base] == 'M'.code.toByte() && d[base + 1] == 'M'.code.toByte())) return false
        fun u16(o: Int): Int = if (o < 0 || o + 2 > d.size) -1 else if (le) (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        else ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
        fun u32(o: Int): Long = if (o < 0 || o + 4 > d.size) -1 else if (le) (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
        else (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        if (u16(base + 2) != 42) return false
        val ifd0 = base + u32(base + 4).toInt()
        val n = u16(ifd0)
        if (n <= 0 || ifd0 + 2 + n * 12 > d.size) return false
        val gpsEntry = (0 until n).map { ifd0 + 2 + it * 12 }.firstOrNull { u16(it) == 0x8825 } ?: return false
        val gps = base + u32(gpsEntry + 8).toInt()
        val m = u16(gps)
        if (m < 0 || gps + 2 + m * 12 > d.size) return false
        for (i in 0 until m) {
            val e = gps + 2 + i * 12
            val size = (TYPE_SIZE.getOrElse(u16(e + 2)) { 1 }.toLong() * u32(e + 4))
            if (size > 4) {
                val at = base + u32(e + 8).toInt()
                if (at >= 0 && at + size <= d.size) d.fill(0, at, (at + size).toInt())
            }
        }
        d.fill(0, gps, gps + 2 + m * 12)
        return m > 0
    }

    private fun startsWith(d: ByteArray, p: ByteArray) = d.size >= p.size && (p.indices).all { d[it] == p[it] }

    /** TIFF tip kodlarının bayt genişliği (1: BYTE … 12: DOUBLE). */
    private val TYPE_SIZE = intArrayOf(1, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8).toList()
    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val TIFF_LE = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_KEY = "XML:com.adobe.xmp".toByteArray(Charsets.ISO_8859_1)
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
