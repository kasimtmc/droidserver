package dev.droidserver.app.ui.help

import org.junit.Test
import kotlin.test.assertEquals

/** Yardımdaki küçük Markdown ayrıştırıcısı. */
class MarkdownTest {
    @Test
    fun bloklar() {
        val blocks = parseBlocks(
            """
            |İlk satır
            |devamı.
            |
            |- madde **bir**
            |  devam satırı
            |  - iç madde
            |1. sıralı
            |> not
            |```bash
            |curl -X POST
            |# yorum
            |```
            || a | b |
            ||---|---|
            || `1` | 2 |
            |#### Alt başlık
            """.trimMargin(),
        )
        assertEquals(
            listOf(
                Block.Paragraph("İlk satır devamı."),
                Block.Item("madde **bir** devam satırı", "•", 0),
                Block.Item("iç madde", "•", 1),
                Block.Item("sıralı", "1.", 0),
                Block.Quote("not"),
                Block.Code("curl -X POST\n# yorum"),
                Block.Table(listOf(listOf("a", "b"), listOf("1", "2"))),
                Block.Heading("Alt başlık"),
            ),
            blocks,
        )
    }
}
