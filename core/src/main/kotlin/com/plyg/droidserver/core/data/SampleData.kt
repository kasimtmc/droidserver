package com.plyg.droidserver.core.data

import com.plyg.droidserver.core.config.GeoPoint
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.TextFormat
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Koleksiyona örnek kayıt üretir: alan tipine, seçeneklere, biçime (e-posta, adres), uzunluk ve aralık sınırlarına ve
 * benzersizliğe uyar; ilişki alanına hedef koleksiyondaki var olan bir kaydı koyar. Desene bağlı zorunlu alan, zorunlu
 * dosya alanı veya boş hedefli zorunlu ilişki varsa üretilemez ([ApiException] 400). Kayıtlar normal doğrulamadan geçer.
 */
object SampleData {
    const val MAX_COUNT = 500

    private val WORDS = listOf(
        "elma", "kitap", "deniz", "bulut", "kalem", "masa", "yıldız", "çiçek", "orman", "nehir", "köprü", "şehir",
        "rüzgâr", "güneş", "ay", "toprak", "dağ", "yol", "kapı", "pencere", "çay", "kahve", "ekmek", "peynir",
    )
    private val NAMES = listOf("Ayşe", "Mehmet", "Zeynep", "Ali", "Elif", "Mustafa", "Fatma", "Can", "Deniz", "Ece", "Emre", "Selin")
    private val SURNAMES = listOf("Yılmaz", "Kaya", "Demir", "Şahin", "Çelik", "Yıldız", "Aydın", "Öztürk", "Arslan", "Doğan")
    private val CITIES = listOf("İstanbul", "Ankara", "İzmir", "Bursa", "Antalya", "Trabzon", "Eskişehir", "Gaziantep", "Konya", "Muğla")

    /**
     * [index] sıradaki örnek kayıt (benzersiz alanlar sırayla ayrışır). [ids]: ilişki hedefi koleksiyonun var olan
     * kimlikleri.
     */
    fun record(c: CollectionConfig, index: Int, ids: (String) -> List<Long>, random: Random = Random.Default): JsonObject = buildJsonObject {
        for (f in c.fields) value(f, index, ids, random)?.let { put(f.name, it) }
    }

    private fun value(f: FieldConfig, i: Int, ids: (String) -> List<Long>, random: Random): JsonElement? {
        if (f.computed != null) return null
        return when (f.type) {
            FieldType.TEXT -> text(f, i, random)?.let(::JsonPrimitive)
            FieldType.INTEGER -> {
                val lo = f.min?.toLong() ?: 0L
                val hi = f.max?.toLong() ?: (lo + 100)
                JsonPrimitive(if (f.unique) lo + i else random.nextLong(lo, hi + 1))
            }
            FieldType.REAL -> {
                val lo = f.min ?: 0.0
                val hi = f.max ?: (lo + 100.0)
                JsonPrimitive(Math.round((lo + random.nextDouble() * (hi - lo)) * 100) / 100.0)
            }
            FieldType.BOOLEAN -> JsonPrimitive(random.nextBoolean())
            FieldType.DATETIME -> JsonPrimitive(Instant.now().minus(random.nextLong(0, 30 * 24 * 60), ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString())
            FieldType.JSON -> buildJsonObject { put("ornek", JsonPrimitive(i + 1)) }
            // Türkiye'den rastgele bir nokta.
            FieldType.GEO -> GeoPoint(Math.round((36.5 + random.nextDouble() * 5) * 1e5) / 1e5, Math.round((27.0 + random.nextDouble() * 16) * 1e5) / 1e5).json()
            FieldType.SELECT -> f.options?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it[random.nextInt(it.size)]) }
            FieldType.MULTISELECT -> f.options?.takeIf { it.isNotEmpty() }?.let { o ->
                JsonArray(o.shuffled(random).take(random.nextInt(1, minOf(o.size, 3) + 1)).map(::JsonPrimitive))
            }
            FieldType.RELATION -> {
                val target = f.collection ?: return null
                val pool = ids(target)
                when {
                    pool.isNotEmpty() -> JsonPrimitive(pool[random.nextInt(pool.size)])
                    f.required -> throw ApiException(400, tr("'{0}' alanı için önce '{1}' koleksiyonuna kayıt ekleyin", f.name, target))
                    else -> null
                }
            }
            FieldType.FILE -> if (f.required) throw ApiException(400, tr("'{0}' zorunlu bir dosya alanı; örnek veri üretilemiyor", f.name)) else null
        }
    }

    private fun text(f: FieldConfig, i: Int, random: Random): String? {
        if (f.options != null && f.options.isNotEmpty()) return f.options[random.nextInt(f.options.size)]
        if (f.pattern != null) {
            if (f.required) throw ApiException(400, tr("'{0}' bir desene uymalı; örnek veri üretilemiyor", f.name))
            return null
        }
        val n = f.name.lowercase()
        val base = when {
            f.format == TextFormat.EMAIL || "mail" in n -> "ornek${i + 1}.${random.nextInt(1000, 9999)}@example.com"
            f.format == TextFormat.URL || n in setOf("url", "link", "site", "web", "website") -> "https://example.com/${i + 1}"
            "phone" in n || "telefon" in n || n == "tel" -> "+90555${random.nextInt(1_000_000, 9_999_999)}"
            n in setOf("city", "sehir", "şehir", "il") -> CITIES[random.nextInt(CITIES.size)]
            n in setOf("name", "ad", "isim", "fullname", "full_name", "adsoyad", "author", "yazar") ->
                NAMES[random.nextInt(NAMES.size)] + " " + SURNAMES[random.nextInt(SURNAMES.size)]
            n in setOf("title", "baslik", "başlık", "subject", "konu") -> sentence(random, 3).replaceFirstChar(Char::uppercaseChar)
            n in setOf("body", "description", "aciklama", "açıklama", "text", "content", "icerik", "içerik", "note", "not", "notes") ->
                sentence(random, 12).replaceFirstChar(Char::uppercaseChar) + "."
            else -> sentence(random, 2)
        }
        val unique = if (f.unique && f.format == null && "mail" !in n) "$base ${i + 1}" else base
        val min = f.minLength ?: 0
        val padded = if (unique.length < min) unique + " " + sentence(random, min).take(min - unique.length - 1).ifEmpty { "x" } else unique
        return f.maxLength?.let { max -> if (padded.length > max) padded.take(max).trimEnd() else padded } ?: padded
    }

    private fun sentence(random: Random, words: Int) = List(words) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")
}
