package dev.droidserver.core.templates

import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HistoryConfig
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.RuleCondition
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.TextFormat
import dev.droidserver.core.config.TrashConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Hazır uygulamalar: koleksiyonlar (ilişkiler ve kurallarla), örnek veri ve API'yi kullanan küçük bir statik site.
 * [apply] konfigürasyona ekler; örnek veri ve site deploy'dan sonra [seed] ve [site] ile kurulur.
 */
enum class AppTemplate(val id: String, val title: String, val description: String, val needsUsers: Boolean) {
    BLOG(
        "blog", tr("Blog"),
        tr("Kategoriler, yazılar (kapak görseli, taslak/yayında, tam metin arama) ve onaylı yorumlar. Herkes yayındaki " +
            "yazıları okur ve yorum bırakır; yorumlar siz onaylayınca görünür."),
        needsUsers = false,
    ),
    SHOP(
        "shop", tr("Mağaza ve sipariş"),
        tr("Ürün kataloğu (görsel, stok, kategori) ve siparişler. Herkes ürünleri görür; müşteriler hesap açıp sipariş " +
            "verir ve yalnızca kendi siparişlerini görür; durumunu siz güncellersiniz."),
        needsUsers = true,
    ),
    APPOINTMENTS(
        "appointments", tr("Randevu"),
        tr("Hizmetler (süre, fiyat) ve randevu talepleri. Herkes hesap açmadan randevu ister; talepleri yalnızca siz görür, " +
            "onaylar veya iptal edersiniz."),
        needsUsers = false,
    ),
    TASKS(
        "tasks", tr("Görev takibi"),
        tr("Projeler ve görevler (durum, öncelik, son tarih, değişiklik geçmişi). Her kullanıcı yalnızca kendi projelerini ve " +
            "görevlerini görür."),
        needsUsers = true,
    );

