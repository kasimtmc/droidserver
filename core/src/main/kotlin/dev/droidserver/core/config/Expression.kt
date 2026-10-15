package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Hesaplanmış alanların ifade dili (küçük ve güvenli: döngü, atama, dış erişim yok).
 *
 * - Değerler: sayı (`3`, `2.5`), metin (`"a"` veya `'a'`), `true`, `false`, `null`, alan adları.
 * - İşleçler (öncelik sırasıyla): `??` (boşsa diğeri), `||`, `&&`, `==` `!=`, `<` `<=` `>` `>=`, `+` `-`, `*` `/` `%`,
 *   tekli `-` `!`. `+` metinlerde birleştirir (boş değer `""` sayılır); sayılarda boş değer sonucu boş yapar.
 *   Sıfıra bölme boş verir.
 * - İşlevler: `round(x[, basamak])`, `floor`, `ceil`, `abs`, `min(...)`, `max(...)`, `upper`, `lower`, `trim`,
 *   `len`, `concat(...)`, `coalesce(...)`, `if(koşul, evet, hayır)`.
 */
class Expression private constructor(private val root: Node, val references: Set<String>) {

    /** Değerlendirir; [values] alan adından ortak biçimdeki değere (Long, Double, Boolean, String; boşsa yok/null). */
    fun evaluate(values: Map<String, Any?>): Any? = eval(root, values)

    private sealed interface Node
    private data class Lit(val v: Any?) : Node
    private data class Ref(val name: String) : Node
    private data class Unary(val op: String, val e: Node) : Node
    private data class Binary(val op: String, val l: Node, val r: Node) : Node
    private data class Call(val fn: String, val args: List<Node>) : Node

    private fun eval(n: Node, v: Map<String, Any?>): Any? = when (n) {
        is Lit -> n.v
        is Ref -> v[n.name]
        is Unary -> {
            val x = eval(n.e, v)
            if (n.op == "!") !truthy(x) else num(x)?.let { -it }
        }
        is Binary -> binary(n, v)
        is Call -> call(n.fn, n.args, v)
    }

    private fun binary(n: Binary, v: Map<String, Any?>): Any? {
        when (n.op) {
            "??" -> return eval(n.l, v) ?: eval(n.r, v)
            "||" -> return truthy(eval(n.l, v)) || truthy(eval(n.r, v))
            "&&" -> return truthy(eval(n.l, v)) && truthy(eval(n.r, v))
        }
        val a = eval(n.l, v)
        val b = eval(n.r, v)
        return when (n.op) {
            "+" -> if (a is String || b is String) text(a) + text(b) else arith(a, b) { x, y -> x + y }
            "-" -> arith(a, b) { x, y -> x - y }
            "*" -> arith(a, b) { x, y -> x * y }
            "/" -> arith(a, b) { x, y -> if (y == 0.0) null else x / y }
            "%" -> arith(a, b) { x, y -> if (y == 0.0) null else x % y }
            "==" -> same(a, b)
            "!=" -> !same(a, b)
            else -> compare(a, b)?.let { c ->
                when (n.op) {
                    "<" -> c < 0
                    "<=" -> c <= 0
                    ">" -> c > 0
                    else -> c >= 0
                }
            }
        }
    }

    private fun call(fn: String, args: List<Node>, v: Map<String, Any?>): Any? {
        if (fn == "if") return if (truthy(eval(args[0], v))) eval(args[1], v) else eval(args[2], v)
        val xs = args.map { eval(it, v) }
        return when (fn) {
            "round" -> num(xs[0])?.let { x ->
                val digits = xs.getOrNull(1)?.let(::num)?.toInt() ?: 0
                BigDecimal.valueOf(x).setScale(digits, RoundingMode.HALF_UP).toDouble()
            }
            "floor" -> num(xs[0])?.let(Math::floor)
            "ceil" -> num(xs[0])?.let(Math::ceil)
            "abs" -> num(xs[0])?.let(Math::abs)
            "min" -> xs.mapNotNull(::num).minOrNull()
            "max" -> xs.mapNotNull(::num).maxOrNull()
            "upper" -> xs[0]?.let { text(it).uppercase(java.util.Locale.forLanguageTag("tr")) }
            "lower" -> xs[0]?.let { text(it).lowercase(java.util.Locale.forLanguageTag("tr")) }
            "trim" -> xs[0]?.let { text(it).trim() }
            "len" -> xs[0]?.let { text(it).length.toDouble() }
            "concat" -> xs.joinToString("") { text(it) }
            "coalesce" -> xs.firstOrNull { it != null }
            else -> null
        }
    }

