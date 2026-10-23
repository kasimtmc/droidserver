package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr
import java.net.URI

/**
 * Alan kısıtları: seçenekler, biçim, uzunluk, düzenli ifade, sayı aralığı ve hesaplanmış alan. Yazılan değer
 * [check] ile, konfigürasyon [problems] ile denetlenir; ikisi de sihirbaz, sunucu ve içe aktarma için ortaktır.
 */
object FieldRules {
    const val MAX_OPTIONS = 200

    /** Hesaplanmış olabilen tipler. */
    val COMPUTABLE = setOf(FieldType.TEXT, FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN)
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /** Alanın değer kısıtı var mı (sihirbaz özeti için). */
    fun hasConstraints(f: FieldConfig) = f.format != null || f.minLength != null || f.maxLength != null || f.pattern != null ||
        f.min != null || f.max != null

    /**
     * Ortak biçimdeki değeri (metin, sayı, çoklu seçimde metin listesi) alanın kısıtlarına göre denetler; uygunsa null,
     * değilse alan hatası olarak gösterilecek açıklama.
     */
    fun check(f: FieldConfig, value: Any?): String? {
        if (value == null) return null
        when (f.type) {
            FieldType.SELECT -> if (value.toString() !in f.options.orEmpty()) return tr("seçeneklerden biri olmalı: {0}", f.options.orEmpty().joinToString())
            FieldType.MULTISELECT -> {
                @Suppress("UNCHECKED_CAST")
                val list = value as? List<String> ?: return tr("seçenek listesi bekleniyor")
                list.firstOrNull { it !in f.options.orEmpty() }?.let { return tr("'{0}' seçeneklerden biri değil ({1})", it, f.options.orEmpty().joinToString()) }
                if (list.size != list.toSet().size) return tr("aynı seçenek iki kez seçilmiş")
                f.minLength?.let { if (list.size < it) return tr("en az {0} seçim", it) }
                f.maxLength?.let { if (list.size > it) return tr("en çok {0} seçim", it) }
            }
            FieldType.TEXT -> {
                val s = value.toString()
                f.minLength?.let { if (s.length < it) return tr("en az {0} karakter", it) }
                f.maxLength?.let { if (s.length > it) return tr("en çok {0} karakter", it) }
                when (f.format) {
                    TextFormat.EMAIL -> if (!EMAIL.matches(s)) return tr("geçerli bir e-posta adresi değil")
                    TextFormat.URL -> if (!isUrl(s)) return tr("http:// veya https:// ile başlayan geçerli bir adres değil")
                    null -> {}
                }
                f.pattern?.let { p -> if (runCatching { !Regex(p).matches(s) }.getOrDefault(true)) return tr("biçim uygun değil ({0})", p) }
            }
            FieldType.INTEGER, FieldType.REAL -> {
                val d = (value as? Number)?.toDouble() ?: return null
                f.min?.let { if (d < it) return tr("en az {0}", Expression.text(it)) }
                f.max?.let { if (d > it) return tr("en çok {0}", Expression.text(it)) }
            }
            else -> {}
        }
        return null
    }

