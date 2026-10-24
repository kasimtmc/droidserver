package com.plyg.droidserver.app.ui.help

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** README bölümlerinin gösterimi için küçük Markdown: başlık, paragraf, liste, alıntı, kod bloğu, tablo; kalın, `kod`, bağlantı. */
internal sealed interface Block {
    data class Heading(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Item(val text: String, val marker: String, val depth: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
}

internal fun parseBlocks(markdown: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = markdown.lines()
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) code.appendLine(lines[i++])
                out += Block.Code(code.toString().trimEnd())
            }
            t.startsWith("|") -> {
                flush()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    // Hücrelerde biçim işaretleri kaldırılır; ayırıcı satır (|---|---|) atlanır.
                    val cells = lines[i].trim().trim('|').split('|').map { it.trim().replace("`", "").replace("**", "") }
                    if (cells.any { c -> c.isNotEmpty() && !c.all { it == '-' || it == ':' } }) rows += cells
                    i++
                }
                out += Block.Table(rows)
                continue
            }
            t.startsWith("#") -> { flush(); out += Block.Heading(t.trimStart('#').trim()) }
            t.startsWith(">") -> { flush(); out += Block.Quote(t.removePrefix(">").trim()) }
            LIST.matches(line) -> {
                flush()
                val m = LIST.matchEntire(line)!!
                val text = StringBuilder(m.groupValues[3])
                // Girintili devam satırları aynı maddeye eklenir.
                while (i + 1 < lines.size && lines[i + 1].startsWith("  ") && lines[i + 1].isNotBlank() && !LIST.matches(lines[i + 1])) text.append(' ').append(lines[++i].trim())
                out += Block.Item(text.toString(), if (m.groupValues[2].endsWith(".")) m.groupValues[2] else "•", m.groupValues[1].length / 2)
            }
            t.isEmpty() -> flush()
            else -> para.append(t).append(' ')
        }
        i++
    }
    flush()
    return out
}

private val LIST = Regex("^( *)([-*]|\\d+\\.) (.*)$")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)\\s]+)\\)")

/** Kalın, satır içi kod ve bağlantılar; yalnızca http(s) bağlantıları tıklanır. */
internal fun inline(text: String, code: Color, link: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(at, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code)) { append(m.groupValues[2]) }
            else -> {
                val url = m.groupValues[4]
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(m.groupValues[3]) }
                } else {
                    append(m.groupValues[3])
                }
            }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in parseBlocks(markdown)) {
            when (b) {
                is Block.Heading -> Text(inline(b.text, codeBg, link), style = MaterialTheme.typography.titleSmall)
                is Block.Paragraph -> Text(inline(b.text, codeBg, link), style = MaterialTheme.typography.bodyMedium)
                is Block.Item -> Row(Modifier.padding(start = (12 * b.depth).dp)) {
                    Text(b.marker + " ", style = MaterialTheme.typography.bodyMedium)
                    Text(inline(b.text, codeBg, link), style = MaterialTheme.typography.bodyMedium)
                }
                is Block.Quote -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(inline(b.text, codeBg, link), Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
                is Block.Code -> Surface(color = codeBg, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        b.text, Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                }
                is Block.Table -> Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Sütunlar en uzun hücreye göre hizalanır (eş aralıklı yazı).
                    val widths = (0 until (b.rows.maxOfOrNull { it.size } ?: 0)).map { c -> b.rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
                    b.rows.forEachIndexed { r, cells ->
                        Text(
                            cells.mapIndexed { c, v -> v.padEnd(widths[c]) }.joinToString("  "),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (r == 0) FontWeight.SemiBold else null,
                        )
                    }
                }
            }
        }
    }
}