    companion object {
        /** İşlev adı → (en az, en çok) argüman sayısı. */
        private val FUNCTIONS = mapOf(
            "round" to (1 to 2), "floor" to (1 to 1), "ceil" to (1 to 1), "abs" to (1 to 1),
            "min" to (1 to 20), "max" to (1 to 20), "upper" to (1 to 1), "lower" to (1 to 1), "trim" to (1 to 1),
            "len" to (1 to 1), "concat" to (1 to 20), "coalesce" to (1 to 20), "if" to (3 to 3),
        )
        private const val MAX_LENGTH = 500

        /** Kullanılabilir işlevler (sihirbaz yardımı). */
        val FUNCTION_NAMES: List<String> get() = FUNCTIONS.keys.toList()

        /** Ayrıştırır; hatalıysa açıklamalı [IllegalArgumentException]. */
        fun parse(source: String): Expression {
            require(source.isNotBlank()) { tr("ifade boş") }
            require(source.length <= MAX_LENGTH) { tr("ifade en çok {0} karakter olabilir", MAX_LENGTH) }
            val p = Parser(tokenize(source))
            val root = p.expr()
            require(p.done()) { tr("beklenmeyen: '{0}'", p.peek()?.text) }
            return Expression(root, p.refs)
        }

        fun truthy(x: Any?): Boolean = when (x) {
            null -> false
            is Boolean -> x
            is Number -> x.toDouble() != 0.0
            is String -> x.isNotEmpty()
            else -> true
        }

        fun num(x: Any?): Double? = when (x) {
            is Number -> x.toDouble()
            is Boolean -> if (x) 1.0 else 0.0
            is String -> x.trim().toDoubleOrNull()
            else -> null
        }

        /** Metne çevirir; tam sayı değerli ondalıklar `.0` olmadan. */
        fun text(x: Any?): String = when (x) {
            null -> ""
            is Double -> if (x % 1.0 == 0.0 && kotlin.math.abs(x) < 1e15) x.toLong().toString() else BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()
            else -> x.toString()
        }

        private fun arith(a: Any?, b: Any?, f: (Double, Double) -> Double?): Double? {
            val x = num(a) ?: return null
            val y = num(b) ?: return null
            return f(x, y)?.takeIf { it.isFinite() }
        }

        private fun same(a: Any?, b: Any?): Boolean {
            if (a == null || b == null) return a == b
            if ((a is Number || a is Boolean) && (b is Number || b is Boolean)) return num(a) == num(b)
            return text(a) == text(b)
        }

        private fun compare(a: Any?, b: Any?): Int? {
            if (a == null || b == null) return null
            if (a is String && b is String) return a.compareTo(b)
            val x = num(a) ?: return null
            val y = num(b) ?: return null
            return x.compareTo(y)
        }

        private data class Token(val kind: Char, val text: String, val value: Any? = null)

        // Tür: n sayı, s metin, i ad, o işleç/noktalama.
        private fun tokenize(s: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            val ops = listOf("??", "||", "&&", "==", "!=", "<=", ">=", "<", ">", "+", "-", "*", "/", "%", "!", "(", ")", ",")
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                        val t = s.substring(start, i)
                        out += Token('n', t, t.toDoubleOrNull() ?: throw IllegalArgumentException(tr("geçersiz sayı: {0}", t)))
                    }
                    c == '"' || c == '\'' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < s.length && s[i] != c) {
                            if (s[i] == '\\' && i + 1 < s.length) i++
                            sb.append(s[i]); i++
                        }
                        require(i < s.length) { tr("kapanmamış metin") }
                        i++
                        out += Token('s', sb.toString(), sb.toString())
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                        out += Token('i', s.substring(start, i))
                    }
                    else -> {
                        val op = ops.firstOrNull { s.startsWith(it, i) } ?: throw IllegalArgumentException(tr("beklenmeyen karakter: '{0}'", c))
                        out += Token('o', op)
                        i += op.length
                    }
                }
            }
            return out
        }

        private class Parser(private val tokens: List<Token>) {
            private var pos = 0
            val refs = mutableSetOf<String>()
            private var depth = 0

            fun peek(): Token? = tokens.getOrNull(pos)
            fun done() = pos >= tokens.size
            private fun accept(op: String): Boolean = (peek()?.let { it.kind == 'o' && it.text == op } == true).also { if (it) pos++ }
            private fun expect(op: String) = require(accept(op)) { tr("'{0}' bekleniyor", op) }

            fun expr(): Node {
                require(++depth <= 50) { tr("ifade çok derin") }
                return binaryLevel(0).also { depth-- }
            }

            private val levels = listOf(listOf("??"), listOf("||"), listOf("&&"), listOf("==", "!="), listOf("<", "<=", ">", ">="), listOf("+", "-"), listOf("*", "/", "%"))

            private fun binaryLevel(level: Int): Node {
                if (level == levels.size) return unary()
                var left = binaryLevel(level + 1)
                while (true) {
                    val op = levels[level].firstOrNull { accept(it) } ?: return left
                    left = Binary(op, left, binaryLevel(level + 1))
                }
            }

            private fun unary(): Node = when {
                accept("-") -> Unary("-", unary())
                accept("!") -> Unary("!", unary())
                else -> primary()
            }

            private fun primary(): Node {
                val t = peek() ?: throw IllegalArgumentException(tr("ifade eksik"))
                pos++
                return when (t.kind) {
                    'n', 's' -> Lit(t.value)
                    'i' -> when (t.text) {
                        "true" -> Lit(true)
                        "false" -> Lit(false)
                        "null" -> Lit(null)
                        else -> if (accept("(")) {
                            val (lo, hi) = FUNCTIONS[t.text] ?: throw IllegalArgumentException(tr("bilinmeyen işlev: {0} ({1})", t.text, FUNCTIONS.keys.joinToString()))
                            val args = mutableListOf<Node>()
                            if (!accept(")")) {
                                do args += expr() while (accept(","))
                                expect(")")
                            }
                            require(args.size in lo..hi) { tr("{0}: {1}-{2} argüman alır", t.text, lo, hi) }
                            Call(t.text, args)
                        } else {
                            refs += t.text
                            Ref(t.text)
                        }
                    }
                    else -> if (t.text == "(") expr().also { expect(")") } else throw IllegalArgumentException(tr("beklenmeyen: '{0}'", t.text))
                }
            }
        }
    }
}
