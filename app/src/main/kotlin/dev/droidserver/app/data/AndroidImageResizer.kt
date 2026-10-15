package dev.droidserver.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
}
