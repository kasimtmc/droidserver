package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/** Sihirbazda "Koleksiyon ekle" ile sunulan hazır koleksiyonlar. */
enum class CollectionTemplate(val title: String, val description: String) {
    TODOS(tr("Yapılacaklar"), tr("Başlık, tamamlandı, son tarih, etiketler")),
    BLOG(tr("Blog yazıları"), tr("Başlık, benzersiz kısa ad, içerik, yayın durumu; okuma herkese açık")),
    PRODUCTS(tr("Ürünler"), tr("Ad, stok kodu, fiyat, stok, aktiflik; okuma herkese açık")),
    GALLERY(tr("Fotoğraflar"), tr("Başlık, görsel (en çok 10 MB, küçük resimli), açıklama; okuma herkese açık")),
    EMPTY(tr("Boş"), tr("Alanları kendiniz ekleyin"));

    fun create(): CollectionConfig = when (this) {
        TODOS -> CollectionConfig(
            name = "todos",
            fields = listOf(
                FieldConfig("title", FieldType.TEXT, required = true),
                FieldConfig("done", FieldType.BOOLEAN, default = JsonPrimitive(false)),
                FieldConfig("due", FieldType.DATETIME),
                FieldConfig("tags", FieldType.JSON),
            ),
        )
        BLOG -> CollectionConfig(
            name = "posts",
            rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
            fields = listOf(
                FieldConfig("title", FieldType.TEXT, required = true),
                FieldConfig("slug", FieldType.TEXT, required = true, unique = true),
                FieldConfig("body", FieldType.TEXT),
                FieldConfig("published", FieldType.BOOLEAN, default = JsonPrimitive(false)),
                FieldConfig("published_at", FieldType.DATETIME),
                FieldConfig("tags", FieldType.JSON),
            ),
        )
        PRODUCTS -> CollectionConfig(
            name = "products",
            rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
            fields = listOf(
                FieldConfig("name", FieldType.TEXT, required = true),
                FieldConfig("sku", FieldType.TEXT, unique = true),
                FieldConfig("price", FieldType.REAL, required = true),
                FieldConfig("stock", FieldType.INTEGER, default = JsonPrimitive(0)),
                FieldConfig("active", FieldType.BOOLEAN, default = JsonPrimitive(true)),
                FieldConfig("attributes", FieldType.JSON),
            ),
        )
        GALLERY -> CollectionConfig(
            name = "photos",
            rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
            fields = listOf(
                FieldConfig("title", FieldType.TEXT),
                FieldConfig("image", FieldType.FILE, required = true, mimeTypes = listOf("image/*"), thumbs = listOf("400x0")),
                FieldConfig("caption", FieldType.TEXT),
            ),
        )
        EMPTY -> CollectionConfig(name = "items", fields = emptyList())
    }
}

/** Varsayılan konfigürasyon ve sır üretimi. */
object ConfigDefaults {
    /** default-config.json'daki yer tutucu anahtar; hiçbir zaman gerçek anahtar olarak kalmamalı. */
    const val PLACEHOLDER_API_KEY = "degistir-beni-en-az-16-karakter"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /** ~190 bit rastgelelik; `ds_` öneki anahtarın ne olduğunu belli eder. */
    fun generateApiKey(): String = "ds_" + randomToken(32)

    /** HS256 için en az 32 karakter gerekir; 48 karakter ~285 bit. */
    fun generateJwtSecret(): String = randomToken(48)

    /** PostgreSQL parolası (yalnızca harf ve rakam: kabuk ve bağlantı adresinde sorun çıkarmaz). */
    fun generateDbPassword(): String = randomToken(32)

    /** Git push webhook'unun sırrı (~238 bit). */
    fun generateWebhookSecret(): String = randomToken(40)

    private fun randomToken(length: Int) = buildString(length) {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
