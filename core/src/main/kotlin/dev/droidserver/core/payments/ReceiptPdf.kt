package dev.droidserver.core.payments

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Tek sayfalık basit makbuz PDF'i (A4), kütüphanesiz. PDF'in yerleşik Helvetica yazı tipi kullanılır (gömülmez);
 * Türkçe harfler ISO-8859-9 düzeninde kodlanır ve `/Differences` ile glif adlarına eşlenir (Ğ İ Ş ğ ı ş), Euro işareti
 * WinAnsi'deki yerinde. Sağa yaslama için Helvetica'nın karakter genişlikleri.
 */
object ReceiptPdf {
    /** Başlık, satıcı, (etiket, değer) satırları, toplam satırı ve alt not. */
    fun render(title: String, merchant: String, rows: List<Pair<String, String>>, total: Pair<String, String>, footer: String): ByteArray {
        val c = StringBuilder()
        fun text(x: Double, y: Double, size: Int, bold: Boolean, s: String) {
            c.append("BT /").append(if (bold) "F2" else "F1").append(' ').append(size).append(" Tf ")
                .append(fmt(x)).append(' ').append(fmt(y)).append(" Td (").append(escape(s)).append(") Tj ET\n")
        }
        fun right(xEnd: Double, y: Double, size: Int, bold: Boolean, s: String) = text(xEnd - width(s, size, bold), y, size, bold, s)
        val left = 64.0
        val end = PAGE_W - 64.0
        var y = PAGE_H - 88.0
        text(left, y, 22, true, title)
        y -= 26
        merchant.lines().filter { it.isNotBlank() }.forEach { text(left, y, 11, false, it.take(90)); y -= 15 }
        y -= 14
        c.append("0.85 G 0.8 w ").append(fmt(left)).append(' ').append(fmt(y)).append(" m ").append(fmt(end)).append(' ').append(fmt(y)).append(" l S\n")
        y -= 28
        for ((label, value) in rows) {
            text(left, y, 11, false, label)
            wrap(value, 11, end - 200.0).forEach { line -> right(end, y, 11, false, line); y -= 15 }
            y -= 7
        }
        y -= 6
        c.append("0 G 1.2 w ").append(fmt(left)).append(' ').append(fmt(y)).append(" m ").append(fmt(end)).append(' ').append(fmt(y)).append(" l S\n")
        y -= 26
        text(left, y, 13, true, total.first)
        right(end, y, 13, true, total.second)
        wrap(footer, 9, end - left).forEachIndexed { i, line -> text(left, 72.0 - i * 12, 9, false, line) }

        val content = c.toString().toByteArray(Charsets.ISO_8859_1)
        val fonts = { base: String ->
            "<< /Type /Font /Subtype /Type1 /BaseFont /$base /Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding " +
                "/Differences [208 /Gbreve 221 /Idotaccent 222 /Scedilla 240 /gbreve 253 /dotlessi 254 /scedilla] >> >>"
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
            ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(PAGE_W)} ${fmt(PAGE_H)}] " +
                "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 4 0 R >>").toByteArray(),
            "<< /Length ${content.size} >>\nstream\n".toByteArray() + content + "endstream".toByteArray(),
            fonts("Helvetica").toByteArray(),
            fonts("Helvetica-Bold").toByteArray(),
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = objects.mapIndexed { i, body ->
            val at = out.size()
            out.write("${i + 1} 0 obj\n".toByteArray()); out.write(body); out.write("\nendobj\n".toByteArray())
            at
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { out.write("%010d 00000 n \n".format(it).toByteArray()) }
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    private const val PAGE_W = 595.0
    private const val PAGE_H = 842.0
    private val LATIN5: Charset = Charset.forName("ISO-8859-9")

    /** Yazı tipi kodlamasındaki baytlar; kodlanamayan karakter `?`. */
    private fun bytes(s: String): ByteArray = ByteArray(s.length) { i ->
        val ch = s[i]
        when {
            ch == '€' -> 0x80.toByte()
            LATIN5.newEncoder().canEncode(ch) -> ch.toString().toByteArray(LATIN5)[0]
            else -> '?'.code.toByte()
        }
    }

    private fun escape(s: String): String = buildString {
        for (b in bytes(s)) {
            val v = b.toInt() and 0xff
            when {
                v == '('.code || v == ')'.code || v == '\\'.code -> append('\\').append(v.toChar())
                v < 32 || v > 126 -> append('\\').append(Integer.toOctalString(v).padStart(3, '0'))
                else -> append(v.toChar())
            }
        }
    }

    /** Helvetica genişlikleri (1/1000 em), ASCII 32–126; diğerleri (aksanlı harfler) yaklaşık 556. */
    private val WIDTHS = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    internal fun width(s: String, size: Int, bold: Boolean): Double =
        s.sumOf { ch -> if (ch.code in 32..126) WIDTHS[ch.code - 32] else 556 } * size / 1000.0 * (if (bold) 1.06 else 1.0)

    /** Kelimelerden satırlara (en çok [max] punto genişliğinde). */
    private fun wrap(s: String, size: Int, max: Double): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in s.split(' ').filter { it.isNotEmpty() }) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && width(next, size, false) > max) { lines += line; line = word } else line = next
        }
        if (line.isNotEmpty()) lines += line
        return lines.ifEmpty { listOf("") }
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)
}
