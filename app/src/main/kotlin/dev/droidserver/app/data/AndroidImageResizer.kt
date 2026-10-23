package dev.droidserver.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.scale
import dev.droidserver.core.files.ImageResizer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Küçük resim: görsel önce gereken boyuta yakın örnekleme oranıyla okunur (büyük fotoğraf belleğe tam açılmaz),
 * sonra ölçeklenir; iki boyut da verilmişse ortadan kırpılır. Çıktı biçimi hedefin uzantısından (gif → png).
 */
class AndroidImageResizer : ImageResizer {
    override fun resize(source: File, target: File, width: Int, height: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return false

        // Hedef boyut: 0 olan kenar en-boy oranından.
        val (w, h) = when {
            width == 0 -> (srcW * height / srcH.toDouble()).roundToInt().coerceAtLeast(1) to height
            height == 0 -> width to (srcH * width / srcW.toDouble()).roundToInt().coerceAtLeast(1)
            else -> width to height
        }
        // En büyük 2'nin kuvveti: örneklenmiş görsel hedeften küçük olmasın.
        var sample = 1
        while (srcW / (sample * 2) >= w && srcH / (sample * 2) >= h) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        try {
            val s = max(w / decoded.width.toDouble(), h / decoded.height.toDouble())
            val scaledW = (decoded.width * s).roundToInt().coerceAtLeast(w)
            val scaledH = (decoded.height * s).roundToInt().coerceAtLeast(h)
            val scaled = decoded.scale(scaledW, scaledH)
            val cropped = Bitmap.createBitmap(scaled, (scaledW - w) / 2, (scaledH - h) / 2, w, h)
            val format = when (target.name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                @Suppress("DEPRECATION") "webp" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            target.outputStream().use { cropped.compress(format, 85, it) }
            if (cropped !== scaled) cropped.recycle()
            if (scaled !== decoded) scaled.recycle()
            return true
        } finally {
            decoded.recycle()
        }
    }

    /**
     * Yükleme dönüşümü: uzun kenar [maxDimension]'ı aşıyorsa küçültülür, EXIF yönü piksellere uygulanır (yeniden
     * kodlanan dosyada EXIF yoktur), [ext] biçiminde %85 kaliteyle yazılır. Biçim aynı ve küçültme gerekmiyorsa false.
     */
    override fun convert(source: File, target: File, ext: String, maxDimension: Int?): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return false
        val long = max(srcW, srcH)
        val limit = maxDimension?.takeIf { it < long }
        val sameFormat = when (ext) {
            "jpg", "jpeg" -> bounds.outMimeType == "image/jpeg"
            "webp" -> bounds.outMimeType == "image/webp"
            else -> bounds.outMimeType == "image/png"
        }
        if (limit == null && sameFormat) return false
        var sample = 1
        if (limit != null) while (long / (sample * 2) >= limit) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val bitmaps = mutableListOf(decoded)
        try {
            var b = decoded
            if (limit != null && max(b.width, b.height) > limit) {
                val s = limit / max(b.width, b.height).toDouble()
                b = b.scale((b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1)).also { bitmaps += it }
            }
            orientation(source)?.let { m -> b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { bitmaps += it } }
            val format = when (ext) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            return target.outputStream().use { b.compress(format, 85, it) }
        } finally {
            bitmaps.distinct().forEach { it.recycle() }
        }
    }

    /** EXIF yönünün matrisi (androidx ExifInterface'teki eşlemeyle aynı); normalse null. */
    private fun orientation(source: File): Matrix? {
        val o = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Matrix().apply {
            when (o) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> return null
            }
        }
    }
}
