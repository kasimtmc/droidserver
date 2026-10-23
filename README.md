# DroidServer

**Türkçe** | [English](README.en.md)

Android cihazı, kullanıcının tercihlerine göre yapılandırılan bir arka uç sunucusuna dönüştüren uygulama.
Kotlin + Ktor + SQLite çekirdeği uygulamanın içinde çalışır; PostgreSQL, Redis, cloudflared gibi
yan servisler isteğe bağlı olarak indirilip yönetilir.

> **Dağıtım:** Play Store dışı (APK / GitHub Releases / F-Droid). `targetSdk = 28` bilinçli bir seçimdir,
> aşağıda nedeni açıklanıyor.

## Mimari

```
┌──────────────────────────── app (Android) ────────────────────────────┐
│  MainActivity (Compose)     ServerService (foreground)   BootReceiver │
│  Durum · Sunucu ·           wake/wifi lock, bildirim,     açılışta    │
│  Bileşenler · Loglar        deploy döngüsü                otomatik    │
│  Sihirbaz · JSON · Geçmiş ·                               başlatma    │
│  API gezgini (WizardViewModel: ortak taslak)                          │
│                 │                   │                                 │
│  ConfigRepository (server.json + geçmiş)   AndroidSqlDriver           │
│  SecretStore (Android Keystore ile şifreli sırlar)                    │
└─────────────────┼───────────────────┼─────────────────────────────────┘
                  ▼                   ▼
┌──────────────────────────── core (saf Kotlin/JVM) ────────────────────┐
│  ServerConfig ◄── ConfigMigrations (v1 → v22), ConfigSecrets          │
│       │                                                               │
│       ▼                                                               │
│  DeployOrchestrator.deploy()                                          │
│    ├─ sırları çöz + ConfigValidator                                   │
│    ├─ bileşenler: ComponentManager + ProcessSupervisor                │
│    │    (yalnızca değişen yeniden başlar) → ReadinessProbe            │
│    ├─ ServerEngine.deploy()                                           │
│    │    ├─ SchemaMigrator: plan() → apply()  (SqlDialect üzerinden)   │
│    │    └─ droidServerModule (Ktor)                                   │
│    │         ├─ otomatik CRUD uçları                                  │
│    │         ├─ API key / JWT                                         │
│    │         └─ CORS, hata yönetimi                                   │
│    └─ artık istenmeyen bileşenleri durdur                             │
│  ComponentManager (indir, sha256, aç, ilk kurulum)                    │
│  ProcessSupervisor (başlat, izle, geri çekilmeli yeniden başlat)      │
└───────────────────────────────────────────────────────────────────────┘
```

**Temel fikir:** Kod üretip cihazda derlemek yerine, sunucu tek bir konfigürasyon dosyasını
(`server.json`) çalışma anında yorumlar. "Deploy" = sırları çöz ve doğrula → bileşenleri senkronize et →
`readiness` tanımlı olanların hazır olmasını bekle → şemayı planla ve uygula → sunucuyu yeniden başlat →
konfigürasyondan çıkarılan bileşenleri durdur. Doğrulama veya migration başarısız olursa (engelleyici şema
sorunu dahil) çalışan sunucuya dokunulmaz.

`core` modülü Android'e bağımlı değildir; tüm iş mantığı bilgisayarda test edilir.

## Derleme

