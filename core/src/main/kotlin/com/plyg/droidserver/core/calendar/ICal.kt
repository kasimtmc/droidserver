package com.plyg.droidserver.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar (RFC 5545) ve vCard (RFC 6350 / 2426) metin biçimi: satır katlama, kaçış, bileşen ayrıştırma ve zamanlar.
 * Yalnızca eşlenen özellikler okunur; geri kalanı (alarm, katılımcı…) yok sayılır.
 */
object ICal {
    data class Prop(val name: String, val params: Map<String, String>, val value: String)

    data class Component(val name: String, val props: List<Prop>, val children: List<Component>) {
        fun first(name: String): Prop? = props.firstOrNull { it.name == name }
        fun child(name: String): Component? = children.firstOrNull { it.name == name }
    }

    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    /** Metindeki ilk bileşen (VCALENDAR, VCARD); yoksa [IllegalArgumentException]. */
    fun parse(text: String): Component {
        val lines = unfold(text)
        val stack = ArrayDeque<Triple<String, MutableList<Prop>, MutableList<Component>>>()
        var root: Component? = null
        for (line in lines) {
            val prop = property(line) ?: continue
            when (prop.name) {
                "BEGIN" -> stack.addLast(Triple(prop.value.uppercase(), mutableListOf(), mutableListOf()))
                "END" -> {
                    val (name, props, children) = stack.removeLastOrNull() ?: continue
                    val c = Component(name, props, children)
                    if (stack.isEmpty()) {
                        if (root == null) root = c
                    } else {
                        stack.last().third += c
                    }
                }
                else -> stack.lastOrNull()?.second?.add(prop)
            }
        }
        return root ?: throw IllegalArgumentException("BEGIN/END yok")
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out.last().append(raw, 1, raw.length)
            else if (raw.isNotEmpty()) out += StringBuilder(raw)
        }
        return out.map { it.toString() }
    }

    /** `AD;PARAM=değer;P2="a,b":değer`; tırnak içindeki `:` ve `;` ayırıcı sayılmaz. */
    private fun property(line: String): Prop? {
        var quoted = false
        var colon = -1
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') quoted = !quoted
            if (ch == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        quoted = false
        for (ch in head) {
            if (ch == '"') quoted = !quoted
            if (ch == ';' && !quoted) { parts += cur.toString(); cur.clear() } else cur.append(ch)
        }
        parts += cur.toString()
        // Gruplu vCard özelliği (item1.EMAIL) grupsuz okunur.
        val name = parts.first().substringAfterLast('.').uppercase()
        val params = parts.drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=', "").trim('"') }
        return Prop(name, params, line.substring(colon + 1))
    }

    /** TEXT değerinin kaçışı (`\\`, `\;`, `\,`, `\n`). */
    fun escape(s: String): String = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                out.append(when (val n = s[i + 1]) { 'n', 'N' -> '\n'; else -> n })
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /** Satırları CRLF ile birleştirir; 75 bayttan uzun satır katlanır (UTF-8 karakteri bölünmeden). */
    fun lines(lines: List<String>): String = buildString {
        for (line in lines) {
            var rest = line
            var first = true
            while (true) {
                val limit = if (first) 75 else 74
                var n = 0
                var bytes = 0
                while (n < rest.length) {
                    val cp = rest.codePointAt(n)
                    val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                    if (bytes + len > limit) break
                    bytes += len
                    n += Character.charCount(cp)
                }
                append(if (first) "" else " ").append(rest, 0, n).append("\r\n")
                if (n >= rest.length) break
                rest = rest.substring(n)
                first = false
            }
        }
    }

    fun utc(t: Instant): String = UTC.format(t)

    fun date(d: LocalDate): String = DATE.format(d)

    /**
     * DTSTART/DTEND değeri: `…Z` UTC, `TZID=` ile yerel saat, yalnız tarih (`VALUE=DATE` veya 8 hane) günün başı. Saat
     * dilimi bilinmiyorsa veya hiç yoksa (gezgin saat) [zone]. İkinci değer: tüm gün mü.
     */
    fun time(p: Prop, zone: ZoneId): Pair<Instant, Boolean>? = runCatching {
        val v = p.value.trim()
        val tz = p.params["TZID"]?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() } ?: zone
        when {
            p.params["VALUE"] == "DATE" || v.length == 8 -> LocalDate.parse(v, DATE).atStartOfDay(tz).toInstant() to true
            v.endsWith("Z") -> Instant.from(UTC.parse(v)) to false
            else -> LocalDateTime.parse(v, LOCAL).atZone(tz).toInstant() to false
        }
    }.getOrNull()

    /** `PT1H30M`, `P1D`, `P2W`, `-PT15M`. */
    fun duration(s: String): Duration? = runCatching {
        val t = s.trim()
        val weeks = Regex("^([+-]?)P(\\d+)W$").matchEntire(t)
        if (weeks != null) Duration.ofDays(weeks.groupValues[2].toLong() * 7).let { if (weeks.groupValues[1] == "-") it.negated() else it }
        else Duration.parse(t.removePrefix("+"))
    }.getOrNull()
}
