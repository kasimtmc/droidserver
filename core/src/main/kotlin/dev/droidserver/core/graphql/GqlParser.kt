package dev.droidserver.core.graphql

import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** GraphQL belgesinin ayrıştırılmış hali (yalnızca sorgu dili; şema tanımı yok). */
class GqlDocument(val operations: List<GqlOperation>, val fragments: Map<String, GqlFragment>)

class GqlOperation(val type: String, val name: String?, val variables: List<GqlVariable>, val selection: List<GqlSelection>)

class GqlVariable(val name: String, val default: GqlValue?)

class GqlFragment(val name: String, val typeCondition: String, val selection: List<GqlSelection>)

sealed interface GqlSelection {
    val directives: List<GqlDirective>
}

class GqlField(
    val alias: String?,
    val name: String,
    val args: Map<String, GqlValue>,
    override val directives: List<GqlDirective>,
    /** Alt seçim yoksa null (skaler alan). */
    val selection: List<GqlSelection>?,
) : GqlSelection {
    val key: String get() = alias ?: name
}

class GqlFragmentSpread(val name: String, override val directives: List<GqlDirective>) : GqlSelection

class GqlInlineFragment(val typeCondition: String?, override val directives: List<GqlDirective>, val selection: List<GqlSelection>) : GqlSelection

class GqlDirective(val name: String, val args: Map<String, GqlValue>)

sealed interface GqlValue {
    data class Literal(val json: JsonElement) : GqlValue
    data class Variable(val name: String) : GqlValue
    data class ListValue(val items: List<GqlValue>) : GqlValue
    data class ObjectValue(val fields: Map<String, GqlValue>) : GqlValue

    /** Değişkenler yerine konmuş JSON değeri. */
    fun resolve(vars: Map<String, JsonElement>): JsonElement = when (this) {
        is Literal -> json
        is Variable -> vars[name] ?: JsonNull
        is ListValue -> JsonArray(items.map { it.resolve(vars) })
        is ObjectValue -> JsonObject(fields.mapValues { it.value.resolve(vars) })
    }
}

class GqlSyntaxException(message: String) : RuntimeException(message)

/**
 * GraphQL sorgu dilinin ayrıştırıcısı (Ekim 2021 belirtimi, sorgu ve mutasyonlar): seçimler, takma adlar, argümanlar,
 * değişkenler ve varsayılanları, adlı ve satır içi parçalar, yönergeler. Tür sistemi tanımları ve abonelikler yoktur.
 */
class GqlParser private constructor(private val src: String) {
    private var pos = 0
    private var depth = 0

    private fun fail(msg: String): Nothing {
        val line = src.substring(0, pos.coerceAtMost(src.length)).count { it == '\n' } + 1
        throw GqlSyntaxException(tr("Sözdizimi hatası (satır {0}): {1}", line, msg))
    }