Gereksinimler: JDK 17+, Android SDK (platform 37), NDK 29.0.14206865 ve CMake 4.1.2 (yerel YZ için; Android Studio SDK
Manager'dan). Depoyu alt modülüyle alın: `git clone --recurse-submodules …` (sonradan: `git submodule update --init`).

```bash
./gradlew :core:test           # çekirdek testleri (bilgisayarda, gerçek SQLite + PostgreSQL + HTTP)
./gradlew :app:testDebugUnitTest  # sihirbaz ViewModel testleri (JVM, emülatör gerekmez)
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Veritabanı testleri SQLite ve PostgreSQL için parametriktir. PostgreSQL, `io.zonky.test:embedded-postgres`
ile gerçek bir sunucu olarak açılır (ikililer ilk koşuda Maven'den iner). Postgres root olarak çalışmayı reddeder;
testler root ile (örn. root kullanıcılı bir Docker'da) koşturulursa uyarı verilir ve Postgres süreçleri otomatik
olarak root olmayan `nobody` kullanıcısıyla çalıştırılır (`runuser` gerekir; bkz. `NonRootPostgres.kt`).

Araç zinciri: Gradle 9.8, AGP 9.4 (yerleşik Kotlin), Kotlin 2.4, Ktor 3.6, Compose BOM 2026.09.

## Konfigürasyon (`server.json`)

İlk açılışta konfigürasyon yoksa **sihirbaz** açılır (başlangıç taslağı `app/src/main/assets/default-config.json`,
yer tutucu anahtar yerine yeni üretilmiş bir anahtarla). Sonrasında **Sunucu** sekmesinden sihirbazla veya
**Gelişmiş (JSON)** ile düzenlenir; ikisi aynı taslağı paylaşır ve taslak `files/config/draft.json`'a kaydedilir
(uygulama kapanınca kaybolmaz). Her kayıtta önceki sürüm `files/config/history/` altına yedeklenir (son 20).

```json
{
  "version": 9,
  "server": { "port": 8080, "basePath": "/api", "cors": { "enabled": true, "allowedHosts": ["*"] } },
  "auth":   { "mode": "api_key", "apiKeys": ["secret:api_key_3f9a2c1d"],
              "users": { "enabled": true, "allowSignup": true,
                         "extraFields": [ { "name": "name", "type": "text", "required": true } ],
                         "publicFields": ["name"] } },
  "database": { "engine": "sqlite", "file": "data.db" },
  "collections": [
    { "name": "todos", "ownerField": "owner",
      "rules": { "list": "owner", "view": "owner", "create": "auth", "update": "owner", "delete": "owner" },
      "fields": [
        { "name": "title", "type": "text", "required": true },
        { "name": "done",  "type": "boolean", "default": false },
        { "name": "due",   "type": "datetime" },
        { "name": "owner", "type": "relation", "collection": "_users", "onDelete": "cascade" } ] },
    { "name": "notes",
      "rules": { "list": "public", "view": "public", "create": "admin", "update": "admin", "delete": "admin" },
      "fields": [
        { "name": "slug", "type": "text", "required": true, "unique": true },
        { "name": "body", "type": "text" } ] }
  ],
  "access": { "mode": "cloudflare", "cloudflare": { "tunnel": "quick" } },
  "components": [ { "id": "cloudflared" } ]
}
```

| Alan | Açıklama |
|---|---|
| `version` | Dosya biçiminin sürümü (güncel: 16). Yeni kurulumda ve sihirbazla oluşturulan dosyada her zaman güncel sürüm yazılır; aşağıdaki yükseltme adımları yalnızca eski uygulama sürümleriyle oluşturulmuş dosyalar içindir (`version` alanı olmayan dosya v1 sayılır). Eski dosya bellekte dönüştürülerek kullanılır ama diskte kendiliğinden yeniden yazılmaz; uygulama "Sürümü yükseltmek ister misiniz?" diye sorar (bkz. aşağıda). Daha yeni sürüm reddedilir |
| `auth.mode` | `none`, `api_key` (Bearer anahtar), `jwt` (HS256; token uygulamadan üretilir) |
| `auth.apiKeys`, `auth.jwtSecret` | Düz metin yazılabilir; kaydedilince şifreli depoya taşınır ve yerine `"secret:<ad>"` referansı konur |
| `database.engine` | `sqlite` (varsayılan, `database.file`) veya `postgres` (`database.url`, `database.user`, `database.password`) |
| `database.password` | `secret:<ad>` referansı; düz metin yazılırsa kaydedince şifreli depoya taşınır |
| `fields[].type` | `text`, `integer`, `real`, `boolean`, `datetime` (ISO-8601), `json`, `relation`, `file`, `select` (listeden bir değer), `multiselect` (seçenek listesi, örn. `["a","b"]`), `geo` (konum, `{"lat":41.01,"lng":28.97}`) |
| `fields[].options` | `select`/`multiselect`: seçenekler (en çok 200; `"` ve `\` içeremez) |
| `fields[].format`, `minLength`, `maxLength`, `pattern` | `text`: `email` veya `url` biçimi, karakter sayısı sınırı, düzenli ifade (tüm değer eşleşmeli). `multiselect`'te `minLength`/`maxLength` seçim sayısıdır |
| `history` | `{keep: 50}`: kayıt geçmişi (kayıt başına en çok `keep` sürüm; bkz. Geçmiş ve çöp kutusu) |
| `trash` | `{days: 30}`: çöp kutusu (silinen kayıt `days` gün saklanır) |
| `sync` | `{conflict: "lww"\|"manual", keepDays: 30}`: çevrimdışı istemci eşitlemesi (bkz. Çevrimdışı eşitleme) |
| `calendar` | `{title, start, end?, description?, location?, allDay?, recurrence?, noOverlap, resource?, feed, feedToken?, dav}`: kayıtlar etkinlik (bkz. Takvim ve kişiler) |
| `contacts` | `{name, email?, phone?, organization?, note?}`: kayıtlar kartvizit (CardDAV) |
| `fields[].min`, `max` | `integer`/`real`: değer aralığı |
| `fields[].computed` | `text`/`integer`/`real`/`boolean`: başka alanlardan hesaplanan değer (bkz. Alan kuralları ve hesaplanan alanlar) |
| `fields[].maxSizeMb`, `mimeTypes`, `multiple`, `thumbs` | `file`: dosya başına sınır (10 MB), kabul edilen tipler (örn. `["image/*", "application/pdf"]`; boşsa hepsi), çoklu dosya (en çok 20), ek küçük resim boyutları (örn. `["400x0"]`; `100x100` her zaman) |
| `fields[].convert`, `maxDimension`, `keepLocation`, `ai` | `file` (görseller): `webp`/`jpeg` dönüşümü, en büyük kenar (piksel), konum bilgisini koru (varsayılan silinir), YZ ile etiket ve metin `{tags, text}` (hedef alan adları) |
| `static` | `enabled`, `path` (`/`), `spaFallback` (true): `files/www/` altındaki siteyi yayınlar (bkz. Dosyalar ve statik site) |
| `backup` | `enabled`, `schedule` (cron, `0 4 * * *`), `keep` (7): zamanlanmış yedek (bkz. Yedekleme) |
| `adminPanel` | `enabled`, `sessionHours` (12): tarayıcıdan `/_admin` paneli (bkz. Web yönetim paneli) |
| `auth.passkeys` | `enabled`, `rpId` (alan adı, örn. `ev.duckdns.org`), `rpName`: passkey ile giriş (bkz. Passkey ile giriş) |
| `scripts` | `enabled`, `timeoutMs` (2000), `cronTimeoutSec` (60), `allowedHosts` (`ctx.http` için), `env` (`ctx.env`'e ortak `env`'in üstüne eklenir; `secret:` referansı olabilir) — bkz. Script'ler |
| `fields[].collection` | `relation` alanının hedef koleksiyonu (kendisi de olabilir: ağaç yapısı) |
| `fields[].onDelete` | `relation`: hedef kayıt silinince `cascade` (bunlar da silinir), `set_null` (alan boşalır), `restrict` (silme 409 ile reddedilir). Boşsa zorunlu alanda `restrict`, değilse `set_null` |
| `rules` | `list`, `view`, `create`, `update`, `delete` için `public` (herkes), `auth` (giriş yapmış kullanıcı), `owner` (kaydın sahibi), `team` (kaydın takımının üyeleri), `admin` (varsayılan), `nobody` (kapalı; yönetici de yapamaz). v5'teki `publicRead`/`readOnly` buna dönüşür |
| `fields[].index` | Benzersiz olmayan indeks: bu alana göre sık filtreleme/sıralama yapılıyorsa (ilişki ve benzersiz alanlar zaten indekslidir; dosya ve JSON alanı olamaz) |
| `search` | Koleksiyonda `?q=` ile aranacak metin alanları (en çok 10; bkz. Arama) |
| `fullText` | Tam metin dizini: `?q=` kelime başıyla, dizinden ve alaka sırasıyla (bkz. Arama) |
| `realtime` | `enabled`, `maxConnections` (100): canlı abonelik (bkz. Canlı abonelik) |
| `webhooks` | `[{name, url, collections, events, secret, enabled}]`: kayıt olaylarında imzalı POST (bkz. Webhook'lar) |
| `email` | `enabled`, `host`, `port` (587), `security` (`starttls`, `ssl`, `none`), `username`, `password` (şifreli depoda), `from`, `fromName`, `publicUrl` (e-postadaki bağlantıların başı), `templates` (hesap e-postası şablonları) — bkz. E-posta |
| `auth.users.sendVerification`, `requireVerified` | Kayıtta doğrulama e-postası (true); doğrulanmadan giriş yok (false; e-posta açık olmalı) |
| `ownerField` | Kaydın sahibini tutan, `_users`'a bağlı ilişki alanı; `owner` kuralı için gerekli (bkz. Kullanıcılar ve kurallar) |
| `teamField` | Kaydın takımını tutan, `_teams`'e bağlı ilişki alanı; `team` kuralı için gerekli (bkz. Takımlar) |
| `auth.users` | `enabled`, `allowSignup` (true), `extraFields` (ad, telefon gibi; ilişki olamaz), `publicFields` (başkalarına görünen ek alanlar), `minPasswordLength` (8), `accessTokenMinutes` (15), `refreshTokenDays` (30) |
| `auth.users.teams` | `enabled`, `allowCreate` (true), `maxMembers` (100), `maxTeamsPerUser` (20): takımlar (bkz. Takımlar) |
| `access.mode` | `local`, `direct`, `cloudflare`, `tailscale` (bkz. İnternetten erişim) |
| `access.direct` | `network` (`wifi`/`mobile`), `port` (HTTPS, varsayılan 8443), `portMapping` (modemde otomatik yönlendirme), `ddns` (`domain`, `token`: DuckDNS; `certificate`: bu ad için Let's Encrypt sertifikası) |
| `access.cloudflare` | `tunnel`: `quick` (hesapsız) veya `named` (token `cloudflared` bileşeninin `TUNNEL_TOKEN` ortamında), `hostname` (gösterim için) |
| `access.allowNoAuth` | İnternete açık modda kimlik doğrulamasız çalışmayı bilerek onaylar (yoksa deploy reddedilir) |
| `server.limits` | `maxBodyKb` (1024), `requestsPerMinute` (600, 0: sınırsız), `authFailures` (10), `banMinutes` (15) |
| `env` | Ortak ortam değişkenleri (`{"AD": "değer"}`; sır olanlar `secret:<ad>`): script'ler (`ctx.env`), uygulamalar ve `sharedEnv` açık bileşenler kullanır (bkz. Ortam ve sırlar) |
| `apps` | Kendi uygulamalarınız: `name`, `runtime`, `command`, `build`, `port`, `path`, `access`, `health`, `env`, `git` … (bkz. Kendi uygulamalarınız) |
| `components[].sharedEnv` | true: bileşene ortak ortam değişkenleri de verilir (kendi `env`'i önceliklidir) |
| `automations` | Kodsuz otomasyon kuralları: `name`, `trigger` (`collection` + `events` veya `schedule`), `condition`, `actions` (bkz. Otomasyon kuralları) |
| `forms` | Herkese açık formlar: `name` (adres `/forms/<name>`), `collection`, `title`, `fields`, `successMessage`, `redirect` … (bkz. Formlar) |
| `pages` | Kodsuz sayfalar: `name` (`/pages/<name>`), `collection`, `title`, `fields`, `detailFields`, `filter`, `sort`, `perPage`, `layout` (`cards`/`table`), `detail`, `login`, `accent` (bkz. Sayfalar ve panolar) |
| `dashboards` | Panolar: `name`, `title`, `widgets: [{type: number\|chart\|table\|map, view, metric, chart, field, limit, width}]`, `shareToken` |
| `payments` | Ödemeler: `providers` (`provider`: `stripe`/`iyzico`/`paddle`, `secretKey`, `apiKey`, `webhookSecret`, `clientToken` (`secret:`), `sandbox`, `enabled`), `publicUrl`, `plans` (`name`, `title`, `provider`, `price`, `role`, `quotas`), `statusField`, `merchant` (bkz. Ödemeler) |
| `ai` | Yapay zekâ: `enabled`, `provider` (`claude`/`openai`/`gemini`), `url`, `apiKey` (`secret:`), `model`, `effort`, `access`, `dailyLimit`, `embeddings`, `semantic` (bkz. Yapay zekâ) |
| `webdav` | Ağ sürücüsü: `enabled`, `path`, `username`, `password` (`secret:`), `readOnly`, `site`, `scripts`, `uploads`, `maxFileMb` (bkz. Ağ sürücüsü) |
| `mqtt` | MQTT (IoT): `enabled`, `port` (1883), `plain`, `tls`, `tlsPort` (8883), `username`, `password` (`secret:`), `routes` (`topic`, `collection`, `valueField`, `topicField`), `events`, `eventPrefix`, `maxClients`, `devices` (cihaz başına kimlik), `scenes`, `homeAssistant` (bkz. MQTT) |
| `sensors` | Sensör panosu: `collection`, `field`, `name`, `unit`, `deviceClass`, `min`, `max` (eşik uyarısı; bkz. MQTT → Ev otomasyonu) |
| `replication` | Canlı kopya: `source`, `keepDays` (7), `replicaOf` (`url`, `key`, `intervalSec`), `failover` (`auto`, `afterSec` (120), `witness`, `failback`), `formerPrimary` (bkz. Canlı kopya) |

Her tabloya otomatik `id`, `created_at`, `updated_at` eklenir. Migration veri silmez:
konfigürasyondan çıkarılan alan/tablolar yerinde kalır ve logda "kullanılmayan" olarak raporlanır.

Deploy öncesi şema planı (`SchemaMigrator.plan()`, veritabanına yazmaz) çıkarılır. Tekrar eden veri içeren
bir alana `unique` eklemek **engelleyicidir** ve deploy reddedilir. Mevcut kayıtlarda boş değer varken
`required` eklemek yalnızca uyarıdır (kural API katmanında yeni yazmalara uygulanır).

**Tip değişikliği:** kayıtlı değeri olan bir alanın tipi değişirse değerlere ne olacağını kullanıcı seçer;
seçim yoksa plan engelleyicidir. Alan tipleri `_ds_meta` tablosunda tutulur, böylece SQLite'ta aynı sütun
tipine düşen `text → json` gibi değişiklikler de yakalanır. Sütun boşsa seçim gerekmez.

```json
{ "name": "views", "type": "integer", "typeChange": { "from": "text", "action": "convert" } }
```

| `action` | Sonuç |
|---|---|
| `convert` | Değerler yeni tipe çevrilir; çevrilemeyenler boşalır. Plan kaç değerin boşalacağını örnekleriyle gösterir (`'abc'`). Kurallar: metin → sayı yalnızca kesin sayılar (`42`, `2.0`), → boolean `true/false/1/0`, → zaman ISO-8601 (saat dilimi yoksa UTC), → JSON geçerli JSON aynen, düz metin JSON metni olur. Sayı → zaman gibi anlamsız dönüşümler sunulmaz. |
| `clear` | Alandaki tüm değerler silinir; kayıtların diğer alanları korunur. |

Seçim yalnızca veritabanındaki tip `from` ise uygulanır; sonraki bir tip değişikliği yeniden seçim ister.
Dönüştürülen değerler `unique` alanda tekrar ediyorsa (`"1"` ve `"01"` → `1`) plan engelleyicidir. Sütun
yeni tiple yeniden kurulur (PostgreSQL: geçici sütun + `DROP/RENAME COLUMN`; Android 8'in SQLite'ında bu
komutlar olmadığından tablo yeniden kurulur, indeksler ve id sayacı korunur).

**Konfigürasyon sürümü:** konfigürasyona alan eklendiğinde veya çıkarıldığında `version` artar ve
`ConfigMigrations`'a bir dönüşüm adımıyla kullanıcıya gösterilecek açıklaması eklenir. Etkin dosya eski
biçimdeyse Durum ekranında "Konfigürasyon sürümü yükseltilsin mi?" kartı (değişikliklerin listesiyle; **Sürümü
yükselt** / **Şimdi değil**), Sunucu ekranında her zaman **Sürümü yükselt** düğmesi görünür. Yükseltilmeyen dosya
eski uygulama sürümleriyle uyumlu kalır; yükseltmede önceki hali Geçmiş'e yedeklenir. Sihirbazdan kaydetmek dosyayı
zaten güncel biçimde yazar (Özet adımında belirtilir).

| Adım | Değişiklik |
|---|---|
| v1 → v2 | API anahtarları ve JWT sırrı şifreli depoya taşınır; dosyada referansları kalır |
| v2 → v3 | İsteğe bağlı `typeChange` ve `database.createIfMissing`; mevcut ayarlar değişmez |
| v3 → v4 | Erişim ayarı `access` bölümüne taşınır (açık `cloudflared` → `cloudflare`, token varsa `named`); doğrudan IP (HTTPS) ve hızlı tünel seçenekleri; Tailscale bileşeni kaldırılır (Tailscale uygulaması kullanılır) |
| v4 → v5 | `relation` alan tipi (`collection`, `onDelete`) ve `server.limits`; mevcut ayarlar değişmez |
| v5 → v6 | `publicRead` / `readOnly` bayrakları `rules`'a dönüşür (davranış aynı kalır); kullanıcı hesapları (`auth.users`) ve `ownerField` |
| v6 → v7 | `file` alan tipi ve statik site (`static`); mevcut ayarlar değişmez |
| v7 → v8 | Script'ler (`scripts`); mevcut ayarlar değişmez |
| v8 → v9 | Zamanlanmış yedek (`backup`) ve web yönetim paneli (`adminPanel`); mevcut ayarlar değişmez |
| v9 → v10 | DuckDNS adı için Let's Encrypt sertifikası; mevcut ayarlar değişmez |
| v10 → v11 | Canlı abonelik, alan indeksleri ve arama, e-posta (doğrulama, parola sıfırlama); mevcut ayarlar değişmez |
| v11 → v12 | Webhook'lar, koşullu kurallar, kapsamlı API anahtarları, sosyal giriş, web push; mevcut ayarlar değişmez |
| v12 → v13 | `select`/`multiselect` tipleri, alan kısıtları, hesaplanan alanlar, geçmiş ve çöp kutusu, kotalar, iki adımlı doğrulama, tam metin dizini, bulut yedeği; Faz 13'ün denetim kaydı, güvenlik duvarı, sağlık uyarıları, yerel ağ keşfi ve canlı kopya bölümleri; mevcut ayarlar değişmez |
| v13 → v14 | Özel roller, şifreli alanlar (`encrypted`, `database.fieldKey`), saklama süresi, görünümler ve raporlar, WebSocket kanalları, telefon özellikleri; mevcut ayarlar değişmez (eski sürüm şifreli değeri düz metin sanacağı için sürüm artar) |
| v14 → v15 | Ortak ortam değişkenleri (`env`), kendi uygulamalar (`apps`, Git kaynağı dahil), `components[].sharedEnv`; mevcut ayarlar değişmez |
| v15 → v16 | Otomasyon kuralları (`automations`), herkese açık formlar (`forms`), GraphQL (`api.graphql`), yapay zekâ (`ai`); mevcut ayarlar değişmez |

**Sırlar:** API anahtarları ve JWT sırrı `server.json`'da düz metin durmaz. Uygulama kaydederken
(ve dosya sürümü yükseltilirken, geçmiş kopyaları dahil) değerleri Android Keystore anahtarıyla şifreleyip
`SecretStore`'a taşır, dosyaya `"secret:<ad>"` yazar; referanslar deploy anında çözülür. Anahtarı görmek
için sihirbazın Güvenlik adımını veya Durum ekranındaki **İstemci erişimi / token** düğmesini kullanın.
Bileşen ortam değişkenleri de referans tutabilir; sihirbaz tünel token'ını (`TUNNEL_TOKEN`) ve DuckDNS token'ını
(`access.direct.ddns.token`) böyle saklar.

### Hazır uygulamalar

Sihirbaz → Koleksiyonlar → **Hazır uygulama** (veya Profiller → Yeni profil → Başlangıç) tek dokunuşla çalışan bir
uygulama kurar: koleksiyonlar, ilişkiler, kurallar, isteğe bağlı örnek kayıtlar ve API'yi kullanan tek sayfalık web sitesi
(örnek veri ve site deploy'dan sonra kurulur).

| Şablon | Koleksiyonlar | Kim ne yapar |
|---|---|---|
| Blog | `categories`, `posts`, `comments` | Herkes yayındaki yazıları okur (tam metin arama), yorum bırakır; yorum `approved` olunca görünür |
| Mağaza ve sipariş | `products`, `orders` | Herkes satıştaki ürünleri görür; hesap açan müşteri sipariş verir, yalnızca kendi siparişlerini görür |
| Randevu | `services`, `appointments` | Herkes hesapsız randevu ister; talepleri yalnızca yönetici görür ve onaylar |
| Görev takibi | `projects`, `tasks` | Her kullanıcı yalnızca kendi projelerini ve görevlerini görür (geçmiş, çöp kutusu) |

Mevcut konfigürasyona eklerken aynı adda koleksiyon varsa eklenmez (yeni profil olarak kurun). Örnek kayıtlar yalnızca
boş koleksiyonlara eklenir; site seçilirse `files/www/index.html` yazılır (mevcut site varsa uyarılır).

### Paketler

Paket, hazır bir uygulamayı ya da eklentiyi tek dosyada taşır: koleksiyonlar, kurallar, görünümler, otomasyonlar,
formlar, sayfalar, panolar, script'ler ve örnek veri. Uygulamada Sunucu → **Paketler**, panelde Konfigürasyon →
Paketler, komut satırında `package install`. Kaynak: dosya (`.zip`), adres (`https://…/paket.zip`) veya GitHub deposu
(`sahip/depo`, dal; arşivdeki üst klasör atılır).

```
magaza/
  package.json        {"name":"magaza","version":"1.2.0","title":"Mağaza","publisher":"Örnek Ltd"}
  config.json         {"collections":[…],"views":[…],"automations":[…],"forms":[…],"pages":[…],"dashboards":[…]}
  scripts/magaza.js   (script'ler açılır)
  sample/products.json  [{"title":"Kalem"}, …]   (boş koleksiyonlara; "@ref:koleksiyon:sıra" ilişki)
  signature.json      (isteğe bağlı imza)
```

- **Kurmadan önce plan**: eklenecek, güncellenecek ve kaldırılacak tanımlar; açılacak özellikler (script'ler,
  kullanıcı hesapları); başka bir paketin ya da elle eklenmiş aynı adlı tanımla **çakışma** (kurulmaz); doğrulama
  hataları; eski sürüme dönüş ve yayımcı anahtarı değişikliği uyarısı. Kurulum konfigürasyonu kaydedip deploy eder;
  örnek veri deploy'dan sonra eklenir.
- **Güncelleme**: aynı adlı paketin yeni sürümü önceki sürümün getirdiklerinin yerine geçer (yeni sürümde olmayan
  görünüm, script… kaldırılır). **Kaldırma** paketin tanımlarını ve script'lerini çıkarır; koleksiyonların tabloları ve
  verileri veritabanında kalır (şema planı "kullanılmıyor" der). Kurulu paketler `server.json`'da `packages` altında.
- **İmza**: yayımcı ECDSA P-256 anahtarıyla paketin dosya özetlerini imzalar; imzalı pakette yayımcı adı ve anahtar
  parmak izi gösterilir, değiştirilmiş paket kurulmaz. İmzasız paket uyarıyla kurulur (yalnızca güvendiğiniz kaynaktan).

```bash
node droidserver.mjs package key -o yayimci.key                       # bir kez; gizli tutun
node droidserver.mjs package build magaza/ -o magaza-1.2.0.zip --key yayimci.key
node droidserver.mjs package plan magaza-1.2.0.zip
node droidserver.mjs package install magaza-1.2.0.zip
```

## Sihirbaz ve yönetim ekranları

İlk açılışta (konfigürasyon yokken) kısa bir **rehber** üç sayfada uygulamayı tanıtır ve başlangıcı sordurur: hazır bir
örnek proje (Blog, Mağaza, Randevu, Görev takibi; koleksiyonlar, kurallar, örnek kayıtlar ve site deploy'dan sonra kurulur)
veya boş sihirbaz. **Yardım** (Durum ekranındaki ? simgesi) bu README'nin bölümlerini çevrimdışı ve aranabilir gösterir
(derlemede uygulamaya girer; arayüz diline göre Türkçe veya İngilizce).

Sihirbaz yedi adımdır; adım geçerli değilse **İleri** kapalıdır ve hata ilgili alanın altında görünür
(`ConfigValidator.validateDetailed()` hataları `collections[0].fields[1].name` gibi alan yollarıyla döndürür).

1. **Genel:** sunucu adı, port (1024 altı engelli), API yolu; isteğe bağlı statik site (yol, bulunamayan sayfada `index.html`).
2. **Veritabanı:** SQLite (dosya adı) veya PostgreSQL (bileşen kurulumu ve veri taşıma bu adımda; bkz. PostgreSQL);
   zamanlanmış yedek (her gece / 6 saatte bir / haftalık veya cron, saklanacak sayı).
3. **Kullanıcılar:** son kullanıcı hesapları açık/kapalı, herkes kayıt olabilir mi, en kısa parola, ek alanlar
   (herkese görünür olup olmadığı dahil). Açarken kimlik doğrulama kapalıysa yönetici için API anahtarı üretilir.
4. **Koleksiyonlar:** hazır şablonlar (Yapılacaklar, Blog yazıları, Ürünler, Fotoğraflar, Boş); her koleksiyon için ad,
   **kim neyi yapabilir** (hazır kümeler: yalnızca yönetici / herkes okur / herkes okur, kullanıcılar ekler, sahibi
   düzenler / herkes yalnızca kendi kayıtlarını görür; ya da işlem başına kural) ve kaydın sahibi alanı ("owner alanı
   ekle"); alanlar sürükle-bırak sıralanır, tipe uygun varsayılan değer girilir. Dosya alanında boyut sınırı,
   kabul edilen dosyalar (her tür / görseller / görseller ve PDF / PDF / ses / video / özel), çoklu dosya ve küçük
   resim boyutları seçilir.
   Çalışan sunucudaki bir alanı silmek uyarıyla gösterilir; tipini değiştirmek kayıtlı değerler için
   **Dönüştür / Boşalt** seçimini ister (dönüştürmenin hangi değerleri çevireceği açıklanır).
5. **Güvenlik:** kimlik doğrulama modu; API anahtarı/JWT sırrı `SecureRandom` ile üretilir (Yeniden üret, Kopyala).
   Script'ler: açık/kapalı, süre sınırı, `ctx.http`'nin istek atabileceği adresler. Web yönetim paneli açık/kapalı.
   Herkesçe bilinen örnek anahtar (`degistir-beni-…`) kullanılıyorsa uyarı gösterilir; kullanıp kullanmamak
   kullanıcının kararıdır. CORS: kapalı / herkes / host listesi.
6. **Erişim:** yalnızca yerel ağ, internetten doğrudan (IP), Cloudflare Tunnel veya Tailscale; seçilen yola göre
   uygulamanın yaptıkları, kullanıcının yapması gereken adımlar ve olası sorunlar gösterilir (bkz. İnternetten erişim).
   İnternete açık modda kimlik doğrulama yoksa ya Güvenlik adımında seçilir ya da risk bilerek onaylanır.
7. **Özet ve deploy:** okunur özet, çalışan veritabanına karşı şema planı ("2 tablo oluşturulacak …");
   engelleyici sorun varsa deploy düğmesi kapalı ve nedeni yazılı. Deploy sonrası Loglar açılır.

**Sunucu** sekmesi etkin konfigürasyonun özetini gösterir. **Geçmiş** ekranında bir sürüm seçilirse şu ankiyle,
iki sürüm seçilirse birbirleriyle farkı görünür; **Bu sürüme dön** önce şema planını gösterir. **API gezgini**
her koleksiyon için gerçek anahtarla hazırlanmış `curl` örnekleri verir ve cihaz içinden deneme isteği atar.
Kullanıcı hesapları açıksa **Kullanıcılar** ekranı çalışan sunucunun hesaplarını listeler: e-postada arama, hesap
açma, parola sıfırlama (e-posta gönderilmez; yeni parolayı kullanıcıya siz iletirsiniz, tüm oturumları kapanır),
yönetici yapma, doğrulandı işareti ve silme. Statik site açıksa Sunucu ekranındaki **Statik site** kartından zip
yüklenir (eskisinin yerine geçer, yeniden deploy gerekmez) veya site kaldırılır. **Durum** ekranı depolama kullanımını
(veritabanı, yüklenen dosyalar, site, telefonda boş alan) gösterir. **Script'ler** ekranında `files/scripts/` altındaki
.js dosyaları yazılır (örnek script, sözdizimi denetimi, "Kaydet ve deploy et"). **Yedekler** ekranında yedek alınır,
dışa aktarılır, geri yüklenir ve yedeklerin otomatik kopyalanacağı klasör seçilir. Durum ekranında son 1 saatin istek
grafiği, pil durumu ve (açıksa) web paneli eşleştirme kodu görünür; pil 45 °C'yi aşarsa bildirim gelir.

## API

```bash
H='Authorization: Bearer en-az-16-karakterlik-anahtar'
BASE=http://192.168.1.50:8080/api

curl $BASE/health
curl -H "$H" -X POST $BASE/collections/todos/records \
     -H 'Content-Type: application/json' -d '{"title":"Süt al","tags":["market"]}'
curl -H "$H" "$BASE/collections/todos/records?done=false&sort=-created_at&page=1&perPage=20"
curl -g -H "$H" "$BASE/collections/todos/records?priority[gte]=3&title[like]=süt&due[null]=false&fields=title,due"
curl -H "$H" "$BASE/collections/posts/records?author=1&expand=author"
curl -H "$H" -X PATCH  $BASE/collections/todos/records/1 -H 'Content-Type: application/json' -d '{"done":true}'
curl -H "$H" -X DELETE $BASE/collections/todos/records/1
curl -H "$H" $BASE/_schema
```

**Filtreler** (`alan=değer` eşittir; `alan[op]=değer`; koşullar VE ile birleşir):

| op | Anlamı | Alan tipleri |
|---|---|---|
| `eq`, `ne` | eşit / eşit değil (`ne` boş değerleri de getirir) | json hariç hepsi |
| `gt`, `gte`, `lt`, `lte` | büyük / küçük | integer, real, datetime, `id`, `created_at`, `updated_at` |
| `like` | içerir, büyük/küçük harf duyarsız (`%` ve `_` harfiyen aranır; SQLite'ta yalnızca ASCII harflerde duyarsız) | text |
| `in` | virgülle ayrılmış değerlerden biri (en çok 100) | text, integer, real, datetime, `id` |
| `null` | `true`: boş, `false`: dolu | `id` hariç hepsi |
| `near` | `enlem,boylam,km`: bu uzaklıktakiler (en çok 1000 km); sıralama verilmezse yakından uzağa | geo |
| `box` | `güney,batı,kuzey,doğu`: bu kutudakiler | geo |

`select` alanı metin gibi `eq`, `ne`, `in`, `null` kullanır. `multiselect`'te `?tags=indirim` "bu seçeneği içerenler"
demektir (harf duyarlı); yalnızca `eq` ve `null` (`true`: hiç seçim yok) kullanılır. Özetlerde `groupBy=status` gibi
`select` alanına göre gruplanabilir.

**Konum alanı (`geo`):** değer `{"lat": 41.0082, "lng": 28.9784, "accuracy": 12}` (doğruluk metre, isteğe bağlı);
veritabanında `41.0082,28.9784,12` metni olarak durur (CSV'de de bu biçim). `?yer[near]=41.01,28.97,5` 5 km içindekileri
yakından uzağa, `?yer[box]=40.9,28.8,41.1,29.1` kutudakileri getirir; SQLite ve PostgreSQL aynı sonucu verir (PostGIS
gerekmez: önce enlem/boylam aralığı, sonra yaklaşık uzaklık; 1000 km'ye kadar hata binde birkaç). 180. boylamın iki
yanındaki noktalar da bulunur. Konuma göre `sort`, `eq` ve özet yapılmaz; alan benzersiz veya indeksli olamaz. Panelde
Kayıtlar → **Harita** konumlu kayıtları OpenStreetMap haritasında gösterir; kayıt düzenlerken **haritadan seç**,
**Telefonun konumu** (telefon özelliklerinde konum açıksa) veya **Bu cihazın konumu** ile doldurulur. Uygulamada Sunucu →
**Harita**. Formlarda `enlem,boylam` metin kutusu çıkar. SDK'larda `GeoPoint` tipi.

**Alan kuralları ve hesaplanan alanlar:** sihirbazda alanın "Kurallar ve hesaplama" bölümünden metin için biçim
(e-posta, web adresi), en az/en çok karakter ve düzenli ifade, sayı için en küçük/en büyük, çoklu seçim için en az/en
çok seçim girilir. Uymayan yazma `400` ve alan başına açıklama döner (`{"details":{"qty":"en çok 1000"}}`); OpenAPI
şeması (`enum`, `minLength`, `pattern`, `minimum`...) ve içe aktarma aynı kuralları kullanır. Hesaplanan alan, yazmada
formülden bulunur ve istemci yazamaz (`400`); güncellemede gönderilmeyen alanlar kaydın mevcut değeriyle hesaplanır:

```text
round(price * qty, 2)                 # sayı; boş girdi sonucu boş yapar, sıfıra bölme boş
upper(name) + " (" + (status ?? "-") + ")"
if(qty > 0, "stokta", "tükendi")
```

İşleçler `?? || && == != < <= > >= + - * / %` ve tekli `- !`; işlevler `round(x[, basamak])`, `floor`, `ceil`, `abs`,
`min`, `max`, `upper`, `lower` (Türkçe kurallarıyla), `trim`, `len`, `concat`, `coalesce`, `if(koşul, evet, hayır)`.
Formül en çok 500 karakter, döngü ve dış erişim yoktur; yalnızca aynı koleksiyonun (dosya ve JSON dışındaki) alanlarına
başvurur. Tam sayı alanında sonuç yuvarlanır.

**İlişkiler:** `relation` alanı hedef koleksiyondaki kaydın kimliğini tutar. Yazarken hedefin var olduğu aynı işlem
içinde denetlenir (yoksa `400`); hedef silinince `onDelete` uygulanır (`restrict` ihlalinde `409`, hiçbir şey silinmez;
`cascade` zincirleme ve döngüye dayanıklı). `?expand=author,editor` hedef kayıtları `expand` nesnesine gömer (tek seviye;
ilişki başına tek sorgu, kayıt sayısından bağımsız; hedefte olmayan kimlik `null`). İstekte görüntüleme
izni olmayan bir koleksiyona `expand` reddedilir (bkz. Kullanıcılar ve kurallar). İlişki alanında `eq`, `ne`, `in`, `null` filtreleri
kullanılır. Kurallar veritabanı yabancı anahtarıyla değil API katmanında uygulanır (bkz. PLAN Faz 4.2): sunucu
veritabanına yazan tek süreçtir; böylece `onDelete` ve tip değişiklikleri tablo yeniden kurmadan, veri taşıma da tablo
sırası gözetmeden çalışır. İlişki sütunlarına arama indeksi eklenir.

**Arama:** koleksiyonda `search` alanları seçiliyse (sihirbazda alanın "Aranabilir" kutusu) `?q=süt ekmek` bu alanlarda
arar: büyük/küçük harf, Türkçe karakter ve aksandan bağımsızdır (`ISTANBUL`, `istanbul`, `İstanbul` aynı; `sut` "Süt"ü
bulur), kelimelerin hepsi geçmelidir (en çok 10 kelime), diğer filtreler ve sıralamayla birlikte kullanılır. Değerlerin
aranabilir hali kayıt yazılırken gizli `_search` sütununa yazılır (yanıtta görünmez); arama alanları değişince, arama
alanının tipi değişince veya veri taşıma/geri yükleme sonrası hesaplanmamış kayıt kalınca deploy'da yeniden
hesaplanır (şema planında "arama dizini yeniden hesaplanacak"). SQLite ve PostgreSQL aynı sonucu verir. Arama alanı
seçilmemiş koleksiyonda `q` `400` döner.

**Tam metin dizini** (`fullText`, sihirbazda koleksiyonun "Tam metin dizini" anahtarı): arama metnindeki her kelime
`_search_terms` tablosunda dizinlenir; `?q=ist` kelimesi "ist" ile başlayan kayıtları bulur ("İstanbul" evet,
"Bistro" hayır), tablo taraması yapılmaz. `sort` verilmezse sonuçlar alakaya göre (aranan kelimelerin kayıtta geçme
sayısı) sıralanır. Dizin taşınabilir SQL'dir: SQLite ve PostgreSQL aynı sonucu verir. Sonradan açılınca deploy'da
mevcut kayıtlardan kurulur ("tam metin dizini kurulacak"), kapatılınca silinir.

**Önbellek ve sıkıştırma:** `GET` yanıtları içerik özetiyle (`ETag: W/"…"`) döner; istemci `If-None-Match` ile
sorarsa değişmemiş yanıt gövdesiz `304` olur (mobil veride tasarruf). 1 KB üstündeki JSON/HTML/JS/CSS yanıtları
`Accept-Encoding`'e göre gzip veya deflate ile sıkıştırılır; canlı abonelik ve dosyalar sıkıştırılmaz.

**İndeks:** `fields[].index` alanı indeksler; çok kayıtta o alana göre filtre ve sıralamayı hızlandırır. Şema planı
indeks ekleme ve kaldırmayı gösterir.

Tipe uymayan operatör veya değer `400` döner (örn. metinde `gt`, sayıda `like`, json alanında herhangi bir filtre).
Köşeli parantezsiz bilinmeyen parametreler yok sayılır. Zaman değerleri ISO-8601'dir; kesir hanesi farklı olsa da
doğru karşılaştırılır. `fields=title,done` yanıtta yalnızca bu alanları döndürür (`id` her zaman; tek kayıtta da
geçerli). curl'de köşeli parantezler için `-g` gerekir.

**Toplu işlem:** `POST /api/batch` en çok 100 oluştur/güncelle/sil isteğini tek veritabanı işleminde çalıştırır: biri
başarısız olursa hiçbiri yazılmaz. Her istek kendi koleksiyonunun kuralıyla denetlenir; script `before` olayları işlem
içinde, `after` olayları, webhook'lar ve canlı abonelik işlem tamamlandıktan sonra çalışır.

```bash
curl -H "$H" -X POST $BASE/batch -H 'Content-Type: application/json' -d '{"requests":[
  {"method":"POST",   "collection":"orders", "body":{"item":"çay","qty":2}},
  {"method":"PATCH",  "collection":"orders", "id":7, "body":{"paid":true}},
  {"method":"DELETE", "collection":"orders", "id":3}
]}'
# → {"results":[{"status":201,"body":{...}},{"status":200,"body":{...}},{"status":204}]}
# Hata: {"status":400,"message":"İstek 2: Doğrulama hatası","details":{"qty":"...","index":"1"}} (index sıfırdan)
```

Dosya alanı içeren koleksiyonlar (ve silinince zincirleme olarak dosyalı kayıtları silen koleksiyonlar) toplu işleme
alınmaz: diskteki dosyalar işlemle geri alınamaz; bunlar tek tek gönderilir.

**Özetler:** `GET /api/collections/{c}/aggregate` kayıtları saymak, toplamak ve gruplamak içindir; listeleme kuralı,
filtreler ve `q` araması aynen uygulanır (`owner` kuralında kullanıcı yalnızca kendi kayıtlarının özetini alır).

```bash
curl -g -H "$H" "$BASE/collections/orders/aggregate?sum=qty,price&avg=price&max=at&paid=true"
# → {"count":2,"sum_qty":5,"sum_price":20.0,"avg_price":10.0,"max_at":"2026-10-02T09:00:00Z"}
curl -H "$H" "$BASE/collections/orders/aggregate?groupBy=at:month&sum=qty"
# → {"groups":[{"key":"2026-10","count":3,"sum_qty":6},{"key":"2026-11","count":1,"sum_qty":6}],"truncated":false}
```

`count` her zaman döner. `sum` ve `avg` integer/real, `min` ve `max` ayrıca datetime ve text alanlarında çalışır (virgülle
birden çok alan). `groupBy` json ve dosya dışındaki bir alanı alır; datetime alanında `:day`, `:month` veya `:year`
zorunludur (UTC). Boş değerler `"key": null` grubunda toplanır; en çok 1000 grup döner (fazlası varsa `truncated: true`).

Hatalar tek biçimdedir: `{"status":400,"message":"Doğrulama hatası","details":{"title":"zorunlu alan"}}`.
Benzersiz alan çakışması `409`, bulunamayan kayıt `404` döner.

### İki adımlı doğrulama, e-postayla ve SMS ile giriş

`auth.users.twoFactor.enabled` açıksa kullanıcılar hesaplarına doğrulayıcı uygulama (Google Authenticator, Aegis,
1Password… RFC 6238, 6 hane, 30 sn) ekleyebilir. Açık olan kullanıcıda giriş iki adımdır:

```bash
POST /api/auth/2fa/setup                       # (kullanıcı token'ı) → {secret, uri}; uri = otpauth://… (QR veya doğrudan açma)
POST /api/auth/2fa/enable   {"code":"123456"}   # → {recoveryCodes:[10 kod]}; yalnızca bir kez gösterilir
POST /api/auth/login        {email, password}  # → {twoFactorRequired:true, challenge, expiresIn:300}
POST /api/auth/2fa/verify   {challenge, code}  # code: doğrulayıcı kodu veya yedek kod (tek kullanımlık) → token çifti
GET  /api/auth/2fa                             # {enabled, required, recoveryCodesLeft}
POST /api/auth/2fa/recovery-codes {code}       # yeni yedek kodlar;  POST /api/auth/2fa/disable {code} kapatır
```

Aynı doğrulayıcı kodu ikinci kez geçmez; hatalı kodlar girişle aynı deneme sınırına tabidir. Gizli anahtar veritabanında
(`_two_factor`) AES-GCM ile şifreli, yedek kodların yalnızca özetleri durur. `required: true` iken kurmamış kullanıcı
giriş yapar (`twoFactorSetupRequired: true`) ama kurana kadar yalnızca hesap uçlarını kullanabilir (yönetici rolü dahil)
ve kurduktan sonra kapatamaz. Telefonunu kaybeden kullanıcı için uygulamadaki Kullanıcılar ekranında **2FA sıfırla**
(veya `PATCH /auth/users/{id} {"twoFactor": false}`). Sosyal giriş ve e-posta koduyla giriş de ikinci adımı ister.

**E-postayla kodla giriş** (`auth.users.emailLogin`, e-posta ayarı gerekir): `POST /auth/request-login-code {email}`
8 haneli, 10 dakika geçerli, tek kullanımlık kodu e-postayla yollar (yanıt hesap olsa da olmasa da aynı);
`POST /auth/login-code {email, code}` ile girilir ve e-posta doğrulanmış sayılır. `emailLoginUrl` verilirse e-postaya
istemcinizin sayfasına `?email=…&code=…` bağlantısı da eklenir.

**SMS ile giriş** (`auth.users.smsLogin`; Sihirbaz → Kullanıcılar → SMS ile giriş): kullanıcı telefon numarasıyla
parolasız girer. Kod sunucu telefonunun kendi SIM'inden SMS olarak gider (operatör ücreti size yansır), bu yüzden
Telefon özellikleri → **SMS gönderme** açık olmalı; izinli ülke kodları ve günlük SMS sınırı oradan geçerlidir.

```bash
POST /api/auth/request-sms-code {"phone":"+905551234567"}   # 202; hesap varsa 6 haneli kod SMS ile gider (10 dk)
POST /api/auth/sms-code         {"phone":"+905551234567","code":"123456"}   # → token çifti (2FA açıksa ikinci adım)
```

- Numara kullanıcının `phoneField` ek alanında (varsayılan `phone`; metin ve **benzersiz** olmalı) durur. Sihirbazda
  açınca alan yoksa eklenir. Kayıtta ve güncellemede boşluk, tire ve parantez atılır, uluslararası biçim (`+90…`) zorunlu.
- Yanıt, numara kayıtlı olsa da olmasa da aynıdır; kayıtlı olmayana SMS gitmez.
- Kötüye kullanıma karşı: aynı numaraya dakikada bir ve günde en çok 5 kod, aynı istemciden saatte en çok 10 istek;
  hatalı kod girişle aynı deneme sınırına tabidir, kod en çok 5 denemede ve 10 dakikada geçersizleşir.
- Kullanıcının numarayı kendisinin değiştirmesini istemiyorsanız alanın görünürlüğünü **Yalnızca yönetici** yapın
  (yönetici olmayanlar o alanı yazamaz); numaraları uygulamadaki Kullanıcılar ekranından siz girersiniz.
- İstemci SDK'larında `auth.requestSmsCode(phone)` ve `auth.loginWithSms(phone, code)`.

**Web paneli:** Güvenlik adımında "Panelde doğrulayıcı kodu da iste" açılırsa panel girişi eşleştirme koduna ek olarak
doğrulayıcı kodu ister; anahtar telefonda üretilir, şifreli depoda durur ("Doğrulayıcı uygulamaya ekle" veya kopyala).

### Passkey ile giriş

Sihirbaz → Güvenlik → **Passkey ile giriş** (`auth.passkeys`): parola yerine telefonun veya bilgisayarın kilidiyle
(parmak izi, yüz, PIN) ya da bir güvenlik anahtarıyla giriş. Kullanıcılar hesaplarına, siz web paneline passkey eklersiniz.

- **Alan adı gerekir:** WebAuthn bir alan adına (`rpId`) bağlıdır ve yalnızca HTTPS'te çalışır; **yerel IP ile çalışmaz.**
  DuckDNS adı (doğrudan erişim + Let's Encrypt) veya adlandırılmış Cloudflare tüneli kullanın; sihirbaz varsa onu önerir.
  Hızlı tünelin adresi her başlatmada değiştiği için uygun değildir. Ad değişirse eklenen passkey'ler çalışmaz.
- Kullanıcı birden çok passkey ekleyebilir (en çok 10), listeler ve siler. Passkey girişi iki adımlı doğrulamanın ikinci
  adımını istemez (cihaz + kilit ekranı zaten iki etkendir). Giriş e-posta sormaz: tarayıcı cihazdaki passkey'i önerir,
  böylece bir e-postanın hesabı olup olmadığı yanıttan anlaşılamaz.
- ES256 ve RS256 desteklenir (Android, iOS, Windows Hello, YubiKey). İmza sayacı gerilerse anahtar kopyalanmış
  sayılır ve giriş reddedilir.
- **Web paneli:** panele eşleştirme koduyla girip Konfigürasyon sekmesindeki **Panel passkey'leri**'nden bu tarayıcıyı ekleyin;
  sonra giriş ekranında **Passkey ile giriş**. Panel passkey'leri kullanıcı hesaplarından ayrıdır.

```bash
POST /api/auth/passkeys/register-options        # (kullanıcı token'ı) → navigator.credentials.create seçenekleri
POST /api/auth/passkeys {name, credential}      # tarayıcının yanıtı (ikili değerler base64url) → {id, name, createdAt}
GET  /api/auth/passkeys    ·    DELETE /api/auth/passkeys/{id}
POST /api/auth/passkeys/login-options            # → navigator.credentials.get seçenekleri
POST /api/auth/passkeys/login {credential}       # → token çifti
```

Tarayıcıda SDK ile:

```ts
await ds.auth.login(email, parola);
await ds.auth.registerPasskey("telefon");      // cihaz kilidi sorulur
// sonraki girişlerde:
await ds.auth.loginWithPasskey();
```

```json
"auth": { "passkeys": { "enabled": true, "rpId": "ev.duckdns.org" } }
```

### Kotalar

`auth.users.quotas` ile (sihirbazda Kullanıcılar → Kotalar) her kullanıcıya üst sınır konur; boş bırakılan sınırsızdır,
yönetici rolündeki kullanıcılar ve API anahtarları muaftır:

| Ayar | Anlamı | Aşılınca |
|---|---|---|
| `records` | Koleksiyon başına en çok kayıt (sahip alanı olan koleksiyonlarda, kullanıcının sahibi olduğu kayıtlar) | `403` |
| `collections` | Koleksiyona özel kayıt sınırı, örn. `{"photos": 100}` | `403` |
| `storageMb` | Sahibi olduğu kayıtlardaki dosyaların toplamı (küçük resimler hariç) | `413` |
| `dailyRequests` | UTC günü başına, kullanıcı token'ıyla yapılan API isteği (sayaç bellekte; uygulama yeniden açılınca sıfırlanır) | `429` |

Hata gövdesinde `details.quota` (`records`, `storage`, `requests`) ve `details.limit` bulunur. Kullanıcı kendi
kullanımını görür; uygulamadaki Kullanıcılar ekranı da her kullanıcının kartında gösterir:

```bash
curl -H "Authorization: Bearer <kullanıcı token'ı>" http://<telefon>:8080/api/auth/me/usage
# → {"records":{"notes":{"used":2,"limit":50}},"storage":{"usedBytes":734003,"limitBytes":10485760},
#    "requests":{"today":12,"limit":1000,"resetsAt":"2026-10-15T00:00:00Z"}}
```

Sınırlar yazmadan hemen önce sayılarak denetlenir; aynı anda gelen isteklerde küçük bir aşım olabilir.

### Geçmiş ve çöp kutusu

Koleksiyonda (sihirbazda "Geçmiş ve çöp kutusu") **kayıt geçmişi** açıksa her oluşturma, güncelleme, silme ve geri
yüklemede kaydın o anki tam hali, kimin yaptığı (`user:5`, `key:ad`, `admin`; script ve içe aktarmada boş) ve zamanı
saklanır; kayıt başına en çok `keep` sürüm kalır. **Çöp kutusu** açıksa silinen kayıt (cascade ile silinen bağlı
kayıtlar da, o koleksiyonda açıksa) dosyalarıyla `days` gün saklanır, sonra (açılışta ve saatte bir) kalıcı silinir.

```bash
GET    /api/collections/notes/records/5/history                  # {items:[{version, action, at, actor, data}], totalItems}
POST   /api/collections/notes/records/5/history/3/revert         # 3. sürümün değerlerine dön (yeni sürüm olarak)
POST   /api/collections/notes/records/5/history/3/revert?fields=title   # yalnızca title alanı
GET    /api/collections/notes/trash                              # {items:[{id, deletedAt, expiresAt, actor, data}], totalItems}
POST   /api/collections/notes/trash/5/restore                    # aynı kimlikle geri yükle
DELETE /api/collections/notes/trash/5                            # dosyalarıyla kalıcı sil
```

Bu uçlar kayıtların gizli alanları dahil tam halini içerdiğinden kurallardan bağımsız olarak yalnızca yönetici (ve o
koleksiyonda yetkili adlandırılmış anahtar; sürüme dönme ve geri yükleme için yazma yetkisi) kullanabilir; web
panelinde kayıt satırındaki **Geçmiş** (sürümün tamamı veya **Tek alan…** ile bir alan) ve koleksiyondaki **Çöp kutusu**
düğmeleriyle açılır. Sürüme dönmede dosya ve
hesaplanan alanlar değişmez. Geri yüklenen kayıt bugünkü kurallarla doğrulanır: bağlandığı kayıt silinmişse veya
sonradan zorunlu alan eklendiyse `400` (önce bağlandığı kaydı geri yükleyin). Silmede `set_null` ile boşaltılan
ilişkiler geri yüklemede geri bağlanmaz. Geçmiş ve çöp kutusu `_history` ve `_trash` tablolarındadır; yedeğe ve veri
taşımaya dahildir, özellik kapatılınca silinmez.

### Saklama süresi

Koleksiyonda **Saklama süresi** (`retention`): belirtilen günden eski kayıtlar saatte bir otomatik silinir. Yaş
varsayılan olarak `created_at`'e göre ölçülür; `updated_at` veya bir tarih-saat alanı (örn. `due`) seçilebilir, isteğe
bağlı koşulla yalnızca bazıları silinir:

```json
{ "name": "tasks", "retention": { "days": 30, "field": "updated_at", "filter": "status=done" }, "trash": { "days": 7 } }
```

Silme normal silme gibidir: ilişkilerin "silinince" ayarı ve dosyalar uygulanır, çöp kutusu açıksa kayıt oraya gider.
Başka kayıt bağlı olduğu için (`restrict`) silinemeyen atlanır ve Loglar'a yazılır. Otomatik silme webhook ve canlı
abonelik olayı üretmez.

### Görünümler ve raporlar

Koleksiyonu ve kurallarını değiştirmeden salt okunur bir liste veya özet yayınlayın (Sihirbaz → Koleksiyonlar →
**Görünümler ve raporlar**). Görünüm koleksiyonun seçilen sütunlarını, ilişki üzerinden bir adım ötesini (`customer.name`),
sabit bir koşulu ve sıralamayı içerir; rapor görünümü kayıtlar yerine sayı, toplam ve ortalamaları (isteğe bağlı gruplu)
döndürür. Kimin görebileceği görünümde seçilir: herkes, giriş yapmış kullanıcılar veya yönetici (+ seçilen roller).

```json
"views": [
  { "name": "paid", "collection": "orders", "columns": ["id", "total", "customer.name"], "filter": "status=paid", "sort": "-total", "access": "public" },
  { "name": "revenue", "collection": "orders", "report": { "groupBy": "created_at:month", "sum": ["total"] }, "access": "admin", "roles": ["accountant"] },
  { "name": "mine", "collection": "orders", "columns": ["id", "total", "status"], "filter": "owner=@me", "access": "auth" }
]
```

```bash
curl http://<telefon>:8080/api/views/paid                 # {items, page, perPage, totalItems}
curl "http://<telefon>:8080/api/views/paid?total[gt]=100" # görünümdeki sütunlarla ek filtre
curl -O "http://<telefon>:8080/api/views/paid?format=csv" # Excel uyumlu CSV
curl http://<telefon>:8080/api/views                      # görebildiğiniz görünümler
```

Yönetici dışına açık görünümde yöneticiye/sahibine özel alanlar ve kullanıcıların e-postası seçilemez. Web panelinde
**Görünümler** sekmesi ilk 100 satırı gösterir ve CSV indirir.

### Kullanıcılar ve kurallar

`auth.users.enabled` açıkken uygulamanızın kullanıcıları hesap açıp giriş yapar; API anahtarı / yönetici JWT'si
**yönetici erişimi** olarak kalır (kullanıcı hesapları yönetici kimliği ister: `auth.mode` `none` olamaz).

```bash
curl -X POST $BASE/auth/signup -H 'Content-Type: application/json' -d '{"email":"ali@example.com","password":"gizli-parola","name":"Ali"}'
# → 201 {"token":"<erişim, 15 dk>","refreshToken":"<30 gün>","expiresIn":900,"user":{"id":1,"email":"ali@example.com",...}}
curl -X POST $BASE/auth/login   -H 'Content-Type: application/json' -d '{"email":"ali@example.com","password":"gizli-parola"}'
curl -X POST $BASE/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'   # yeni çift; eskisi geçersiz
curl -X POST $BASE/auth/logout  -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'   # 204
curl -H "Authorization: Bearer <token>" $BASE/auth/me
curl -H "Authorization: Bearer <token>" -X PATCH $BASE/auth/me -d '{"name":"Ali V.","password":"yeni","currentPassword":"gizli-parola"}'
curl -H "$H" "$BASE/auth/users?email=ali"                       # yönetici: liste, POST ekle, PATCH /{id} (rol, parola), DELETE /{id}
```

- Kayıtlar sistem koleksiyonu `_users`'ta durur: `email` (küçük harfe çevrilir, benzersiz), `password_hash`, `verified`,
  `role` (`user` / `admin`) ve ek alanlar. Yanıtlarda parola özeti hiç yer almaz.
- **Parola:** PBKDF2-HMAC-SHA256, 310.000 tur, rastgele 16 bayt tuz, sabit zamanlı karşılaştırma; biçim
  `pbkdf2$<tur>$<tuz>$<özet>` (tur sayısı özetle saklandığı için ileride artırılabilir). Olmayan e-postada da özet
  hesaplanır (yanıt süresi hesabın varlığını belli etmez).
- **Token'lar:** erişim token'ı HS256 JWT'dir; imza anahtarı veritabanında rastgele üretilir (yönetici JWT sırrından
  ayrı, kullanıcı token'ı yönetici yerine geçemez). Her istekte kullanıcı veritabanından okunur: silinen kullanıcının
  token'ı hemen geçersizleşir, rol değişikliği hemen geçerlidir. Yenileme token'ı rastgele 256 bittir, `_sessions`'ta
  yalnızca SHA-256 özeti saklanır ve **tek kullanımlıktır** (her yenilemede yenisi verilir; oturum aynı kalır). Kullanıcı
  parolasını değiştirince diğer oturumları kapanır (değiştiren cihaz girişli kalır); yönetici sıfırlayınca tüm oturumları.
- **Oturumlar:** `GET /api/auth/sessions` açık oturumları listeler (User-Agent'tan cihaz, adres, son kullanım, `current`);
  `DELETE /api/auth/sessions/{id}` birini, `DELETE /api/auth/sessions` diğerlerinin hepsini kapatır. Kapatılan oturumun
  erişim token'ı da süresini beklemeden hemen geçersizleşir. Yönetici `GET`/`DELETE /api/auth/users/{id}/sessions` ile,
  uygulamada Kullanıcılar → **Oturumlar**'da ve panelin Kullanıcılar sekmesinde görür ve kapatır. SDK: `auth.sessions()`,
  `auth.closeSession(id)`, `auth.closeOtherSessions()`.
- **Giriş sınırı:** aynı istemciden aynı e-postaya 5, toplamda 20 hatalı denemeden sonra 15 dakika `429` (doğru parola da
  beklemelidir); ayrıca genel `authFailures` engeli geçerlidir.
- E-posta doğrulama ve e-postayla parola sıfırlama yok (SMTP gerekir); yönetici uygulamadan sıfırlar.

**Kurallar** (`rules`, koleksiyon başına; varsayılan hepsi `admin`):

| Kural | Kim |
|---|---|
| `public` | Herkes, token'sız da |
| `auth` | Giriş yapmış her kullanıcı |
| `owner` | Kaydın sahibi (`ownerField` = istekteki kullanıcı); listede yalnızca kendi kayıtları, başkasının kaydı `404`. Oluşturmada `auth` ile aynı |
| `admin` | API anahtarı, yönetici JWT'si veya rolü `admin` olan kullanıcı |
| `nobody` | Kimse (yönetici de); örn. salt okunur koleksiyon için yazma işlemleri |

Yönetici `nobody` dışındaki her kuralı aşar. İzin yokken token'sız istek `401`, giriş yapmış ama yetkisiz kullanıcı
`403` alır; geçersiz veya süresi dolmuş token anonim sayılır. Yönetici olmayan isteklerde `ownerField` istemciden
alınmaz: yeni kayıtta istekteki kullanıcıyla doldurulur (anonimse boş), sonradan değiştirilemez (`403`); yönetici
serbestçe yazar. `expand` hedef koleksiyonun `view` kuralına uyar (`owner` hedefte yalnızca kendi kayıtları gömülür);
`_users`'a bağlı ilişkiler genişletilince yalnızca `id` ve `publicFields` görünür. `auth.mode` `none` iken herkes
yönetici sayılır ve kurallar uygulanmaz (eski davranış).

**Kötüye kullanıma karşı** (`server.limits`, istemci adresine göre; telefonun kendisinden gelenler sınırlanmaz):
dakikada `requestsPerMinute`'tan fazla istek `429` + `Retry-After`; art arda `authFailures` hatalı kimlik
denemesi (`401`) yapan adres `banMinutes` dakika `429` alır; `maxBodyKb`'yi aşan gövde (uzunluğu bildirilmeyen
dahil) `413`. Gerçek istemci adresi HTTPS ucundan ve cloudflared'dan gelen `X-Forwarded-For` / `CF-Connecting-IP`
başlığından okunur; bu başlıklara yalnızca telefonun içinden gelen bağlantıda güvenilir. Düz HTTP portuna
internetten (genel IPv4/IPv6 adresinden; ev ağının IPv6 öneki hariç) gelen istekler `403` alır: internete açılan uç
HTTPS ucu veya tüneldir. Tüm yanıtlarda `X-Content-Type-Options: nosniff`, API yanıtlarında `Cache-Control: no-store`.

**İçe / dışa aktarma:** `GET /api/collections/{c}/export?format=csv|json|zip` koleksiyonu dosya olarak indirir (liste
kuralı, filtreler, `q` ve `sort` geçerli; gizli alanlar hariç; en çok 100 000 kayıt). `delimiter=semicolon` Türkçe Excel'in
beklediği noktalı virgüllü CSV'dir; CSV UTF-8 BOM ile başlar ve formül gibi başlayan metinlerin (`=`, `+`, `-`, `@`)
başına `'` eklenir (elektronik tabloda çalışmasınlar diye; içe aktarırken geri alınır).

```bash
curl -g -H "$H" "$BASE/collections/products/export?format=csv&delimiter=semicolon&active=true" -o urunler.csv
curl -H "$H" -X POST "$BASE/collections/products/import?dryRun=true" -H 'Content-Type: text/csv' --data-binary @urunler.csv
# → {"columns":["id","name",...],"mapping":{"id":"id","name":"name",...},"total":120,"created":120,"updated":0,"errors":[],"sample":[...],"dryRun":true,"applied":false}
curl -H "$H" -X POST "$BASE/collections/products/import?mode=upsert" -H 'Content-Type: text/csv' --data-binary @urunler.csv
```

İçe aktarma yalnızca yönetici içindir ve tek işlemdir: bir satır bile hatalıysa hiçbiri yazılmaz (`400`, hatalı satırlar
`errors`'ta, en çok 100). `dryRun=true` aynı denetimleri (benzersizlik ve ilişkiler dahil) yapıp geri alır. CSV'de ayırıcı
başlıktan anlaşılır; `12,5` gibi virgüllü ondalık, `evet/hayır`, `2026-10-14` ve `2026-10-14 09:30` (UTC) kabul edilir; boş
hücre alanı göndermez (yeni kayıtta varsayılan değer, güncellemede değişmez). Sütun adları alan adlarıyla eşleşir
(büyük/küçük harf duyarsız) ya da `mapping={"Ürün adı":"name","Not":null}` ile eşlenir. `mode=upsert`'te `id`'si olan kayıt
güncellenir, olmayan eklenir (dışa aktarılan dosyayı düzenleyip geri yüklemek için). JSON'da kayıt dizisi gönderilir.
İçe aktarılan kayıtlar webhook, canlı abonelik ve script olayı üretmez. En çok 10 000 satır ve 20 MB (genel gövde
sınırından bağımsız). Uygulamada Sunucu → İçe / dışa aktarma (önizleme ve sütun eşleme), web panelinde kayıt tablosunun
üstünde.

Dosya alanları **zip** ile taşınır: `format=zip` dışa aktarmada `data.json` ve kayıtların dosyaları (`files/<id>/<ad>`)
gelir; aynı zip (ya da `data.csv` içeren bir zip) `import?format=zip` ile `Content-Type: application/zip` gövdesi olarak geri
yüklenir. Satırdaki dosya adı, satırın `id`'siyle zip'teki dosyaya bağlanır ve normal yükleme gibi denetlenir (boyut, içerikten
tespit edilen tip, izinli tipler); dosyalar yeni adla kaydedilir, güncellenen kaydın o alandaki eski dosyaları zip'tekilerle
değişir ve ancak işlem kalıcı olunca silinir. Deneme veya hata nedeniyle geri alınan aktarmada taşınan dosyalar da silinir.
Zip en çok 512 MB (sıkıştırılmış ve açılmış). CSV/JSON içe aktarmada dosya alanı eşlenemez.

```bash
curl -H "$H" "$BASE/collections/notes/export?format=zip" -o notlar.zip
curl -H "$H" -X POST "$BASE/collections/notes/import?format=zip&dryRun=true" -H 'Content-Type: application/zip' --data-binary @notlar.zip
curl -H "$H" "$BASE/collections/notes/import/template?delimiter=semicolon" -o notlar-sablon.csv
```

**Şablon** (`GET /api/collections/{c}/import/template`, yönetici) yazılabilir alanların başlıklarını ve kurallara uyan bir
örnek satırı verir; başlıktaki parantez ipucudur (`durum (zorunlu; açık|bitti)`, `etiketler (ev|iş; birden çok: virgülle)`,
`proje (projects kimliği)`) ve içe aktarmada yok sayılır, yani şablon doldurulup doğrudan yüklenebilir. Web panelinde
**Şablon** düğmesi.

**Toplu düzenleme ve silme:** web panelinde kayıt tablosunda satırları (veya başlıktaki kutuyla sayfanın hepsini) seçip
**Alan değiştir…** (seçilen alana yeni değer; boş bırakılırsa alan temizlenir) ya da **Seçilenleri sil**. Dosya alanı
olmayan koleksiyonda tek `/batch` isteğidir (hepsi ya da hiçbiri, her kayıt kendi kuralıyla); dosya alanlı koleksiyonda
`/batch` kullanılamadığından kayıtlar tek tek gönderilir.

**Koşullu kurallar:** bir kurala koşul eklenince (Sihirbaz → Koleksiyon → Kim neyi yapabilir? → "koşul") kural izin
verse de yalnızca koşula uyan kayıtlar erişilir. Koşul liste filtresi sözdizimidir, `&` ile VE (`like` hariç):

```json
"rules": {
  "list": "public", "view": "public", "create": "auth", "update": "owner", "delete": "owner",
  "conditions": {
    "list":   {"filter": "status=published", "owner": true},
    "view":   {"filter": "status=published", "owner": true},
    "create": {"filter": "status=draft"},
    "update": {"filter": "status=draft"}
  }
}
```

Bu blogda herkes yayınlananları, yazar (`"owner": true`, sahip istisnası) kendi taslaklarını da görür; kullanıcı yalnızca
taslak ekler ve yalnızca taslağı düzenler (yayınlamak için `status` taslakken değiştirilir). Listede ve özetlerde koşula
uymayan kayıt görünmez, tek kayıtta ve güncelleme/silmede `404`; oluşturmada gönderilen kayıt uymazsa `403` ve hiçbir şey
yazılmaz. Kullanıcıya bağlı ilişki alanında `@me` istekteki kullanıcıdır: `{"filter": "assignee=@me"}` ile herkes yalnızca
kendine atanan görevleri görür. Koşullar `expand`, canlı abonelik ve toplu işlemde de uygulanır; yönetici koşula bağlı
değildir. Koşul yalnızca "Herkes", "Giriş yapmış kullanıcılar" ve "Kaydın sahibi" kurallarında kullanılır.

**Alan gizliliği:** alanın "Kim görür" seçeneği (`fields[].visibility`) `owner` ise alan yalnızca kaydın sahibine ve
yöneticiye, `admin` ise yalnızca yöneticiye döner (diğerlerinin yanıtında, `expand`'de ve canlı abonelikte alan yoktur).
Yönetici olmayanlar gizli alana göre filtreleyemez, sıralayamaz, özetleyemez, genişletemez (`400`; sahibine görünen
alanda "Kaydın sahibi" kuralıyla yalnızca kendi kayıtları kapsandığında serbest); gizli alan aranabilir olamaz.
`admin` alanını yalnızca yönetici yazar (örn. moderasyon notu, iç puan). Script'ler, webhook'lar ve yönetici kayıtların
tamamını görür.

### Özel roller

Kullanıcı (`user`) ve yönetici (`admin`) dışında kendi rollerinizi tanımlayın: Sihirbaz → Kullanıcılar → **Roller**
(`auth.users.roles`). Rolü Sunucu → Kullanıcılar'da (veya panelde, ya da `PATCH /auth/users/{id} {"role":"editor"}`
ile) atarsınız; ne yapabileceğini koleksiyonun kurallarında işlem başına seçersiniz (**Ayrıca:** editör):

```json
"auth": { "users": { "enabled": true, "roles": [{ "name": "editor", "title": "Editör" }, { "name": "moderator" }] } },
"collections": [{ "name": "posts", "ownerField": "author",
  "rules": { "list": "public", "view": "public", "create": "auth", "update": "owner", "delete": "admin",
             "roles": { "update": ["editor"], "delete": ["editor", "moderator"] } } }]
```

Burada yazar kendi yazısını düzenler; editör hepsini düzenler ve siler, moderatör siler. Rol izni o işlemi tüm
kayıtlarda koşulsuz verir ama yönetici yetkisi değildir: yöneticiye özel alanlar gizli kalır, "Kapalı" işlem yine
kapalıdır. Script uçlarında `routerAdd("GET", "/rapor", fn, "role:editor")` (yönetici de çağırabilir); işleyicide
`ctx.auth.role`.

### Takımlar

Aile, ekip veya şube gibi gruplar için: Sihirbaz → Kullanıcılar → **Takımlar** (`auth.users.teams`). Kullanıcılar takım
kurar (kuran **sahip** olur; `allowCreate: false` ise yalnızca yönetici kurar), sahip e-postayla üye ekler. Koleksiyonda
**Takım üyeleri görür ve düzenler** hazır kuralı seçilince bir `team` ilişki alanı eklenir (`teamField`) ve `team` kuralı
uygulanır:

- Kullanıcı yalnızca üyesi olduğu takımların kayıtlarını listeler, görür, değiştirir, siler; başka takımın kaydı 404.
- Yeni kayıt yalnızca kullanıcının takımına eklenir; tek takımı varsa alan kendiliğinden dolar, birden çok takımı varsa
  `team` gönderilmeli. Kayıt üyesi olunmayan takıma taşınamaz.
- Takımdan çıkan kullanıcı o takımın kayıtlarını hemen göremez (üyelik her istekte okunur).
- Takım silinince takım alanının silme davranışına göre kayıtları silinir (`cascade`), boşalır veya silme reddedilir.

```json
"auth": { "users": { "enabled": true, "teams": { "enabled": true, "allowCreate": true, "maxMembers": 100, "maxTeamsPerUser": 20 } } },
"collections": [{ "name": "tasks", "teamField": "team",
  "fields": [{ "name": "title", "type": "text" }, { "name": "team", "type": "relation", "collection": "_teams", "onDelete": "cascade" }],
  "rules": { "list": "team", "view": "team", "create": "team", "update": "team", "delete": "team" } }]
```

```bash
curl -X POST -H "Authorization: Bearer <token>" -d '{"name":"Ev"}' http://telefon:8080/api/teams        # kur
curl -X POST -H "Authorization: Bearer <token>" -d '{"email":"ayse@example.com"}' .../api/teams/1/members  # üye ekle
curl -H "Authorization: Bearer <token>" http://telefon:8080/api/teams/1                                 # üyeler
curl -X DELETE -H "Authorization: Bearer <token>" .../api/teams/1/members/<kendi id>                     # ayrıl
```

Takım rolleri `owner` (üye ekler/çıkarır, rol verir, adı değiştirir, siler) ve `member`; takımın son sahibi ayrılamaz.
Üyeler birbirinin e-postasını görür. SDK'da `ds.teams.create("Ev")`, `ds.teams.addMember(id, { email })`. Uygulamada
Sunucu → **Takımlar** tüm takımları yönetici olarak gösterir. `team` kuralına koşul da eklenebilir (`status=open`).

### Şifreli alanlar

Metin ve JSON alanlarında **Şifreli sakla** (`"encrypted": true`): değer veritabanında, geçmişte, çöp kutusunda,
yedeklerde ve canlı kopyada AES-256-GCM ile şifreli durur, API'de (kurallara göre) açık döner. Telefon çalınsa veya
yedek dosyası sızsa bile TC kimlik no, telefon, adres gibi bilgiler okunamaz.

- Anahtar ilk şifreli alanda üretilir ve telefonun şifreli deposunda durur (`database.fieldKey`: `secret:field_key`);
  **yedeklere girmez**. Sihirbaz → Veritabanı → **Alan şifreleme** → *Anahtarı kopyala* ile bir parola yöneticisine
  kaydedin; yedeği başka telefona yüklerken orada *Var olan anahtarı gir*. Yanlış anahtarla geri yükleme reddedilir.
- Şifreli alana göre filtrelenemez, sıralanamaz, özetlenemez, aranamaz; benzersiz veya indeksli olamaz, koşulda ve
  hesaplanan alanda kullanılamaz (aynı değer her seferinde farklı şifrelenir).
- Şifrelemeyi sonradan açıp kapatmak güvenlidir: deploy mevcut değerleri dönüştürür.

### Sosyal giriş (Google, GitHub)

Sihirbaz → Kullanıcılar → Sosyal giriş: kendi OAuth uygulamanızın istemci kimliği ve sırrı (sır şifreli depoda),
sunucunun tarayıcıdan erişilen adresi (`publicUrl`, örn. DuckDNS + Let's Encrypt adresi) ve izinli dönüş adresleri.
Sağlayıcıya kaydedilecek dönüş adresi uygulamada gösterilir: `<publicUrl>/api/auth/oauth/<google|github>/callback`.

```text
1. İstemci tarayıcıda açar:   GET /api/auth/oauth/google?redirect=https://uygulamam.com/giris
2. Kullanıcı Google'da izin verir; sunucu kodu PKCE ile token'a çevirir, doğrulanmış e-postayı okur.
3. Tarayıcı döner:            https://uygulamam.com/giris?code=<tek kullanımlık kod>   (hata: ?error=<ileti>)
4. İstemci:                   POST /api/auth/oauth/exchange {"code": "..."} → {token, refreshToken, expiresIn, user}
```

Doğrulanmış e-posta var olan hesapla eşleşir (hesap doğrulanmış olur); yoksa kayıt açıksa rastgele parolalı, doğrulanmış
hesap açılır (parola istenirse "parolamı unuttum" ile belirlenir). Kayıt kapalıysa veya zorunlu ek alan varsa hesap
açılmaz. Güvenlik: PKCE (S256), tek kullanımlık `state` ve akışın başladığı tarayıcıya bağlı HttpOnly çerez (başkasının
başlattığı akışla oturum açtırma engellenir), yalnızca izinli dönüş adresleri (açık yönlendirme yok), adreste token
yerine 60 sn geçerli tek kullanımlık kod. Mobil uygulama özel şemalı adres (`uygulamam://giris`) kullanabilir.
`GET /api/auth/oauth/providers` açık sağlayıcıları listeler (giriş ekranındaki düğmeler için).

### Web push bildirimleri

Sihirbaz → Kullanıcılar → Web push: açınca VAPID anahtar çifti telefonda üretilir (özel anahtar şifreli depoda). Kullanıcı
web uygulamanızda bildirime izin verince tarayıcının aboneliği hesabına bağlanır; sunucu bildirimi (sekme kapalıyken de)
o tarayıcıya gönderir. İçerik tarayıcının anahtarıyla şifrelenir (RFC 8291, `aes128gcm`), istek VAPID ile imzalanır
(RFC 8292); itme servisi (Google, Mozilla, Apple) içeriği göremez.

```js
// Web uygulamanız (kullanıcı giriş yapmış, HTTPS):
const reg = await navigator.serviceWorker.register("/sw.js");
const { publicKey } = await (await fetch(`${base}/api/push/key`)).json();
const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: publicKey });
await fetch(`${base}/api/push/subscribe`, { method: "POST", headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json" },
  body: JSON.stringify(sub.toJSON()) });

// sw.js
self.addEventListener("push", (e) => {
  const m = e.data.json(); // {title, body, url, data}
  e.waitUntil(self.registration.showNotification(m.title, { body: m.body, data: m.url }));
});
self.addEventListener("notificationclick", (e) => { e.notification.close(); if (e.notification.data) e.waitUntil(clients.openWindow(e.notification.data)); });
```

Gönderim: script'lerden `ctx.push.send(kullanıcıId, "Başlık", "Metin", "/adres")` (cihaz sayısını döndürür), yönetici
`POST /api/push/send {"user": 5, "title": "...", "body": "...", "url": "..."}` veya otomatik kurallar (`push.notify`):
koleksiyonda kayıt eklenince/değişince/silinince kaydın sahibine, `{alan}` yer tutucularıyla (`"Siparişiniz: {durum}"`).
Kullanıcı başına en çok 20 cihaz; servis aboneliğin bittiğini söylerse (404/410) abonelik silinir. Abonelik adresi yalnızca
bilinen itme servislerinden biri olabilir (sunucunun iç ağdaki adreslere istek atması engellenir). Bildirim en çok 3800
bayt. VAPID anahtarları yeniden üretilirse tarayıcıların yeniden abone olması gerekir.

### Dosyalar ve statik site

```bash
curl -H "$H" -F 'title=Deniz' -F 'image=@tatil.jpg' $BASE/collections/photos/records
# → {"id":7,"title":"Deniz","image":"tatil_k3j9x0a2mq.jpg",...}
curl -H "$H" -o tatil.jpg $BASE/files/photos/7/tatil_k3j9x0a2mq.jpg
curl -H "$H" -o kucuk.jpg "$BASE/files/photos/7/tatil_k3j9x0a2mq.jpg?thumb=100x100"
curl -H "$H" -X PATCH -F 'image=@yeni.jpg' $BASE/collections/photos/records/7          # eskisinin yerine geçer
curl -H "$H" -X PATCH -H 'Content-Type: application/json' -d '{"image":null}' $BASE/collections/photos/records/7
```

- `file` alanında dosyalar `multipart/form-data` ile `POST`/`PATCH` edilir; diğer form alanları tiplerine çevrilir
  (`count=3` → 3). Dosyalar `files/storage/<koleksiyon>/<kayıt-id>/` altında, veritabanında yalnızca adları durur
  (tekli alanda ad, çoklu alanda dizi). Ad `<özgün-ad>_<rastgele>.<uzantı>` biçimindedir.
- **Boyut sınırı akış sırasında** uygulanır: dosya geçici klasöre yazılırken sınır aşılırsa okuma durur ve `413` döner
  (tamamı belleğe ya da diske alınmaz). Dosya yüklemeleri genel gövde sınırına (`maxBodyKb`) değil alanın sınırına tabidir.
- **İçerik tipi dosyanın ilk baytlarından** bulunur (PNG, JPEG, GIF, WebP, HEIC, PDF, MP4, MP3, OGG, WAV, zip, metin…);
  `mimeTypes`'a uymayan dosya ve uzantısı içeriğiyle uyuşmayan dosya (`resim.png` adlı bir HTML) `400` ile reddedilir.
  Kaydedilen uzantı içerikten gelir: HTML/SVG gibi çalıştırılabilen metinler `.txt` olur.
- **İndirme** `GET /api/files/<koleksiyon>/<id>/<ad>`: kaydın `view` kuralı geçerlidir (`owner`'da başkasının dosyası
  `404`); ad o kayda ait olmalıdır. Yanıtta `Content-Security-Policy: sandbox` (yüklenen içerik sunucunun adresinden betik
  çalıştıramaz); görsel, ses, video, PDF ve düz metin tarayıcıda açılır, diğerleri indirilir.
- **Küçük resim:** `?thumb=GxY` yalnızca izinli boyutlarda (`100x100` ve alanın `thumbs`'ı; `400x0` oranı korur), ilk
  istekte üretilip önbelleğe alınır (telefonda `BitmapFactory`; büyük fotoğraf örneklenerek açılır). Görsel değilse özgün dosya döner.
- **Video ve ses akışı:** indirmede `Range` istekleri (`206 Partial Content`) desteklenir: oynatıcılar ileri sarar, yarıda
  kalan indirme sürer. Dosya adları değişmediği için yanıt `Cache-Control: private, max-age=31536000, immutable` taşır
  (tarayıcı önbelleğe alır, ara sunucular almaz); `Last-Modified` ile koşullu istek `304` döner.
- **Konum bilgisi:** fotoğraftaki GPS konumu yüklenirken silinir (JPEG, PNG, WebP; EXIF'in GPS bölümü ve konum içeren
  XMP). Yön, tarih gibi diğer bilgiler kalır, dosya boyutu değişmez. Alanda **Konum bilgisini koru** (`keepLocation`)
  açılırsa dokunulmaz.
- **Dönüşüm ve küçültme:** `convert: "webp"` (veya `jpeg`) ve `maxDimension: 2048` ile yüklenen görsel telefonda yeniden
  kodlanır: uzun kenar sınıra küçültülür, EXIF yönü piksellere uygulanır, meta veri (konum dahil) yazılmaz. Ad ve tip yeni
  biçimden gelir (`tatil_….webp`). Android AVIF kodlayamadığı için AVIF yok; GIF (hareketli olabilir) dönüştürülmez.
- **YZ ile etiket ve metin (OCR):** alanda `ai: {"tags": "etiketler", "text": "metin"}` ve Yapay zekâ açıkken görsel kayda
  bağlandıktan sonra arka planda sağlayıcıya gönderilir (4 MB'tan büyüğü küçültülerek). Etiketler metin alanına virgülle,
  JSON alanına dizi, çoklu seçim alanına yalnızca seçeneklerden uyanlar olarak; görseldeki yazı metin alanına yazılır.
  Kayıt güncellemesi normal yoldan geçer: anlamsal arama dizini, canlı abonelik ve otomasyonlar görür. Görsel destekleyen
  bir model gerekir (Claude, GPT-4o/5, Gemini; Ollama'da llava gibi). Görsel sağlayıcıya gider; telefondan çıkmasın
  isteniyorsa yerel model (Faz 18.7) beklenmeli.
- `PATCH` ile JSON'da dosya alanına `null` (dosyaları kaldır) veya mevcut adlardan tutulacaklar yazılır; çoklu alanda yeni
  yüklemeler eklenir. Kaldırılan dosyalar ve kayıt silinince (cascade ile silinenler dahil) klasörü silinir.

### Yedekleme

Yedek tek bir zip'tir: `manifest.json`, `server.json` (sırların değerleri hariç, `secret:` referansları), `db/<tablo>.jsonl`
(her satır bir kayıt), `files/storage|scripts|www/…`. Veritabanı motora özgü döküm (VACUUM INTO, pg_dump) yerine kayıt kayıt
yazılır: aynı yedek SQLite'a da PostgreSQL'e de yüklenir, Android'in eski SQLite sürümlerinde çalışır, PostgreSQL bileşenine
ek araç gerekmez. Okuma tek işlemde (PostgreSQL'de REPEATABLE READ) yapılır; yedek tutarlı bir anın görüntüsüdür ve sunucu
yedek alınırken çalışmaya devam eder.

- **Zamanlanmış yedek** (`backup`): telefonun saatine göre; yedekler `files/backups/`'ta, en yeni `keep` tanesi kalır (elle ve
  geri yükleme öncesi alınanlar silinmez). Sunucu → Yedekler'den bir klasör seçilirse (Drive, SD kart …) her yedeğin kopyası
  oraya da yazılır.
- **Geri yükleme:** önce yedek baştan sona doğrulanır (biçim, konfigürasyon, kayıt sayıları, güvenli dosya adları), mevcut
  durumun yedeği alınır, sunucu durdurulur; veritabanı tek işlemde yedekteki hale gelir (hata olursa hiçbir şey değişmez,
  sunucu eski haliyle açılır), sonra klasörler değişir. Yedekteki konfigürasyon kaydedilir ama veritabanı ayarı bu
  telefonunki kalır. Yeni bir telefona yüklenince sırlar (API anahtarı vb.) depoda olmadığı için Güvenlik adımında yeniden
  üretilir.

**Bulut yedeği** (Sihirbaz → Veritabanı → Bulut yedeği): zamanlanmış (ve isteğe bağlı elle alınan) yedekler telefonda
şifrelenip WebDAV (Nextcloud, ownCloud, Synology) veya S3 uyumlu bir depoya (Backblaze B2, Cloudflare R2, MinIO, AWS)
`.dsbk` olarak yüklenir; depoda son `keep` tanesi tutulur.

- Şifreleme: AES-256-GCM, 1 MiB'lık parçalar (parça çıkarma, sıra değiştirme ve kesilme fark edilir); anahtar
  parolada PBKDF2-HMAC-SHA256 (200 000 tekrar) ile türetilir. Sağlayıcı içeriği göremez.
- Parola açılışta rastgele üretilir (25 karakter) veya kendiniz yazarsınız; **telefon dışında not edin** — başka
  telefona geri yüklemek için gerekir, kaybolursa yedek açılamaz. Depo parolası/gizli anahtar ve şifreleme parolası
  şifreli depoda durur (`secret:` referansı).
- S3 istekleri AWS Signature V4 ile imzalanır (yol biçimli adres: `uç/kova/önek/ad`); WebDAV temel kimlik doğrulama
  kullanır (Nextcloud'da uygulama parolası önerilir), klasör yoksa oluşturulur.
- Yeni telefona taşıma: uygulamayı kurun, Sihirbaz'da aynı bulut ayarını ve şifreleme parolasını girip kaydedin,
  Sunucu → Yedekler → **Buluttaki yedekleri listele** → **İndir ve geri yükle**. "Bağlantıyı dene" ayarı hemen sınar.

```json
"backup": { "enabled": true, "remote": { "kind": "s3", "url": "https://s3.eu-central-003.backblazeb2.com",
  "bucket": "evim-yedek", "region": "eu-central-003", "prefix": "droidserver/", "username": "<key ID>",
  "password": "secret:backup_password_1", "passphrase": "secret:backup_passphrase_2", "keep": 30 } }
```

### Metrikler

`GET /api/_metrics` (yönetici) istek sayısı, durum kodu dağılımı, süren istek, uç başına sayı ve gecikme (son 512 isteğin
p50/p95; yoldaki kimlikler `:id` olur), son 60 dakikanın dakikalık serisi ve cihaz durumu (pil seviyesi, sıcaklık, şarj,
boş depolama, bellek). `?format=prometheus` Prometheus metin biçimi. Metrikler bellektedir; uygulama yeniden başlayınca sıfırlanır.
Başarılı `_metrics` okumaları (panelin ve Prometheus'un düzenli yoklaması) istek sayılarına eklenmez; reddedilenler (401) eklenir.

### Web yönetim paneli

`adminPanel.enabled` iken tarayıcıdan `http://<telefon>:<port>/_admin/` (veya internet adresinizle). Açılış ekranı
metriklerdir (grafik ve uç tablosu; sekme açıkken 10 saniyede bir yenilenir). Diğer sekmeler: kayıtlar (tablo,
filtre ve arama, ekle/düzenle/sil; canlı abonelik açıksa kendiliğinden yenilenir), kullanıcılar, canlı loglar, API
(OpenAPI dokümanı ve istemci SDK'sı indirme), yedekler (al, indir), **şema** (koleksiyon, alan ve kural düzenleyici: alan
tipi ve seçenekleri, kısıtlar, hesaplanan alan, ilişkiler, arama/tam metin, geçmiş ve çöp kutusu; tip değişince kayıtlı
değerlerin dönüştürülmesi ya da silinmesi sorulur, "Planı göster" değişiklikleri ve veri kaybını gösterir; kaldırılan alan ve
koleksiyonların verileri silinmez) ve konfigürasyon (düzenle, denetle, kaydet ve deploy et). Kayıt satırlarında **Geçmiş**,
koleksiyonda **Çöp kutusu** (geçmiş/çöp kutusu açıksa). Giriş isteğe bağlı olarak doğrulayıcı kodu da ister (bkz. İki adımlı doğrulama). Giriş, telefonun Durum ekranında oluşturulan **tek kullanımlık 6 haneli
kodla**: kod 5 dakika geçerli, bir kez kullanılır, 5 hatalı denemede geçersizleşir; başarılı girişte `sessionHours` süreli
yönetici oturumu açılır (bellekte; uygulama kapanınca biter, Durum ekranından hepsi kapatılabilir). Panel bağımlılıksız düz
JavaScript'tir (core kaynaklarında `admin/`), yalnızca kendi dosyalarını yükler (`Content-Security-Policy`), başka sitenin
çerçevesine konamaz ve verileri yalnızca metin olarak yazar. Kayıt/kullanıcı/metrik işleri normal API'yi panel oturumuyla
kullanır; konfigürasyon, log ve yedek için `/_admin/api/...`.

**SQL** sekmesi salt okunur bir sorgu konsoludur (`POST /api/_sql` `{query, limit}` → `{columns, rows, truncated, ms}`,
yönetici). Yalnızca tek bir `SELECT`/`WITH`/`VALUES` ifadesi çalışır; sorgu ayrıca salt okunur bir bağlantıda koşar (SQLite'ta
`query_only`, Android'de salt okunur açılmış veritabanı, PostgreSQL'de `READ ONLY` işlem), yani denetimi atlatan bir yazma
da veritabanında reddedilir. En çok 1000 satır (varsayılan 200) ve 5 saniye; sunucunun sırlarını tutan iç tablolar ve parola
özetleri sorgulanamaz, şifreli alanlar `••••` olarak gelir, her sorgu denetim kaydına yazılır. Ctrl+Enter çalıştırır.

**Örnek veri:** kayıt tablosundaki **Örnek veri** düğmesi (`POST /api/_sample/{koleksiyon}` `{count}`, 1-500, yönetici)
alan tiplerine, seçeneklere, uzunluk ve aralık sınırlarına, benzersizliğe ve biçime (e-posta, adres; alan adından ad, şehir,
telefon, başlık) uyan kayıtlar ekler; ilişki alanına hedef koleksiyondaki var olan bir kaydı koyar. Tek işlemdir ve otomasyon,
webhook veya canlı olay üretmez. Zorunlu ilişkinin hedefi boşsa, zorunlu dosya alanı veya desene bağlı zorunlu metin varsa
`400` döner.

Uygulamadaki ekranların panel karşılıkları:

- **Kullanıcılar → Takımlar** (takımlar açıksa): tüm takımlar, kur, sil, üye ekle (e-postayla), sahip/üye yap, çıkar.
- **Otomasyon:** otomasyon kurallarını ekle, düzenle, aç/kapat, sil ve **Şimdi dene**; son çalışmalar. **Formlar**: adres,
  başlık, koleksiyon, alanlar ve sıraları, teşekkür metni veya yönlendirme, gönderim sınırı.
- **Servisler:** MQTT (durum, bağlı cihazlar, son mesajlar, yönlendirmeler, kayıt olayları, cihazlara mesaj), ağ sürücüsü
  (paylaşımlar, salt okunur, adres), canlı kopya ve devralma (eşitleme durumu, otomatik devralma, tanık adresi, geri alma,
  **Devral**), yapay zekâ (bugünkü kullanım, anlamsal dizin, yeniden kur), kendi uygulamalarınız (durum, sürüm, deploy) ve
  komut satırı aracının kurulum komutları.

Bu ekranlardaki ayarlar Şema ve Konfigürasyon sekmeleriyle aynı yoldan kaydedilir: uygulamadaki doğrulamadan geçer, önce
plan gösterilir, denetim kaydına `panel` olarak düşer. Yeni MQTT veya ağ sürücüsü parolası panelden yazılabilir (telefon
şifreli depoya taşır; boş bırakılırsa kayıtlı parola korunur). YZ sağlayıcı anahtarı, kopya kurulumu (ana sunucunun
anahtarı) ve uygulama zip'i yalnızca telefondan girilir.

### Denetim kaydı

Yönetici işlemleri kim, ne zaman, nereden bilgisiyle kaydedilir: sunucu başlatma ve konfigürasyon değişiklikleri
(eklenen/kaldırılan koleksiyon ve alanlar, tip ve kural değişiklikleri, oluşturulan/iptal edilen anahtarlar, erişim ve
panel ayarları), kullanıcı açma/güncelleme/silme (rol, parola sıfırlama, 2FA sıfırlama; parolanın kendisi yazılmaz),
kaydı eski sürüme döndürme, çöp kutusundan geri yükleme ve kalıcı silme, içe aktarma, elle yedek ve geri yükleme, panel
girişleri (başarısızlar dahil). Kayıt veritabanında değil profilin klasöründe (`audit.jsonl`) durur: veritabanı taşıma
veya yedekten geri yükleme onu silemez. `audit.keepDays` (365) günden eskiler atılır.

```bash
curl -H "Authorization: Bearer <yönetici anahtarı>" "http://<telefon>:8080/api/_audit?action=user.&q=ali&limit=100"
curl -H "Authorization: Bearer <yönetici anahtarı>" -o denetim.csv "http://<telefon>:8080/api/_audit?format=csv"
```

Uygulamada Sunucu → **Denetim kaydı** (arama, CSV paylaşımı), panelde **Denetim** sekmesi.

### Sağlık uyarıları

Sihirbaz → Genel → **Sağlık uyarıları** (varsayılan açık, telefon bildirimiyle). Sunucu çalışırken yarım dakikada bir
denetlenir:

| Uyarı | Ne zaman | Kritik |
|---|---|---|
| Depolama dolmak üzere / doldu | boş alan `diskFreeMb` (500) MB'ın / 100 MB'ın altında | doldu: evet |
| Sunucu hataları arttı | son 5 dakikada (en az 20 istek) 5xx oranı `errorRatePercent` (%20) üstünde | |
| Sertifika bitiyor | Let's Encrypt sertifikasının bitmesine `certificateDays` (14) günden az | |
| Pil çok sıcak | pil `batteryTempC` (45) °C ve üstü | |
| Sunucu çalışmıyor / bileşen durdu | sunucu başlatılamadı; bileşen yeniden başlatma sınırını aştı | evet |
| Yedek alınamadı | zamanlanmış yedek, klasöre kopyalama veya bulut yüklemesi başarısız | |
| Uygulama kapandı | uygulama yakalanmayan bir hatayla kapandı (sonraki açılışta) | evet |
| Sistem sunucuyu yeniden başlattı | Android servisi öldürüp yeniden başlattı; Android 11+ nedenini de yazar | |

Aynı sorun `repeatHours` (6) saatte bir hatırlatılır, düzelince bir kez "düzeldi" gelir. **Sessiz saatlerde**
(`quietHours`, örn. 23:00-07:00) yalnızca kritik uyarılar gider; diğerleri sorun sürüyorsa sessiz saat bitince.

Kanallar: telefon bildirimi, **e-posta** (`alerts.email`; sunucunun SMTP ayarlarıyla) ve **Telegram**: @BotFather'da
`/newbot` ile bot açın, token'ı sihirbaza yapıştırın (şifreli depoya gider), bota bir mesaj yazıp **Sohbeti bul**'a
dokunun; **Deneme gönder** ile hemen sınayın.

```json
"alerts": { "email": ["ben@example.com"], "telegram": { "botToken": "secret:telegram_token_1", "chatId": "987654321" },
            "quietHours": { "from": "23:00", "to": "07:00" } }
```

Süren sorunlar ve son uyarılar: Sunucu → **Sağlık uyarıları** (deneme uyarısı da buradan), panelde **Metrikler**
sekmesinin başı, API'de `GET /api/_alerts` ve `POST /api/_alerts/test` (yönetici).

**Telegram'dan yönetim:** aynı bot, `alerts.telegram.adminChatId` sohbetinden komut kabul eder (sihirbazda **Yönetici
sohbeti**; uyarılar örneğin ailece görülen bir gruba giderken yönetim size özel kalır). Komutlar: `/durum` (sunucu,
adres, pil, boş alan, son bir saatin istekleri, sorunlu bileşenler), `/yedek`, `/loglar [n]` (son 20, en çok 100 satır),
`/baslat`, `/durdur` (onay düğmesiyle; 2 dakika geçerli), `/yardim`. Bot uzun yoklamayla çalışır, telefona dışarıdan
bağlantı gerekmez. Başka sohbetlerden gelen komutlar yanıtsız kalır (logda bir kez görünür); bot kapalıyken biriken
2 dakikadan eski komutlar çalıştırılmaz. `/durdur` sunucuyu durdurur ama uygulamanın servisi Telegram'ı dinlemek için
açık kalır (bildirimde "Durduruldu"; uyanma kilidi bırakılır), `/baslat` yeniden başlatır. Telefondan veya bildirimden
**Durdur** her şeyi kapatır; o zaman `/baslat` çalışmaz.

```json
"telegram": { "botToken": "secret:telegram_token_1", "chatId": "-1001234567890", "adminChatId": "987654321" }
```

### Güvenlik duvarı

Sihirbaz → Güvenlik → **Güvenlik duvarı**:

- **Engellenen adresler**: tek IP veya aralık (`203.0.113.7`, `10.0.0.0/8`, `2001:db8::/32`); bu adreslerden gelen
  istekler 403 alır.
- **Yalnızca izin verilenler**: doluysa yalnızca listedekiler (ve açıksa yerel ağ) erişir. Telefonun kendisi hiçbir
  zaman engellenmez.
- **Kullanıcı başına dakikada istek** (`firewall.userRequestsPerMinute`): her hesap ayrı sayılır; aynı adresi paylaşan
  kullanıcılar birbirini etkilemez. Adlandırılmış anahtarlara da ayrı sınır verilebilir (`auth.keys[].requestsPerMinute`);
  anahtarın sınırı adresten bağımsızdır.

```json
"firewall": { "deny": ["203.0.113.0/24"], "allow": ["198.51.100.0/24"], "allowLocalNetwork": true, "userRequestsPerMinute": 120 }
```

Çok sayıda hatalı kimlik denemesi yapan adresler (`server.limits.authFailures`) süreli engellenir. Engelli adresler
Sunucu → **Güvenlik duvarı** ekranında ve paneldeki **Denetim** sekmesinde görünür; oradan engel kaldırılır veya bir
adres elle engellenir. Engeller yeniden deploy'da sürer, her engel ve kaldırma denetim kaydına yazılır.

```bash
curl -H "Authorization: Bearer <yönetici anahtarı>" http://<telefon>:8080/api/_firewall
curl -X POST -H "Authorization: Bearer <yönetici anahtarı>" -H "Content-Type: application/json" \
  -d '{"client":"203.0.113.50","minutes":1440,"reason":"tarama"}' http://<telefon>:8080/api/_firewall/bans
curl -X DELETE -H "Authorization: Bearer <yönetici anahtarı>" http://<telefon>:8080/api/_firewall/bans/203.0.113.50
```

### Script'ler

`scripts.enabled` iken `files/scripts/` altındaki `.js` dosyaları her deploy'da ad sırasıyla yüklenir (JavaScript,
Mozilla Rhino; ok fonksiyonu, `let`/`const`, şablon metin desteklenir). Sözdizimi hatası, bilinmeyen koleksiyon veya
geçersiz cron ifadesi deploy'u reddeder (`main.js:12: …`); çalışan sunucu eski haliyle sürer.

```js
// GET /api/merhaba/Ayşe → {"mesaj":"Merhaba Ayşe"}. Dördüncü değer erişim: "public" (varsayılan), "auth", "admin".
routerAdd("GET", "/merhaba/:ad", (req, ctx) => ({ mesaj: "Merhaba " + req.params.ad }))
routerAdd("POST", "/siparis", (req, ctx) => reply(201, ctx.db.create("orders", req.body)), "auth")

onBeforeCreate("todos", (record, ctx) => {          // kaydı değiştirebilir; fail() işlemi durdurur
  record.title = (record.title || "").trim()
  if (!record.title) fail(400, "Başlık boş olamaz")
})
onBeforeUpdate("todos", (changes, ctx) => { if (ctx.current.done) fail(409, "Tamamlanmış iş değişmez") })
onAfterCreate("orders", (record, ctx) => { ctx.http.post("https://hooks.example.com/yeni", { id: record.id }) })
cronAdd("0 3 * * *", (ctx) => ctx.log("gece görevi"))   // dakika saat gün ay haftanın-günü (telefonun saati)
```

- **Uçlar** API yolunun altındadır (`/api/merhaba/...`); sunucunun kendi uçları (`/collections`, `/files`, `/auth`,
  `/health`, `/_schema`) kullanılamaz. `req`: `method`, `path`, `params`, `query`, `headers`, `body` (JSON). Dönüş değeri
  JSON yanıt olur; `reply(durum, gövde, başlıklar)` durum kodu verir, `fail(durum, mesaj)` hata döner.
- **Kayıt olayları** (`onBefore/AfterCreate/Update/Delete`, koleksiyon adı veya `"*"`) yalnızca API'den gelen yazmalarda
  çalışır. `before` kaydı değiştirebilir veya `fail()` ile reddeder; güncelleme ve silmede `ctx.current` mevcut kayıttır.
  `after` hataları yanıtı değiştirmez, loga yazılır.
- **`ctx`**: `ctx.db.list/get/create/update/delete(koleksiyon, …)` (yönetici yetkisiyle, kayıt olaylarını tetiklemez;
  `list(ad, { filter: { "title[like]": "süt" }, q: "arama", sort, page, perPage })`; yazmaları canlı abonelere de
  gider), `ctx.http.get/post/request` (yalnızca `allowedHosts`, yönlendirme izlenmez, 10 sn, 1 MB; yanıt
  `{ status, body, json }`), `ctx.mail.send(alıcı, konu, metin[, html])` (e-posta açıksa), `ctx.log(...)`, `ctx.env`,
  `ctx.auth` (`{ type: "anonymous" | "user" | "admin" | "key", id, key, admin }`), `ctx.push.send(...)` (web push açıksa).
- **Güvenlik:** Java sınıflarına erişim yok (`java`, `Packages` tanımsız, her sınıfı reddeden `ClassShutter`); her çağrının
  süre sınırı var (`timeoutMs`; zamanlanmış görevde `cronTimeoutSec`): süreyi aşan script durdurulur ve script bunu
  `try/catch` ile yakalayamaz; özyineleme derinliği sınırlı. Script hatası `500` döner (ayrıntı istemciye değil loga:
  `dosya:satır` ve script yığını), sunucuyu durdurmaz. Script'ler aynı anda çalışabilir (iş parçacığı güvenli nesneler).

**Arka plan işleri:** yavaş veya güvenilmez işleri (e-posta, dış servis, rapor) isteği bekletmeden kuyrukta yapın.
Kuyruk veritabanındadır (`_jobs`): uygulama veya telefon yeniden başlasa da işler kaybolmaz.

```js
jobAdd("fatura", (data, ctx, job) => {               // job: { id, name, attempt, attempts }
  const o = ctx.db.get("orders", data.order)
  const r = ctx.http.post("https://fatura.example.com/kes", { tutar: o.total })
  if (r.status === 422) fail(422, "geçersiz sipariş")  // 4xx: kalıcı hata, yeniden denenmez
  ctx.db.update("orders", o.id, { invoice: r.json.no })
  return { no: r.json.no }                             // sonuç olarak saklanır
}, { retries: 5, timeoutSec: 60, concurrency: 1 })

onAfterCreate("orders", (record, ctx) => ctx.jobs.enqueue("fatura", { order: record.id }, { key: "fatura-" + record.id }))
```

- Başarısız iş üstel beklemeyle (30 sn, 1 dk, 2 dk, … en çok 1 saat) `retries` kez daha denenir, sonra `failed` olur.
- `ctx.jobs.enqueue(ad, veri, { delaySec, key, retries })` iş kimliğini döndürür; `key` doluysa aynı anahtarlı bitmemiş
  iş varsa yenisi eklenmez. Aynı anda en çok `scripts.jobs.concurrency` (2) iş, tür başına `concurrency`.
- İşler **en az bir kez** çalışır: iş sürerken uygulama kapanırsa açılışta yeniden kuyruğa alınır; aynı işi iki kez
  yapmak zarar vermemeli. Biten işler `scripts.jobs.keepDays` (7) gün sonra silinir.
- Liste, yeniden çalıştırma, iptal: Sunucu → **Arka plan işleri**, panelde **İşler** sekmesi veya
  `GET /api/_jobs?status=failed`, `POST /api/_jobs` (`{"name":"fatura","data":{…}}`), `POST /api/_jobs/{id}/retry`,
  `POST /api/_jobs/{id}/cancel` (yönetici; denetim kaydına yazılır).

**Statik site:** `static.enabled` iken `files/www/` altındaki dosyalar `static.path` altında yayınlanır (API yolu hep
önceliklidir; `/api/...` siteye düşmez). Klasör adresi `index.html`'i verir; `spaFallback` açıkken uzantısız bulunamayan
adresler (`/urunler/42`) `index.html` döner (tek sayfalık uygulamalar). Yol klasör dışına çıkamaz, `.` ile başlayan
dosyalar sunulmaz; yanıtlar `Cache-Control: no-cache`. Site uygulamadan zip olarak yüklenir: ayrı klasöre açılır,
tamamlanınca eskisinin yerine geçer; klasör dışına çıkan (`../`) adlar veya 500 MB / 20.000 dosya sınırını aşan arşiv
reddedilir ve eski site yerinde kalır. Zip'teki her şey tek bir klasördeyse o klasörün içi köke alınır.

### OpenAPI ve istemci örnekleri

`GET /api/_openapi.json` konfigürasyondan üretilmiş bir **OpenAPI 3.1** dokümanı döndürür: koleksiyon uçları, alan
şemaları (zorunlu alanlar, tarih biçimi, ilişkiler, dosyalar), filtre ve arama parametreleri, kullanıcı ve e-posta uçları,
canlı abonelik, script uçları. Doküman **isteyenin yetkisine göre** üretilir: anonim istek yalnızca herkese açık
işlemleri, kullanıcı kurallarının izin verdiklerini, yönetici her şeyi görür (kapalı işlemler dokümanda yoktur). Postman,
Insomnia, Swagger Editor'e içe aktarılabilir veya istemci kod üreticileriyle kullanılabilir. Web panelinin **API**
sekmesi aynı dokümanı uç uç gösterir (parametreler, gövde alanları, curl) ve `openapi.json` olarak indirir. Uygulamadaki
API gezgini örnekleri **curl**, **JavaScript** (`fetch`, akış için `EventSource`, dosya için `FormData`) veya **Kotlin**
(OkHttp) olarak verir.

**Tipli istemci (SDK):** konfigürasyondan tek dosyalık istemci üretilir — koleksiyon tipleri (seçim alanları birleşim
tipi, hesaplanan alanlar yalnızca okunur), listeleme/filtre/sıralama, ekleme/güncelleme/silme, dosya yükleme, giriş
(iki adımlı doğrulama ve e-posta koduyla dahil), token'ı kendiliğinden yenileme ve canlı abonelik. Uzman modundaki yollar
ve alan adları kullanılır. Uygulamada API gezgini → İstemci SDK'sı (paylaş/kaydet), panelde API sekmesi veya:

```bash
curl -H "Authorization: Bearer <yönetici anahtarı>" -o droidserver.ts http://<telefon>:8080/api/_sdk/ts
curl -H "Authorization: Bearer <yönetici anahtarı>" -o DroidServerClient.kt "http://<telefon>:8080/api/_sdk/kotlin?package=com.ornek.api"
curl -H "Authorization: Bearer <yönetici anahtarı>" -o droidserver.dart http://<telefon>:8080/api/_sdk/dart
curl -H "Authorization: Bearer <yönetici anahtarı>" -o DroidServer.swift http://<telefon>:8080/api/_sdk/swift
```

```ts
const ds = new DroidServer("https://evim.duckdns.org/api");
await ds.auth.login("ali@example.com", "parola");            // token saklanır, süresi dolunca yenilenir
const page = await ds.todos.list({ filter: { done: false, qty: { gt: 0 } }, sort: "-created_at" });
await ds.todos.create({ title: "Süt al" }, { image: file }); // dosya alanı varsa
const stop = ds.subscribe(["todos"], (e) => console.log(e.action, e.record));
```

TypeScript istemcisi bağımlılıksızdır (`fetch`; tarayıcı, Node 18+, React Native, Deno) ve yalnızca tip silmeyle
çalışan sözdizimi kullanır. Kotlin istemcisi JVM ve Android'de `HttpURLConnection` ve kotlinx.serialization ile çalışır
(JVM'de PATCH için sunucu `X-HTTP-Method-Override` başlığını tanır). Şemayı içerdiği için `/_sdk` yalnızca yöneticiye açıktır.

**Dart / Flutter** (`droidserver.dart`): yalnızca Dart 3 SDK'sı (`dart:io`); Dart VM ve Flutter Android, iOS ve masaüstünde
çalışır, Flutter web'de çalışmaz. Kayıtlar `fromJson`'lu sınıflar, girdiler adlandırılmış parametreli sınıflardır (Dart'ta
ayrılmış sözcük olan alan `_` ile biter: `in` → `in_`). Canlı abonelik bir `Stream`'dir; dinlemeyi iptal etmek kapatır.
İş bitince `close()`.

```dart
final ds = DroidServerClient('https://evim.duckdns.org/api', fallbackUrls: ['http://192.168.1.20:8080/api']);
await ds.auth.login('ali@example.com', 'parola');
final page = await ds.todos.list(const ListOptions(filter: {'done': 'false'}, sort: '-created_at'));
await ds.todos.create(const TodosInput(title: 'Süt al'), files: {'image': [File('foto.jpg')]});
final sub = ds.subscribe(['todos']).listen((e) => print('${e.action} ${e.record}'));
```

**Swift** (`DroidServer.swift`): Swift 5.9+ ve async/await, bağımlılık yok; Apple platformları ve Linux
(FoundationNetworking). Swift 6 kipinde uyarısız derlenir. Kayıtlar `Decodable`, girdiler `Encodable` yapılardır; JSON
alanları ve takım/hesap yanıtları `JSONValue`. Canlı abonelik bir `AsyncStream`'dir.

```swift
let ds = DroidServerClient("https://evim.duckdns.org/api")
_ = try await ds.auth.login("ali@example.com", "parola")
let page = try await ds.todos.list(ListOptions(sort: "-created_at", filter: ["done": "false"]))
_ = try await ds.todos.create(TodosInput(title: "Süt al"), files: ["image": [URL(fileURLWithPath: "foto.jpg")]])
for await e in ds.subscribe(["todos"]) { print(e.action, e.record) }
```

Dört istemci de aynı yüzeyi sunar: CRUD ve filtre, dosya yükleme ve adresi, giriş (iki adımlı, e-posta ve SMS koduyla),
token'ı 401'de bir kez yenileme, takımlar, yedek adresler (`fallbackUrls`), QR bağlantı metni (`fromLink`) ve canlı
abonelik (koparsa kaldığı olaydan sürer). Dart ve Swift istemcileri testlerde gerçek sunucuya karşı derlenip çalıştırılır.

### E-posta: doğrulama ve parola sıfırlama

`email.enabled` iken (Sihirbaz → Kullanıcılar → E-posta gönderimi; Gmail, Outlook, Yandex, iCloud hazır ayarları ve
"Deneme gönder") sunucu SMTP ile e-posta gönderir. Bağımlılıksız küçük bir SMTP istemcisidir: SSL (465) veya STARTTLS
(587) ile şifreli bağlantı, sunucu sertifikası ve adı doğrulanır, parola şifresiz bağlantıda gönderilmez. Gmail ve
Outlook'ta hesap parolası değil **uygulama şifresi** gerekir.

| Uç | Açıklama |
|---|---|
| `POST /api/auth/request-verification` `{email}` (veya kullanıcı token'ıyla) | Doğrulama e-postası; `202` |
| `POST /api/auth/confirm-verification` `{token}` | Hesabı doğrular, kullanıcıyı döndürür |
| `POST /api/auth/request-reset` `{email}` | Parola sıfırlama e-postası; hesap olsa da olmasa da aynı `202` |
| `POST /api/auth/confirm-reset` `{token, password}` | Yeni parola; tüm oturumlar kapanır, e-posta doğrulanmış sayılır |

E-postalar arka planda gönderilir; yanıt hesap var olsa da olmasa da aynı ve aynı hızda döner (adresin kayıtlı olduğu
sızmaz). Belirteçler kısa ömürlü ve tek amaçlıdır: doğrulama 24 saat, sıfırlama 1 saat; e-posta değişince doğrulama,
parola değişince sıfırlama belirteci geçersizleşir (sıfırlama bağlantısı tek kullanımlık). Erişim token'ı yerine
geçmezler, erişim token'ı da onların yerine. Aynı adrese aynı türden dakikada 1 / saatte 5, sunucu genelinde saatte 200
e-posta. `email.publicUrl` (örn. `https://evim.duckdns.org:8443`; sihirbaz erişim ayarından önerir) verilirse e-postada
bağlantı olur ve sunucunun kendi küçük sayfaları açılır: `/_auth/verify` hesabı doğrular, `/_auth/reset` yeni parola
formunu gösterir (JavaScript'siz; istemci uygulama yazmadan akış çalışır). Verilmezse e-postada kod gönderilir,
istemci uygulama kodu `confirm-*` uçlarına iletir. `requireVerified` açıkken doğrulanmamış kullanıcı giriş yapamaz
(`403`) ve kayıtta oturum verilmez. Script'ler `ctx.mail.send(alıcı, konu, metin[, html])` ile e-posta gönderebilir
(aynı sınırlar; aşılırsa 429).

**Şablonlar:** doğrulama, parola sıfırlama ve giriş kodu e-postalarının konusu, metni ve isteğe bağlı HTML'i
değiştirilebilir (Sihirbaz → Kullanıcılar → E-posta → **Hesap e-postası şablonları**, örnek değerlerle önizleme).
Yer tutucular `{app}` (gönderen adı), `{email}`, `{code}` ve `{link}`'tir (`publicUrl` yoksa `{link}` kodu verir); HTML'e
yazılan değerler kaçışlanır. `lang` verilen şablon yalnızca o dili isteyen istemciye (`Accept-Language`'ın ilk dili) gider,
dilsiz şablon diğerlerine; şablonu olmayan e-posta yerleşik metinle gider.

```json
"templates": [
  { "kind": "verify", "subject": "{app}: hesabınızı doğrulayın", "text": "Merhaba,\n\nDoğrulamak için: {link}" },
  { "kind": "verify", "lang": "en", "subject": "{app}: verify your account", "text": "Hi,\n\nVerify: {link}", "html": "<a href=\"{link}\">Verify</a>" }
]
```

**Toplu bildirim:** `POST /api/_broadcast` (yönetici; panelde Kullanıcılar → **Toplu bildirim…**) kullanıcılara e-posta
(`channel: "email"`) veya web push (`"push"`) gönderir. Alıcı süzgeci: `role`, `team`, `verifiedOnly` (e-postada varsayılan
açık) ve `_users` alanlarına liste filtresi (`where`, örn. `created_at[gt]=2026-01-01`; parola özetiyle süzülemez).
`dryRun: true` alıcı sayısını döndürür; asıl gönderim kalıcı iş kuyruğunda bir iştir (`_broadcast`): dakikada en çok
`perMinute` (30; 1-600) kişiye, kimlik sırasıyla gider, ilerleme (gönderilen, başarısız, son kimlik) işin sonucunda tutulur
ve sunucu yeniden başlayınca kaldığı yerden sürer. İş `/_jobs/{id}/cancel` ile (panelde İşler) iptal edilir; art arda 10
gönderim başarısız olursa (örn. SMTP reddi) iş durur. Her e-postaya **abonelikten çıkma** bağlantısı eklenir
(`/_auth/unsubscribe`; adrese bağlı, imzalı); bağlantı onay sayfası açar, çıkma formla yapılır (e-posta tarayıcıları
bağlantıyı önceden açsa da kimse istemeden çıkmaz). Çıkan adresler `_unsubscribed`'da tutulur ve hesap e-postalarını
etkilemez. E-postayla toplu gönderim için `email.publicUrl` gerekir. Push'ta aboneliği olmayan kullanıcılar sayılmaz.

```bash
curl -H "$H" -X POST "$BASE/_broadcast" -H 'Content-Type: application/json' \
  -d '{"subject":"Bakım","text":"Pazar 10:00-11:00 arası kapalıyız.","role":"user","dryRun":true}'
# → {"recipients":42}; dryRun olmadan 202 {"job":7,"recipients":42}
```

### Adlandırılmış API anahtarları

Sihirbaz → Güvenlik → Adlandırılmış anahtarlar: her istemciye (n8n, rapor betiği, pano, mobil uygulamanın sunucusu) ayrı
anahtar. Erişim **tam yetki** (yönetici anahtarıyla aynı), **salt okunur** (tüm koleksiyonları okur) veya **belirli
koleksiyonlar** (okuma ya da okuma-yazma) olabilir; isteğe bağlı son kullanma (30 gün, 90 gün, 1 yıl). Sınırlı anahtar
izinli koleksiyonlarda kuralları aşar (yönetici gibi; listeleme, özet, dışa/içe aktarma, toplu işlem, canlı abonelik dahil)
ama yönetici uçlarına (`/_schema`, `/_metrics`, `/_webhooks`, `/auth/users`, panel) giremez; izin dışı işlem `403`.
Anahtar `dsk_` ile başlar ve oluşturulunca **bir kez** gösterilir: konfigürasyonda yalnızca SHA-256 özeti ve tanımak
için ilk karakterleri durur (`auth.keys[]`). İptal (anahtar kartındaki anahtarı kapatmak) veya silme deploy edilince geçerli olur;
iptal edilmiş ya da süresi dolmuş anahtar nedeni yazan bir `401` alır. Son kullanım zamanı uygulamada görünür. Eski
`auth.apiKeys` tam yetkili olarak çalışmaya devam eder.

### Webhook'lar

Sihirbaz → Güvenlik → Webhook'lar: kayıt eklenince, değişince veya silinince seçtiğiniz adrese (Zapier, n8n, Make,
Discord köprüsü, kendi servisiniz) JSON POST gider. İsteğe bağlı koleksiyon ve olay filtresi. Gövde:

```json
{"id": "<teslim kimliği>", "event": "todos.create", "collection": "todos", "record": {...}, "timestamp": "2026-10-14T10:00:00Z"}
```

Başlıklar: `X-DroidServer-Event`, `X-DroidServer-Delivery`, `X-DroidServer-Timestamp` (Unix saniye) ve imza sırrı
tanımlıysa `X-DroidServer-Signature: sha256=<HMAC-SHA256(sır, "<zaman>.<gövde>")>`. Alıcı aynı hesabı yapıp sabit
zamanlı karşılaştırmalı ve eski zaman damgalarını (örn. 5 dakikadan eski) reddetmelidir. Sır uygulamada üretilir,
şifreli depoda saklanır ve bir kez gösterilir. 2xx başarıdır; değilse 10 sn, 1 dk ve 5 dk sonra yeniden denenir (aynı
teslim kimliğiyle; alıcı tekrarları kimlikle ayıklayabilir). Yönlendirme izlenmez, 10 sn zaman aşımı. Bekleyen teslimler
bellektedir: uygulama kapanırsa kaybolur. Script'lerin `ctx.db` yazmaları da iletilir. Sunucu → Webhook'lar ekranında son
200 teslim ve "Deneme" (ping olayı); yönetici için `GET /api/_webhooks`, `POST /api/_webhooks/{ad}/test`.

### Otomasyon kuralları

Sunucu → Otomasyonlar: kod yazmadan "şu olunca şunu yap". Bir kural bir tetikleyici, isteğe bağlı bir koşul ve sırayla
çalışan eylemlerden oluşur.

| Parça | Seçenekler |
|---|---|
| Tetikleyici | Kayıt olayı: koleksiyon + `create` / `update` / `delete`. Zamanlama: cron ifadesi (`0 8 * * *`, telefonun saatiyle); koleksiyon seçilirse koşula uyan kayıtlar sayılır (`{count}`) ve güncelleme eylemi hepsine (en çok 1000) uygulanır |
| Koşul | Liste filtresi sözdizimi: `status=new`, `qty[gte]=10&paid=true` (`like` ve `@me` yok) |
| `email` | `to` (adres veya `{email}` gibi alan), `subject`, `body`; e-posta gönderimi açık olmalı |
| `push` | `to`: `owner` (kaydın sahibi) veya kullanıcı kimliği / `{alan}`; `subject` başlık, `body`, `url` |
| `webhook` | Tanımlı bir webhook'un adı; olay `automation.<kural>`, imza ve yeniden deneme webhook'larla aynı |
| `update` | `values`: alan → değer; olayda tetikleyen kayıt, zamanlamada koşula uyan kayıtlar güncellenir |
| `create` | `collection` + `values` |
| `log` | `body` uygulamanın log ekranına yazılır |

Metinlerde `{alan}` kaydın değeriyle (özgün ve uzman modu adlarıyla), `{now}` (UTC zaman), `{count}`, `{rule}`,
`{collection}` ve `{event}` çalışma bilgileriyle dolar. Değerler alanın tipine çevrilir (`"evet"` → `true`, `"12,5"` → 12.5;
boş metin → `null`).

```json
"automations": [{
  "name": "yeni-siparis",
  "trigger": {"collection": "orders", "events": ["create"]},
  "condition": "status=new",
  "actions": [
    {"type": "email", "to": "{email}", "subject": "Siparişiniz alındı (#{id})", "body": "{qty} adet {item}"},
    {"type": "update", "values": {"status": "received"}}
  ]
}]
```

Kayıt olayları arka planda sırayla işlenir; istek beklemez. Kuralların yazmaları da olay üretir (canlı abone, webhook,
başka kurallar) ama zincir 3 adımda durur ve değeri değiştirmeyen güncelleme yazılmaz, böylece kendini tetikleyen kural
döngüye girmez. Ekranda son 100 çalışma (başarılı/hatalı ve ne yapıldığı) ve "Şimdi dene" var: zamanlanmış kural hemen
çalışır, olay kuralı koşula uyan son kayıtla denenir. Yönetici için `GET /api/_automations` ve
`POST /api/_automations/{ad}/run`.

### Formlar

Sunucu → Formlar: bir koleksiyona kayıt ekleyen herkese açık sayfa (iletişim, başvuru, anket). Adres
`http://<telefon>:8080/forms/<ad>`; tünel veya doğrudan erişimle internetten de açılır. Sayfa açık/koyu temalıdır,
JavaScript ve dış kaynak kullanmaz; alan tipine göre giriş kutusu (e-posta, sayı, tarih-saat, seçim, onay kutusu, çok
satırlı metin) ve tarayıcı doğrulaması (zorunlu, uzunluk, aralık, düzenli ifade) kendiliğinden gelir. Sunucunun
doğrulama hataları ilgili alanın altında gösterilir, girilen değerler korunur.

| Ayar | Anlamı |
|---|---|
| `fields` | Formdaki alanlar ve sırası (`name`, `label`, `help`, `multiline`); boşsa yazılabilir tüm alanlar. Hesaplanan ve dosya alanları formda olamaz; zorunlu alanlar formda olmalı |
| `submitLabel`, `successMessage` | Düğme ve teşekkür metni |
| `redirect` | Gönderimden sonra gidilecek adres (`https://…` veya `/…`) |
| `maxPerTenMinutes` | Aynı adresten 10 dakikada en çok gönderim (5) |

Gönderim koleksiyonun ekleme kuralından bağımsızdır: koleksiyon herkese kapalı kalırken yalnızca formdaki alanlar
yazılabilir. Spam koruması: insanların görmediği tuzak alanı (doluysa başarılı görünür ama kaydedilmez), imzalı zaman
damgası (en az 2 sn doldurma, en çok 2 saat geçerlilik) ve adres başına sınır. Gelen kayıt diğer kayıtlar gibi olay
üretir: gönderimde e-posta veya bildirim için Otomasyonlar'da koleksiyona "eklenince" kuralı kurun.

```json
"forms": [{"name": "iletisim", "collection": "messages", "title": "Bize ulaşın",
  "fields": [{"name": "name", "label": "Adınız"}, {"name": "email", "label": "E-posta"}, {"name": "body", "label": "Mesajınız", "multiline": true}]}]
```

### Sayfalar ve panolar

**Sayfalar** (Sunucu → Sayfalar; panelde Sayfalar ve panolar; `pages`) bir koleksiyonu `/pages/<ad>` adresinde liste,
`/pages/<ad>/<id>` adresinde detay olarak yayınlar. HTML sunucuda üretilir: JavaScript yok, arama motorları okur,
telefonda ve masaüstünde düzgün görünür (kart veya tablo düzeni, tema rengi, açık/koyu tema).

- Listede seçilen alanlar (boşsa ilk beşi; ilki başlık), sabit süzgeç (`status=yayında`, `@me` ziyaretçi), varsayılan
  sıralama; ziyaretçi listedeki alanlara göre sıralar, koleksiyonda arama alanı varsa arar (`?q=`), sayfalar (`?page=`).
- **Kurallar aynen uygulanır**: liste ve görüntüleme kuralı, alan gizliliği (gizli alan hiç görünmez). Kural giriş
  istiyorsa ya da sayfada **Giriş gereksin** açıksa ziyaretçi `/pages/_login`'de kullanıcı hesabıyla girer (iki adımlı
  doğrulama dahil). Oturum çerezdedir (yalnızca `/pages` altında, HttpOnly, SameSite=Lax; HTTPS'te Secure), süresi
  dolan token sessizce yenilenir; `owner` kuralında herkes yalnızca kendi kayıtlarını görür.
- Görseller sayfanın kendi adresinden (`/pages/<ad>/<id>/files/…`, aynı kural), konum OpenStreetMap bağlantısı, ilişki
  hedefin ilk metin alanıyla gösterilir. Sayfalar başka kaynak yüklemez ve çerçeveye konamaz.

**Panolar** (Sunucu → Panolar; panelde Sayfalar ve panolar; `dashboards`) görünümlerden beslenen kutulardır:
**sayı** (özetli görünümün değeri, örn. `sum_tutar`), **grafik** (gruplu özetin çubuk/çizgi grafiği), **tablo**
(görünümün ilk satırları) ve **harita** (görünümdeki konum alanı). Kutular panelde sürükle-bırakla, uygulamada
yukarı/aşağı ile sıralanır; genişlik 1-3 sütun. `shareToken` verilirse `/dashboards/<ad>?token=…` salt okunur olarak
paylaşılır (giriş gerekmez, adresi bilen görür; grafikler SVG, harita karo görselleri, betik yok). Yönetici verisi
`GET /api/_dashboards/<ad>`.

### Ödemeler (Stripe, iyzico, Paddle)

Sunucu → **Ödemeler** (`payments`) ödeme sağlayıcılarını bağlar; birden çok sağlayıcı açılabilir. Müşteri
**sağlayıcının kendi ödeme sayfasında** öder: kart bilgisi telefondan hiç geçmez. Sonuç sağlayıcının imzalı
bildirimiyle (webhook) gelir.

| Sağlayıcı | Ne zaman | Gerekenler |
|---|---|---|
| **Stripe** | Uluslararası kartlar, Apple/Google Pay; abonelik | Gizli anahtar (`sk_test_…` testte), webhook imza sırrı (`whsec_…`) |
| **iyzico** | Türkiye: yerli kartlar, taksit | API anahtarı ve gizli anahtar, test için sandbox; alıcının adı, kimlik no, telefonu, adresi |
| **Paddle** | Vergi ve fatura Paddle'da (satıcı kaydı); abonelik | API anahtarı, bildirim sırrı, istemci token'ı; varsayılan ödeme bağlantısı `<adres>/payments/paddle` |

**Erişim şart:** bildirimler telefona internetten ulaşmalı, bu yüzden doğrudan erişim veya **kendi alan adınızla**
Cloudflare Tunnel gerekir (hızlı tünelin adresi her başlatmada değişir). Konfigürasyon denetimi bunu ister; ekrandaki
**Denetle** adresin yanıt verdiğine bakar. `publicUrl` sunucunun HTTPS adresidir. Sağlayıcının panelinde bildirim adresi
olarak `<publicUrl>/payments/hook/<stripe|iyzico|paddle>` girilir (ekranda kopyalanır).

- **Bağlantı:** `POST /api/_payments` (yönetici) `{"amount": "149.90", "currency": "TRY", "description": "…",
  "provider"?, "collection"?, "record"?, "email"?, "buyer"? (iyzico), "data"?}` → `{id, token, url, page, status, …}`.
  `url` sağlayıcının ödeme sayfası, `page` (`/payments/<token>`) alıcının döndüğü durum sayfası (bildirim gecikirse
  durumu sağlayıcıya sorar; ödenince **makbuz**). Script'lerden `ctx.payments.create({...})`; panelde Konfigürasyon →
  Ödemeler'den de oluşturulur. Liste `GET /api/_payments?status=paid`.
- **Otomasyonda** `payment` eylemi: tutar `{total}`, ilişkili kayıttan `{service.price}` ya da hesap
  (`{product.price} * {quantity}`); ödeme sayfasının adresi seçilen alana, `{payment_url}` sonraki eylemlere (e-posta).
  Yeni kayıtta çalışır; güncellemede koşul `payment_url[null]=true` istemeli (bağlantı bir kez üretilir).
- **Durum:** `pending`, `paid`, `failed`, `canceled`, `refunded`. Bağlı kayıtta `payment_status` alanı (`statusField`)
  varsa oraya yazılır ve otomasyonlar, canlı aboneler, webhook'lar duyar. Ödenmiş ödeme geri gitmez (sırasız olay);
  aynı bildirim ikinci kez gelirse yok sayılır (`_payment_events`). iyzico'da sonuç her zaman iyzico'ya sorulur; iyzico
  panelinden yapılan iadeler altı saatte bir raporlama servisinden bulunur (son 90 gün).
- **İade:** Ödemeler ekranında veya panelde ödenmiş ödemenin yanında **İade et**, API'de `POST /api/_payments/<id>/refund`
  (tam iade). Stripe ve iyzico hemen onaylar; Paddle'da çoğu iade onay bekler, durum onay bildirimiyle `refunded` olur.
- **Abonelik** (Stripe, Paddle): planlar (`plans`: ad, başlık, sağlayıcıdaki fiyat kimliği, rol, isteğe bağlı kota).
  Kullanıcı `POST /api/payments/subscribe {"plan"}` ile ödeme sayfasına gider; abonelik sürdükçe planın rolünü alır,
  bitince önceki rolüne döner (yönetici rolü değişmez). Planın `quotas`'ı o roldeki kullanıcılara genel kota yerine
  uygulanır. `GET /api/payments/plans`, `GET /api/payments/subscriptions`; kullanıcı aboneliğini
  `POST /api/payments/subscriptions/<id>/cancel` ile **dönem sonunda** bitirir (rol dönem bitene kadar kalır,
  `canceling: true`). SDK'larda `payments` (`plans`, `subscribe`, `subscriptions`, `cancel`).
- **Makbuz:** sağlayıcının belgesi varsa ona (Stripe makbuzu, Paddle faturası), yoksa sunucunun ürettiği basit PDF
  (satıcı `merchant`). Fatura yerine geçmez.
- **Şablonlar:** ödemeler açıkken kurulan **Mağaza ve sipariş** ve **Randevu** şablonlarına ödeme eklenir. Randevuda
  bağlantı talep gelince hizmetin fiyatıyla üretilir (e-posta açıksa gönderilir); siparişte tutarı müşteri gönderdiği
  için bağlantı, siz siparişi **onaylandı** yapınca üretilir ve müşteri Siparişlerim'de görür. Müşteri ödeme alanlarını
  yazamaz.

### GraphQL

Sihirbaz → Genel → **GraphQL** açıkken `POST /api/graphql` (`{"query", "variables", "operationName"}`) çalışır. Şema
koleksiyonlardan üretilir; her koleksiyon için:

| Kök | Ne yapar |
|---|---|
| `<ad>(filter, sort, page, perPage, q)` | Liste: `{ items { … } totalItems page perPage }`; `filter` liste filtresi sözdiziminde (`done=false&points[gte]=3`) |
| `<ad>_by_id(id)` | Tek kayıt |
| `create_<ad>(data)`, `update_<ad>(id, data)`, `delete_<ad>(id)` | Mutasyonlar; `delete` `{ id deleted }` döndürür |

```graphql
query {
  acik: tasks(filter: "done=false", sort: "-points", perPage: 10) { totalItems items { id title project { name } } }
}
mutation Ekle($d: JSON!) { create_tasks(data: $d) { id title } }
```

İlişki alanı alt seçimle istenirse genişletilir (REST'teki `expand`, hedefin görüntüleme kuralıyla); alt seçimsiz
kimlik döner. Kurallar, alan gizliliği, uzman modu adları, script kancaları, otomasyonlar, webhook'lar ve canlı
abonelik REST'le aynıdır; REST'te kapatılan uç GraphQL'de de yoktur. Takma adlar, değişkenler, adlı ve satır içi
parçalar, `@include` / `@skip` desteklenir. Hatalar GraphQL biçimindedir: başarısız kök alan `null`, `errors[]`
içinde `path` ve `extensions.code` (REST durum kodu) ve doğrulama `details`'i. İçgözlem (`__schema`) yerine şema dili
(SDL) `GET /api/graphql/schema` adresindedir; kod üreticiler ve IDE'ler bu dosyayı kullanabilir. Sınırlar: en çok 8
düzey derinlik, 20 kök alan ve 1000 alan.

**Abonelikler** (canlı abonelik açıksa): `ws://<telefon>:8080/api/graphql/ws`, `graphql-transport-ws` protokolü
(graphql-ws, Apollo, urql istemcileriyle doğrudan çalışır). Kimlik `connection_init` içinde verilir (tarayıcı WebSocket'i
başlık gönderemez): `{"Authorization": "Bearer <token>"}`. Her koleksiyon için `<ad>(id, events)` kökü olay başına
`{ action record { … } }` gönderir; kurallar canlı abonelikle aynıdır (her olayda görüntüleme kuralı), silmede `record`
yalnızca `id` taşır, ilişkiler genişletilmez. Aynı bağlantıdan sorgu ve mutasyon da gönderilebilir.

```js
import { createClient } from "graphql-ws";
const client = createClient({ url: "ws://192.168.1.20:8080/api/graphql/ws", connectionParams: { Authorization: "Bearer " + token } });
client.subscribe({ query: 'subscription { tasks(events: ["create"]) { action record { id title } } }' }, { next: (e) => console.log(e.data), error: console.error, complete() {} });
```

### Yapay zekâ (Claude, OpenAI, Gemini) ve anlamsal arama

Sunucu → **Yapay zekâ**: uygulamalarınız yapay zekâyı sunucu üzerinden kullanır. Sağlayıcıyı siz seçersiniz; API
anahtarı telefonda şifreli depoda durur, istemcilere hiç gitmez; ücret o sağlayıcıdaki kendi hesabınızdan ödenir.

| Sağlayıcı (`provider`) | Hazır modeller | Notlar |
|---|---|---|
| **Claude** (`claude`, varsayılan) | Opus 5.5 (varsayılan), Sonnet 5.5, Haiku 5.5 | Resmi Claude Java SDK'sı. Opus ve Sonnet'te sunucu tarafı geri düşüş açık: güvenlik denetimi bir isteği reddederse Anthropic'in önerdiği modelle otomatik yeniden denenir |
| **OpenAI** (`openai`) | GPT-6 Astra, GPT-6.1 Sol (varsayılan), GPT-6 Luna | Chat Completions. `url` verilirse OpenAI uyumlu servis: telefondaki/ağdaki Ollama (`http://127.0.0.1:11434/v1`), LM Studio, OpenRouter; yerel serviste anahtar gerekmez |
| **Google Gemini** (`gemini`) | Gemini 4 Argon, Gemini 3.8 Flash (varsayılan), Gemini 3.5 Flash-Lite | `generateContent`; anahtar `x-goog-api-key` başlığında |
| **Telefonda** (`local`) | Qwen2.5 0.5B / 1.5B, Gemma 3 1B, Llama 3.2 1B (indirilir) | llama.cpp ile telefonda; anahtar ve ücret yok, veri telefondan çıkmaz (bkz. Telefonda yerel YZ) |

Listede olmayan bir model kimliği de yazılabilir. Özen düzeyi (düşük/orta/yüksek) Claude'da `effort`, OpenAI'de
`reasoning_effort`, Gemini'de `thinkingLevel` olarak gönderilir; desteklemeyen modellerde gönderilmez. Bir istek
reddedilirse yanıtta `refused: true` ve `stopReason: "refusal"` döner (sağlayıcıdan bağımsız). OpenAI ve Gemini
SDK'sız, doğrudan HTTP ile çağrılır (APK büyümesin diye).

| Uç | Ne yapar |
|---|---|
| `POST /api/ai/chat` | `{"prompt": "…"}` veya `{"messages": [{"role": "user", "content": "…"}, …]}` → `{text, model, stopReason, refused, usage, remaining}` |
| `POST /api/ai/ask/<koleksiyon>` | `{"question": "…", "limit": 20}`: ilgili kayıtlar (anlamsal dizin varsa anlamca en yakınlar, yoksa en yeniler; sorgu parametreleri filtre, `sort`, `q` olarak uygulanır) modele verilir; yanıt `[#id]` kaynaklarıyla ve `sources` listesiyle döner |
| `GET /api/ai/search/<koleksiyon>?q=…&limit=10` | Anlamsal arama: kelime geçmese de anlamca yakın kayıtlar, `_score` benzerliğiyle; diğer parametreler filtre |
| `GET /api/_ai`, `POST /api/_ai/reindex` | Yönetici: bugünkü istek ve token sayısı, dizin durumu; dizini yeniden kur |

Güvenlik: anonim istek kabul edilmez; **kim kullanabilir** "giriş yapmış kullanıcılar" veya "yalnızca yönetici".
Yönetici olmayan her kimliğe günlük istek sınırı (varsayılan 50) uygulanır, aşılınca 429. Kayıtlar her zaman
isteyenin liste kuralıyla okunur: modele ve sonuca yalnızca isteyenin zaten görebildiği kayıtlar ve alanlar girer;
kayıtlar modele "talimat değil veri" olarak verilir.

**Anlamsal arama** için metni vektöre çeviren bir gömme servisi seçilir: **Voyage AI** (Anthropic'in önerdiği; anahtar
dash.voyageai.com) veya **OpenAI uyumlu** bir uç (telefondaki ya da ağdaki Ollama, LM Studio; örn.
`ollama pull nomic-embed-text`, anahtarsız). Dizinlenecek alanlar koleksiyon başına seçilir (metin ve seçim alanları;
şifreli ve görünürlüğü kısıtlı alanlar dizinlenemez, çünkü metin dış servise gider ve sonuçlar içeriği sızdırırdı).
Vektörler veritabanında `_ds_embeddings` tablosunda durur; kayıt olaylarıyla arka planda güncellenir, açılışta eksikler
tamamlanır (değişmeyen kayıt yeniden gönderilmez). Yedeğe ve veri taşımaya girmez, gerektiğinde yeniden kurulur.

```json
"ai": {
  "enabled": true, "provider": "claude", "apiKey": "secret:ai_api_key", "model": "claude-opus-5-5", "dailyLimit": 50,
  "embeddings": { "provider": "voyage", "apiKey": "secret:embeddings_key" },
  "semantic": [ { "collection": "notes", "fields": ["title", "body"] } ]
}
```

#### Telefonda yerel YZ

Sağlayıcı **Telefonda (yerel model)** seçilince model llama.cpp ile telefonun işlemcisinde çalışır: anahtar, hesap ve
ücret yok; sohbet, kayıtlara soru, otomasyonlarda YZ adımı ve (yerel gömme modeliyle) anlamsal arama için veri
telefondan çıkmaz. Yapay zekâ → **Yerel modeller** ekranından model indirilir (Hugging Face'ten; boyut, gereken bellek,
lisans ve boş yer gösterilir; mobil veride ve düşük pilde uyarır; yarım kalan indirme sürer; dosya SHA-256 ile
doğrulanır):

| Model | Boyut | Not |
|---|---|---|
| Qwen2.5 0.5B Instruct | 470 MB | en küçük; eski telefonlar, kısa özet ve sınıflandırma |
| Gemma 3 1B | 770 MB | Türkçe dahil çok dilli |
| Llama 3.2 1B Instruct | 770 MB | genel amaçlı |
| Qwen2.5 1.5B Instruct | 1,1 GB | daha iyi yanıt; 4 GB+ bellek |
| EmbeddingGemma 300M (gömme) | 320 MB | çok dilli anlamsal arama |
| BGE-M3 (gömme) | 605 MB | güçlü çok dilli |
| all-MiniLM-L6-v2 (gömme) | 24 MB | küçük; İngilizce |

Model ilk istekte belleğe yüklenir, 5 dakika kullanılmazsa boşaltılır. Yüklemeden önce boş bellek ve telefonun ısısı
denetlenir: yetmezse istek `503` döner ve **sağlık uyarısı** gelir ("Yerel YZ çalıştırılamadı"). Yanıtlar bulut
modellerinden yavaş ve daha basittir; çalışırken pil tüketir. llama.cpp uygulamaya kaynaktan derlenir (yalnızca CPU,
arm64-v8a ve x86_64; APK'ya ABI başına yaklaşık 6 MB); 32 bit telefonlarda yerel YZ yoktur. F-Droid'in kuralı gereği
Gemini Nano (AICore) gibi kapalı ve hazır ikili çalışma zamanları kullanılmaz.

```json
"ai": { "enabled": true, "provider": "local", "model": "qwen2.5-0.5b-instruct-q4_k_m",
        "embeddings": { "provider": "local", "model": "embeddinggemma-300m-q8_0" } }
```

**Otomasyonlarda YZ adımı** (her sağlayıcıyla): Otomasyonlar → eylem **YZ ile alan doldur**: istem (`{alan}` kayıttan
dolar, örn. `Bir cümleyle özetle: {body}`) ve tetikleyen kaydın yazılacak alanı. Seçim alanında yanıt seçeneklerden biri
olmalıdır (sınıflandırma: "öncelik: düşük / yüksek"), çoklu seçimde uyan seçenekler, metinde uzunluk sınırına kırpılır.

### Çevrimdışı eşitleme

Koleksiyonda **Çevrimdışı eşitleme** (Sihirbaz → Koleksiyon → Geçmiş, çöp kutusu, saklama ve eşitleme; `sync`) açıkken
istemci SDK'ları (TypeScript, Kotlin, Dart, Swift) kayıtları cihazda tutar, bağlantı yokken de okur ve yazar, bağlanınca
eşitler. Sunucu her değişikliği veritabanı tetikleyicisiyle izler (API, script, içe aktarma ve zincirleme silmeler dahil):
kayıt başına sürüm, alan başına son değiştiği sürüm ve zaman, silinenler için iz (`keepDays` gün; daha uzun bağlanmayan
istemci baştan eşitlenir).

| Uç | Açıklama |
|---|---|
| `GET /api/sync/{c}?since=&epoch=&limit=` | İmleçten sonraki değişiklikler: `{epoch, reset, next, more, changes:[{id, version, action, record}]}`. Liste kuralı ve alan gizliliği uygulanır; istemcinin artık göremediği kayıt `delete` olarak gelir. `reset`: istemci yerel kopyayı silip baştan eşitlenmeli |
| `POST /api/sync/{c}` `{now, changes:[…]}` | Bekleyen değişiklikler (en çok 100): `create` (`tempId`), `update` ve `delete` (`id`, son görülen sürüm `base`, alan düzenleme zamanları `times`). Her biri ayrı uygulanır: `ok`, `merged`, `conflict`, `gone` veya `error` |

**Çakışma:** istemci çevrimdışıyken aynı alanı sunucuda başkası da değiştirdiyse varsayılan **son yazan kazanır**
(`lww`, alan başına; istemcinin saati gönderdiği `now` ile sunucu saatine göre düzeltilir): kaybeden alan sunucudaki
değerinde kalır, diğer alanlar uygulanır ve istemciye `merged` bildirilir. **Elle çöz** (`manual`) kipinde çakışan alan
uygulanmaz, istemci `onConflict` ile her iki değeri görür ve isterse yeniden yazar. Sunucuda daha yeni değişikliği olan
kaydın silinmesi de çakışmadır.

```ts
const notes = ds.notes.offline();                 // tarayıcıda IndexedDB, Node'da bellek; kendi deponuzu da verebilirsiniz
notes.onConflict = (c) => console.warn(c.status, c.local, c.server);
await notes.create({ title: "Uçakta yazıldı" });   // bağlantı gerekmez; kimlik eşitlenene kadar negatif
const stop = notes.live();                         // bağlanınca ve sunucuda değişiklik oldukça eşitler
```

```kotlin
val notes = ds.notes.offline(FileOfflineStore(context.filesDir.resolve("offline")))
notes.create(NotesInput(title = "Uçakta yazıldı"))
notes.sync()   // bloklayıcı; ağ yoksa IOException, bekleyenler kalır
```

Dart'ta `ds.notes.offline(FileOfflineStore(dir))`, Swift'te `try ds.notes.offline(FileOfflineStore(directory: url))`
(`actor`). Depo bir arayüzdür (anahtar → JSON metni): hazır olanlar bellek, dosya ve tarayıcıda IndexedDB; SQLite gibi bir
depo takılabilir. Sınırlar: dosya alanları yalnızca adıyla gelir (dosya indirilmez, çevrimdışıyken yüklenmez); çevrimdışı
oluşturulan kayda başka koleksiyondan ilişki, kayıt eşitlendikten sonra kurulmalı. Eşitleme ana sunucuyla yapılır (kopyaya
gelen eşitleme istekleri ana sunucuya iletilir).

### Takvim ve kişiler

Koleksiyonda **Takvim** (Sihirbaz → Koleksiyon → Takvim ve kişiler; `calendar`) açıkken alanlar etkinliğe eşlenir:
başlık (metin), başlangıç ve bitiş (tarih-saat), isteğe bağlı açıklama, yer (metin ya da konum), tüm gün (evet/hayır) ve
tekrar (`RRULE` metni, örn. `FREQ=WEEKLY;BYDAY=MO`).

- **Çakışma olmasın** (`noOverlap`): aynı **kaynakta** (`resource`: oda, kişi, araç alanı; boşsa kaynaksız kayıtlar
  kendi arasında) zamanı üst üste binen kayıt `409` ile reddedilir (`#12 kaydıyla çakışıyor`); uç uca eklenen olur.
  Bitiş başlangıçtan önce olamaz (`400`). Denetim yazmayla aynı işlemdedir; PostgreSQL'de eşzamanlı iki rezervasyon
  sıraya girer.
- **Takvim aboneliği** (`feed`): `GET /api/calendar/{koleksiyon}.ics` iCalendar döner. Yetkiyle (liste kuralı) ya da
  takvim uygulamaları başlık gönderemediği için gizli anahtarla: `…/bookings.ics?token=<feedToken>` (kurallar aşılır,
  adresi bilen herkes görür). Google Takvim, Outlook ve iOS "abone ol" ile eklenir; salt okunurdur.
- **Telefon takvimiyle eşitleme** (`dav`, CalDAV) ve **Kişiler** (`contacts`, CardDAV): ağ sürücüsü (WebDAV) açıkken
  aynı adres ve kullanıcı adı/parolayla. Android'de DAVx⁵ → "URL ve kullanıcı adıyla giriş" → `http://telefon:8080/dav/`;
  iOS Ayarlar → Takvim/Kişiler → Hesap ekle → Diğer → CalDAV/CardDAV; Thunderbird ve macOS da aynı adresle. Sunucu
  `/.well-known/caldav` ve `/.well-known/carddav`'ı bildirir. Telefondan eklenen, değiştirilen ve silinen etkinlik/kişi
  kayda yazılır (doğrulama, çakışma kuralı, script, otomasyon ve canlı abonelik olayı normal yoldan). Yalnızca eşlenen
  alanlar saklanır: alarm, katılımcı, fotoğraf gibi özellikler sunucuya gidince düşer. Saat dilimli zamanlar UTC'ye
  çevrilir; tüm gün etkinlikleri telefonun saat diliminde günün başıdır. Kişilerde ad, e-posta, telefon, kurum ve not.
  Parola düz HTTP'de Digest ile gider; Basic yalnızca HTTPS'te.

### Canlı abonelik

`realtime.enabled` iken (Sihirbaz → Genel → Canlı abonelik) istemciler kayıt değişikliklerini anında alır:
`GET /api/realtime?collections=todos,notes` bir **Server-Sent Events** akışı döndürür. Her olay:

```
id: 3f2a9c1b-17
data: {"action":"create","collection":"todos","record":{"id":5,"title":"süt al",...}}
```

`action` `create`, `update` veya `delete`'tir (silmede `record` yalnızca `{"id":..}`). Bağlanınca `event: ready` gelir.
Bağlanırken her koleksiyonda listeleme izni aranır (yoksa 401/403); her olayda görüntüleme kuralı uygulanır: `owner`
kuralında kullanıcı yalnızca kendi kayıtlarının olaylarını alır. Script'lerin `ctx.db` yazmaları da yayınlanır; ilişki
yüzünden zincirleme silinen veya boşaltılan kayıtlar için ayrı olay yayınlanmaz.

```js
// Tarayıcı: EventSource başlık gönderemez, token adreste verilir (yalnızca bu uçta kabul edilir).
const es = new EventSource(`${base}/api/realtime?collections=todos&token=${userToken}`);
es.onmessage = (m) => { const e = JSON.parse(m.data); /* e.action, e.collection, e.record */ };
es.addEventListener("reset", () => yenidenYukle()); // kaçırılan olaylar getirilemedi
```

Bağlantı en çok 30 dakika açık kalır, 25 saniyede bir boş satır gönderilir; `EventSource` kopunca `Last-Event-ID` ile
kendiliğinden yeniden bağlanır ve son 1000 olay içindeyse kaçırdıklarını alır, değilse (veya sunucu yeniden deploy
edildiyse) `reset` olayı gelir. Olayları okuyamayacak kadar yavaş kalan istemcinin bağlantısı kapatılır (yazmaları
bekletmez; istemci yeniden bağlanıp devam eder). `realtime.maxConnections` (varsayılan 100) aşılırsa 503. Adresteki token
proxy günlüklerinde görünebileceği için kısa ömürlü kullanıcı token'ı önerilir; başlık gönderebilen istemciler
(`fetch`, mobil uygulama, curl) `Authorization` başlığını kullanmalı. Web panelinin kayıt tablosu da bu akışla canlı yenilenir.

### Uzman modu: otomatik alanlar ve uçlar

Sihirbazın üst çubuğundaki **Uzman** düğmesi (uygulama ayarı, konfigürasyonu değiştirmez) otomatik eklenen her şeyi
görünür ve düzenlenebilir yapar:

- **Uçlar:** her yerleşik uç kapatılabilir (404) veya yolu değiştirilebilir. Genel adımında sunucu, yönetici ve web push
  uçları, Kullanıcılar adımında hesap ve sosyal giriş uçları, koleksiyon düzenleyicide kayıt uçları ("taban yol" ile tek
  seferde, örn. `/urunler` → `GET /api/urunler`, `GET /api/urunler/{id}`). Yoldaki parametreler (`{id}` gibi) korunmalı;
  çakışan yollar reddedilir. OpenAPI, istemci örnekleri ve web paneli yeni yolları kullanır.

  ```json
  "api": {"endpoints": {"auth.login": {"path": "/hesap/giris"}, "batch": {"enabled": false}}},
  "collections": [{"name": "urunler", "endpoints": {"list": {"path": "/urunler"}, "delete": {"enabled": false}}, ...}]
  ```
- **Sistem alanları:** koleksiyonlarda `id`, `created_at`, `updated_at`; kullanıcılarda ayrıca `email`, `password`,
  `verified`, `role`. Yeni ad verilebilir (`"systemFields": {"id": {"name": "kimlik"}}`) veya API'den kaldırılabilir
  (`{"removed": true}`). Ad yanıtlarda, filtre/sıralama/alan seçiminde, gövdelerde, hata ayrıntılarında, canlı
  abonelikte, webhook'larda ve dışa/içe aktarmada geçerlidir; özgün ad isteklerde de kabul edilir. Veritabanı sütunu
  değişmez (veri taşınmaz, geri almak kolaydır); script'ler özgün adları görür. `email` veya `password` kaldırılırsa
  parolayla kayıt/giriş/sıfırlama uçları kapanır (yalnızca sosyal giriş kalır).
- **Ayarlar:** token süreleri, hatalı giriş sınırı ve süresi (`auth.users.loginAttempts`, `loginBlockMinutes`), istek
  sınırları (`server.limits`).

### WebSocket kanalları

Canlı abonelik (SSE) kayıt değişikliklerini bildirir; sohbet, çok oyunculu oyun veya canlı imleç gibi **iki yönlü**
mesajlaşma için Sihirbaz → Genel → Canlı abonelik → **WebSocket kanalları** (`realtime.channels`). Kanal adı tam ad
(`lobby`) ya da sonu `*` olan kalıp (`room:*` → `room:42`); her kanalda kimin katılıp kimin gönderebileceği, bağlı
olanların listesi ve yeni katılana gönderilecek son ileti sayısı seçilir.

```js
const ws = new WebSocket("wss://evim.duckdns.org:8443/api/realtime/ws?token=" + token);
ws.onopen = () => ws.send(JSON.stringify({ type: "join", channel: "room:42" }));
ws.onmessage = (e) => {
  const m = JSON.parse(e.data); // joined {members, history} · message {from, data, at} · presence {event, member} · error
  if (m.type === "message") console.log(m.from?.user, m.data);
};
ws.send(JSON.stringify({ type: "send", channel: "room:42", data: { text: "selam" } }));
```

Script'ler sunucudan yayın yapabilir: `ctx.channels.publish("lobby", { duyuru: "bakım 22:00" })`. Kanallar bellekte
tutulur (kalıcı mesajlar için koleksiyon kullanın); bağlantı başına saniyede en çok 20 ileti, ileti en çok 16 KB.

### Telefon özellikleri (SMS, konum, durum)

Sihirbaz → Genel → **Telefon özellikleri** (`phone`): her biri ayrı açılır, açılınca Android izni istenir.

- **SMS gönderme**: doğrulama kodu, sipariş bildirimi. Yalnızca izinli ülke kodlarıyla başlayan numaralara (varsayılan
  `+90`) ve günlük sınırla (varsayılan 20; aşılınca 429) gönderilir; her gönderim denetim kaydına yazılır (numara
  maskeli, metin yazılmaz). Operatörünüzün SMS ücreti uygulanır.
- **Konum**: telefonun son bilinen konumu (GPS'i ayrıca çalıştırmaz).
- **Cihaz durumu**: pil, sıcaklık, şarj, boş depolama, bellek.

```js
routerAdd("POST", "/otp", (req, ctx) => {
  const code = String(Math.floor(100000 + Math.random() * 900000));
  ctx.sms.send(req.body.phone, "Doğrulama kodunuz: " + code);   // {sent, today, remaining}
  return { ok: true };
}, "public");
const where = ctx.device.location();   // {available, latitude, longitude, accuracyM, at}
const status = ctx.device.status();    // {batteryPercent, charging, …}
```

Yönetici API'si: `POST /api/_phone/sms {"to":"+90…","text":"…"}`, `GET /api/_phone/location`, `GET /api/_phone/status`.
Herkese açık bir SMS ucu yazacaksanız istemci başına sınır (güvenlik duvarı, kota) ve doğrulama ekleyin.

### Uygulama kilidi

Durum ekranında **Uygulama kilidi**: DroidServer açılırken ve arka planda seçilen süreden (hemen, 1 dk, 5 dk) uzun
kaldıktan sonra telefonun kendi doğrulaması (parmak izi, yüz veya ekran kilidi PIN'i) istenir. Açıkken ekran görüntüsü
alınamaz, son uygulamalar listesinde içerik görünmez; API anahtarı/token, şifreleme anahtarı, bulut yedeği parolası
gibi sırları kopyalamak ve erişim QR'ı ayrıca onay ister. Kilidi açıp kapatmak da doğrulama ister; telefonda ekran
kilidi yoksa kullanılamaz. Sunucu kilitliyken de çalışmaya devam eder.

### Hızlı ayar kutucuğu ve ana ekran widget'ı

Uygulamayı açmadan yönetim için:

- **Hızlı ayar kutucuğu:** bildirim panelini aşağı çekin, düzenle (kalem) simgesine basıp **DroidServer** kutucuğunu
  ekleyin. Dokununca sunucu açılır veya kapanır; açıkken kutucuk etkin görünür ve altında adres yazar (Android 10+).
  Telefon kilitliyken dokunulursa önce ekran kilidinin açılması istenir. Konfigürasyon yoksa uygulama açılır.
- **Widget:** ana ekranda boş bir yere uzun basın → Widget'lar → **DroidServer**. Durum, yerel ağ adresi, son bir
  saatteki istek sayısı ve pil gösterilir; **Başlat / Durdur** düğmesiyle aç/kapat, karta dokununca uygulama açılır.
  Değerler sunucu çalışırken yarım dakikada bir, durum değişince hemen yenilenir. Birden çok sunucu profili varsa
  başlıkta etkin profilin adı görünür; kutucuk ve widget her zaman etkin profili yönetir.

### Tasker ve otomasyon uygulamaları

Durum → ⚙ Ayarlar → **Tasker ve otomasyon** açıkken Tasker, MacroDroid veya Automate sunucuyu yönetebilir ve sunucu
olaylarına tepki verebilir. Ayar telefon düzeyindedir; komutlar ve olaylar etkin profile aittir.

**Komutlar** açık (explicit) yayınla gönderilir. Her komutta ayarlardaki gizli anahtar `token` ek değeri olarak
bulunmalıdır. Android yayını hangi uygulamanın gönderdiğini güvenilir biçimde bildirmediği için yetki bu anahtarla
verilir; anahtarsız, yanlış anahtarlı veya köprü kapalıyken gelen komut yok sayılır ve Loglar'a "reddedildi" yazılır.

| Action | Ne yapar |
|---|---|
| `dev.droidserver.action.START` | Sunucuyu başlatır (konfigürasyon yoksa bir şey yapmaz) |
| `dev.droidserver.action.STOP` | Sunucuyu durdurur |
| `dev.droidserver.action.DEPLOY` | Konfigürasyonu yeniden yükleyip deploy eder |

Tasker'da: Görev → Sistem → **Send Intent**. Action yukarıdakilerden biri, Extra `token:<anahtar>`, Package
`dev.droidserver.app`, Class `dev.droidserver.app.service.TaskerReceiver`, Target **Broadcast Receiver**.
MacroDroid'de: Eylem → Uygulamalar → **Send Intent** (aynı alanlar). Bilgisayardan denemek için:
`adb shell am broadcast -a dev.droidserver.action.STOP -n dev.droidserver.app/.service.TaskerReceiver --es token <anahtar>`.

**Olaylar** yalnızca ayarlarda seçilen uygulamalara (Tasker, MacroDroid, Automate veya paket adıyla eklenen) gönderilir:

| Action | Ek değerler |
|---|---|
| `dev.droidserver.event.STARTED` | `profile`, `port`, `url` (yerel ağ adresi) |
| `dev.droidserver.event.STOPPED` | `profile` |
| `dev.droidserver.event.FAILED` | `profile`, `reason` |
| `dev.droidserver.event.ALERT` | `profile`, `kind`, `title`, `message`, `resolved`, `critical` (sağlık uyarısı; bildirim kanalı açıkken) |

Tasker'da: Profil → Olay → Sistem → **Intent Received**, Action yukarıdakilerden biri; değerler `%url`, `%title` gibi
değişkenlerle gelir. Anahtar şifreli depoda durur; **Yenile** ile değiştirince eskisiyle gelen komutlar çalışmaz.

### Komut satırı aracı (CLI)

Sunucuyu bilgisayardan komutlarla yönetmek için tek dosyalık, bağımlılıksız bir araç (Node.js 18+; Windows, macOS,
Linux). Sunucu onu kimliksiz verir; uygulamada Sunucu → **Komut satırı (CLI)** kopyalanabilir komutları gösterir.

```bash
curl -o droidserver.mjs http://192.168.1.20:8080/api/_cli
node droidserver.mjs login http://192.168.1.20:8080/api          # yönetici anahtarı sorulur (ekranda görünmez)
node droidserver.mjs status                                     # durum, istekler, pil, boş alan
node droidserver.mjs list todos done=false --sort -created_at   # filtre: liste sözdizimi
node droidserver.mjs create todos '{"title":"Süt al"}'
node droidserver.mjs export todos --format json -o todos.json
node droidserver.mjs import todos yeni.csv --dry-run
node droidserver.mjs logs -f                                    # canlı log
node droidserver.mjs config get -o config.json && node droidserver.mjs deploy config.json
node droidserver.mjs backup create && node droidserver.mjs backup download <ad>
node droidserver.mjs api GET /_jobs                              # herhangi bir uç
```

- Anahtar `~/.droidserver/cli.json`'da yalnızca sizin okuyabileceğiniz izinlerle durur (adlandırılmış, iptal edilebilir
  bir anahtar önerilir). `--profile ev` ile birden çok sunucu; CI'da `DROIDSERVER_URL` ve `DROIDSERVER_KEY` ortam
  değişkenleri dosyanın yerine geçer.
- `login <adres> --email <e-posta>` kullanıcı olarak girer (iki adımlı doğrulama dahil); kurallar o kullanıcıya göre
  uygulanır. `connect "<droidserver://…>"` telefondaki QR metniyle bağlanır.
- `logs`, `config`, `deploy` ve `backup` web panelinin uçlarını kullanır: panel açık olmalı. `deploy` önce planı denetler,
  engel varsa durur; sonra sonucu loglardan izler.
- Kendinden imzalı HTTPS (doğrudan erişim) için sertifikayı verin: `NODE_EXTRA_CA_CERTS=sertifika.pem node droidserver.mjs …`.
- Mesajlar sistem diline göre Türkçe veya İngilizce. Dosya alanları yalnızca zip ile taşınır: `export notes --format zip -o notlar.zip`,
  `import notes notlar.zip`.

### MQTT (IoT)

Sensörler, ESP32/Arduino ve Tasmota gibi cihazlar telefona MQTT ile bağlanır (Sunucu → **MQTT (IoT)**, `mqtt`).
Telefonun kendi MQTT sunucusu vardır; ayrı bir broker gerekmez.

- **Kimlik:** tüm cihazlar için ortak kullanıcı adı ve parola (parola boş bırakılırsa üretilir, şifreli depoda) ve/veya
  **cihaz başına kimlik** (`mqtt.devices`): her cihazın adı kullanıcı adıdır, parolası ayrıdır; birini silmek diğerlerini
  etkilemez. Cihaz listesi varken ortak giriş kapatılabilir (ortak parola boş). Durumda her bağlantının kimliği görünür.
  Hatalı denemeler güvenlik duvarına sayılır. MQTT **yalnızca yerel ağda** çalışır: internetten gelen bağlantılar
  reddedilir (ev ağı, telefonla aynı IPv6 ağı ve Tailscale kabul edilir).
- **TLS (şifreli bağlantı):** `mqtt.tls` açıkken 8883'te (`tlsPort`) `mqtts://`. Telefon bir kez **yerel kök sertifika**
  üretir; sunucu sertifikası bu kökle telefonun yerel IP adresleri için imzalanır ve adres değişince ya da bitişine 30
  gün kalınca kendiliğinden yenilenir (aynı anahtarla). Kökü cihazlara bir kez yükleyin (MQTT ekranında **Kökü paylaş**,
  panelde **Kök sertifikayı indir**, `GET /_mqtt/ca.pem`): mosquitto `--cafile droidserver-ca.pem -p 8883`, ESP32
  `WiFiClientSecure.setCACert(...)`, Home Assistant'ta MQTT → Gelişmiş seçenekler → özel CA. IP adresiyle bağlanan
  cihazlarda ad doğrulaması da geçer. Düz bağlantı (1883, şifresiz) varsayılan olarak açık kalır; TLS açıkken
  kapatılabilir (`plain: false`). Durumda hangi cihazın TLS ile bağlı olduğu görünür.
- **Mesajları kaydet:** konu süzgecine uyan mesaj koleksiyona kayıt olarak eklenir; otomasyonlar, webhook'lar ve canlı
  abonelik de tetiklenir. JSON nesnesinin alanları koleksiyonun alanlarına yazılır (bilinmeyenler atlanır); düz değer
  (`23.5`, `on`) değer alanına. Konu alanı süzgecin `+` bölümünü tutar (cihaz/oda adı).
- **Kayıt olayları:** seçilen koleksiyonlardaki her değişiklik `droidserver/<koleksiyon>/<create|update|delete>`
  konusuna JSON olarak yayınlanır.
- **Komut:** uygulamadan veya `POST /_mqtt/publish {"topic":"ev/salon/lamba","payload":"on"}` (yönetici; `"retain": true`
  ile tutulur) ile cihazlara mesaj gönderin. Cihazlar birbirine de yayın yapabilir (`+`, `#` jokerleri).
- **Tutulan mesajlar (retain):** RETAIN bayraklı mesaj konunun son değeri olarak saklanır ve o konuya sonradan abone olan
  cihaza hemen gönderilir (Home Assistant ve Tasmota durumları için); boş içerik saklananı siler. Deploy ve yeniden
  başlatmalarda kalır; en çok 1000 konu ve toplam 4 MB.
- QoS 0, 1 ve 2 ile gelen mesajlar alınır, abonelere QoS 0 ile iletilir; vasiyet (will) yoktur.

**Ev otomasyonu:**

- **Otomasyonda MQTT:** eylem **MQTT mesajı gönder** (konu ve içerikte `{alan}`, isteğe bağlı tutulan); tetikleyici
  **MQTT mesajında** (konu süzgeci): cihaz mesajı kayda yazılmadan kuralı çalıştırır, içerik JSON ise alanları `{alan}`,
  ayrıca `{topic}`, `{payload}`. Örnek: `ev/+/hareket` gelince `ev/salon/lamba = on`.
- **Sahneler** (`mqtt.scenes`): adlı mesaj grupları (örn. "Akşam": lamba açık, perde kapalı). MQTT ekranından, ana
  ekrandaki **DroidServer sahneler** widget'ından (ilk dördü), panelden veya `POST /api/_mqtt/scenes/<ad>` ile çalışır.
- **Sensör panosu** (`sensors`; Sunucu → **Sensör panosu**, panelde Servisler): koleksiyondaki sayı veya evet/hayır alanı
  için son 6 saat / 24 saat / 7 gün grafiği; `min`/`max` dışındaki yeni değer sağlık uyarısı verir (Telegram, e-posta,
  bildirim). `GET /api/_sensors?hours=24`.
- **Home Assistant** (`mqtt.homeAssistant.enabled`): Home Assistant'ın MQTT entegrasyonunu telefona bağlayın; sensörler
  keşif mesajlarıyla (`homeassistant/sensor/…/config`, tutulan) kendiliğinden eklenir, değerler her yeni kayıtta
  `droidserver/<koleksiyon>/state` konusuna gider. Listeden çıkarılan sensör Home Assistant'tan da kalkar.

```json
"sensors": [{ "collection": "readings", "field": "temp", "name": "Salon", "unit": "°C", "deviceClass": "temperature", "min": 15, "max": 28 }],
"mqtt": { "homeAssistant": { "enabled": true },
  "scenes": [{ "name": "Akşam", "messages": [{ "topic": "ev/salon/lamba", "payload": "on" }, { "topic": "ev/salon/perde", "payload": "kapali", "retain": true }] }] }
```

```json
"mqtt": { "enabled": true, "port": 1883, "username": "device", "password": "secret:mqtt_password_ab12cd34",
  "routes": [{ "topic": "sensors/+/temp", "collection": "readings", "valueField": "temp", "topicField": "device" }],
  "events": ["readings"], "devices": [{ "name": "esp-salon", "password": "secret:mqtt_device_9f3a1c2b" }] }
```

```bash
mosquitto_pub -h 192.168.1.20 -u device -P <parola> -t sensors/salon/temp -m 23.5        # → readings kaydı (device=salon)
mosquitto_sub -h 192.168.1.20 -u device -P <parola> -t 'droidserver/readings/#'           # kayıt olayları
curl -X POST -H "Authorization: Bearer <anahtar>" -d '{"topic":"ev/salon/lamba","payload":"on"}' http://192.168.1.20:8080/api/_mqtt/publish
```

Durum: `GET /_mqtt` (bağlı cihazlar, abonelikleri, son mesajlar ve sonuçları). Uygulamadaki ekran da aynısını gösterir.

### Ağ sürücüsü (WebDAV)

Sunucu → **Ağ sürücüsü**: telefondaki klasörleri bilgisayarda sürücü gibi açar. Dosyaları sürükleyip bırakabilir,
doğrudan düzenleyebilir, klasör açabilirsiniz. Adres `http://<telefon>:8080/dav` (yol değiştirilebilir); kullanıcı adı
ve parola ekranda (parola boş bırakılırsa güçlü bir parola üretilir, şifreli depoda durur).

| Paylaşım | Klasör | Yazılabilir |
|---|---|---|
| `drive` | Telefondaki sürücü klasörü (her zaman; yedeğe girmez) | Evet ("Sürücü yalnızca okunur" kapalıyken) |
| `site` | Statik site (`www/`), isteğe bağlı | Evet; değişiklik hemen yayında |
| `scripts` | Script'ler, isteğe bağlı | Evet; deploy edince geçerli olur |
| `uploads` | Kayıtlara yüklenen dosyalar, isteğe bağlı | Hayır (salt okunur) |

Bağlanma:

- **Windows:** Bu bilgisayar → **Ağ konumu ekle** → adres. Kabul etmezse `\\<telefon>@8080\dav` yazın. Windows düz
  HTTP'de Digest kullanır, Basic'i yalnızca HTTPS'de dener (sunucu da Basic'i yalnızca HTTPS'de kabul eder); bu yüzden
  "parola yanlış" diyorsa kullanıcı adı ve parolayı yeniden girin, HTTPS'e geçmek gerekmez. **WebClient** hizmeti
  çalışıyor olmalı. Windows tek dosyayı varsayılan olarak 50 MB ile sınırlar (kayıt defterinde
  `WebClient\Parameters\FileSizeLimitInBytes`).
- **macOS:** Finder → Git → **Sunucuya Bağlan** → adres.
- **Linux (GNOME):** Dosyalar → Diğer Konumlar → `dav://<telefon>:8080/dav`; ya da `davfs2`.
- **Linux (KDE/Dolphin):** adres çubuğuna `webdav://<telefon>:8080/dav`; ya da Ağ → **Ağ klasörü ekle** → WebDAV,
  Sunucu: yalnızca IP (`http://` ve port olmadan), Kapı: `8080`, Klasör: `/dav`, şifreleme kapalı.
- **Telefon ve tablet:** Solid Explorer, CX Dosya Gezgini, iOS Dosyalar (WebDAV sunucusu ekle).

Güvenlik:

- Kimlik **Digest** ile doğrulanır; parola ağda açık gitmez, bu yüzden Windows düz HTTP'de de bağlanır. Basic yalnızca
  HTTPS üzerinden (doğrudan erişim veya tünel) kabul edilir; internetten bağlanacaksanız HTTPS adresini kullanın. Ağ
  sürücüsü ekranı bu adresi ayrıca gösterir (Linux'ta `davs://` GNOME, `webdavs://` KDE).
- Hatalı parolalar güvenlik duvarının hatalı deneme sayacına girer (istemcilerin kimliksiz ilk isteği sayılmaz).
- Yollar yalnızca paylaşılan klasörün içinde çözülür; `..`, sembolik bağlantılar ve klasör dışına çıkan yollar reddedilir.
- Tek dosya en çok `maxFileMb` (varsayılan 2048 MB); yükleme geçici dosyaya yazılır, tamamlanınca yerine konur.
- Kilitler gerçek değildir (istemciler yazmadan önce kilit ister); aynı dosyayı iki bilgisayardan aynı anda düzenlemeyin.

```json
"webdav": { "enabled": true, "path": "/dav", "username": "droid", "password": "secret:webdav_password", "site": true }
```

### Sorun giderici

Sunucuya ulaşılamıyorsa Durum → **Sorun giderici**: konfigürasyonu, sunucunun yanıt verip vermediğini, yerel ağ
adresini, internet bağlantısını, internetten erişimi (tünel adresi, modem yönlendirmesi, DuckDNS), sertifika süresini,
bileşenleri, pil optimizasyonunu, bildirim iznini, boş depolamayı ve etkin sağlık uyarılarını tek ekranda denetler.
Her bulgunun yanında çözüm düğmesi vardır (sunucuyu başlat, ilgili Android ayarı, sihirbazın Erişim adımı, Loglar).

Uzun süre çalışmayı engelleyenler de burada görünür: uygulamanın pil ayarı **Kısıtlı** ise (Android sunucuyu arka planda
durdurur), bileşen kullanılırken Android 12+'nın alt süreç kısıtı açıksa (bkz. Bilinen Android kısıtları) ve sistem
sunucu servisini öldürüp yeniden başlattıysa. Sonuncusunda Android 11+ önceki sürecin neden bittiğini de söyler (bellek
yetmedi, aşırı pil kullanımı, kullanıcı durdurdu…); aynı bilgi Loglar'a ve sağlık uyarısı olarak da düşer.

### Ortam ve sırlar (.env)

Sunucu → **Ortam ve sırlar**: tüm sunucu için ortak ortam değişkenleri (`env`). Script'ler `ctx.env`'den okur (script'e
özel `scripts.env` aynı adı ezer), kendi uygulamalarınıza ortam değişkeni olarak verilir, "ortak ortamı kullan" açık
bileşenler de alır. Her değişken ya **sır** (kilit simgesi; varsayılan) ya düz metindir:

- Sırlar Android Keystore anahtarıyla şifreli depoda durur; `server.json`'da, geçmişte ve yedeklerde yalnızca
  `secret:env_…` referansı bulunur. Ekranda maskelenir; **Değerleri göster**, **.env metni** ve **Dışa aktar** uygulama
  kilidi açıksa kimlik doğrulaması ister.
- **.env metni**: `AD=değer` satırları, `#` yorum, `export` öneki, tek/çift tırnak, çift tırnakta `\n` ve birden çok
  satırlı değer (sertifika). Değişken genişletme (`${AD}`) yapılmaz. Aynı ad iki kez yazılırsa sonuncusu geçerlidir.
- **İçe aktar** bir `.env` dosyasındaki değişkenleri ekler (yeniler sır sayılır); **Dışa aktar** tüm değerleri düz metin
  yazar (yeni telefona taşımak için; işiniz bitince silin). Yedekte sır değerleri bulunmadığından yeni telefonda yeniden
  girilir; eksik olanlar ekranda listelenir.
- Kaydedince sonraki deploy'da geçerli olur ("Kaydet ve deploy et" hemen uygular). Kaydedilmemiş sihirbaz taslağı
  korunur, aynı değişiklik ona da uygulanır.

### Kendi uygulamalarınız

Sunucu → **Uygulamalar**: kendi yazdığınız sunucuyu (Node.js, Python, Java/Kotlin veya derlenmiş bir ikili) telefonda
çalıştırın. Örnek: Express ile yazılmış bir API.

1. Bileşenler'den çalışma ortamını kurun (örn. **nodejs**; bkz. Bileşen kataloğu).
2. Uygulamalar → **+**: ad `api`, ortam `nodejs`, komut `node server.js`, port `3000`. Uygulama portu `PORT` ortam
   değişkeninden okumalı (`app.listen(process.env.PORT, "127.0.0.1")`).
3. **Zip yükle** (kodu bağımlılıklarıyla, örn. `node_modules` dahil) veya **Git'ten çek**. Deploy başlar.
4. Uygulama `https://<sunucu>/apps/api/…` adresinde yayınlanır: HTTPS ucu, tünel, güvenlik duvarı ve hız sınırı ondan
   da geçer. Yol öneki varsayılan olarak atılır (`/apps/api/users` → uygulamaya `/users`).

```json
"apps": [{
  "name": "api", "runtime": "nodejs", "command": ["node", "server.js"], "build": [["npm", "ci", "--omit=dev"]],
  "port": 3000, "path": "/apps/api", "access": "public", "health": "/health",
  "env": { "LOG_LEVEL": "info" }, "sharedEnv": true, "websockets": true,
  "git": { "provider": "github", "repo": "sahip/depo", "branch": "main", "subdir": "backend",
           "token": "secret:git_token_…", "webhookSecret": "secret:git_hook_…" }
}]
```

| Alan | Açıklama |
|---|---|
| `runtime` | Kurulu çalışma ortamı bileşeni (`nodejs`, `python`, `openjdk`); boşsa komut koddaki bir program veya `sh` |
| `command` | Başlatma komutu; ilk öğe ortamın `bin`'inde, sonra kodda (`node_modules/.bin`, `bin/`) aranır. Kabuk çalıştırılmaz |
| `build` | Her yeni sürümde bir kez, başlatmadan önce (en çok 5 komut, 15 dk). Telefonda derleme gerektiren paketler kurulamayabilir: bağımlılıkları zip'e koymak en güvenlisi |
| `port` | 1024-65535; sunucunun ve diğer uygulamaların portuyla aynı olamaz |
| `path`, `stripPath` | Yayın yolu (`/apps/<ad>`); API yolu, `/_admin`, `/_auth` ve başka uygulamanın yoluyla çakışamaz. `stripPath: false` ise önek uygulamaya iletilir (`BASE_PATH` ortam değişkeninde) |
| `access` | `public`, `auth` (giriş yapmış kullanıcı), `admin`. Kimlik sunucuda doğrulanır; uygulamaya `X-DroidServer-User`, `X-DroidServer-Role`, `X-DroidServer-Admin` (anahtarla gelince `X-DroidServer-Key`) başlıklarıyla gider; sunucunun token'ı uygulamaya iletilmez. İstemcinin gönderdiği `X-DroidServer-*` başlıkları atılır |
| `health`, `startTimeoutSec` | Sağlık denetimi yolu (2xx/3xx beklenir; boşsa yalnızca port) ve en çok bekleme (60 sn) |
| `env`, `sharedEnv` | Uygulamaya özel değişkenler (düz metin; sırları Ortam ve sırlar'da tutun) ve ortak değişkenlerin verilmesi (varsayılan açık) |
| `restart`, `keepReleases`, `maxBodyMb`, `websockets` | Çökünce (`on_failure`, `always`, `never`), saklanan sürüm (3), istek gövdesi sınırı (10 MB, yüklemeler dahil), WebSocket iletimi |

Sunucunun uygulamaya verdikleri: `PORT`, `HOST=127.0.0.1`, `BASE_PATH`, `DATA_DIR` (sürümler arası kalıcı klasör; SQLite
dosyanızı buraya koyun), `HOME`, `TMPDIR`, `DROIDSERVER_APP` ve çalışma ortamının değişkenleri. İstekler akış halinde
iletilir (SSE, uzun yanıtlar, büyük indirmeler bellekte biriktirilmez); `X-Forwarded-For/Proto/Host/Prefix` eklenir,
uygulamanın `/login` gibi yönlendirmeleri `/apps/api/login`'e çevrilir.

**Sürümler ve geri dönüş:** her yükleme veya Git çekimi yeni bir klasördür (`apps/<ad>/releases/<zaman>`); çalışan
sürüme dokunulmaz. Deploy'da yeni sürüm başlatılır ve sağlık denetimi beklenir; geçemezse (veya kurulum komutu başarısız
olursa) bırakılır, önceki sürüm yeniden başlar ve sorun Loglar'a ve bildirime yazılır. **Sürümler** listesinden eski bir
sürüme dönülebilir. Arşivdeki tek üst klasör (GitHub arşivleri) atlanır; betikler ve ELF ikilileri çalıştırılabilir
yapılır. Arşiv açılınca en çok 1 GB / 200 000 dosya, arşiv dışına yazan girdiler reddedilir.

**Git'ten deploy:** GitHub, GitLab (kendi sunucunuz dahil), Gitea/Forgejo veya düz bir zip adresi. Telefonda `git`
gerekmez: sağlayıcının API'siyle dalın son commit'i öğrenilir ve o commit'in arşivi indirilir; aynı commit zaten
yüklüyse indirilmez. Gizli depo için yalnızca okuma izinli token yeter (GitHub: ince ayarlı token, Contents: Read);
token şifreli depoda saklanır ve yalnızca deponun sunucusuna gönderilir (GitHub'ın arşiv yönlendirmesine gönderilmez).
**Push'ta otomatik deploy** açılınca bir webhook sırrı üretilir; deponun webhook ayarına
`https://<sunucu>/api/_apps/<ad>/hook` adresini, `application/json` içerik türünü ve sırrı girin. Doğrulama: GitHub
`X-Hub-Signature-256`, Gitea/Forgejo `X-Gitea-Signature` (HMAC-SHA256), GitLab `X-Gitlab-Token`; CI'dan çağırmak için
`X-DroidServer-Token` başlığı. Yalnızca seçili dala push deploy edilir; ping olayı yanıtlanır. Yönetici `POST
/api/_apps/<ad>/deploy` ile çekip deploy eder, `GET /api/_apps` durumları listeler.

**Kısıtlar:** uygulama yalnızca `127.0.0.1`'de dinlemeli, ama telefondaki başka uygulamalar da bu porta doğrudan
bağlanabilir (Android'de yerel portlar uygulamalar arası ortaktır); `auth`/`admin` kuralı yalnızca sunucudan gelen
isteklere uygulanır, hassas işlemlerde kendi kimlik doğrulamanızı da kullanın. Docker yok (root gerekir). Normal Linux
(glibc) ikilileri çalışmaz; Termux derlemeleri veya statik derlenmiş programlar gerekir. Android 12+ alt süreç sayısını
sınırlar (bkz. Bilinen Android kısıtları). WebSocket alt protokol (Sec-WebSocket-Protocol) seçimi iletilmez. JVM
uygulamaları 200-300 MB bellek kullanır; komuta `-Xmx256m` gibi bir sınır ekleyin.

### Sunucu profilleri

Sunucu → Profiller: aynı telefonda birden çok bağımsız sunucu (örn. "Ev", "Test", "Müşteri A"). Her profilin kendi
konfigürasyonu, portu, SQLite veritabanı, yüklenen dosyaları, script'leri, statik sitesi, yedekleri ve geçmişi vardır;
mevcut kurulum "Varsayılan" profildir (klasör düzeni değişmez, diğerleri `files/profiles/<id>/` altında). Aynı anda bir
profil çalışır: geçişte çalışan sunucu durur, yeni profil kuruluysa onun sunucusu başlar (kurulmadıysa sihirbaz açılır).
Yüklü bileşenler (PostgreSQL, cloudflared), HTTPS sertifikası ve şifreli sır deposu ortaktır; PostgreSQL kullanan
profillere farklı veritabanı adı verin. Profil silinince verisi de silinir (varsayılan ve etkin profil silinemez).

### Canlı kopya (ikinci telefon)

Ana telefon çökerse veriler kaybolmasın ve hizmet sürsün diye ikinci bir telefon **salt okunur canlı kopya** tutabilir.

1. Ana sunucuda Sihirbaz → Veritabanı → **Bu sunucu kopyalanabilir** (`replication.source`). Kopyalanan her tabloya
   veritabanı tetikleyicisi kurulur: API, script, içe aktarma, hesap işlemleri ve zincirleme silmeler dahil her yazma
   sıra numarasıyla `_changes` günlüğüne düşer (`replication.keepDays`, 7 gün).
2. İkinci telefonda Sihirbaz → Veritabanı → **Bu telefonu başka bir sunucunun kopyası yap**: **Yerel ağda ara** (mDNS) veya
   adresi yazın, ana sunucunun yönetici anahtarını girin (adlandırılmış tam yetkili anahtar önerilir), **Bağlan**.
   Koleksiyonlar, kurallar, kullanıcı ayarları ve sırlar (anahtarlar, JWT sırrı, SMTP…) anadan gelir; port, veritabanı,
   erişim, yedek ve uyarı ayarları kopyanın kendisinin kalır. Kopya PostgreSQL, ana SQLite olabilir (veya tersi).
3. Deploy: ilk kopyalama tabloları sayfa sayfa alır (dosyalar dahil), sonra `intervalSec` (5) saniyede bir artımlı
   eşitlenir. Durum ekranındaki **Canlı kopya** kartı geride kalan değişiklik sayısını, son temas ve hatayı gösterir;
   5 dakikadan uzun süre eşitlenemezse sağlık uyarısı gelir.

Kopya kendi veritabanına **yazmaz**: okuma istekleri aynı anahtar ve token'larla kopyada yanıtlanır; veri yazmaları
(kayıt ekleme/güncelleme/silme, giriş, kayıt olma, takım, push aboneliği, toplu işlem, GraphQL ve eşitleme; uzman
modundaki yollarıyla) **ana sunucuya iletilir** ve yanıtı istemciye döner. İstek istemcinin yetki başlığıyla aynen gider;
kuralları ve doğrulamayı yalnızca ana sunucu uygular. İstemcinin adresi de iletilir (ana sunucu bunu yalnızca kendi API
anahtarıyla gelen kopyaya güvenerek kullanır), böylece istek sınırı ve güvenlik duvarı kopyaya değil istemciye uygulanır.
Ana sunucuya ulaşılamazsa yazma `502` ile açıkça reddedilir. Yönetim uçları (SQL konsolu, MQTT, yedek…) kopyanın kendisinde
kalır; zamanlanmış görevler ve iş kuyruğu bekler. Ana sunucunun konfigürasyonu değişince (yeni koleksiyon, alan, kural) kopya bunu görür,
yeni konfigürasyonu kaydedip kendini yeniden kurar; o sırada değişiklik uygulamaz. Oturumlar ve iş kuyruğu kopyalanmaz.

**Elle devralma:** kopyada Durum → Canlı kopya → **Devral**: eşitleme durur, kopya yazılabilir ana sunucu olur (kimlik
sayaçları ileri alınır, denetim kaydına yazılır) ve kopyalanabilir kalır. Anahtarlar aynıdır, kullanıcıların erişim
token'ları geçerli kalır (yenileme token'ları ana sunucudaydı: süresi dolan yeniden giriş yapar). Kopya en fazla
`intervalSec` kadar geridedir; son saniyelerdeki yazmalar kaybolabilir.

**Otomatik devralma** (kopyada Sihirbaz → Veritabanı → **Otomatik devral**, `replication.failover.auto`): ana sunucudan
`afterSec` (120) saniye boyunca hiç yanıt gelmezse kopya kendiliğinden devralır ve kritik sağlık uyarısı gönderir. Herhangi
bir HTTP yanıtı (hata kodu dahil) ana sunucunun ayakta olduğu sayılır. Kopan bu telefon olmasın diye devralmadan önce:
ilk kopyalama bitmiş olmalı, telefon bir ağa bağlı olmalı ve **tanık adresi** (`witness`, örn. modem `http://192.168.1.1`)
ayarlıysa ona ulaşılmalı.

**İki sunucuya ayrı ayrı yazılmaz:** devralan sunucu eski ana sunucuyu hatırlar (`formerPrimary`) ve 15 saniyede bir ona
"devredildin" der (`/_replication/fence`). Eski ana sunucu bunu alınca yazmayı hemen keser, kendi verisini yedekler
(`devir-oncesi` yedeği) ve yeni ana sunucunun kopyası olur: veriler baştan oradan alınır. Eski ana sunucu kapalıyken
devralındıysa açılışta, yazmaları açmadan önce, kendisine bağlanmış kopyalara sorar (`/_replication/role`); biri onu
devraldıysa salt okunur açılır ve kopyaya döner.

**Geri alma** (ana sunucuda **Dönünce ana rolünü geri al**, `failback`): devredilip kopyaya dönen eski ana sunucu bir
dakika güncel kalınca yeni ana sunucuya yazmayı kısa süre durdurtur (`/_replication/step-down`), son değişikliğe kadar
eşitler ve ana rolünü **kayıpsız** geri alır; öbürü yeniden kopya olur. 30 saniyede yetişemezse vazgeçer ve yazmalar
açılır (iki dakika sonra kendiliğinden de açılır).

**İstemciler yeni ana sunucuyu nasıl bulur:**

- **DuckDNS:** iki telefonda aynı DuckDNS adını ayarlayın. Kopya adı yayınlamaz; devralınca yeni ana sunucu yayınlar.
- **Yedek adres (SDK):** istemciye iki adresi de verin; bağlantı kurulamazsa veya sunucu `503` dönerse (salt okunur kopya)
  sıradaki denenir ve çalışan adres hatırlanır.

**Birden çok kopya ve okuma dağıtımı:** bir ana sunucuya istediğiniz kadar kopya bağlanabilir. Ana sunucu her kopyanın
adresini, bağlı olup olmadığını, kaç değişiklik geride olduğunu ve konfigürasyon sürümünü tutar (uygulamada Durum →
**Kopyalar**, panelde Servisler → Canlı kopya, `GET /_replication/replicas`). SDK'lara `readUrls` verilirse okumalar
sırayla kopyalara gider (ulaşılamazsa ana adres); bir yazmadan sonra `readYourWritesMs` (5000) boyunca okumalar ana
sunucudan yapılır, böylece istemci kendi yazdığını hemen görür.

```ts
const ds = new DroidServer("http://192.168.1.20:8080/api", { apiKey, readUrls: ["http://192.168.1.21:8080/api", "http://192.168.1.22:8080/api"] });
```

```kotlin
val ds = DroidServerClient("http://192.168.1.20:8080/api", apiKey = key, fallbackUrls = listOf("http://192.168.1.21:8080/api"))
```

```ts
const ds = new DroidServer("http://192.168.1.20:8080/api", { apiKey, fallbackUrls: ["http://192.168.1.21:8080/api"] });
```

```bash
# Ana sunucu uçları (yönetici): kopyalar bunları kullanır
curl -H "Authorization: Bearer <anahtar>" "http://<ana>:8080/api/_replication/changes?after=0&limit=100"
curl -H "Authorization: Bearer <anahtar>" "http://<ana>:8080/api/_replication/snapshot?table=todos&after=0"
curl "http://<sunucu>:8080/api/_replication/role"     # {"role":"primary|replica|fenced","id":…}; kimliksiz
```

### Yerel ağ keşfi ve QR

Sunucu çalışırken yerel ağda **mDNS/DNS-SD** ile duyurulur (`_droidserver._tcp`; ad, port, TXT'de `path` = API yolu,
doğrudan erişimde `https` portu, `v`). Anahtar veya token duyurulmaz; kapatmak için Sihirbaz → Erişim → **Yerel ağda
görünür ol** (`access.discovery: false`).

```bash
dns-sd -B _droidserver._tcp          # macOS;  Linux: avahi-browse -r _droidserver._tcp
```

Durum → **Bağlan (QR)** üç tür QR üretir (adres seçilebilir: yerel IP, HTTPS adresi veya elle yazılan tünel adresi):

- **Yalnızca adres:** `droidserver://connect?url=http%3A%2F%2F192.168.1.20%3A8080%2Fapi&name=Evim`.
- **Adres + erişim:** JWT modunda 1 saat / 1 gün / 7 gün geçerli **kısa ömürlü token** (`&token=…&exp=…`); API anahtarı
  modunda ana anahtar (süresizdir, uyarıyla; sınırlı erişim için adlandırılmış anahtar önerilir).
- **Panele giriş:** `http://…/_admin/#pair=<kod>`; QR'ı okuyan cihazın tarayıcısı panele kendiliğinden girer. Kod tek
  kullanımlık, 5 dakika geçerli; adresin `#` parçası sunucuya gönderilmez ve girişten sonra adresten silinir.

İstemci SDK'ları bağlantı metnini okur ve (Kotlin) yerel ağda arar:

```kotlin
val ds = DroidServerClient.fromLink(qrText)            // JWT ise token, değilse API anahtarı olarak
val servers = DroidServerClient.discover(2_000)        // [FoundServer(name, port, addresses, attributes)], .baseUrl
```

```ts
const ds = DroidServer.fromLink(qrText);               // tarayıcılar mDNS yapamaz; parseLink(qrText) de var
```

Android istemcide keşif için `WifiManager.MulticastLock` gerekebilir; Android'in kendi `NsdManager`'ı da aynı türü
(`_droidserver._tcp`) bulur.

### Dil / Language

Durum ekranının altındaki **Dil / Language** seçimi: *Telefonun dili* (varsayılan; telefon Türkçe değilse İngilizce),
**Türkçe** veya **English**. Seçilen dil uygulamanın kendisi, web paneli, API hata ve doğrulama iletileri, OpenAPI
belgesi ve gönderilen e-postalar için geçerlidir (uygulama ve sunucu aynı süreçte çalıştığı için tek dil). Değişince
uygulama yeniden açılır; sunucu çalışıyorsa durdurulup yeniden başlatılır. Loglar ve kayıtlı veriler (örn. şablonların
örnek kayıtları, denetim kaydındaki ayrıntılar) çevrilmez.

Kaynak metinler Türkçedir ve katalog anahtarıdır: Kotlin'de `tr("{0} kayıt silindi", n)`, panelde `T("Kaydet")`.
Kataloglar `core/src/main/resources/i18n/` altında: `en.json` (uygulama + sunucu) ve `panel-en.json` (web paneli;
`/_admin/i18n.js` ile yüklenir). Yeni dil: `Lang`'a bir satır ve iki katalog dosyası. `I18nTest` kaynaktaki her metnin
katalogda olduğunu ve `{0}` yer tutucularının eşleştiğini denetler; yeni metin eklerken kataloğa da ekleyin.

## İnternetten erişim

Sihirbazın **Erişim** adımında seçilir. Her yolda uygulama yapabileceğini kendisi yapar, yapamadığını adım adım anlatır;
sunucu çalışırken Durum ekranı adresleri ve algılanan sorunları gösterir.

| Yol | Ne gerekir | Uygulamanın yaptığı | Bilinmesi gerekenler |
|---|---|---|---|
| **Yalnızca yerel ağ** | — | `http://<telefon-ip>:<port>` | Misafir ağları cihazları birbirinden yalıtabilir |
| **Doğrudan (IP)** | Wi-Fi: modemde port yönlendirme; mobil: operatörden genel IPv6 | HTTPS ucu, sertifika (kendinden imzalı veya DuckDNS adıyla Let's Encrypt), modemde otomatik port açma (NAT-PMP / UPnP), CGNAT tespiti, DuckDNS güncelleme | CGNAT'ta Wi-Fi'da çalışmaz; mobil veride yalnızca IPv6 |
| **Cloudflare Tunnel** | Hızlı tünel: hiçbir şey; kendi alan adı: ücretsiz hesap + alan adı | `cloudflared` bileşenini kurar ve çalıştırır, hızlı tünel adresini gösterir | Hızlı tünel adresi her başlatmada değişir; trafik Cloudflare'den geçer |
| **Tailscale** | Telefonda ve erişecek cihazda Tailscale uygulaması | Uygulamanın kurulu/bağlı olduğunu algılar, Tailscale adresini gösterir | Sunucu internete açılmaz; Android'de aynı anda tek VPN |

**Doğrudan erişim ayrıntıları** (`core/net/`):

- **HTTPS ucu** (`TlsProxy`): Ktor CIO TLS desteklemediği için TLS `access.direct.port`'ta (varsayılan 8443, IPv6 +
  IPv4) sonlanır ve istek `127.0.0.1:<server.port>`'a aktarılır. Yalnızca TLS 1.2/1.3; en çok 64 eşzamanlı bağlantı,
  başlıklar 15 sn'de gelmezse ve 16 KB'ı aşarsa bağlantı kapanır (yavaş istemci saldırısı). İstemcinin gönderdiği
  `X-Forwarded-For` vb. silinir, gerçek adres eklenir. Erişim kapatılınca açık bağlantılar da kesilir.
- **Sertifika** (`TlsIdentity`): ilk kullanımda EC P-256 kendinden imzalı sertifika üretilir, uygulama klasöründe
  saklanır (parolası şifreli depoda), böylece parmak izi değişmez. İstemciler genel anahtarı sabitler:
  `curl --pinnedpubkey "sha256//<pin>" -k https://<adres>:8443/api/health` (Durum ekranında kopyalanır).
- **Let's Encrypt** (`CertificateManager`, `AcmeClient`; isteğe bağlı, DuckDNS adı gerekir): sihirbazda "Let's Encrypt
  sertifikası al" açılınca `<ad>.duckdns.org` için herkesçe tanınan sertifika alınır; tarayıcı uyarısı kalkar, `-k` ve
  sabitleme gerekmez. Doğrulama DNS-01 ile yapılır: değer DuckDNS'in TXT kaydına yazılır (80/443 portları gerekmez,
  HTTPS hangi portta olursa olsun çalışır), iş bitince silinir. Sertifika HTTPS ucunun **mevcut anahtarıyla** istenir,
  bu yüzden açık anahtar sabitlemesi (pin) değişmez. 90 günlük sertifika bitişine 30 gün kalınca kendiliğinden yenilenir;
  alınamazsa kendinden imzalı sertifikayla devam edilir, 6 saat sonra yeniden denenir (Let's Encrypt'in başarısız
  doğrulama sınırına takılmamak için). ACME hesabı telefonda üretilen ayrı bir anahtardır, e-posta istenmez. Seçeneği
  açmak Let's Encrypt kullanım koşullarını kabul etmektir. Tarayıcı yalnızca ada güvenir; IP adresiyle bağlanılırsa uyarı verir.
- **Port yönlendirme** (`PortMapper`): Wi-Fi'da önce NAT-PMP, olmazsa UPnP IGD; süreli açılır, yarı ömründe
  yenilenir, sunucu durunca kaldırılır. Olmazsa elle yönlendirme adımları (DHCP rezervasyonu ve "cihaz MAC adresi"
  dahil) gösterilir.
- **Ulaşılabilirlik** (`Reachability`): modemin dış adresi paylaşımlı (100.64/10) veya özel ise CGNAT, internetten
  görünen adresten (api.ipify.org) farklıysa çift NAT uyarısı; mobilde IPv4 CGNAT, IPv6 operatöre bağlı.
- **DuckDNS** (isteğe bağlı): adres değiştikçe A/AAAA kaydı güncellenir; Let's Encrypt doğrulamasında TXT kaydı da.
- Ağ değişince (Wi-Fi ↔ mobil, yeni adres) birkaç saniye içinde yeniden kontrol edilir; ayrıca 10 dakikada bir.

**Cloudflare Tunnel:** `cloudflared` paketi Termux derlemesinden üretilir (`tools/package-cloudflared.sh`): bionic
libc'ye bağlı olduğu için adresleri Android'in çözümleyicisiyle çözer (`GODEBUG=netdns=cgo`; Özel DNS ve VPN'e uyar).
Go'nun okuduğu `resolv.conf` yolu binary'de uygulamanın yazdığı `files/dns.conf`'a çevrilir (ağın DNS sunucuları; ağ
değişince yenilenir). Hızlı tünel `cloudflared tunnel --url http://127.0.0.1:<port>` ile çalışır, adres günlükten okunur.
Kendi alan adınızla: Zero Trust > Networks > Tunnels'da tünel oluşturup Public hostname'i `http://localhost:<port>`
yapın, token'ı sihirbaza girin.

## Uygulama güncellemesi

Durum ekranındaki **Uygulama güncellemesi** kartı yeni sürümü GitHub Releases'ta (`kasimtmc/droidserver`) arar: ayar
açıksa günde bir (sunucu çalışırken arka planda, yeni sürüm bir kez bildirilir; uygulama açılınca da), ayrıca "Şimdi
denetle" ile. Ön sürümler (beta) yalnızca seçilirse önerilir; otomatik denetim kapatılabilir.

"İndir ve kur": APK indirilir, sürümdeki `.sha256` dosyasıyla özeti doğrulanır (özet dosyası olmayan sürüm kurulmaz),
paket adı, sürüm kodu ve **imza sertifikası** kurulu uygulamayla karşılaştırılır, sonra Android'in `PackageInstaller`'ı
ile kurulur; Android her kurulumu ayrıca onaylatır ("bilinmeyen uygulamaları yükleme" izni yalnızca bu uygulama için
bir kez istenir). Kurulumda uygulama kapanır; sunucu çalışıyorsa yeni sürüm açılınca kendiliğinden yeniden başlar
(`MY_PACKAGE_REPLACED`), veriler ve ayarlar korunur.

Android Studio'dan kurulan **debug** sürümü farklı anahtarla imzalıdır; yayın sürümleri onun üzerine kurulamaz (kart
bunu söyler). Yayın sürümüne geçmek için Yedekler'den yedek alıp dışa aktarın, uygulamayı kaldırın, sürüm sayfasındaki
APK'yı kurun ve yedeği geri yükleyin.

## F-Droid ve gizlilik

F-Droid için meta veri `fastlane/metadata/android/` altındadır (başlık, açıklamalar, sürüm notu, ekran görüntüleri);
gönderim adımları ve açık konular `docs/fdroid/README.md`'de. Yayın derlemesi tekrarlanabilir olacak şekilde ayarlıdır
(APK'ya derleme ortamının Git bilgisi ve Google'ın şifreli bağımlılık bloğu yazılmaz): aynı kaynaktan iki temiz derleme
bayt bayt aynı APK'yı verir. Gizlilik politikası ve izin gerekçeleri: [PRIVACY.md](PRIVACY.md). Lisans: GPL-3.0-or-later ([LICENSE](LICENSE)). Play Store sürümü
targetSdk 28 kaldıkça yok (bkz. Neden targetSdk 28?).

## Sürüm yayınlama

`.github/workflows/release.yml`: `v1.2.3` etiketi gönderilince (veya Actions'tan elle, sürüm girerek) testler koşar,
imzalı yayın APK'sı üretilir, `apksigner` ile denetlenir ve sürüme `droidserver-1.2.3.apk` + `.apk.sha256` olarak
yüklenir (`-beta.1` gibi ekli sürümler ön sürüm olur). Sürüm notu `.github/release-notes/<sürüm>.md` varsa onunla başlar
(şablon: `TEMPLATE.md`; uygulama içi güncelleme bu metni gösterir), altına commit'lerden üretilen notlar eklenir. Sürüm adı ve kodu etiketten
gelir (`-PappVersion=1.2.3`; kod `1.2.3 → 1020399`, ön sürümler aynı sürümün kararlısından küçük).

İmza anahtarını bir kez oluşturun ve dört secret ekleyin (Settings → Secrets and variables → Actions veya `gh`).
`keytool` JDK ile gelir (PATH'te yoksa Android Studio'nun `jbr/bin/keytool`'u). Depo PKCS12 biçiminde olduğu için ayrı
anahtar parolası sorulmaz: `ANDROID_KEY_PASSWORD` depo parolasıyla aynıdır.

```bash
keytool -genkeypair -keystore release.jks -alias droidserver -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEY_ALIAS --body droidserver
gh secret set ANDROID_KEYSTORE_PASSWORD    # parolayı sorar
gh secret set ANDROID_KEY_PASSWORD         # aynı parola
```

Sürümü etiketle (`git tag v0.9.0 && git push origin v0.9.0`) ya da iş akışını elle çalıştırın; etiketi o oluşturur:

```bash
gh workflow run release.yml -f version=0.9.0
```

Uygulama içi güncellemeyi gerçek sürümle denemek için aynı commit'ten bir sonraki sürümü çıkarmak yeterlidir
(`-f version=0.9.1`); 0.9.0 kurulu telefonda Durum → ⚙ Ayarlar → Güncelleme → Şimdi denetle.

`release.jks`'i ve parolalarını güvenli bir yerde saklayın: anahtar kaybolursa kurulu uygulamalar yeni sürümleri
güncelleme olarak alamaz. Uygulama indirirken kimlik doğrulamadığı için sürümler herkese açık olmalıdır. Bileşen
paketleri ayrı iş akışıyla (`components.yml`) `components` sürümüne yüklenir; güncelleme denetimi onu atlar.

## Bileşenler (yan servisler)

Uygulama, sizin barındırdığınız bir `components.json` manifestinden paket indirir
(örnek: `docs/components.example.json`; Postgres için bu deponun GitHub Releases'ı, bkz. PostgreSQL). Her paket için:

1. ABI'ye uygun zip indirilir, **sha256 doğrulanır** (uyuşmazsa kurulmaz).
2. Zip-slip korumalı açılır, `executables` dosyalarına çalıştırma izni verilir.
3. İlk başlatmada `setup` komutları bir kez çalışır (örn. Postgres için `initdb`).
4. `ProcessSupervisor` süreci başlatır; `restart` politikasına göre 1 sn → 60 sn arası geri çekilmeyle
   yeniden başlatır, 10 denemeden sonra `FAILED` durumuna geçer.
5. Deploy'da bileşen yalnızca tanımı (komut, ortam, sürüm …) değiştiyse veya kalıcı olarak durduysa yeniden başlar.
6. Paket `"readiness": { "tcpPort": 5432, "timeoutSec": 30 }` tanımlıyorsa sunucu, port bağlantı kabul edene
   kadar deploy edilmez. Süre dolarsa veya süreç çökerse hata loglanır ve deploy devam eder.

7. `setupFiles` ilk kurulum süresince var olan geçici dosyalardır (örn. initdb parola dosyası); yalnızca sahibi
   okuyabilir ve kurulum bitince (başarısız olsa da) silinir. `stopCommand` verilirse durdururken SIGTERM yerine
   önce o çalışır (Postgres için `pg_ctl stop -m fast`).

8. `tasks` kullanıcının çalıştırabileceği bakım işleridir (örn. Postgres `set-password`): bileşen önce durdurulur
   (`stopFirst`), komut çalışır, `stdin` verilmişse komuta yazılır; sonraki deploy bileşeni yeniden başlatır.
   Sır içeren değerler çıktıda `***` olarak gizlenir.

Yer tutucular: `${PKG}` paket klasörü, `${DATA}` bileşenin kalıcı veri klasörü, `${env:AD}` bileşenin ortam değişkeni;
`tasks[].stdin` içinde ayrıca `${sqlstr:AD}`: ortam değişkeninin SQL metin değeri (dolar tırnaklı).

Yer tutucular komutta da geçerlidir (örn. Redis `--requirepass ${env:REDIS_PASSWORD}`). İsteğe bağlı alanlar:
`kind` (`service` varsayılan; `runtime`: süreç değil, uygulamaların kullandığı çalışma ortamı), `paths` (çalışma
ortamında PATH'e eklenen klasörler, örn. `${PKG}/bin`), `secrets` (bileşenin ihtiyaç duyduğu sırlar: katalogdan
çalıştırılınca ortak ortamda yoksa üretilip şifreli depoya eklenir).

### Bileşen kataloğu

Bu deponun `components` sürümü (varsayılan bileşen deposu) şunları da yayınlar; Bileşenler ekranı açılınca listelenir:

| Paket | Tür | Kullanım |
|---|---|---|
| `nodejs` | Çalışma ortamı | Node.js LTS ve npm/npx. Uygulama komutu `node server.js`, kurulum `npm ci --omit=dev` |
| `python` | Çalışma ortamı | Python 3 ve pip. Komut `python3 app.py`; bağımlılıklar için kurulum `pip install -r requirements.txt --target .deps` ve uygulama ortamında `PYTHONPATH=.deps` |
| `openjdk` | Çalışma ortamı | OpenJDK 21 (Java, Kotlin, Ktor, Spring). Komut `java -Xmx256m -jar app.jar` |
| `redis` | Servis | Yalnızca `127.0.0.1:6379`, parolalı. **Kur ve çalıştır** parolayı `REDIS_PASSWORD` olarak üretir ve Ortam ve sırlar'a ekler; uygulamalarınız oradan okur |

Servisler **Sunucuyla birlikte çalıştır** anahtarıyla konfigürasyona eklenip deploy edilir (ortak ortamı da alırlar);
çalışma ortamları yalnızca kurulur. Paketler `tools/package-termux.sh <redis|nodejs|python|openjdk>` ile Termux
derlemelerinden üretilir: bağımlılıklar çözülür, kütüphaneler pakete göreli bulunur, `npm`/`pip` gibi betikler telefonda
çalışan sarmalayıcılarla değiştirilir (Termux'un `#!` yolu telefonda yok), Python ve Node'a kök sertifikalar eklenir.
**Not:** paketler henüz gerçek bir cihazda denenmedi; ilk kurulumda Loglar ekranını izleyin.

**Binary'leri nereden bulacaksınız:** Termux paket deposu ARM64 için PostgreSQL, Redis, nginx vb. derlemeleri
sunar; Android'in bionic libc'sine göre derlenmişlerdir. PostgreSQL için `tools/package-postgres.sh` bu derlemeyi
bileşen paketine çevirir (aşağıya bakın), `tools/package-cloudflared.sh` cloudflared'ı, `tools/package-termux.sh`
katalogdakileri. Go ile yazılmış statik binary'ler (PocketBase, Caddy) doğrudan çalışır ama DNS için olmayan
`/etc/resolv.conf`'u arar; Termux derlemeleri tercih edin.

## PostgreSQL

Sihirbazın **Veritabanı** adımında PostgreSQL seçilince:

- uygulama bir veritabanı parolası üretip şifreli depoya koyar; `database` bölümü
  `jdbc:postgresql://127.0.0.1:5432/postgres` adresine, `droid` kullanıcısına ayarlanır ve `postgres` bileşeni
  listeye eklenir;
- bileşen kurulu değilse aynı adımdaki **Kur** düğmesi bileşen deposundan indirir;
- ilk deploy'da `initdb` parolayı geçici `.pwfile` dosyasından okur (dosya sonra silinir), `postgres` yalnızca
  `127.0.0.1`'i dinleyerek başlar ve port açılana kadar sunucu bekletilir;
- veritabanı değişirken mevcut kayıtların **taşınıp taşınmayacağı** sorulur (seçilmeden ilerlenemez).
  Taşınacaksa tablolar kimlikleriyle kopyalanır, kimlik sayacı ayarlanır ve satır sayıları karşılaştırılır;
  sorun olursa eski veritabanıyla devam edilir, eski veriler silinmez. Ayrıca seçilir:
  - taşıma sürerken sunucu **durur** ya da **salt okunur** çalışır (okumalar sürer, yazmalar `503` +
    `Retry-After` alır; süren yazmalar bitmeden taşıma başlamaz),
  - yeni veritabanında aynı koleksiyonda kayıt varsa taşıma **durur** ya da oradaki kayıtlar **silinip**
    buradakiler yazılır;
  - alanın tipi de değişiyorsa değerler yukarıdaki tip değişikliği seçimine göre çevrilir.
- **Veritabanı adı** adresin son kısmıdır (varsayılan `postgres`); **Yoksa oluştur** işaretliyse
  (`"createIfMissing": true`) ilk bağlantıda `CREATE DATABASE` yapılır.
- **Parola:** üretilen parola gösterilip kopyalanabilir, yeniden üretilebilir veya kullanıcı kendi parolasını
  girebilir. Postgres çalışırken parola değişirse kaydederken yöntem seçilir: **değiştir** (eski parolayla
  bağlanıp `ALTER ROLE`; sunucu çalışmaya devam eder) veya **sıfırla** (eski parola geçmiyorsa: Postgres kısa
  süre durur, bileşenin `set-password` işi tek kullanıcı modunda parolayı değiştirir).
- **PostgreSQL verisini sil** (onaylı, geri alınamaz): veri klasörü silinir, sonraki başlatmada `initdb`
  konfigürasyondaki parolayla yeniden çalışır.

Postgres çökerse supervisor yeniden başlatır; bu sırada API `503` döner ve Postgres geri gelince istekler
kendiliğinden düzelir (bağlantı havuzu ölü bağlantıları atıp yenisini açar).

**Paketi üretmek** (Linux veya macOS; binary'ler çalıştırılmaz, yalnızca paketlenir):

```bash
tools/package-postgres.sh dist/          # ARCH=aarch64 varsayılan; arm ve x86_64 de desteklenir
# → dist/postgres-18.6-arm64-v8a.zip ve .sha256; patchelf varsa kütüphane yolları pakete göreli yapılır
```

Betik Termux deposundan `postgresql` paketini ve tüm bağımlılıklarını çözüp indirir, sha256'larını doğrular,
`bin/` (postgres, initdb, pg_ctl, psql, pg_dump …), `lib/` ve `share/` düzeninde zipler.
Termux'a özgü iki şey düzeltilir: sembolik bağlantılar zip'te bağlantı olarak kalmaz (Android'deki açıcı onları
bozar; kütüphaneler SONAME adıyla tek kopya tutulur) ve binary'lere gömülü Termux geçici klasörü
(`/data/data/com.termux/files/usr/tmp`; Termux'suz telefonda yoktur ve paylaşımlı bellek kütüphanesi orada sonsuz
döngüye girer) uygulamanın klasörüne çevrilir (`tools/patch-termux-paths.py`). Uygulama aynı iki düzeltmeyi eski
paketlerle kurulmuş bileşenlere de başlatırken bir kez uygular.
`tools/make-manifest.sh dist <adres-öneki>` zip'lerden `components.json` üretir (şablon:
`docs/components.example.json`'daki `postgres` girdisi).

**Barındırma (GitHub Releases):** `.github/workflows/components.yml` (elle çalıştırma veya `master`'a ilgili
dosyalar gelince) üç ABI (arm64-v8a, armeabi-v7a, x86_64) için PostgreSQL, cloudflared ve katalog paketlerini (bir mimaride Termux paketi yoksa atlanır) üretir, manifesti yazar ve hepsini bu
deponun `components` sürümüne yükler. Uygulamadaki **bileşen deposu adresi** varsayılan olarak bu projeninki:
`https://github.com/kasimtmc/droidserver/releases/download/components/components.json` (Bileşenler sekmesinden
kendi adresinizle değiştirilebilir; çatallarda `<sahip>/<depo>` kısmını değiştirin). Uygulama indirirken kimlik
doğrulamaz: depo gizliyse sürüm dosyaları indirilemez; depoyu herkese açık yapın ya da iş akışını herkese açık
ayrı bir depoda çalıştırın. Manifestteki komutlar (parola dosyalı `initdb`, `set-password` işi, `pg_ctl stop`) testlerde gerçek
PostgreSQL ikilileriyle (embedded-postgres'in Linux derlemesi) uçtan uca denenir. **Not:** Termux derlemesi henüz
gerçek bir cihazda denenmedi; ilk kurulumda Loglar ekranını izleyin.

Kendi PostgreSQL sunucunuzu kullanmak için Veritabanı adımında bağlantı adresini ve kullanıcıyı değiştirin;
`postgres` bileşenini JSON'da `"enabled": false` yapın. Sertifikayı doğrulamayan SSL (`sslfactory=...NonValidatingFactory`)
adreste reddedilir; uzak sunucu için `sslmode=verify-full` kullanın. (pgjdbc jar'ındaki bu sınıf için lint uyarısı
`app/lint.xml`'de yalnızca o jar için bastırılır.)

## Neden targetSdk 28?

Android 10+ ile hedeflenen uygulamalar, yazılabilir klasörlerden (`filesDir`) binary çalıştıramaz (W^X).
`targetSdk 28` bu kısıtı kaldırır; Termux da aynı yöntemi kullanır. Bedeli: Play Store'a yüklenemez ve
Android 14+ kurulumda "eski sürüm için tasarlandı" uyarısı gösterir. Uygulama Android 8.0'dan (`minSdk 26`)
güncel sürümlere kadar kurulabilir.

## Bilinen Android kısıtları ve çözümleri

| Sorun | Bu projede | Sizin yapmanız gereken |
|---|---|---|
| Arka planda öldürülme | Foreground service + `START_STICKY` + wake/wifi lock | Uygulamada "pil optimizasyonundan muaf tut" |
| Üretici pil yönetimi (Xiaomi, Samsung, Huawei) | — | Ayarlardan otomatik başlatma / kısıtlamasız pil; bkz. dontkillmyapp.com |
| Phantom process killer (Android 12+) | Supervisor yeniden başlatır | Aşağıdaki ADB komutu veya Geliştirici seçenekleri > "Alt süreç kısıtlamalarını devre dışı bırak" (14+) |
| 1024 altı portlar | Validator engeller | 8080/8443 kullanın |
| Mobil veride dışarıdan erişim yok (CGNAT) | Doğrudan erişim IPv6'yı dener; Cloudflare hızlı tüneli hesapsız çalışır | Erişim adımında Cloudflare veya Tailscale |
| Telefonun yerel IP'si değişir | Durum ekranı güncel adresi gösterir; port yönlendirme yeniden yapılır | Modemde DHCP rezervasyonu, Wi-Fi'da "cihaz MAC adresi" |

```bash
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell settings put global settings_enable_monitor_phantom_procs false
```

## Proje yapısı

```
core/src/main/kotlin/dev/droidserver/core/
  config/      ServerConfig, ConfigValidator (alan yollu hatalar), ConfigMigrations (sürüm dönüşümleri),
               ConfigSecrets (sır referansları), ConfigTemplates, ConfigSummary (erişim/CORS modu, özet),
               ConfigDiff (geçmiş farkı, veri kaybı uyarıları), FieldDefaults, DatabaseChoice (SQLite/Postgres)
  db/          SqlDriver arayüzü, SqlDialect (SqliteDialect, PostgresDialect), JdbcSqlDriver,
               PostgresDriver (bağlantı havuzu), SchemaMigrator (plan + apply), DataCopier (veri taşıma)
  api/         RecordService (CRUD + doğrulama), ServerModule (Ktor kurulumu, auth, uçlar), RequestGuard (istemci
               başına sınırlar), ApiExamples (curl/JS/Kotlin, deneme), RealtimeRoutes (SSE), SearchText (arama),
               OpenApi (doküman), AuthPages (/_auth sayfaları)
  realtime/    RealtimeHub (olay dağıtımı, son olaylar tamponu)
  mail/        SmtpClient (STARTTLS/SSL, AUTH), Mailer (hesap e-postaları, sınırlar)
  net/         DirectAccess (doğrudan erişim), TlsProxy, TlsIdentity, PortMapper (NAT-PMP/UPnP), Addresses
               (adres türleri, ulaşılabilirlik), WanServices (genel IP, DuckDNS), AcmeClient + CertificateManager
               (Let's Encrypt, DNS-01), Der (sertifika ve CSR kodlama)
  update/      ReleaseChecker (GitHub Releases, sha256 doğrulamalı indirme), Versions (anlamsal sürüm)
  server/      DeployOrchestrator (deploy sırası), ServerEngine (HTTP + şema yaşam döngüsü, şema planı)
  process/     ProcessSupervisor, ReadinessProbe
  components/  ComponentManager
  automation/  AutomationEngine (kodsuz kurallar: kayıt olayı ve zamanlama, eylemler)
  graphql/     GqlParser, GraphQL (şema, sorgu ve mutasyonlar; REST'le aynı kurallar)
  webdav/      WebDav (ağ sürücüsü: PROPFIND, PUT, MOVE…, Digest kimlik)
  mqtt/        MqttBroker (MQTT 3.1.1 sunucusu: QoS 0-2, jokerler), MqttIngest (mesaj → kayıt)
  replication/ ChangeLog, Replicator (canlı kopya), Failover (otomatik devralma, devretme, geri alma)
  auth/        UserService, TeamService (takımlar), SmsGuard, Totp, OAuth, ApiKeys
  ai/          AiChat (Claude: resmi Java SDK; OpenAiChat, GeminiChat: HTTP), SemanticIndex (vektör dizini),
               HttpEmbedder (Voyage AI / OpenAI uyumlu gömme)
core/src/test/  CoreTest, ComponentManagerTest, ConfigMigrationsTest, DeployOrchestratorTest, WizardSupportTest,
                SQLite + PostgreSQL için: DialectContractTest, SchemaPlanTest, DataCopierTest; PostgresResilienceTest
                AccessConfigTest, DirectAccessTest (TLS ucu, NAT-PMP/UPnP sahte modemle), RequestGuardTest
                (93 test; TestDb gömülü PostgreSQL'i yönetir)
app/src/main/kotlin/dev/droidserver/app/
  DroidServerApp      bağımlılık kabı (AppGraph), ayarlar
  data/               AndroidSqlDriver, AndroidNetwork (ağ türleri, modem adresi, DNS), ConfigRepository,
                      DraftStore, SecretStore, LogBuffer, NetUtil
  data/AppUpdater     uygulama güncellemesi (imza denetimi, PackageInstaller)
  service/            ServerService, BootReceiver, UpdateReceiver (kurulum sonucu), QuickControls (hızlı ayar
                      kutucuğu, ana ekran widget'ı), TaskerReceiver (otomasyon komutları)
  ui/                 MainActivity, AppNavigation (alt çubuk + tam ekran rotalar)
    status/ config/ components/ logs/   sekmeler; config/: Sunucu, JSON, Geçmiş, API gezgini
    wizard/           WizardViewModel (saf Kotlin) ve adım ekranları
    common/ theme/    ortak bileşenler, tema
app/src/test/   WizardViewModelTest (15 test, uçtan uca sihirbaz → deploy dahil)
.github/workflows/  release.yml (imzalı APK → GitHub Releases), components.yml (bileşen paketleri)
tools/          package-postgres.sh, package-cloudflared.sh (Termux derlemelerinden bileşen paketleri), make-manifest.sh
```

## Yol haritası

- [x] **Sihirbaz arayüzü:** JSON düzenleyici yerine adım adım koleksiyon/alan/auth seçimi
- [x] **PostgreSQL:** Postgres bileşeni + pgjdbc; Postgres lehçesi, SQLite'tan veri taşıma
- [x] **İlişkiler:** `relation` alan tipi, `onDelete`, `expand` parametresi (kurallar API katmanında)
- [x] **Gelişmiş filtre:** `gt`, `lt`, `like`, `in`, `null` operatörleri ve alan seçimi
- [x] **Kullanıcı yönetimi:** `_users` koleksiyonu, kayıt/giriş/yenileme uçları, koleksiyon başına yetki kuralları, uygulamadan hesap yönetimi
- [x] **Özel iş mantığı:** JavaScript (Rhino) ile kullanıcı tanımlı uçlar, kayıt olayları ve zamanlanmış görevler
- [x] **Statik dosya / dosya yükleme:** `file` alan tipi (içerikten tip tespiti, akışta boyut sınırı, küçük resim), statik site (zip ile yükleme)
- [x] **İnternetten erişim:** doğrudan IP (HTTPS + otomatik port yönlendirme + DuckDNS), Cloudflare hızlı/adlandırılmış tünel, Tailscale
- [x] **TLS:** doğrudan erişimde Let's Encrypt sertifikası (DuckDNS adıyla DNS-01, kendiliğinden yenileme)
- [x] **Yedekleme:** zamanlanmış yedek (SQLite ve PostgreSQL, motorlar arası geri yüklenebilir), klasöre kopya, geri yükleme
- [x] **Web admin paneli:** `/_admin` altında tarayıcıdan yönetim (eşleştirme koduyla giriş)
- [x] **Metrikler:** istek sayısı, gecikme, pil/ısı durumu (`/_metrics`, Prometheus, Durum grafiği, ısınma bildirimi)
- [x] **Kendini güncelleme:** GitHub Releases'tan yeni sürüm denetimi, sha256 + imza doğrulamalı kurulum; imzalı sürüm iş akışı
- [x] Bileşenlerin her deploy'da değil, yalnızca konfigürasyonu değiştiğinde yeniden başlatılması
- [x] **Canlı abonelik:** `/api/realtime` (SSE), kurallar her olayda, kaldığı yerden devam
- [x] **Arama ve indeksler:** `?q=` (Türkçe karakterden bağımsız), alan indeksi
- [x] **E-posta:** SMTP, hesap doğrulama, parola sıfırlama, `ctx.mail`
- [x] **OpenAPI:** yetkiye göre doküman, panelde API sekmesi, JavaScript/Kotlin örnekleri
- [x] **Webhook'lar:** imzalı POST, yeniden deneme, teslim geçmişi
- [x] **Toplu işlem ve özetler:** `/api/batch` (hepsi ya da hiçbiri), `/aggregate` (toplam, ortalama, gruplama)
- [x] **İnce yetki:** koşullu kurallar (`status=published`, `@me`, sahip istisnası), alan gizliliği
- [x] **İçe / dışa aktarma:** CSV (Excel uyumlu), JSON ve dosyalarıyla zip, önizleme ve sütun eşleme, CSV şablonu;
  panelde toplu düzenleme/silme, salt okunur SQL konsolu ve örnek veri
- [x] **Adlandırılmış API anahtarları:** salt okunur / koleksiyona sınırlı, süreli, iptal edilebilir
- [x] **Sosyal giriş:** Google ve GitHub (OAuth2 + PKCE)
- [x] **Web push:** VAPID, RFC 8291, `ctx.push`, kayıt olayında bildirim
- [x] **Sunucu profilleri:** aynı telefonda birden çok bağımsız sunucu
- [x] **Dil:** Türkçe ve İngilizce arayüz (uygulama, web paneli, API iletileri, e-postalar)
- [x] **Özel roller ve şifreli alanlar:** editör/moderatör gibi roller; AES-256-GCM ile alan şifreleme
- [x] **Veri yaşam döngüsü:** saklama süresi, salt okunur görünümler ve raporlar (CSV)
- [x] **WebSocket kanalları:** sohbet ve oyun için iki yönlü kanallar, çevrimiçi listesi
- [x] **Telefon özellikleri:** SMS (sınırlı), konum, cihaz durumu; uygulama kilidi; sorun giderici
- [x] **Ortam ve sırlar:** `.env` biçiminde ortak ortam değişkenleri, şifreli sırlar, içe/dışa aktarma
- [x] **Kendi uygulamalarınız:** Node.js/Python/Java/ikili uygulama, zip veya Git'ten deploy, sağlık denetimi ve geri dönüş, push webhook'u
- [x] **Bileşen kataloğu:** Node.js, Python, OpenJDK, Redis paketleri; tek dokunuşla kur ve çalıştır (cihazda doğrulama bekliyor)
- [x] **Otomasyon kuralları:** kodsuz "şu olunca şunu yap": kayıt olayı veya zamanlama, koşul; e-posta, bildirim, webhook, kayıt güncelleme/oluşturma
- [x] **Formlar:** koleksiyona kayıt ekleyen herkese açık sayfa, spam koruması, teşekkür sayfası veya yönlendirme
- [x] **GraphQL:** koleksiyonlardan üretilen şema, sorgu ve mutasyonlar, ilişki genişletme, REST'le aynı kurallar,
  WebSocket abonelikleri (`graphql-transport-ws`)
- [x] **Yapay zekâ:** anahtarı sunucuda tutan vekil (Claude, OpenAI veya Gemini; OpenAI uyumlu yerel modeller), kayıtlara soru sorma, anlamsal arama (Voyage AI veya Ollama), günlük sınır
- [x] **SMS ile giriş:** telefon numarası ve sunucu telefonundan giden kodla parolasız giriş, numara ve istemci başına sınırlar
- [x] **Hızlı ayar ve widget:** bildirim panelinden ve ana ekrandan aç/kapat, durum, adres, istek ve pil
- [x] **Tasker ve otomasyon:** anahtarla başlat/durdur/deploy komutları, seçilen uygulamalara sunucu olayları ve sağlık uyarıları
- [x] **Ağ sürücüsü:** telefondaki klasörleri bilgisayarda WebDAV sürücüsü olarak bağla (Digest kimlik, salt okunur paylaşımlar)
- [x] **Otomatik devralma:** ana sunucu düşünce kopya devralır, dönen eski ana sunucu kopyaya döner ve isterse rolünü kayıpsız geri alır; SDK'da yedek adres
- [x] **Takımlar:** kullanıcılar takım kurar ve üye ekler; `team` kuralıyla her takım yalnızca kendi kayıtlarını görür
- [x] **Komut satırı (CLI):** bilgisayardan kayıtlar, dışa/içe aktarma, loglar, deploy ve yedek (tek dosya, Node.js)
- [x] **MQTT (IoT):** telefonda MQTT sunucusu; cihaz mesajları koleksiyona, kayıt olayları cihazlara, uygulamadan komut
- [x] **Sağlamlaştırma:** sorun gidericide "Kısıtlı" pil, alt süreç kısıtı ve sistemin servisi yeniden başlatması (nedeniyle); yeniden başlatma uyarısı
- [x] **Web paneli eşitleme:** panelde Takımlar, Otomasyon (kurallar, formlar) ve Servisler (MQTT, ağ sürücüsü, canlı kopya ve devralma, YZ, uygulamalar, CLI)
- [x] **Passkey ile giriş:** kullanıcılar ve web paneli için parolasız giriş (WebAuthn, alan adı ve HTTPS gerekir)
- [x] **Oturum yönetimi:** açık oturumlar (cihaz, adres, son kullanım), tek tek veya hepsini kapatma; kapatılan oturumun token'ı hemen düşer
- [x] **MQTT güvenliği:** yalnızca yerel ağ, cihaz başına kimlik, tutulan mesajlar
- [x] **MQTT TLS:** 8883'te şifreli bağlantı; telefonun yerel kök sertifikası, IP değişince kendiliğinden yenilenen sunucu sertifikası
- [x] **Ev otomasyonu:** otomasyonda MQTT eylemi ve tetikleyicisi, sahneler (widget dahil), sensör panosu ve eşik uyarısı, Home Assistant keşfi
- [x] **Telegram'dan yönetim:** ayrı yönetici sohbetinden `/durum`, `/yedek`, `/loglar`, `/baslat`, `/durdur` (onaylı)
- [x] **E-posta şablonları ve toplu bildirim:** hesap e-postaları dil başına şablonla; kullanıcılara süzgeçli, hız sınırlı, iptal edilebilir toplu e-posta / web push, abonelikten çıkma
- [x] **Yaygınlaştırma:** ilk açılış rehberi ve örnek proje, uygulama içi çevrimdışı yardım, F-Droid meta verisi, tekrarlanabilir derleme, gizlilik politikası
- [x] **Çevrimdışı eşitleme:** koleksiyon başına; SDK'larda (TS, Kotlin, Dart, Swift) yerel kopya, bekleyen yazma kuyruğu, alan bazında son yazan kazanır veya elle çözme
- [x] **Kopyalarla ölçek:** kopyaya gelen yazma ana sunucuya iletilir, birden çok kopya ve kopya tablosu, SDK'da okuma adresleri ve kendi yazdığını oku
- [x] **Ödemeler:** Stripe, iyzico ve Paddle; barındırılan ödeme sayfası, imzalı bildirimler (bir kez), kayda durum, otomasyonda ödeme bağlantısı, abonelikte rol ve kota, makbuz PDF, ödemeli sipariş ve randevu şablonları
- [x] **Telefonda yerel YZ:** llama.cpp kaynaktan, indirilebilir GGUF modelleri (sohbet ve gömme, SHA-256 doğrulamalı), bellek/ısı denetimi ve sağlık uyarısı, otomasyonlarda YZ adımı
- [x] **Eklenti ve şablon paketleri:** koleksiyon, görünüm, otomasyon, form, sayfa, pano, script ve örnek veri tek dosyada; kurmadan önce plan, güncelleme, kaldırma, ECDSA imzası; dosyadan, adresten, GitHub'dan
- [x] **Kodsuz sayfalar ve panolar:** koleksiyondan sunucuda üretilen liste/detay sayfaları (kurallar, girişli oturum, arama, sıralama), görünümlerden sayı/grafik/tablo/harita panoları, paylaşılabilir salt okunur adres
- [x] **Takvim ve kişiler:** çakışma kuralı (kaynak başına), `.ics` aboneliği (gizli adresle), CalDAV ve CardDAV ile telefon takvimi ve rehberiyle iki yönlü eşitleme
- [x] **Konum:** `geo` alanı, `near` (yakından uzağa) ve `box` filtreleri, panelde ve uygulamada OpenStreetMap haritası, haritadan ve telefondan konum seçme