    /** Şablonun koleksiyonları (ilişki hedefleri önce). */
    fun collections(): List<CollectionConfig> = when (this) {
        BLOG -> listOf(
            CollectionConfig(
                "categories",
                listOf(FieldConfig("name", FieldType.TEXT, required = true), FieldConfig("slug", FieldType.TEXT, required = true, unique = true)),
                rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC),
            ),
            CollectionConfig(
                "posts",
                listOf(
                    FieldConfig("title", FieldType.TEXT, required = true, maxLength = 200),
                    FieldConfig("slug", FieldType.TEXT, required = true, unique = true, pattern = "^[a-z0-9-]+$"),
                    FieldConfig("excerpt", FieldType.TEXT, maxLength = 300),
                    FieldConfig("body", FieldType.TEXT),
                    FieldConfig("cover", FieldType.FILE, mimeTypes = listOf("image/*"), thumbs = listOf("800x0")),
                    FieldConfig("category", FieldType.RELATION, collection = "categories", onDelete = OnDelete.SET_NULL),
                    FieldConfig("status", FieldType.SELECT, required = true, options = listOf("taslak", "yayında"), default = JsonPrimitive("taslak")),
                    FieldConfig("published_at", FieldType.DATETIME, index = true),
                    FieldConfig("tags", FieldType.JSON),
                ),
                rules = Rules(
                    list = Rule.PUBLIC, view = Rule.PUBLIC,
                    conditions = mapOf("list" to RuleCondition("status=yayında"), "view" to RuleCondition("status=yayında")),
                ),
                search = listOf("title", "excerpt", "body"),
                fullText = true,
                history = HistoryConfig(keep = 20),
                trash = TrashConfig(),
            ),
            CollectionConfig(
                "comments",
                listOf(
                    FieldConfig("post", FieldType.RELATION, required = true, collection = "posts", onDelete = OnDelete.CASCADE),
                    FieldConfig("name", FieldType.TEXT, required = true, maxLength = 60),
                    FieldConfig("body", FieldType.TEXT, required = true, maxLength = 2000),
                    FieldConfig("approved", FieldType.BOOLEAN, default = JsonPrimitive(false)),
                ),
                rules = Rules(
                    list = Rule.PUBLIC, view = Rule.PUBLIC, create = Rule.PUBLIC,
                    conditions = mapOf(
                        "list" to RuleCondition("approved=true"),
                        "view" to RuleCondition("approved=true"),
                        "create" to RuleCondition("approved=false"),
                    ),
                ),
            ),
        )
        SHOP -> listOf(
            CollectionConfig(
                "products",
                listOf(
                    FieldConfig("name", FieldType.TEXT, required = true, maxLength = 200),
                    FieldConfig("sku", FieldType.TEXT, unique = true),
                    FieldConfig("description", FieldType.TEXT),
                    FieldConfig("price", FieldType.REAL, required = true, min = 0.0),
                    FieldConfig("stock", FieldType.INTEGER, default = JsonPrimitive(0), min = 0.0),
                    FieldConfig("image", FieldType.FILE, mimeTypes = listOf("image/*"), thumbs = listOf("400x400")),
                    FieldConfig("category", FieldType.SELECT, options = listOf("Genel", "Giyim", "Elektronik", "Ev", "Kitap")),
                    FieldConfig("active", FieldType.BOOLEAN, default = JsonPrimitive(true)),
                ),
                rules = Rules(
                    list = Rule.PUBLIC, view = Rule.PUBLIC,
                    conditions = mapOf("list" to RuleCondition("active=true"), "view" to RuleCondition("active=true")),
                ),
                search = listOf("name", "description", "sku"),
            ),
            CollectionConfig(
                "orders",
                listOf(
                    FieldConfig("customer", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.SET_NULL),
                    FieldConfig("items", FieldType.JSON, required = true),
                    FieldConfig("total", FieldType.REAL, required = true, min = 0.0),
                    FieldConfig("status", FieldType.SELECT, required = true, options = listOf("yeni", "hazırlanıyor", "kargoda", "teslim edildi", "iptal"), default = JsonPrimitive("yeni")),
                    FieldConfig("address", FieldType.TEXT, required = true, maxLength = 500),
                    FieldConfig("phone", FieldType.TEXT, required = true, maxLength = 30),
                    FieldConfig("note", FieldType.TEXT, maxLength = 1000),
                ),
                rules = Rules(
                    list = Rule.OWNER, view = Rule.OWNER, create = Rule.AUTH,
                    conditions = mapOf("create" to RuleCondition("status=yeni")),
                ),
                ownerField = "customer",
                history = HistoryConfig(keep = 20),
            ),
        )
        APPOINTMENTS -> listOf(
            CollectionConfig(
                "services",
                listOf(
                    FieldConfig("name", FieldType.TEXT, required = true),
                    FieldConfig("description", FieldType.TEXT),
                    FieldConfig("duration_min", FieldType.INTEGER, required = true, min = 5.0, max = 600.0),
                    FieldConfig("price", FieldType.REAL, min = 0.0),
                    FieldConfig("active", FieldType.BOOLEAN, default = JsonPrimitive(true)),
                ),
                rules = Rules(
                    list = Rule.PUBLIC, view = Rule.PUBLIC,
                    conditions = mapOf("list" to RuleCondition("active=true"), "view" to RuleCondition("active=true")),
                ),
            ),
            CollectionConfig(
                "appointments",
                listOf(
                    FieldConfig("service", FieldType.RELATION, required = true, collection = "services", onDelete = OnDelete.RESTRICT),
                    FieldConfig("starts_at", FieldType.DATETIME, required = true, index = true),
                    FieldConfig("name", FieldType.TEXT, required = true, maxLength = 100),
                    FieldConfig("phone", FieldType.TEXT, required = true, maxLength = 30),
                    FieldConfig("email", FieldType.TEXT, format = TextFormat.EMAIL),
                    FieldConfig("note", FieldType.TEXT, maxLength = 1000),
                    FieldConfig("status", FieldType.SELECT, required = true, options = listOf("bekliyor", "onaylandı", "iptal"), default = JsonPrimitive("bekliyor")),
                ),
                rules = Rules(create = Rule.PUBLIC, conditions = mapOf("create" to RuleCondition("status=bekliyor"))),
                history = HistoryConfig(keep = 10),
            ),
        )
        TASKS -> listOf(
            CollectionConfig(
                "projects",
                listOf(
                    FieldConfig("name", FieldType.TEXT, required = true, maxLength = 100),
                    FieldConfig("description", FieldType.TEXT),
                    FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
                ),
                rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER),
                ownerField = "owner",
            ),
            CollectionConfig(
                "tasks",
                listOf(
                    FieldConfig("project", FieldType.RELATION, required = true, collection = "projects", onDelete = OnDelete.CASCADE),
                    FieldConfig("title", FieldType.TEXT, required = true, maxLength = 200),
                    FieldConfig("notes", FieldType.TEXT),
                    FieldConfig("status", FieldType.SELECT, required = true, options = listOf("yapılacak", "sürüyor", "bitti"), default = JsonPrimitive("yapılacak")),
                    FieldConfig("priority", FieldType.SELECT, options = listOf("düşük", "orta", "yüksek"), default = JsonPrimitive("orta")),
                    FieldConfig("due", FieldType.DATETIME, index = true),
                    FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.CASCADE),
                ),
                rules = Rules(Rule.OWNER, Rule.OWNER, Rule.AUTH, Rule.OWNER, Rule.OWNER),
                ownerField = "owner",
                search = listOf("title", "notes"),
                history = HistoryConfig(keep = 20),
                trash = TrashConfig(days = 14),
            ),
        )
    }

    /** Konfigürasyonda zaten olan koleksiyon adları (eklemeye engel; ilişkiler bu adlara bağlı). */
    fun conflicts(config: ServerConfig): List<String> = collections().map { it.name }.filter { n -> config.collections.any { it.name == n } }

    /**
     * Koleksiyonları ekler, [site] ise statik siteyi açar. Kullanıcı hesapları gerekiyorsa açılır (kimlik doğrulama
     * kapalıysa çağıran anahtar üretmelidir; bkz. sihirbaz). Ad çakışmasında [IllegalArgumentException].
     */
    fun apply(config: ServerConfig, site: Boolean = true): ServerConfig {
        conflicts(config).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException(tr("Bu adlarda koleksiyon zaten var: {0}", it.joinToString())) }
        var out = config.copy(collections = config.collections + collections())
        if (needsUsers && !out.auth.users.enabled) out = out.copy(auth = out.auth.copy(users = out.auth.users.copy(enabled = true, allowSignup = true)))
        if (site) out = out.copy(static = out.static.copy(enabled = true))
        return out
    }

    /** Statik sitenin `index.html`'i; API yolu konfigürasyondan. */
    fun site(basePath: String): String {
        val text = AppTemplate::class.java.getResourceAsStream("/templates/$id.html")?.use { it.readBytes().decodeToString() }
            ?: error(tr("Şablon sitesi bulunamadı: {0}", id))
        return text.replace("__API__", basePath.trimEnd('/').ifEmpty { "" })
    }

    /** Örnek kayıtlar: koleksiyon -> kayıtlar; `"@ref:koleksiyon:sıra"` o koleksiyondaki n. örneğin kimliği olur. */
    fun samples(): List<Pair<String, List<JsonObject>>> = SAMPLES[this].orEmpty().map { (c, json) -> c to Json.parseToJsonElement(json).jsonArray.map { it.jsonObject } }

    /**
     * Örnek kayıtları ekler (yalnızca boş koleksiyonlara; tekrar çalıştırmak çoğaltmaz). Eklenen kayıt sayısı.
     * Kayıt olayı (script, webhook) üretmez.
     */
    fun seed(db: SqlDriver, config: ServerConfig): Int = SampleSeed.seed(db, config, samples())

    companion object {
        fun of(id: String): AppTemplate? = entries.firstOrNull { it.id == id }

        private val SAMPLES: Map<AppTemplate, List<Pair<String, String>>> = mapOf(
            BLOG to listOf(
                "categories" to """[{"name":"Duyurular","slug":"duyurular"},{"name":"Rehberler","slug":"rehberler"}]""",
                "posts" to """[
                  {"title":"Merhaba dünya","slug":"merhaba-dunya","excerpt":"Bu blog bir Android telefonda çalışıyor.","body":"İlk yazı. Yazıları uygulamadaki Kayıtlar ekranından veya web panelinden düzenleyebilirsiniz.","category":"@ref:categories:0","status":"yayında","published_at":"2026-01-01T09:00:00Z","tags":["duyuru"]},
                  {"title":"Telefonu sunucu yapmak","slug":"telefonu-sunucu-yapmak","excerpt":"Eski telefonunuzu nasıl değerlendirirsiniz?","body":"Şarjda ve Wi-Fi'da duran bir telefon küçük bir site ve API için yeterlidir. Pil sağlığı için şarjı %80'de sınırlamayı düşünün.","category":"@ref:categories:1","status":"yayında","published_at":"2026-01-02T09:00:00Z","tags":["rehber","android"]},
                  {"title":"Taslak yazı","slug":"taslak-yazi","body":"Bu yazı yayında değil; yalnızca siz görürsünüz.","status":"taslak"}
                ]""",
                "comments" to """[{"post":"@ref:posts:0","name":"Ayşe","body":"Harika fikir!","approved":true},{"post":"@ref:posts:0","name":"Mehmet","body":"Onay bekleyen yorum.","approved":false}]""",
            ),
            SHOP to listOf(
                "products" to """[
                  {"name":"Pamuklu tişört","sku":"TS-001","description":"Yüzde yüz pamuk, beyaz.","price":249.9,"stock":20,"category":"Giyim","active":true},
                  {"name":"Kablosuz kulaklık","sku":"KL-010","description":"Bluetooth 5.3, 30 saat pil.","price":899.0,"stock":5,"category":"Elektronik","active":true},
                  {"name":"Seramik kupa","sku":"EV-100","description":"350 ml.","price":129.5,"stock":40,"category":"Ev","active":true},
                  {"name":"Satıştan kalkan ürün","sku":"ESKI-1","price":10.0,"stock":0,"category":"Genel","active":false}
                ]""",
            ),
            APPOINTMENTS to listOf(
                "services" to """[
                  {"name":"Saç kesimi","description":"Yıkama dahil.","duration_min":30,"price":300.0},
                  {"name":"Danışmanlık görüşmesi","description":"Çevrim içi veya yüz yüze.","duration_min":60,"price":750.0}
                ]""",
            ),
        )
    }
}
