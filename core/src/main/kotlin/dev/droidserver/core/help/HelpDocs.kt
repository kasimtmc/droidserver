package dev.droidserver.core.help

import java.util.Locale

/**
 * Uygulama içi yardım: README'yi (`## ` ve `### ` başlıklarından) bölümlere ayırır. Kod bloklarındaki `#` satırları
 * başlık sayılmaz; ilk başlıktan önceki giriş "Genel bakış" olur. Geliştiriciye yönelik bölümler ([SKIP]) çıkarılır.
 */
object HelpDocs {
    data class Section(val title: String, val level: Int, val body: String, val parent: String?)

    fun sections(markdown: String, overview: String): List<Section> {
        val out = mutableListOf<Section>()
        var title = overview
        var level = 2
        var parent: String? = null
        var top: String? = null
        val body = StringBuilder()
        var fence = false
        fun flush() {
            val text = body.toString().trim()
            if (text.isNotEmpty() && SKIP.none { title.startsWith(it) } && (level == 2 || SKIP.none { parent?.startsWith(it) == true })) {
                out += Section(title, level, text, parent)
            }
            body.clear()
        }
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) fence = !fence
            val heading = if (fence) null else HEADING.matchEntire(line)
            if (heading == null) {
                // Girişteki ana başlık ve dil bağlantısı atlanır.
                if (!(title == overview && (line.startsWith("# ") || "](README" in line))) body.appendLine(line)
                continue
            }
            flush()
            level = heading.groupValues[1].length
            title = heading.groupValues[2].trim().replace("`", "")
            if (level == 2) {
                top = title
                parent = null
            } else {
                parent = top
            }
        }
        flush()
        return out
    }

    /** Başlığında veya metninde [query] geçen bölümler (büyük/küçük harf ve Türkçe İ/ı duyarsız); başlıkta geçenler önce. */
    fun search(sections: List<Section>, query: String): List<Section> {
        val q = fold(query.trim())
        if (q.isEmpty()) return sections
        val inTitle = sections.filter { q in fold(it.title) }
        return inTitle + sections.filter { it !in inTitle && q in fold(it.body) }
    }

    private fun fold(s: String) = s.replace('İ', 'i').replace('I', 'ı').lowercase(Locale.ROOT).replace('ı', 'i')

    private val HEADING = Regex("^(#{2,3}) (.+)$")

    /** Geliştirici bölümleri (derleme, sürüm yayınlama, proje yapısı): uygulamada gösterilmez. */
    private val SKIP = listOf(
        "Mimari", "Architecture", "Derleme", "Building", "Sürüm yayınlama", "Publishing a release", "Proje yapısı", "Project structure",
        "Yol haritası", "Roadmap",
    )
}
