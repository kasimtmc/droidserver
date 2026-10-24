package com.plyg.droidserver.core.discovery

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant

/**
 * QR ile paylaşılan bağlantı bilgisi: `droidserver://connect?url=<API adresi>&name=<ad>[&token=<token>][&exp=<unix sn>]`.
 * [token] API anahtarı veya JWT olabilir (JWT'de [expiresAt] kısa ömürlü davetin bitişi). İstemciler (SDK `fromLink`)
 * düz `http(s)://` adresini de kabul eder.
 */
data class ConnectLink(val url: String, val name: String? = null, val token: String? = null, val expiresAt: Instant? = null) {
    fun toUri(): String = buildString {
        append("$SCHEME://connect?url=").append(enc(url))
        name?.let { append("&name=").append(enc(it)) }
        token?.let { append("&token=").append(enc(it)) }
        expiresAt?.let { append("&exp=").append(it.epochSecond) }
    }

    companion object {
        const val SCHEME = "droidserver"

        /** `droidserver://connect?...` veya düz `http(s)://` adresi; tanınmazsa null. */
        fun parse(text: String): ConnectLink? {
            val t = text.trim()
            if (t.startsWith("http://") || t.startsWith("https://")) return runCatching { URI(t) }.getOrNull()?.let { ConnectLink(t.trimEnd('/')) }
            if (!t.startsWith("$SCHEME://connect?")) return null
            val params = t.substringAfter('?').split('&').filter { '=' in it }
                .associate { dec(it.substringBefore('=')) to dec(it.substringAfter('=')) }
            val url = params["url"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            return ConnectLink(url, params["name"], params["token"], params["exp"]?.toLongOrNull()?.let(Instant::ofEpochSecond))
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}

/** QR kodu (orta düzey hata düzeltme, kenar boşluksuz; boşluğu çizen ekler). */
object QrCode {
    /** Satır satır modüller (true: koyu). */
    fun matrix(text: String): List<BooleanArray> {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return (0 until bits.height).map { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
    }

    /** SVG (4 modül beyaz kenar boşluğuyla, ölçeklenebilir). */
    fun svg(text: String): String {
        val m = matrix(text)
        val size = m.size + 8
        val path = buildString {
            m.forEachIndexed { y, row -> row.forEachIndexed { x, dark -> if (dark) append("M${x + 4} ${y + 4}h1v1h-1z") } }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
            """<rect width="$size" height="$size" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }
}
