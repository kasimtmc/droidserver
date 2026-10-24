package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr

/**
 * `.env` biçimi: `AD=değer` satırları. Desteklenenler: boş satır ve `#` yorumları, başta `export `, tırnaksız değer
 * (sonundaki ` # yorum` atılır), tek tırnak (olduğu gibi, birden çok satır olabilir) ve çift tırnak (`\n`, `\t`, `\"`,
 * `\\` kaçışları; birden çok satır olabilir). Değişken genişletme (`${AD}`) yapılmaz: değer yazıldığı gibi alınır.
 * Aynı ad iki kez yazılırsa sonuncusu geçerlidir ve uyarı verilir.
 */
object EnvFile {
    val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    const val MAX_VARS = 200
    const val MAX_VALUE = 32 * 1024
    const val SECRET_KIND = "env"

    data class Entry(val name: String, val value: String)

    /** [line] 1'den başlar; [warning] kaydetmeyi engellemez. */
    data class Problem(val line: Int, val message: String, val warning: Boolean = false) {
        override fun toString() = tr("Satır {0}: {1}", line, message)
    }

    data class Parsed(val entries: List<Entry>, val problems: List<Problem>) {
        fun toMap(): Map<String, String> = entries.associate { it.name to it.value }

        /** Kaydetmeyi engelleyen sorunlar; boşsa kaydedilebilir. */
        val errors: List<Problem> get() = problems.filterNot { it.warning }
    }

    fun parse(text: String): Parsed {
        val src = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val entries = LinkedHashMap<String, Entry>()
        val problems = mutableListOf<Problem>()
        var i = 0
        var line = 1
        fun advanceLine() { while (i < src.length && src[i] != '\n') i++; if (i < src.length) { i++; line++ } }

        while (i < src.length) {
            // Satır başındaki boşluklar
            while (i < src.length && (src[i] == ' ' || src[i] == '\t')) i++
            if (i >= src.length) break
            if (src[i] == '\n') { i++; line++; continue }
            if (src[i] == '#') { advanceLine(); continue }
            val startLine = line
            val eq = src.indexOf('=', i).takeIf { it >= 0 && src.indexOf('\n', i).let { nl -> nl < 0 || it < nl } }
            if (eq == null) {
                problems += Problem(startLine, tr("'=' yok; AD=değer biçiminde olmalı"))
                advanceLine()
                continue
            }
            val name = src.substring(i, eq).trim().removePrefix("export ").trim()
            i = eq + 1
            while (i < src.length && (src[i] == ' ' || src[i] == '\t')) i++
            val value: String
            when (src.getOrNull(i)) {
                '"', '\'' -> {
                    val quote = src[i++]
                    val sb = StringBuilder()
                    var closed = false
                    while (i < src.length) {
                        val c = src[i]
                        if (c == quote) { closed = true; i++; break }
                        if (c == '\n') line++
                        if (quote == '"' && c == '\\' && i + 1 < src.length) {
                            when (val n = src[i + 1]) {
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                '"', '\\' -> sb.append(n)
                                else -> sb.append('\\').append(n)
                            }
                            i += 2
                            continue
                        }
                        sb.append(c)
                        i++
                    }
                    if (!closed) {
                        problems += Problem(startLine, tr("Tırnak kapanmamış"))
                        break
                    }
                    // Kapanan tırnaktan sonra yalnızca boşluk veya yorum olabilir.
                    val rest = src.substring(i, src.indexOf('\n', i).let { if (it < 0) src.length else it }).trim()
                    if (rest.isNotEmpty() && !rest.startsWith("#")) problems += Problem(line, tr("Tırnaktan sonra beklenmeyen metin: {0}", rest))
                    value = sb.toString()
                    advanceLine()
                }
                else -> {
                    val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    var raw = src.substring(i, end)
                    val comment = Regex("\\s#").find(raw)
                    if (comment != null) raw = raw.substring(0, comment.range.first)
                    value = raw.trim()
                    i = end
                    if (i < src.length) { i++; line++ }
                }
            }
            when {
                !NAME.matches(name) -> problems += Problem(startLine, tr("Ad geçersiz: '{0}' (harf, rakam ve _, harfle veya _ ile başlar, en çok 64)", name))
                value.length > MAX_VALUE -> problems += Problem(startLine, tr("{0} çok uzun (en çok {1} karakter)", name, MAX_VALUE))
                else -> {
                    if (entries.containsKey(name)) problems += Problem(startLine, tr("{0} iki kez yazılmış; sonuncusu geçerli", name), warning = true)
                    entries.remove(name)
                    entries[name] = Entry(name, value)
                }
            }
        }
        if (entries.size > MAX_VARS) problems += Problem(line, tr("En çok {0} değişken olabilir ({1} var)", MAX_VARS, entries.size))
        return Parsed(entries.values.toList(), problems)
    }


    /** Değerleri `.env` metnine yazar; başka araçlarla da aynı okunacak biçimde tırnaklanır. */
    fun format(values: Map<String, String>, comments: Map<String, String> = emptyMap()): String = buildString {
        for ((name, value) in values) {
            comments[name]?.let { c -> c.lines().forEach { appendLine("# $it") } }
            append(name).append('=').append(quote(value)).append('\n')
        }
    }

    private val PLAIN = Regex("^[A-Za-z0-9_./:@%+,=\\-]*$")

    internal fun quote(value: String): String = when {
        PLAIN.matches(value) -> value
        '\'' !in value && '\n' !in value && '\r' !in value -> "'$value'"
        else -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    }

    /**
     * Düzenlenen değerleri konfigürasyona yazılacak hale getirir: [secret] adlar şifreli depoya taşınır (`secret:<ad>`
     * referansı), diğerleri düz metin kalır.
     */
    fun toConfig(values: List<Entry>, secret: Set<String>, sink: SecretSink): Map<String, String> =
        values.associate { e ->
            e.name to if (e.name in secret && e.value.isNotEmpty()) ConfigSecrets.ref(sink.store(SECRET_KIND, e.value)) else e.value
        }

    /** Konfigürasyondaki değerler (referanslar çözülmüş), sır olanların adları ve çözülemeyenler. */
    data class Loaded(val values: List<Entry>, val secret: Set<String>, val missing: Set<String>)

    fun fromConfig(env: Map<String, String>, resolve: (String) -> String?): Loaded {
        val secret = mutableSetOf<String>()
        val missing = mutableSetOf<String>()
        val values = env.map { (name, v) ->
            if (ConfigSecrets.isRef(v)) {
                secret += name
                val plain = resolve(v.removePrefix(ConfigSecrets.PREFIX))
                if (plain == null) missing += name
                Entry(name, plain.orEmpty())
            } else Entry(name, v)
        }
        return Loaded(values, secret, missing)
    }

    /** Ad sır gibi görünüyor mu (yeni değişkenin varsayılanı için). */
    fun looksSecret(name: String): Boolean =
        SECRET_HINTS.any { it in name.uppercase() }

    private val SECRET_HINTS = listOf("SECRET", "TOKEN", "KEY", "PASS", "PWD", "PRIVATE", "CREDENTIAL", "AUTH", "DSN", "SALT", "COOKIE", "SESSION", "SIGN")
}