    private fun skip() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '﻿' -> pos++
                c == '#' -> while (pos < src.length && src[pos] != '\n' && src[pos] != '\r') pos++
                else -> return
            }
        }
    }

    private fun peek(): Char? { skip(); return src.getOrNull(pos) }

    private fun eat(c: Char): Boolean {
        if (peek() == c) { pos++; return true }
        return false
    }

    private fun expect(c: Char) { if (!eat(c)) fail(tr("'{0}' bekleniyordu", c)) }

    private fun name(): String {
        skip()
        val start = pos
        if (pos < src.length && (src[pos] == '_' || src[pos].isAsciiLetter())) pos++ else fail(tr("ad bekleniyordu"))
        while (pos < src.length && (src[pos] == '_' || src[pos].isAsciiLetter() || src[pos] in '0'..'9')) pos++
        return src.substring(start, pos)
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun peekName(): String? {
        skip()
        val save = pos
        return if (pos < src.length && (src[pos] == '_' || src[pos].isAsciiLetter())) name().also { pos = save } else null
    }

    private fun document(): GqlDocument {
        val ops = mutableListOf<GqlOperation>()
        val fragments = mutableMapOf<String, GqlFragment>()
        while (peek() != null) {
            when {
                peek() == '{' -> ops += GqlOperation("query", null, emptyList(), selectionSet())
                peekName() == "fragment" -> {
                    name()
                    val n = name()
                    if (n == "on") fail(tr("parça adı 'on' olamaz"))
                    if (name() != "on") fail(tr("'on' bekleniyordu"))
                    val type = name()
                    directives()
                    if (fragments.put(n, GqlFragment(n, type, selectionSet())) != null) fail(tr("'{0}' parçası iki kez tanımlanmış", n))
                }
                peekName() in setOf("query", "mutation", "subscription") -> {
                    val type = name()
                    if (type == "subscription") fail(tr("abonelikler desteklenmiyor; canlı abonelik için /realtime kullanın"))
                    val n = if (peek() != '(' && peek() != '{' && peek() != '@') name() else null
                    val vars = if (eat('(')) buildList {
                        while (!eat(')')) {
                            expect('$')
                            val v = name()
                            expect(':')
                            type()
                            val default = if (eat('=')) value(const = true) else null
                            directives()
                            add(GqlVariable(v, default))
                        }
                    } else emptyList()
                    directives()
                    ops += GqlOperation(type, n, vars, selectionSet())
                }
                else -> fail(tr("işlem (query, mutation) veya parça bekleniyordu"))
            }
        }
        if (ops.isEmpty()) fail(tr("belgede işlem yok"))
        return GqlDocument(ops, fragments)
    }

    private fun type() {
        if (eat('[')) { type(); expect(']') } else name()
        eat('!')
    }

    private fun selectionSet(): List<GqlSelection> {
        expect('{')
        if (++depth > MAX_DEPTH) fail(tr("sorgu çok derin (en çok {0} düzey)", MAX_DEPTH))
        val out = mutableListOf<GqlSelection>()
        while (!eat('}')) {
            if (peek() == null) fail(tr("'}' bekleniyordu"))
            out += selection()
        }
        depth--
        if (out.isEmpty()) fail(tr("boş seçim"))
        return out
    }

    private fun selection(): GqlSelection {
        if (peek() == '.') {
            if (!src.startsWith("...", pos)) fail(tr("'...' bekleniyordu"))
            pos += 3
            val n = peekName()
            return if (n != null && n != "on") {
                name()
                GqlFragmentSpread(n, directives())
            } else {
                val type = if (n == "on") { name(); name() } else null
                GqlInlineFragment(type, directives(), selectionSet())
            }
        }
        val first = name()
        val (alias, n) = if (eat(':')) first to name() else null to first
        val args = arguments(const = false)
        val dirs = directives()
        val sel = if (peek() == '{') selectionSet() else null
        return GqlField(alias, n, args, dirs, sel)
    }

    private fun arguments(const: Boolean): Map<String, GqlValue> {
        if (!eat('(')) return emptyMap()
        val out = LinkedHashMap<String, GqlValue>()
        while (!eat(')')) {
            val n = name()
            expect(':')
            if (out.put(n, value(const)) != null) fail(tr("'{0}' argümanı iki kez verilmiş", n))
        }
        return out
    }

    private fun directives(): List<GqlDirective> = buildList {
        while (eat('@')) add(GqlDirective(name(), arguments(const = false)))
    }

    private fun value(const: Boolean): GqlValue {
        val c = peek() ?: fail(tr("değer bekleniyordu"))
        return when {
            c == '$' -> {
                if (const) fail(tr("burada değişken kullanılamaz"))
                pos++
                GqlValue.Variable(name())
            }
            c == '[' -> {
                pos++
                val items = mutableListOf<GqlValue>()
                while (!eat(']')) items += value(const)
                GqlValue.ListValue(items)
            }
            c == '{' -> {
                pos++
                val fields = LinkedHashMap<String, GqlValue>()
                while (!eat('}')) {
                    val n = name()
                    expect(':')
                    fields[n] = value(const)
                }
                GqlValue.ObjectValue(fields)
            }
            c == '"' -> GqlValue.Literal(JsonPrimitive(string()))
            c == '-' || c in '0'..'9' -> GqlValue.Literal(number())
            else -> when (val n = name()) {
                "true" -> GqlValue.Literal(JsonPrimitive(true))
                "false" -> GqlValue.Literal(JsonPrimitive(false))
                "null" -> GqlValue.Literal(JsonNull)
                else -> GqlValue.Literal(JsonPrimitive(n)) // sıralama (enum) değeri: metin olarak
            }
        }
    }

    private fun number(): JsonPrimitive {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        var float = false
        if (pos < src.length && src[pos] == '.') { float = true; pos++; while (pos < src.length && src[pos] in '0'..'9') pos++ }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            float = true; pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val text = src.substring(start, pos)
        return if (float) JsonPrimitive(text.toDoubleOrNull() ?: fail(tr("sayı geçersiz: {0}", text)))
        else JsonPrimitive(text.toLongOrNull() ?: fail(tr("sayı geçersiz: {0}", text)))
    }

    private fun string(): String {
        if (src.startsWith("\"\"\"", pos)) {
            pos += 3
            val end = src.indexOf("\"\"\"", pos).takeIf { it >= 0 } ?: fail(tr("kapanmamış metin"))
            return blockString(src.substring(pos, end).also { pos = end + 3 }.replace("\\\"\"\"", "\"\"\""))
        }
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail(tr("kapanmamış metin"))
            val c = src[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\n', '\r' -> fail(tr("kapanmamış metin"))
                '\\' -> {
                    val e = src.getOrNull(pos++) ?: fail(tr("kapanmamış metin"))
                    when (e) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(pos, (pos + 4).coerceAtMost(src.length))
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: fail(tr("geçersiz kaçış: \\u{0}", hex)))
                            pos += 4
                        }
                        else -> fail(tr("geçersiz kaçış: \\{0}", e))
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    /** Belirtimdeki blok metin kuralı: ilk satır dışındaki ortak girinti ve baştaki/sondaki boş satırlar atılır. */
    private fun blockString(raw: String): String {
        val lines = raw.split("\r\n", "\n", "\r")
        val indent = lines.drop(1).filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        val out = lines.mapIndexed { i, l -> if (i == 0) l else l.drop(indent) }.toMutableList()
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    companion object {
        const val MAX_DEPTH = 8
        const val MAX_LENGTH = 100_000

        fun parse(text: String): GqlDocument {
            if (text.length > MAX_LENGTH) throw GqlSyntaxException(tr("Sorgu çok uzun (en çok {0} karakter)", MAX_LENGTH))
            return GqlParser(text).document()
        }
    }
}
