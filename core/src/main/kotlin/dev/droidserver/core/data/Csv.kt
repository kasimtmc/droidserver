package dev.droidserver.core.data

import dev.droidserver.core.i18n.tr

/**
 * RFC 4180 CSV: alanlar gerekirse çift tırnakla, tırnak içindeki tırnak ikilenerek yazılır; satır sonu CRLF.
 * Okurken ayırıcı (virgül veya Türkçe Excel'in noktalı virgülü) başlık satırından anlaşılır, UTF-8 BOM atlanır.
 */
object Csv {
    const val BOM = '﻿'

    fun row(values: List<String?>, delimiter: Char = ','): String = values.joinToString(delimiter.toString()) { v ->
        val s = v ?: ""
        if (s.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /**
     * Elektronik tabloda formül olarak çalışabilecek metinlerin (`=`, `+`, `-`, `@`, sekme ile başlayan) başına `'`
     * eklenir (CSV enjeksiyonu); [unguard] içe aktarırken geri alır.
     */
    fun guard(s: String): String = if (s.isNotEmpty() && s[0] in FORMULA) "'$s" else s

    fun unguard(s: String): String = if (s.length > 1 && s[0] == '\'' && s[1] in FORMULA) s.substring(1) else s

    private val FORMULA = setOf('=', '+', '-', '@', '\t', '\r')

    /** Başlık satırında tırnak dışındaki noktalı virgül virgülden çoksa `;`. */
    fun detectDelimiter(text: String): Char {
        var commas = 0
        var semis = 0
        var quoted = false
        for (c in text.removePrefix(BOM.toString())) {
            when {
                c == '"' -> quoted = !quoted
                quoted -> {}
                c == '\n' -> break
                c == ',' -> commas++
                c == ';' -> semis++
            }
        }
        return if (semis > commas) ';' else ','
    }

    /** Tüm satırlar; tamamen boş satırlar atlanır. Kapanmamış tırnak [IllegalArgumentException]. */
    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val s = text.removePrefix(BOM.toString())
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var i = 0
        var quoted = false
        var line = 1
        var quoteLine = 0
        fun endField() { row += field.toString(); field.setLength(0) }
        fun endRow() {
            endField()
            if (row.size > 1 || row[0].isNotEmpty()) rows += row
            row = mutableListOf()
        }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> { if (c == '\n') line++; field.append(c) }
                }
            } else {
                when (c) {
                    '"' -> if (field.isEmpty()) { quoted = true; quoteLine = line } else field.append(c)
                    delimiter -> endField()
                    '\r' -> {}
                    '\n' -> { endRow(); line++ }
                    else -> field.append(c)
                }
            }
            i++
        }
        require(!quoted) { tr("{0}. satırda açılan tırnak kapanmamış", quoteLine) }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
