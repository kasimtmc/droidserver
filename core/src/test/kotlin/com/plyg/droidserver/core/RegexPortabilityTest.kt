package com.plyg.droidserver.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android'in regex motoru (ICU) JVM'inkinden katıdır: kaçışsız `}` ve niceleyici olmayan kaçışsız `{` derleme
 * hatasıdır (`PatternSyntaxException`), JVM ise kabul eder. Böyle bir desen JVM testlerinden geçer ama uygulama
 * açılırken çöker (ComponentManager'daki `${env:AD}` deseninde oldu). Bu test kaynaklardaki `Regex("...")`
 * desenlerini ICU kuralına göre tarar.
 */
class RegexPortabilityTest {
    private val literal = Regex("""Regex\("((?:[^"\\]|\\.)*)"""")
    private val quantifier = Regex("""^\{\d+(,\d*)?\}""")

    @Test
    fun `kaynaklardaki regex desenleri Android ICU ile uyumlu`() {
        val files = listOf(File("src/main"), File("../app/src/main"))
            .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.size > 10, "kaynaklar bulunamadı (çalışma klasörü: ${File(".").absolutePath})")

        var count = 0
        val problems = mutableListOf<String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                for (m in literal.findAll(line)) {
                    count++
                    val pattern = unescapeKotlin(m.groupValues[1])
                    problem(pattern)?.let { problems += "${f.name}:${i + 1}: $it: $pattern" }
                }
            }
        }
        assertTrue(count >= 10, "yalnızca $count desen bulundu")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `denetleyici ICU hatalarini yakalar`() {
        assertEquals("kaçışsız '}'", problem("\\$\\{env:([A-Z]*)}"))
        assertEquals("kaçışsız '{'", problem("\\\${env:x\\}"))
        assertNull(problem("\\$\\{env:([A-Z]*)\\}"))
        assertNull(problem("^[a-z][a-z0-9_]{0,62}$"))
        assertNull(problem("T(\\d{2})-(\\d{2,})"))
        assertNull(problem("[{}]"))
    }

    /** Kotlin metin kaçışlarını çözer (`\\` → `\`, `\$` → `$`, `\"` → `"`). */
    private fun unescapeKotlin(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\', '$', '"', '\'' -> append(n)
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append('\\').append(n)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** ICU kuralı: `{` yalnızca `{n}`, `{n,}`, `{n,m}` niceleyicisini açar, `}` yalnızca onu kapatır; `[...]` içinde serbest. */
    private fun problem(p: String): String? {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++ // kaçışlı karakteri atla
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val q = quantifier.find(p.substring(i)) ?: return "kaçışsız '{'"
                    i += q.value.length - 1
                }
                c == '}' -> return "kaçışsız '}'"
            }
            i++
        }
        return null
    }
}
