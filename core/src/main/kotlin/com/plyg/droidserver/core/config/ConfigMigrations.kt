package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * server.json şema sürümleri arasında dönüşüm. Her adım bir sürümün JSON'unu bir sonrakine çevirir;
 * şema değiştiğinde (alan eklendi, çıkarıldı, anlamı değişti) [CURRENT] artırılır, buraya bir adım ve kullanıcıya
 * gösterilecek açıklaması ([changes]) eklenir. `version` alanı olmayan dosya v1 sayılır.
 *
 * Uygulama eski sürümdeki dosyayı bellekte yükselterek kullanır ama diskteki dosyayı kendiliğinden yeniden yazmaz:
 * kullanıcıya sorulur ("Sürümü yükselt"); yükseltilmeyen dosya eski uygulama sürümleriyle uyumlu kalır.
 *
 *  v1 -> v2: sır alanları (`auth.apiKeys`, `auth.jwtSecret`) `"secret:<ad>"` referansı tutabilir.
 *            Depo verilirse v1'deki tüm sırlar depoya taşınır (v1'de referans olmadığı için "secret:" ile
 *            başlayan bir değer de düz metindir). Depo yoksa değerler yerinde kalır; v2 düz metni de kabul eder.
 *  v2 -> v3: isteğe bağlı yeni alanlar `collections[].fields[].typeChange` ve `database.createIfMissing`;
 *            mevcut alanlar değişmez.
 *  v3 -> v4: erişim modu bileşenlerden türetilmek yerine `access` bölümünde durur. Etkin `cloudflared` → Cloudflare
 *            (token varsa adlandırılmış tünel); etkin `tailscale` bileşeni (paketi hiç olmadı) → Tailscale modu ve bileşen
 *            silinir. Kimlik doğrulamasız Cloudflare kurulumu, yükseltmeden sonra da açılabilsin diye `allowNoAuth` alır.
 *  v4 -> v5: `relation` alan tipi ve isteğe bağlı `fields[].collection`, `fields[].onDelete`, `server.limits`;
 *            mevcut alanlar değişmez (eski uygulama ilişki alanlı dosyayı anlamaz, sürüm bu yüzden artar).
 *  v5 -> v6: koleksiyonların `publicRead` / `readOnly` bayrakları `rules`'a dönüşür (davranış aynı kalır):
 *            `publicRead` → list/view `public`, değilse `admin`; `readOnly` → create/update/delete `nobody`, değilse
 *            `admin`. İsteğe bağlı yeni alanlar: `auth.users`, `collections[].ownerField`.
 *  v21 -> v22: MQTT TLS (`mqtt.tls`, `mqtt.tlsPort`, `mqtt.plain`; yerel kökle imzalı sertifika); mevcut alanlar
 *            değişmez.
 *  v20 -> v21: Faz 18 Grup 4: `payments` (Stripe, iyzico, Paddle; `_payments`, `_subscriptions`, `_payment_events`
 *            tabloları), `ai` otomasyon eyleminin yanına `payment`; mevcut alanlar değişmez.
 *  v19 -> v20: Faz 18 Grup 3: `pages` (kodsuz sayfalar), `dashboards` (panolar), yerel YZ sağlayıcısı ve paketler
 *            (`packages`); mevcut alanlar değişmez.
 *  v18 -> v19: Faz 18 Grup 2: `geo` alan tipi, `collections[].calendar` (takvim, `.ics`, CalDAV) ve `contacts` (CardDAV;
 *            `_ds_dav` tablosu), dosya alanlarında medya ayarları; mevcut alanlar değişmez.
 *  v17 -> v18: Faz 18: çevrimdışı eşitleme (`collections[].sync`; `_sync`, `_sync_fields` tabloları); mevcut alanlar
 *            değişmez.
 *  v16 -> v17: Faz 17: `auth.passkeys` (passkey ile giriş; `_passkeys`, `_panel_passkeys` tabloları), MQTT cihaz kimlikleri
 *            ve tutulan mesajlar, `alerts.telegram.adminChatId` (Telegram'dan yönetim), `email.templates` (hesap e-postası
 *            şablonları) ve toplu bildirim (`_unsubscribed` tablosu); mevcut alanlar değişmez.
 *  v15 -> v16: Faz 16: otomasyon kuralları (`automations`), herkese açık formlar (`forms`) ve `api.graphql`; mevcut alanlar değişmez.
 *  v14 -> v15: Faz 15: ortak ortam değişkenleri (`env`), kendi uygulamalar (`apps`, Git kaynağı dahil) ve
 *            `components[].sharedEnv`; mevcut alanlar değişmez.
 *  v13 -> v14: Faz 14: `auth.users.roles` ve `collections[].rules.roles` (özel roller), `fields[].encrypted` ve
 *            `database.fieldKey` (alan şifreleme; eski sürüm şifreli değeri düz metin sanacağı için sürüm artar),
 *            `collections[].retention`, `views`, `realtime.channels`, `phone`; mevcut alanlar değişmez.
 *  v12 -> v13: `select` ve `multiselect` alan tipleri, isteğe bağlı alan kısıtları (`options`, `format`, `minLength`,
 *            `maxLength`, `pattern`, `min`, `max`), hesaplanmış alan (`computed`), `collections[].history` ve
 *            `collections[].trash`, `auth.users.quotas`, `auth.users.twoFactor`, `auth.users.emailLogin`,
 *            `adminPanel.totpSecret`, `collections[].fullText`, `backup.remote`, Faz 13'ün `audit`, `firewall`,
 *            `alerts`, `access.discovery` ve `replication` bölümleri; mevcut alanlar değişmez.
 *  v11 -> v12: isteğe bağlı `webhooks` ve Faz 11'in diğer alanları (toplu işlem, koşullu kural, API anahtarları,
 *            OAuth2, web push).
 *  v10 -> v11: isteğe bağlı `realtime` (canlı abonelik), `fields[].index`, `collections[].search` (arama),
 *            `email` (SMTP; doğrulama ve parola sıfırlama), `auth.users.requireVerified`.
 *  v9 -> v10: isteğe bağlı `access.direct.ddns.certificate` (DuckDNS adı için Let's Encrypt sertifikası).
 *  v8 -> v9: isteğe bağlı `backup` (zamanlanmış yedek) ve `adminPanel` (web yönetim paneli) bölümleri.
 *  v7 -> v8: isteğe bağlı `scripts` bölümü (JavaScript uçları, kayıt olayları, zamanlanmış görevler).
 *  v6 -> v7: `file` alan tipi (isteğe bağlı `maxSizeMb`, `mimeTypes`, `multiple`, `thumbs`) ve `static` (statik site);
 *            mevcut alanlar değişmez.
 */
object ConfigMigrations {
    const val CURRENT = 22

    private val steps: Map<Int, (JsonObject, SecretSink?) -> JsonObject> = mapOf(
        1 to ::v1ToV2,
        2 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        3 to { json: JsonObject, _: SecretSink? -> v3ToV4(json) },
        4 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        5 to { json: JsonObject, _: SecretSink? -> v5ToV6(json) },
        6 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        7 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        8 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        9 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        10 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        11 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        12 to { json: JsonObject, _: SecretSink? -> json }, // yeni alan tipleri ve isteğe bağlı alanlar eklendi
        13 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        14 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        15 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        16 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        17 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        18 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar ve yeni alan tipi eklendi
        19 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        20 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
        21 to { json: JsonObject, _: SecretSink? -> json }, // yalnızca isteğe bağlı alanlar eklendi
    )

    /** Kullanıcıya gösterilen değişiklikler: adım (vN -> vN+1) başına bir satır. */
    private val descriptions: Map<Int, String> = mapOf(
        1 to tr("API anahtarları ve JWT sırrı şifreli depoya taşınır; dosyada yalnızca referansları kalır."),
        2 to tr("Alan tipi değişince kayıtlı değerler için seçim (typeChange) ve PostgreSQL için \"veritabanı yoksa oluştur\" " +
            "(createIfMissing) ayarları eklenir. Mevcut ayarlar değişmez."),
        3 to tr("Erişim ayarı ayrı bir bölüme (access) taşınır; yeni seçenekler: internetten doğrudan IP ile erişim (HTTPS) ve " +
            "hesapsız Cloudflare hızlı tüneli. Tailscale artık telefondaki Tailscale uygulamasıyla çalışır; eski tailscale " +
            "bileşeni kaldırılır."),
        4 to tr("Koleksiyonlar arası ilişki alanı (relation: başka koleksiyondaki kayda bağlanır, silinince ne olacağı seçilir) " +
            "ve istek sınırları (server.limits) eklenir. Mevcut ayarlar değişmez."),
        5 to tr("Son kullanıcı hesapları (kayıt/giriş) ve koleksiyon başına erişim kuralları eklenir. \"Okuma herkese açık\" ve " +
            "\"salt okunur\" ayarları kurallara dönüşür; kim neyi yapabiliyorsa yine aynısını yapabilir."),
        6 to tr("Dosya alanı (file: görsel, PDF vb. yükleme ve indirme) ve statik site yayınlama eklenir. Mevcut ayarlar değişmez."),
        7 to tr("JavaScript ile kendi uçlarınızı, kayıt olaylarına bağlı mantığı ve zamanlanmış görevleri yazabileceğiniz " +
            "script'ler (scripts) eklenir. Mevcut ayarlar değişmez."),
        8 to tr("Zamanlanmış yedek (backup) ve tarayıcıdan yönetim paneli (adminPanel) ayarları eklenir. Mevcut ayarlar değişmez."),
        9 to tr("İnternetten doğrudan erişimde DuckDNS adı için Let's Encrypt sertifikası seçeneği eklenir (tarayıcı uyarısı " +
            "kalkar). Mevcut ayarlar değişmez."),
        10 to tr("Canlı abonelik (kayıt değişikliklerini anında alma), alan indeksleri ve arama, e-posta ile hesap doğrulama ve " +
            "parola sıfırlama ayarları eklenir. Mevcut ayarlar değişmez."),
        11 to tr("Webhook'lar, koşullu erişim kuralları, kapsamlı API anahtarları, sosyal giriş ve web push bildirimleri " +
            "ayarları eklenir. Mevcut ayarlar değişmez."),
        12 to tr("Seçim ve çoklu seçim alan tipleri, alan kısıtları (e-posta/adres biçimi, uzunluk, düzenli ifade, sayı " +
            "aralığı), başka alanlardan hesaplanan alanlar, kayıt geçmişi, çöp kutusu, kullanıcı kotaları, iki adımlı doğrulama, e-postayla kodla giriş, tam metin dizini, şifreli bulut yedeği, " +
            "denetim kaydı, güvenlik duvarı, sağlık uyarıları, yerel ağda görünürlük ve canlı kopya eklenir. Mevcut ayarlar değişmez."),
        13 to tr("Özel roller, şifreli alanlar, saklama süresi, görünümler ve raporlar, WebSocket kanalları ve telefon özellikleri (SMS, konum) eklenir. Mevcut ayarlar değişmez."),
        14 to tr("Ortak ortam değişkenleri ve sırlar (.env), kendi uygulamalarınızı (Node.js, Python, Java) çalıştırma ve Git'ten deploy eklenir. Mevcut ayarlar değişmez."),
        15 to tr("Kod yazmadan otomasyon kuralları (kayıt eklenince veya belirli saatlerde e-posta, bildirim, webhook gönderme ya da kayıt güncelleme), koleksiyonlara kayıt ekleyen herkese açık formlar, isteğe bağlı GraphQL ucu ve yapay zekâ (Claude ile sohbet, kayıtlara soru sorma, anlamsal arama) eklenir. Mevcut ayarlar değişmez."),
        16 to tr("Passkey ile parolasız giriş (kullanıcılar ve web paneli), MQTT'de cihaz başına kimlik, ev otomasyonu, Telegram'dan yönetim ve hesap e-postası şablonları eklenir. Mevcut ayarlar değişmez."),
        17 to tr("Uygulamaların bağlantı yokken de çalışabilmesi için koleksiyon başına çevrimdışı eşitleme eklenir. Mevcut ayarlar değişmez."),
        18 to tr("Konum alanı, takvim (çakışma kuralı, takvim aboneliği, telefon takvimiyle eşitleme), kişiler ve görsel/video ayarları eklenir. Mevcut ayarlar değişmez."),
        19 to tr("Kodsuz sayfalar, panolar, telefonda çalışan yerel yapay zekâ ve paketler eklenir. Mevcut ayarlar değişmez."),
        20 to tr("Ödemeler (Stripe, iyzico, Paddle) ve abonelikler eklenir. Mevcut ayarlar değişmez."),
        21 to tr("MQTT için TLS (8883) eklenir. Mevcut ayarlar değişmez."),
    )

    /** [from] sürümünden güncel sürüme yükseltirken neler değişir; güncelse boş. */
    fun changes(from: Int): List<String> = (from until CURRENT).map { "v$it → v${it + 1}: ${descriptions.getValue(it)}" }

    fun versionOf(json: JsonObject): Int = (json["version"] as? JsonPrimitive)?.intOrNull ?: 1

    fun upgrade(json: JsonObject, secrets: SecretSink? = null): JsonObject {
        val from = versionOf(json)
        require(from <= CURRENT) { tr("Konfigürasyon sürümü v{0} bu uygulamadan yeni (desteklenen: v{1}); uygulamayı güncelleyin", from, CURRENT) }
        require(from >= 1) { tr("Geçersiz konfigürasyon sürümü: {0}", from) }
        var out = json
        for (v in from until CURRENT) {
            out = steps.getValue(v)(out, secrets)
        }
        return if (from == CURRENT) out else JsonObject(mapOf("version" to JsonPrimitive(CURRENT)) + (out - "version"))
    }

    private fun v1ToV2(json: JsonObject, secrets: SecretSink?): JsonObject =
        if (secrets == null) json
        else ConfigSecrets.mapSecrets(json) { kind, v -> if (v.isEmpty()) v else ConfigSecrets.ref(secrets.store(kind, v)) }

    private fun v5ToV6(json: JsonObject): JsonObject {
        val collections = json["collections"] as? JsonArray ?: return json
        return JsonObject(json + ("collections" to JsonArray(collections.map { c ->
            if (c !is JsonObject) return@map c
            fun flag(key: String) = (c[key] as? JsonPrimitive)?.booleanOrNull == true
            val read = JsonPrimitive(if (flag("publicRead")) "public" else "admin")
            val write = JsonPrimitive(if (flag("readOnly")) "nobody" else "admin")
            val rules = JsonObject(mapOf("list" to read, "view" to read, "create" to write, "update" to write, "delete" to write))
            JsonObject(c - "publicRead" - "readOnly" + ("rules" to rules))
        })))
    }

    private fun v3ToV4(json: JsonObject): JsonObject {
        val components = (json["components"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun enabled(c: JsonObject) = (c["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        fun id(c: JsonObject) = (c["id"] as? JsonPrimitive)?.content
        val cloudflared = components.firstOrNull { id(it) == "cloudflared" && enabled(it) }
        val tailscale = components.any { id(it) == "tailscale" && enabled(it) }
        val authNone = ((json["auth"] as? JsonObject)?.get("mode") as? JsonPrimitive)?.content.let { it == null || it == "none" }

        val access: Map<String, JsonElement>? = when {
            cloudflared != null -> {
                val token = ((cloudflared["env"] as? JsonObject)?.get("TUNNEL_TOKEN") as? JsonPrimitive)?.content
                // Elle yazılmış argümanlar (örn. "tunnel run --token ...") adlandırılmış tünel demektir; olduğu gibi çalışır.
                val customArgs = (cloudflared["args"] as? JsonArray)?.isNotEmpty() == true
                val named = !token.isNullOrBlank() || customArgs
                buildMap<String, JsonElement> {
                    put("mode", JsonPrimitive("cloudflare"))
                    put("cloudflare", JsonObject(mapOf("tunnel" to JsonPrimitive(if (named) "named" else "quick"))))
                    if (authNone) put("allowNoAuth", JsonPrimitive(true))
                }
            }
            tailscale -> mapOf("mode" to JsonPrimitive("tailscale"))
            else -> null
        }
        val kept = components.filterNot { id(it) == "tailscale" }
        var out = json
        if (kept.size != components.size) out = JsonObject(out + ("components" to JsonArray(kept)))
        if (access != null) out = JsonObject(out + ("access" to JsonObject(access)))
        return out
    }
}