    private fun isUrl(s: String): Boolean = runCatching {
        val u = URI(s)
        (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    /**
     * Tip değişince yeni tipe uymayan kısıtları atar (sihirbaz). Uzunluk metinde karakter, çoklu seçimde seçim sayısı
     * olduğundan yalnızca aynı tipte kalır.
     */
    fun fitTo(f: FieldConfig, type: FieldType): FieldConfig {
        val text = type == FieldType.TEXT
        val number = type == FieldType.INTEGER || type == FieldType.REAL
        val sameLength = type == f.type && (text || type == FieldType.MULTISELECT)
        return f.copy(
            options = f.options.takeIf { type == FieldType.SELECT || type == FieldType.MULTISELECT },
            format = f.format.takeIf { text },
            pattern = f.pattern.takeIf { text },
            minLength = f.minLength.takeIf { sameLength },
            maxLength = f.maxLength.takeIf { sameLength },
            min = f.min.takeIf { number },
            max = f.max.takeIf { number },
            computed = f.computed.takeIf { type in COMPUTABLE },
            encrypted = f.encrypted && (text || type == FieldType.JSON),
        )
    }

    /** Hesaplanmış alanın değeri her zaman hesaplanır: zorunlu ve varsayılan atılır. */
    fun withComputed(f: FieldConfig, computed: String?): FieldConfig =
        if (computed == null) f.copy(computed = null) else f.copy(computed = computed, required = false, default = null)

    /** Hesaplanmış alan değerini alan tipine çevirir (sayıda tam sayıya yuvarlar). */
    fun computedValue(f: FieldConfig, raw: Any?): Any? = when (f.type) {
        FieldType.TEXT -> raw?.let(Expression::text)
        FieldType.INTEGER -> Expression.num(raw)?.let { kotlin.math.round(it).toLong() }
        FieldType.REAL -> Expression.num(raw)
        FieldType.BOOLEAN -> raw?.let(Expression::truthy)
        else -> null
    }

    /** Konfigürasyon sorunları: (alt yol, ileti). [fields] koleksiyonun tüm alanları (hesaplanmış alan başvuruları için). */
    fun problems(f: FieldConfig, fields: List<FieldConfig>): List<Pair<String, String>> = buildList {
        val isText = f.type == FieldType.TEXT
        val isChoice = f.type == FieldType.SELECT || f.type == FieldType.MULTISELECT
        if (isChoice) {
            val options = f.options.orEmpty()
            when {
                options.isEmpty() -> add("options" to tr("en az bir seçenek girin"))
                options.size > MAX_OPTIONS -> add("options" to tr("en çok {0} seçenek", MAX_OPTIONS))
                options.any { it.isBlank() } -> add("options" to tr("boş seçenek olamaz"))
                options.size != options.toSet().size -> add("options" to tr("aynı seçenek iki kez yazılmış"))
                options.any { '"' in it || '\\' in it } -> add("options" to tr("seçeneklerde \" ve \\ kullanılamaz"))
            }
        } else if (f.options != null) {
            add("options" to tr("seçenekler yalnızca seçim alanlarında"))
        }
        if (f.format != null && !isText) add("format" to tr("biçim yalnızca metin alanlarında"))
        if (f.pattern != null) {
            if (!isText) add("pattern" to tr("düzenli ifade yalnızca metin alanlarında"))
            else runCatching { Regex(f.pattern) }.onFailure { add("pattern" to tr("geçersiz düzenli ifade: {0}", it.message?.lineSequence()?.firstOrNull())) }
        }
        if ((f.minLength != null || f.maxLength != null) && !isText && f.type != FieldType.MULTISELECT) {
            add("minLength" to tr("uzunluk sınırı yalnızca metin ve çoklu seçim alanlarında"))
        }
        if ((f.minLength ?: 0) < 0 || (f.maxLength ?: 0) < 0) add("minLength" to tr("uzunluk negatif olamaz"))
        if (f.minLength != null && f.maxLength != null && f.minLength > f.maxLength) add("maxLength" to tr("en çok, en azdan küçük olamaz"))
        if ((f.min != null || f.max != null) && f.type != FieldType.INTEGER && f.type != FieldType.REAL) {
            add("min" to tr("sayı aralığı yalnızca sayı alanlarında"))
        }
        if (f.min != null && f.max != null && f.min > f.max) add("max" to tr("en büyük, en küçükten küçük olamaz"))
        if (f.type == FieldType.GEO && (f.unique || f.index)) add("index" to tr("konum alanı benzersiz veya indeksli olamaz"))
        f.computed?.let { src ->
            if (f.type !in COMPUTABLE) {
                add("computed" to tr("hesaplanmış alan metin, tam sayı, ondalık veya true/false olmalı"))
            }
            if (f.required) add("computed" to tr("hesaplanmış alan zorunlu olamaz (değeri her zaman hesaplanır)"))
            if (f.default != null) add("computed" to tr("hesaplanmış alana varsayılan verilemez"))
            try {
                val e = Expression.parse(src)
                // Kimlik ve zaman alanları yeni kayıtta hesaplama anında henüz yok; yalnızca koleksiyon alanları.
                val known = fields.filter { it.type != FieldType.FILE && it.type != FieldType.JSON && it.type != FieldType.GEO }.map { it.name }.toSet()
                e.references.firstOrNull { it !in known }?.let { add("computed" to tr("bilinmeyen alan: {0}", it)) }
                if (f.name in e.references) add("computed" to tr("alan kendisine başvuramaz"))
                fields.firstOrNull { it.encrypted && it.name in e.references }?.let { add("computed" to tr("şifreli alana başvuramaz: {0}", it.name)) }
            } catch (ex: IllegalArgumentException) {
                add("computed" to tr("ifade hatalı: {0}", ex.message))
            }
        }
        if (f.encrypted) {
            when {
                !isText && f.type != FieldType.JSON -> add("encrypted" to tr("şifreleme yalnızca metin ve JSON alanlarında"))
                f.unique -> add("encrypted" to tr("şifreli alan benzersiz olamaz (aynı değer her seferinde farklı şifrelenir)"))
                f.index -> add("encrypted" to tr("şifreli alan indekslenemez"))
                f.computed != null -> add("encrypted" to tr("hesaplanmış alan şifrelenemez"))
            }
        }
        f.default?.let { d ->
            val canonical: Any? = when (f.type) {
                FieldType.MULTISELECT -> (d as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                else -> (d as? kotlinx.serialization.json.JsonPrimitive)?.let { p -> p.content.toDoubleOrNull()?.takeIf { !p.isString } ?: p.content }
            }
            runCatching { check(f, canonical) }.getOrNull()?.let { add("default" to tr("varsayılan değer: {0}", it)) }
        }
    }
}
