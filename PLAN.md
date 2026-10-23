# DroidServer — Geliştirme Planı

Bu dosya, v0.1.0 iskeletinin üzerine eklenecek özelliklerin uygulama planıdır.
Claude Code ile faz faz ilerlemek için yazıldı: her fazda hedef, dokunulacak dosyalar,
adımlar, kabul kriterleri ve testler var. Fazlar bağımlılık sırasına göre dizildi.

---

## 0. Çalışma kuralları (her fazda geçerli)

**Mimari sınırlar**
- `core` modülü Android'e bağımlı OLAMAZ. Android API'si gerektiren her şey `app` modülünde,
  arayüz/soyutlama `core`'da durur (örnek: `SqlDriver` ↔ `AndroidSqlDriver`).
- İş mantığı `core`'da yazılır ve JVM testleriyle doğrulanır. `app` ince bir kabuktur.
- Kullanıcıya dönen hata ve log mesajları Türkçe; kod, tanımlayıcılar ve yorumlar mevcut stile uygun.
- SQL'e yazılan her tanımlayıcı `quoteIdent()`'ten geçer; istemciden gelen değerler her zaman parametre olarak bağlanır.
- Konfigürasyon şeması değiştiğinde `ServerConfig.version` artırılır ve eski sürümü okuyabilen
  bir dönüştürücü eklenir (bkz. Faz 1.4).

**Komutlar**
```bash
./gradlew :core:test            # her değişiklikten sonra
./gradlew :app:assembleDebug    # app'e dokunulduysa
./gradlew :app:lint             # faz sonunda
```
Android SDK yolu `local.properties` (`sdk.dir=...`) veya `ANDROID_HOME` ile verilir.

**Yeni bağımlılık eklerken:** sürümü `gradle/libs.versions.toml`'a ekle, Maven'deki güncel kararlı
sürümü kullan, Android'de (minSdk 26) çalıştığını doğrula. Saf Java/Kotlin kütüphaneleri tercih edilir.

**Faz tamamlanma tanımı:** testler yeşil, APK derleniyor, README'deki ilgili bölüm ve bu dosyadaki
onay kutuları güncel.

---

## Yol haritası özeti

| Faz | Konu | Bağımlılık | Boyut |
|---|---|---|---|
| 1 | Altyapı düzeltmeleri (dialect, şema farkı, deploy sırası) | — | M |
| 2 | Sihirbaz arayüzü | 1 | L |
| 3 | PostgreSQL | 1 | L |
| 4 | Gelişmiş filtre + ilişkiler | 1, (3) | M |
| 5 | Kullanıcılar ve yetki kuralları | 4 | L |
| 6 | Dosya yükleme + statik site | 5 | M |
| 7 | Özel iş mantığı (script) | 5 | L |
| 8 | Yedekleme, metrikler, web admin paneli | 5 | L |
| 9 | Erişim/TLS kolaylıkları, kendini güncelleme | — | M |
| 10 | Canlı abonelik, arama/indeksler, e-posta, OpenAPI | 5 | L |
| 11 | Webhook, toplu işlem/özet, koşullu kural, içe/dışa aktarma, API anahtarları, OAuth2, web push, çoklu profil | 10 | XL |
| 12 | Alan doğrulama, geçmiş/çöp kutusu, kotalar, iki adımlı doğrulama, performans, bulut yedeği, istemci SDK'sı, panelden şema | 11 | XL |
| 13 | Denetim kaydı, güvenlik duvarı, sağlık uyarıları, iş kuyruğu, uygulama şablonları, yerel ağ keşfi/QR, canlı kopya, İngilizce arayüz | 12 | XL |
| 14 | Özel roller, alan şifreleme, saklama süresi, görünümler, WebSocket kanalları, telefon özellikleri, uygulama kilidi, sorun giderici | 13 | XL |
| 15 | Ortam ve sırlar, kendi uygulamalarınız, bileşen kataloğu, Git'ten deploy | 14 | L |
| UI | Arayüz yenileme (tema, sunucu ekranı, durum panosu, boş durumlar, web paneli) | 15 | L |
| 16 | Otomasyon, formlar, GraphQL, YZ; hızlı ayar/widget, SMS girişi, Tasker, WebDAV; devralma, takımlar, CLI, MQTT | UI | XXL |
| 17 | Sağlamlaştırma ve yayın, panel eşitleme, güvenli bağlantılar, ev otomasyonu, geliştirici ve veri araçları, iletişim kanalları, yaygınlaştırma | 16 | XXL (planlandı) |
| 18 | Çevrimdışı istemci eşitleme, kopyalarla ölçek, konum ve takvim, medya, sayfa ve pano yapıcı, eklenti paketleri, yerel YZ, ödemeler | 17 | XXL (planlandı) |

Faz 2 ve Faz 3 birbirinden bağımsızdır; Faz 1'den sonra paralel yürüyebilir.

---

## Faz 1 — Altyapı düzeltmeleri

Sonraki fazların hepsi bunlara dayanıyor. Mevcut iskeletteki bilinen eksikler de burada kapanıyor.

### 1.1 SQL lehçesi soyutlaması
Şu an `SchemaMigrator` SQLite'a özgü (`PRAGMA table_info`, `sqlite_master`, `AUTOINCREMENT`).

- [x] `core/.../db/SqlDialect.kt` oluştur:
  ```kotlin
  interface SqlDialect {
      fun listTables(db: SqlDriver): Set<String>
      fun listColumns(db: SqlDriver, table: String): Map<String, String>   // ad -> veritabanı tipi
      fun createTableSql(table: String): String                             // id, created_at, updated_at
      fun columnType(t: FieldType): String
      fun isUniqueViolation(e: Throwable): Boolean
      fun insertReturningId(table: String, cols: List<String>): String
  }
  ```
- [x] `SqliteDialect` mevcut davranışı taşır; `PostgresDialect` Faz 3'te eklenir.
- [x] `SqlDriver`'a `val dialect: SqlDialect` ekle. `SchemaMigrator` ve `RecordService` lehçe üzerinden çalışsın.
- [x] `RecordService.wrapConstraint` içindeki "UNIQUE" metin araması yerine `dialect.isUniqueViolation()`.

### 1.2 Şema farkı (dry-run) ve tip değişikliği tespiti
Şu an migrator alan tipi değişikliklerini **sessizce yok sayıyor**; bu bir hata.

- [x] `SchemaMigrator.plan(collections): MigrationPlan` — hiçbir şey yazmadan yapılacakları döndürür.
  `migrate()` içeride `plan()` + `apply(plan)` olarak yeniden yazılır.
- [x] `MigrationPlan` şunları ayrı listeler: oluşturulacak tablolar, eklenecek sütunlar,
  eklenecek/kaldırılacak unique indeksler, kullanılmayan sütun/tablolar ve **engelleyici sorunlar**.
- [x] Engelleyici sorun örnekleri: mevcut sütunun tipi farklı; mevcut verisi tekrar eden bir alana `unique` eklenmesi
  (önce `SELECT col, COUNT(*) ... HAVING COUNT(*) > 1` ile kontrol). Engelleyici sorun varsa deploy reddedilir.
- [x] `required` sonradan eklendiğinde: mevcut satırlarda NULL varsa uyarı (engelleyici değil; API katmanı zaten uygular).

### 1.3 Deploy sırası ve bileşen senkronizasyonu
- [x] `ServerService.deployAll()` sırası: **bileşenler → hazır olmayı bekle → engine.deploy**.
  Postgres gibi veritabanı bileşenleri engine'den önce ayakta olmalı.
- [x] `ComponentPackage`'a opsiyonel `readiness` ekle: `{ "tcpPort": 5432, "timeoutSec": 30 }`.
  `ProcessSupervisor` veya ayrı bir `ReadinessProbe` TCP bağlantısı açılana kadar bekler.
- [x] Bileşenler yalnızca değiştiyse yeniden başlasın: `ProcessSupervisor.Spec`'in hash'ini sakla
  (komut, env, sürüm); aynıysa `start()` dokunmasın. Şu an her deploy hepsini yeniden başlatıyor.
- [x] Deploy mantığını servisten çıkar: `core/.../server/DeployOrchestrator.kt`. Servis sadece onu çağırır;
  orkestratör JVM'de test edilir.

### 1.4 Konfigürasyon sürümleme ve sırlar
- [x] `ConfigMigrations` (core): `version` alanına bakıp eski JSON'u yeni modele dönüştürür. İlk dönüşüm v1 → v2.
- [x] `AndroidSqlDriver.query()` null argümanı reddediyor; `IS NULL` filtreleri için `SQLiteDatabase.rawQueryWithFactory`
  + `SQLiteQuery.bindNull` ile düzelt.
- [x] Sırlar (`apiKeys`, `jwtSecret`) düz metin JSON'da duruyor. `app` tarafında Android Keystore ile şifreleyen
  `SecretStore` ekle; konfigürasyonda `"jwtSecret": "secret:jwt"` gibi referans tutulur, deploy anında çözülür.
  `core` yalnızca `(String) -> String?` çözücü fonksiyonu alır.

**Kabul kriterleri**
- [x] Mevcut 8 test değişmeden geçiyor.
- [x] Yeni testler: tip değişikliği planda engelleyici olarak görünüyor; tekrar eden veriye unique eklemek engelleniyor;
  aynı bileşen spec'iyle iki deploy süreci yeniden başlatmıyor; v1 konfigürasyonu v2'ye dönüşüyor.
  (`SchemaPlanTest`, `DeployOrchestratorTest`, `ConfigMigrationsTest` — toplam 20 test)

**Uygulama notları** (sonraki fazlar için)
- `SqlDialect`'e plandakilere ek olarak `listIndexes()` ve `matchesType(dbType, fieldType)` eklendi;
  Postgres'te `information_schema` tip adları (`timestamp with time zone` …) `matchesType` ile eşlenmeli.
- Alan tipleri `_ds_meta` tablosunda `field:<tablo>.<alan>` anahtarıyla tutulur. SQLite'ta aynı sütun tipine
  düşen değişiklikler (`text → json`, `integer → boolean`) de böylece engellenir. Konfigürasyondan çıkarılan
  alanın kaydı silinmez; aynı ad başka tiple geri eklenirse yine engellenir.
- `MigrationPlan.summary()` sihirbazın özet adımındaki metni üretir ("2 tablo oluşturulacak, …").
- Hazır olmayan bileşen deploy'u durdurmaz (log + `DeployOrchestrator.Result.componentErrors`); veritabanı
  gerçekten gerekliyse sunucu zaten bağlanamayıp başarısız olur. İstenmeyen bileşenler motor deploy'u
  başarılı olduktan sonra durdurulur (eski sunucu onları kullanıyor olabilir).
- v2'nin farkı sır referansları: v1 → v2 dönüşümü depo verilirse tüm sırları depoya taşır (`ConfigSecrets.secureText`),
  verilmezse yalnızca sürümü yükseltir. `ConfigRepository` ilk okumada `server.json`'u ve geçmiş kopyalarını
  bu şekilde günceller; kayıtta düz metin sırlar yine depoya taşınır. Doğrulama çözülmüş değerler üzerinde yapılır.
- Faz 5'teki `publicRead/readOnly → rules` dönüşümü `ConfigMigrations`'a v5 → v6 adımı olarak eklendi.

---

## Faz 2 — Sihirbaz arayüzü

**Hedef:** Kullanıcı JSON yazmadan, adım adım sunucusunu tasarlayıp deploy edebilsin.
JSON düzenleyici "Gelişmiş mod" olarak kalır ve sihirbazla aynı taslağı paylaşır.

### 2.1 Altyapı
- [x] Bağımlılıklar: `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`,
  `androidx.compose.material:material-icons-extended` (ikonlar için).
- [x] `app/.../ui/wizard/WizardViewModel.kt`: `MutableStateFlow<ServerConfig>` taslak; her adım taslağın bir
  parçasını günceller. Taslak `files/config/draft.json`'a otomatik kaydedilir (uygulama kapanırsa kaybolmasın).
- [x] Mevcut tek dosyalı `MainActivity.kt`'yi böl: `ui/status/`, `ui/config/`, `ui/components/`, `ui/logs/`,
  `ui/wizard/`, `ui/theme/`. Navigasyon: alt çubuk (Durum, Sunucu, Bileşenler, Loglar); sihirbaz tam ekran akış.
- [x] İlk açılışta konfigürasyon yoksa doğrudan sihirbaz açılır.

### 2.2 Adımlar
Her adımın altında "Geri / İleri" ve adım geçerli değilse ileriyi engelleyen satır içi hata mesajları.
Doğrulama `ConfigValidator`'dan gelir; hatalar alan yoluna göre (`collections[0].fields[1].name`)
eşlenebilsin diye validator'ı `List<ValidationError(path, message)>` döndürecek şekilde genişlet.

1. **Genel** — sunucu adı, port (varsayılan 8080, 1024 altı engelli), basePath.
2. **Veritabanı** — SQLite / PostgreSQL kartları. PostgreSQL seçilirse bileşenin kurulu olup olmadığını göster;
   değilse "Kur" düğmesi bu adımın içinde (Faz 3 tamamlanana kadar "yakında" etiketiyle devre dışı).
3. **Koleksiyonlar** — liste + "Koleksiyon ekle". Koleksiyon düzenleme ekranı:
   - ad, `publicRead`, `readOnly`
   - alan listesi (sürükle-bırak sıralama), her alan için: ad, tip seçici, zorunlu, benzersiz, varsayılan değer
     (tipine uygun giriş: boolean için switch, datetime için tarih seçici, json için çok satırlı alan)
   - hazır şablonlar: "Yapılacaklar", "Blog yazıları", "Ürünler", "Boş"
   - mevcut sunucudaki bir koleksiyonu düzenlerken veri kaybettirecek değişikliklerde (alan silme, tip değiştirme)
     uyarı ikonu ve açıklama
4. **Güvenlik** — kimlik doğrulama modu kartları (Yok / API anahtarı / JWT) ve her birinin ne zaman uygun olduğu.
   API anahtarı ve JWT sırrı `SecureRandom` ile üretilir ("Yeniden üret", "Kopyala"). CORS: kapalı / herkes / host listesi.
   Varsayılan konfigürasyondaki yer tutucu anahtar burada mutlaka değiştirilir.
5. **Erişim** — Yalnızca yerel ağ / İnternetten doğrudan (IP) / Cloudflare Tunnel (hızlı veya kendi alan adı) /
   Tailscale (uygulama). Seçilen yola ve ağ türüne göre adımlar ve olası sorunlar; internete açık modda kimlik
   doğrulama yoksa bilinçli onay (bkz. Faz 9.1).
6. **Özet ve deploy** — üç bölüm:
   - konfigürasyon özeti (okunur biçimde)
   - **şema planı**: Faz 1.2'deki `SchemaMigrator.plan()` sonucunu canlı veritabanına karşı göster
     ("2 tablo oluşturulacak, 1 sütun eklenecek, 1 sütun artık kullanılmıyor")
   - engelleyici sorun varsa deploy düğmesi devre dışı ve neden açıklanır
   - "Kaydet ve deploy et" → `ConfigRepository.saveText()` + `ServerService.deploy()`, ardından Loglar'da canlı ilerleme

### 2.3 Ek ekranlar
- [x] **Sunucu** sekmesi: aktif konfigürasyonun özeti + "Sihirbazla düzenle" + "Gelişmiş (JSON)" + "Geçmiş".
- [x] **Geçmiş**: `ConfigRepository.history()` listesi, iki sürüm arasındaki fark, "Bu sürüme dön" (önce plan göster).
- [x] **API gezgini** (küçük ama değerli): her koleksiyon için hazır `curl` örnekleri, tek dokunuşla kopyala;
  cihaz içinden örnek istek atıp yanıtı gösterme.

**Kabul kriterleri**
- [x] Yeni kurulan uygulamada sihirbaz baştan sona JSON'a dokunmadan çalışan bir sunucu üretiyor.
  (`WizardViewModelTest`'te sihirbaz adımları → `ServerEngine.deploy` → gerçek HTTP isteği; cihazda elle denenmedi.)
- [x] Sihirbazda yapılan değişiklik JSON modunda, JSON'da yapılan değişiklik sihirbazda görünüyor
  (ikisi aynı etkinlik kapsamlı `WizardViewModel` taslağını kullanır).
- [x] Ekran döndürme ve uygulama kapatılıp açılması taslağı kaybettirmiyor (ViewModel döndürmede korunur;
  her değişiklik `files/config/draft.json`'a yazılır, açılışta oradan devam edilir).
- [x] Testler: `WizardViewModelTest` (9, JVM; Robolectric yok), `WizardSupportTest` (6, core: alan yolları,
  şablonlar, varsayılan değerler, erişim/özet, fark ve uyarılar, API örnekleri + deneme isteği, şema planı).

**Uygulama notları** (sonraki fazlar için)
- `ConfigValidator.validateDetailed()` → `ValidationError(path, message)`; `validate()` mesaj listesi olarak kaldı.
  Yeni kurallar: boş sunucu adı, CORS açık ama host yok, SQLite dosya adı (yol ayracı yok), alan varsayılanının tipi.
- Sihirbaza özgü kurallar ViewModel'de: yer tutucu anahtar (`ConfigDefaults.PLACEHOLDER_API_KEY`), depoda olmayan
  sır referansı, tünel seçiliyken eksik token (`access.token` yolu). Validator sırların çözülmüş hali üzerinde koşar.
- Erişim modu v4'ten beri `access.mode` alanıdır (önceden etkin bileşenden türetiliyordu). Cloudflare dışına geçince
  `cloudflared` bileşeni silinmez, `enabled=false` olur (token korunur); Tailscale bileşeni kaldırıldı.
  Token `secret:` referansıdır; `ConfigSecrets.resolve` artık bileşen ortamındaki referansları da çözer.
- Yeni kurulumda varsayılan konfigürasyon, yer tutucu yerine üretilmiş bir anahtarla oluşturulur
  (`ConfigRepository.defaultConfigText`); yer tutucu hiçbir zaman diske yazılmaz.
- `ServerEngine.plan(config)`: sunucu aynı veritabanıyla çalışıyorsa canlı bağlantı, değilse geçici bağlantı.
- `material-icons-extended` debug APK'yı ~18 MB'tan ~26 MB'a büyüttü; sürüm derlemesinde küçültme (R8) açılırsa düşer.
- PostgreSQL kartı ve "Kur" düğmesi "yakında" etiketiyle kapalı; Faz 3'te `DatabaseStep` açılacak.

---

## Faz 3 — PostgreSQL

**Hedef:** `database.engine = "postgres"` seçildiğinde uygulamanın indirdiği Postgres bileşeni başlar,
sunucu ona bağlanır ve tüm CRUD/migration özellikleri SQLite'takiyle aynı davranır.

### 3.0 Riskleri erken kapat (önce bunlar)
- [x] **pgjdbc Android'de çalışıyor mu?** Boş bir test uygulamasında pgjdbc ile yerel bir Postgres'e bağlanıp
  sorgu at. Sorun çıkarsa: pgjdbc'nin eski 42.2.x serisi Android'de bilinen çalışan sürümdür; o da olmazsa
  basit bir PG wire protokol istemcisi değerlendirilir.
  *Durum:* pgjdbc 42.7.14 eklendi; `DriverManager` yerine sürücü doğrudan kullanılıyor (Android'de ServiceLoader
  kaydına güvenilmez). JVM'de gerçek Postgres'e karşı test edildi; cihaz testinde de çalıştı (p4-p5, 14 Ekim 2026).
- [x] **Binary kaynağı.** Postgres Android'de doğrudan derlenmiş haliyle çalışmaz: Android'de SysV shared memory yok.
  Termux bunu yamalarla (`libandroid-shmem`, `dynamic_shared_memory_type = mmap`) çözüyor. Pratik yol:
  - Termux deposundan `postgresql` ve bağımlılık `.deb` paketlerini (`libandroid-shmem`, `openssl`, `libxml2`,
    `libicu`, `readline`, `zlib`, `libandroid-execinfo` vb.) indirip açan bir **paketleme betiği** yaz:
    `tools/package-postgres.sh` → `postgres-<sürüm>-arm64.zip` (`bin/`, `lib/`, `share/`) + sha256.
  - Postgres yer değiştirebilir kurulumdur (share/lib yollarını binary konumuna göre bulur); kütüphaneler için
    `LD_LIBRARY_PATH=${PKG}/lib` kullanılır. Termux önekine sabit kodlanmış yol kalmışsa `patchelf --set-rpath` ile düzelt.
  - Betik ve sonucu README'de belgelenir; zip kendi bileşen deponuzda barındırılır.
  *Durum:* betik çalıştırıldı: PostgreSQL 18.6 + 16 bağımlılık, 26 MB zip; tüm `NEEDED` kütüphaneler pakette
  veya Android sisteminde, RUNPATH `$ORIGIN/../lib`. Binary'ler bu makinede çalıştırılamadığı için davranış doğrulanmadı.
- [x] Gerçek cihazda elle doğrula: `initdb` → `postgres` → `psql` ile bağlantı. *(Cihaz testi p2-p4.)*
  *(Manifest komutları — parola dosyalı `initdb`, `pg_ctl stop`, `set-password` işi — JVM testinde gerçek
  PostgreSQL ikilileriyle uçtan uca doğrulandı; Termux derlemesi cihazda denenmedi.)*

### 3.1 Bileşen tanımı
- [x] `docs/components.example.json` içindeki postgres girdisini gerçek değerlerle güncelle:
  - `setup`: `initdb -D ${DATA}/pgdata -U droid --auth=scram-sha-256 --pwfile=${DATA}/.pwfile`
  - `command`: `postgres -D ${DATA}/pgdata -p 5432 -k ${DATA} -c listen_addresses=127.0.0.1 -c dynamic_shared_memory_type=mmap`
  - `readiness`: `{ "tcpPort": 5432, "timeoutSec": 60 }` (Faz 1.3)
  - Parola dosyası kurulumda `SecretStore`'dan üretilip yazılır, ilk kurulumdan sonra silinir.
- [x] Postgres yalnızca `127.0.0.1`'i dinler; dışarı açılmaz. Dış erişim her zaman HTTP API üzerinden.
- [x] Kapatma: `ProcessSupervisor.stop()` SIGTERM gönderiyor; Postgres için bu "smart shutdown" demek.
  Hızlı kapanış için `pg_ctl stop -m fast` kullanan opsiyonel `stopCommand` alanı ekle.

### 3.2 Sürücü ve lehçe
- [x] `PostgresDialect`:
  - tablolar: `information_schema.tables` (şema `public`), sütunlar: `information_schema.columns`
  - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `created_at/updated_at TIMESTAMPTZ`
  - tip eşlemesi: text→`TEXT`, integer→`BIGINT`, real→`DOUBLE PRECISION`, boolean→`BOOLEAN`,
    datetime→`TIMESTAMPTZ`, json→`JSONB`
  - unique ihlali: `SQLException.sqlState == "23505"`
  - insert: `INSERT ... RETURNING id`
- [x] `RecordService` dönüşümleri lehçeye taşınsın: SQLite'ta boolean 0/1 ve datetime metin, Postgres'te yerel tipler.
  `toSql()`/`toJson()` için `dialect.encode(field, value)` / `dialect.decode(field, raw)`.
- [x] `JdbcSqlDriver` tek bağlantı + kilit kullanıyor. Postgres için küçük bir havuz (HikariCP Android'de ağır;
  basit 4 bağlantılık havuz yeterli) ve bağlantı koparsa yeniden bağlanma.
- [x] `AndroidSqlDriver.open()` içindeki `POSTGRES -> error(...)` yerine bu sürücü.

### 3.3 Veri taşıma
- [x] "SQLite'tan PostgreSQL'e taşı" işlemi: aynı koleksiyon tanımıyla Postgres'te şemayı kur, tabloları sayfa sayfa
  kopyala, `id` dizisini (`setval`) ayarla, satır sayılarını karşılaştır, başarılıysa konfigürasyonu değiştir.
  Sihirbazın veritabanı adımında, mevcut SQLite verisi varken Postgres seçilirse önerilir.

**Kabul kriterleri**
- [x] `CoreTest` içindeki CRUD, auth ve migration testleri **her iki lehçe** için parametrik olarak koşuyor.
  JVM'de Postgres için `io.zonky.test:embedded-postgres` (gerçek Postgres binary'si indirir) kullan.
  (`DialectContractTest`'e taşındı; `SchemaPlanTest` ve `DataCopierTest` de iki lehçede. Postgres root olarak
  çalışmadığı için root ile koşturulursa bu testler atlanır.)
- [x] Cihazda: Postgres bileşeni kurulur, deploy edilir, uygulama yeniden başlatılınca veri yerinde, telefon yeniden
  başlatılınca (otomatik başlatma açıkken) Postgres ve API sırasıyla geri geliyor. *(Cihaz testi p4-p6.)*
- [x] Postgres çökerse supervisor yeniden başlatıyor; API bu sürede 503 dönüyor ve bağlantı geri gelince düzeliyor.
  *(503 ve kendiliğinden yeniden bağlanma JVM'de `PostgresResilienceTest` ile doğrulandı; cihazda da doğrulandı:
  cihaz testi p7.)*

**Uygulama notları** (sonraki fazlar için)
- Lehçe ortak değer biçimi: TEXT→String, INTEGER→Long, REAL→Double, BOOLEAN→Boolean, DATETIME→Instant,
  JSON→JSON metni. `RecordService` önce bu biçime doğrular, `dialect.encode/decode` veritabanı tipine çevirir.
- Postgres bağlantısı `stringtype=unspecified` ile açılır: JSON metni sütun tipinden JSONB'ye çevrilir
  (pgjdbc'ye özel sınıf kullanmadan). `PooledJdbcDriver`: en çok 4 bağlantı, işlem boyunca aynı bağlantı,
  ödünç verirken `isValid` ile ölü bağlantı ayıklama; bağlanamama / kopma `DatabaseUnavailableException` → HTTP 503.
- `database.user` ve `database.password` eklendi (parola `secret:` referansı; kaydederken depoya taşınır).
  Ek alanlar varsayılanlı olduğu için eski dosyalar dönüştürücü olmadan okunur; sürüm v2'de kaldı.
- Bileşen paketine `setupFiles` (`${env:AD}` ile; kurulumdan sonra silinir, yalnızca sahibi okur) ve
  `stopCommand` eklendi. Sihirbaz Postgres'i seçince parola bir kez üretilir ve korunur: veri klasörü ilk
  kurulumda bu parolayla oluşur; sonradan değiştirmek için bkz. 3.4.
- Veri taşıma (`DeployOrchestrator.copyDatabase`): yeni bileşenler başlatılır, sunucu durdurulur, `DataCopier`
  hedefte tek işlemde şemayı kurar, kimlikleri koruyarak kopyalar, sayacı ayarlar, satır sayılarını karşılaştırır.
  Hata olursa sunucu eski konfigürasyonla geri açılır. Hedef doluysa ne olacağı kullanıcının seçimi (bkz. 3.4).
- Benzersiz indeks adları `ux_<tablo>_<alan>_<özet8>` oldu (farklı tablolarda çakışma ve Postgres'in 63 karakter
  sınırı); eski `ux_<tablo>_<alan>` adları tanınır ve gerektiğinde yalnızca o tablodakiler düşürülür.

### 3.4 Kullanıcı seçenekleri (karar uygulamada değil kullanıcıda)
Faz 3 sonunda uygulamanın kullanıcı adına verdiği kararlar seçeneğe çevrildi:
- [x] **Tip değişikliği:** kayıtlı değeri olan alanın tipi değişirse `typeChange: {from, action}` —
  `convert` (çevrilebilenler çevrilir, çevrilemeyenler boşalır; plan sayıları ve örnekleri gösterir) veya
  `clear`. Seçim yoksa engelleyici; sütun boşsa seçim gerekmez. Sihirbazda alan kartında Dönüştür/Boşalt.
  Veri taşımayla birlikte de uygulanır. (`TypeConversions`, `SchemaMigrator.planTypeChange/changeType`,
  `SqlDialect.replaceColumn`: Postgres'te `DROP/RENAME COLUMN`, SQLite'ta tablo yeniden kurulur.)
- [x] **Veri taşıma:** taşınıp taşınmayacağı sorulur (varsayılan yok); taşıma sırasında sunucu durur veya salt
  okunur çalışır (`WriteGate`: yazmalar 503 + `Retry-After`, süren yazmalar beklenir); hedef doluysa durdur veya
  hedefteki kayıtları sil (`DataCopier.copy(replaceExisting)`).
- [x] **PostgreSQL veritabanı adı** ve **yoksa oluştur** (`database.createIfMissing`, `CREATE DATABASE` 3D000'da).
- [x] **Parola:** göster/kopyala, yeniden üret, kendi parolanı gir; çalışırken değişirse **değiştir**
  (`ALTER ROLE`, dolar tırnaklı) veya **sıfırla** (bileşen işi: tek kullanıcı modunda `postgres --single`).
  Bileşen manifestine `tasks` eklendi (`${sqlstr:AD}`, çıktıda sırlar gizlenir).
- [x] **Postgres verisini sil** (onaylı): veri klasörü silinir, sonraki başlatmada `initdb` yeniden çalışır.
- [x] Herkesçe bilinen örnek API anahtarı engel değil uyarı.

### 3.5 Açık kalan geliştirici kararları (kullanıcı seçti)
- [x] **pgjdbc lint uyarıları:** `app/lint.xml`'de yalnızca pgjdbc jar'ı için bastırıldı; `ConfigValidator`
  bağlantı adresinde `sslfactory=...NonValidatingFactory`'yi reddediyor.
- [x] **Konfigürasyon sürümü:** v3 (isteğe bağlı yeni alanlar). Eski dosya kendiliğinden yeniden yazılmıyor;
  Durum ekranında "Sürümü yükseltmek ister misiniz?" kartı, Sunucu ekranında "Sürümü yükselt" düğmesi
  (`ConfigMigrations.changes`, `ConfigRepository.pendingUpgrade/upgradeFile`).
- [x] **Root ile testler:** uyarı verilip Postgres süreçleri `nobody` ile çalıştırılıyor (`NonRootPostgres`); atlanan test yok.
- [x] **Postgres paketinin barındırılması:** GitHub Releases (`.github/workflows/components.yml`,
  `tools/make-manifest.sh`). Depo gizliyken uygulama indiremez (bkz. README).
- [x] **Cihazda deneme:** cihaz testi `postgres` ve `secenekler` bölümleri (14 Ekim 2026).

---

## Faz 4 — Gelişmiş filtre ve ilişkiler

### 4.1 Filtre operatörleri
Mevcut: yalnızca `alan=değer` eşitliği.
- [x] Köşeli parantez sözdizimi: `?priority[gt]=3&title[like]=süt&status[in]=a,b&due[null]=true`
  Operatörler: `eq, ne, gt, gte, lt, lte, like` (büyük/küçük harf duyarsız), `in`, `null`.
- [x] Ayrıştırıcı `core/.../api/FilterParser.kt` — saf fonksiyon, tip kontrolü alan tipine göre
  (`gt` metin alanında hata, `like` sayıda hata). JSON alanlarında filtre yok.
- [x] `fields=title,done` ile sütun seçimi; `id` her zaman döner.
- Notlar: `ne` boş değerleri de getirir; SQLite'ta zaman metin olarak değişken kesir hanesiyle saklandığından
  karşılaştırma ve sıralama `julianday()` ile yapılır (`SqlDialect.comparable`); `like` SQLite'ta yalnızca ASCII
  harflerde büyük/küçük harf duyarsız (PostgreSQL `ILIKE` tam). Testler: `FilterParserTest`, `DialectContractTest`
  (her operatör iki lehçede).

### 4.2 İlişkiler
- [x] Yeni alan tipi `relation`: `{ "name": "author", "type": "relation", "collection": "users", "onDelete": "cascade|set_null|restrict" }`
  (konfigürasyon v5; `onDelete` boşsa zorunlu alanda `restrict`, değilse `set_null`).
- [x] ~~DDL: `BIGINT REFERENCES ...`~~ **Karar: kurallar API katmanında, veritabanı yabancı anahtarı yok.** Gerekçe: SQLite'ta
  FK her bağlantıda açılmalı (Android'de ayrı ayar); `onDelete` değişikliği veya tipin ilişkiye dönmesi SQLite'ta tablo
  yeniden kurmayı gerektirir; SQLite → PostgreSQL taşımada FK'ler bağımlılık sırası ister (kendine bağlanan tablolarda bu
  da yetmez). Sunucu veritabanına yazan tek süreç olduğundan: yazarken hedefin varlığı aynı işlemde denetlenir
  (PostgreSQL'de hedef satır `FOR SHARE` ile kilitlenir), silme davranışı tek işlemde uygulanır (cascade zincirleme,
  döngüye dayanıklı; restrict ihlalinde hiçbir şey silinmez). İlişki sütunlarına arama indeksi (`ix_…`) eklenir.
- [x] `?expand=author` ile ilişkili kaydı `expand` nesnesine göm (tek seviye; ilişki başına tek `IN (...)` sorgusu).
  Herkese açık okunan koleksiyondan korumalı koleksiyona expand geçerli kimlik ister.
- [x] Validator: hedef koleksiyon var mı, zorunlu alanda `set_null` yok, ilişki olmayan alanda `collection`/`onDelete` yok.
  Döngüsel `cascade` API katmanında güvenli olduğu için uyarı gerekmedi.
- [x] Sihirbazda alan tipi seçicide "İlişki", hedef koleksiyon ve silme davranışı seçimi; koleksiyon adı değişince ilişkiler izler.

**Kabul kriterleri:** her operatör için pozitif/negatif test; `expand` sorgu sayısının sabit kaldığını doğrulayan test
(sürücüyü sayaçla saran bir test sürücüsü); silmede üç `onDelete` davranışının testi.
Karşılandı: `FilterParserTest`, `DialectContractTest` (SQLite + PostgreSQL: operatörler, `CountingDriver` ile sabit sorgu
sayısı, restrict/set_null/cascade, ağaç ve döngü), `RelationHttpTest` (expand yetkisi), `WizardViewModelTest`.

---

## Faz 5 — Kullanıcılar ve yetki kuralları

**Hedef:** Uygulamayı kullanan son kullanıcılar kayıt olup giriş yapabilsin; kayıtlara erişim
kurallarla sınırlansın. Mevcut API anahtarı/JWT modu "yönetici erişimi" olarak kalır.

### 5.1 Kullanıcılar
- [x] Konfigürasyonda `auth.users: { "enabled": true, "allowSignup": true, "extraFields": [...], "publicFields": [...] }`
  (v6). Sistem koleksiyonu `_users`: `email` (unique, küçük harf), `password_hash`, `verified`, `role`, ek alanlar;
  yenileme token'ları `_sessions`'ta (kullanıcı silinince cascade). İkisi de koleksiyon olarak tanımlı
  (`SystemCollections`): şema güncelleme, veri taşıma ve `onDelete` kullanıcı koleksiyonlarıyla aynı yoldan çalışır.
- [x] Parola özeti: `PBKDF2WithHmacSHA256`, 310.000 tur, 16 bayt rastgele tuz, sabit zamanlı karşılaştırma.
  Biçim: `pbkdf2$<tur>$<tuz>$<özet>`. Olmayan e-postada da özet hesaplanır.
- [x] Uçlar: `POST /api/auth/signup`, `POST /api/auth/login` → erişim token'ı (JWT, 15 dk) + yenileme token'ı
  (rastgele 256 bit, veritabanında SHA-256 özeti, 30 gün, her kullanımda döner), `POST /api/auth/refresh`,
  `POST /api/auth/logout`, `GET/PATCH /api/auth/me` (parola değişikliği mevcut parolayla; tüm oturumlar kapanır),
  yönetici için `/api/auth/users` (liste, ekle, rol/parola/doğrulandı, sil).
- [x] Giriş denemelerine IP + e-posta bazlı hız sınırı (bellekte; 5 / istemci başına 20 hatalı deneme → 15 dk `429`).
- [ ] E-posta doğrulama/parola sıfırlama: SMTP gerekir; şimdilik yönetici uygulamadan sıfırlar (Sunucu → Kullanıcılar). (İsteğe bağlı alt faz.)

### 5.2 Kurallar
PocketBase tarzı serbest ifade dili yerine önce güvenli ve basit bir model:
- [x] Koleksiyon başına:
  ```json
  "rules": { "list": "public", "view": "public", "create": "auth", "update": "owner", "delete": "owner" },
  "ownerField": "author"
  ```
  Değerler: `public`, `auth` (giriş yapmış herkes), `owner` (`ownerField` = istekteki kullanıcı), `admin`, `nobody`.
- [x] `owner` kuralında liste sorgusuna otomatik `WHERE owner = ?` eklenir (başkasının kaydı 404); yönetici olmayan
  isteklerde `ownerField` istemciden alınmaz, token'daki kullanıcıyla doldurulur ve sonradan değiştirilemez.
- [x] Mevcut `publicRead`/`readOnly` alanları `ConfigMigrations` (v5 → v6) ile `rules`'a dönüştürülür; davranış aynı kalır.
- [x] Yönetici erişimi (API anahtarı, yönetici JWT'si veya rolü `admin` olan kullanıcı) `nobody` dışındaki tüm
  kuralları aşar. `nobody`, eski `readOnly`'nin anlamını korumak için yöneticiyi de kapsar.
- [x] Sihirbaza "Kullanıcılar" adımı ve koleksiyon düzenleyicide kural seçicileri (hazır kümeler + işlem başına).

**Kabul kriterleri:** kural matrisi testi (5 kural × 5 işlem × anonim/kullanıcı/başka kullanıcı/yönetici);
yenileme token'ının tek kullanımlık olduğu; parola özetinin düz metin içermediği; hız sınırının devreye girdiği testler.
Karşılandı: `UsersHttpTest` (SQLite + PostgreSQL: matris, kayıt/giriş/yenileme/çıkış, giriş sınırı, sahip alanı,
`expand` ile kullanıcı alanları, yönetici işlemleri, kayıt kapalı + yönetici JWT), `UsersConfigTest` (özet biçimi,
sınırlayıcı, doğrulama kuralları), `ConfigMigrationsTest` (v5 → v6), `WizardViewModelTest` (sihirbazdan kullanıcılı
sunucu: kayıt olan iki kullanıcı yalnızca kendi kayıtlarını görür).

**Uygulama notları** (sonraki fazlar için)
- Ktor `Authentication` eklentisi kaldırıldı; isteği yapan (`Caller`: anonim / yönetici / kullanıcı) her istekte bir kez
  çözülür, kurallar `Access.scope()` ile uygulanır, kayıt düzeyindeki kısıt `RecordService`'e `Scope` olarak geçer.
- Kullanıcı token'ının imza anahtarı `_ds_meta`'da (`auth:token_key`) rastgele üretilir; konfigürasyonda sır yoktur.
  Veritabanı taşınınca yeni anahtar üretilir: erişim token'ları geçersizleşir, yenileme token'ları (taşınan `_sessions`) çalışır.
- `auth.mode = none` iken herkes yönetici sayılır (eski davranış); kullanıcı hesapları bu yüzden yönetici kimliği ister.
  Sihirbaz hesapları açarken kimlik doğrulama kapalıysa API anahtarı üretir.
- Uygulama içi kullanıcı yönetimi `ServerEngine.users()` ile çalışan sunucunun bağlantısını kullanır.

---

## Faz 6 — Dosya yükleme ve statik site

- [x] Alan tipi `file`: `{ "type": "file", "maxSizeMb": 10, "mimeTypes": ["image/*"], "multiple": false, "thumbs": ["400x0"] }`
  (konfigürasyon v7). Dosyalar `files/storage/<koleksiyon>/<kayıt-id>/<ad>` altında; veritabanında yalnızca dosya adı
  (çoklu alanda JSON dizi). Ad `<özgün-ad>_<rastgele10>.<içerikten-uzantı>`.
- [x] Yükleme: `multipart/form-data` ile `POST`/`PATCH`. Boyut sınırı akış sırasında uygulanır (geçici dosyaya yazılırken
  aşılırsa okuma durur, 413). MIME tipi uzantıya değil içeriğin ilk 512 baytına bakarak bulunur (`MimeTypes`); uzantısı
  içeriğiyle uyuşmayan dosya reddedilir, kaydedilen uzantı içerikten gelir (HTML/SVG `.txt` olur).
- [x] İndirme: `GET /api/files/<koleksiyon>/<id>/<dosya>`; erişim kaydın `view` kuralına tabi, ad kayda ait olmalı.
  `Content-Security-Policy: sandbox`. Kayıt silinince (cascade dahil) klasörü, değiştirilen/kaldırılan dosyalar işlem
  tamamlanınca silinir.
- [x] Görseller için küçük resim (`?thumb=100x100`) — `app`'te `BitmapFactory` (`AndroidImageResizer`), `core`'da
  `ImageResizer` arayüzü. Yalnızca izinli boyutlar (`100x100` + alanın `thumbs`'ı) üretilir ve önbelleğe alınır (disk
  doldurma saldırısına karşı; PocketBase'deki gibi).
- [x] Statik site: `"static": { "enabled": true, "path": "/", "spaFallback": true }`. Dosyalar `files/www/` altında;
  Ktor `staticFiles` yerine kendi yönlendirmesi (`StaticSite.resolve`: klasör dışına çıkış ve gizli dosyalar engelli, API
  yolu siteye düşmez, SPA yönlendirmesi yalnızca uzantısız adreslerde). Uygulamadan zip yükleyerek güncelleme
  (`StaticSite.install`: ayrı klasöre açılır, tamamlanınca yer değiştirir; zip-slip ve boyut sınırı).
- [x] Depolama kullanımı Durum ekranında gösterilir (veritabanı, dosyalar, site, boş alan).

**Kabul kriterleri:** sınırı aşan dosya akış ortasında reddediliyor; sahte uzantılı dosya reddediliyor;
yetkisiz kullanıcı dosyayı indiremiyor; kayıt silinince klasörü temizleniyor.
Karşılandı: `FilesHttpTest` (SQLite + PostgreSQL: yükleme/indirme/değiştirme/silme, 413, sahte uzantı, izinsiz tip,
yetkisiz indirme, cascade ile silinen kaydın klasörü, küçük resim önbelleği, statik site ve zip), `FilesConfigTest`
(içerikten tip tespiti, güvenli adlar, doğrulama), `WizardViewModelTest`.

**Uygulama notları**
- Ktor 3.6'da `receiveMultipart(formFieldLimit)` dosya parçalarına da uygulanır; sınır en büyük dosya alanının biraz
  üstüne ayarlanır, alanın kendi sınırı akış içinde ayrıca uygulanır. Metin parçaları gövde sınırıyla sınırlı.
- `RequestGuard` multipart gövdeleri belleğe almaz ve gövde sınırını uygulamaz; `Cache-Control: no-store` yalnızca API yolunda.
- Dosya alanı başka tipe dönüştürülemez (yalnızca boşaltma); kullanıcı ek alanları dosya olamaz.

---

## Faz 7 — Özel iş mantığı (script)

**Hedef:** Kullanıcı derleme gerektirmeden kendi endpoint'lerini ve kayıt olaylarına bağlı mantığı yazabilsin.

- [x] Motor: **Mozilla Rhino 1.7.15** (saf Java; JVM testleri ve Android aynı kodla). 1.8+ Java 11 ister ve Android 8'de
  riskli olduğu için 1.7.15 seçildi (ok fonksiyonu, `let`/`const`, şablon metin var). Yorumlama kipi: Android'de bytecode
  üretilemez, komut sayacı da yalnızca bu kipte çalışır. Performans denemesi yapılmadı (telefondaki yük düşük; gerekirse
  QuickJS aynı `ScriptRuntime` yüzüyle değerlendirilir).
- [x] Script dosyaları `files/scripts/*.js`, uygulamadan düzenlenir (Sunucu → Script'ler: örnek, sözdizimi denetimi,
  "Kaydet ve deploy et").
- [x] API: `routerAdd(yöntem, yol, işleyici, erişim)`, `onBefore/AfterCreate/Update/Delete(koleksiyon | "*", işleyici)`,
  `cronAdd(ifade, işleyici)`, `fail(durum, mesaj)`, `reply(durum, gövde, başlıklar)`. `ctx.db` (koleksiyon API'si, yönetici
  yetkisi, olay tetiklemez), `ctx.auth`, `ctx.log`, `ctx.http` (izin listesi), `ctx.env` (konfigürasyon v8 `scripts.env`).
- [x] **Güvenlik:** güvenli standart nesneler + her sınıfı reddeden `ClassShutter`; komut gözlemcisiyle süre sınırı
  (`Error` atar: JS yakalayamaz), yorumlayıcı yığın derinliği sınırı; `ctx.http` için host izin listesi, yönlendirme
  izlenmez. Script hatası 500 döner ve loga yazılır, sunucuyu düşürmez. Bellek sınırı yok (süre sınırı dolaylı korur).
- [x] Script'ler deploy'un parçası: deploy sırasında derlenir ve üst düzey kod çalıştırılır; sözdizimi/kayıt hatası
  deploy'u reddeder, çalışan sunucu sürer.
- [x] Zamanlanmış görevler: `cronAdd("0 3 * * *", (ctx) => {...})`, telefonun saat diliminde; uzun görev diğerlerini
  geciktirmez, aynı görev üst üste başlatılmaz.

**Kabul kriterleri:** özel route, before/after hook'lar ve cron için testler; sonsuz döngülü script'in zaman aşımına
uğradığı ve `java.lang.System`'e erişemediği testler.
Karşılandı: `ScriptsTest` (uçlar, erişim, `reply`/`fail`, olaylar ve `ctx.current`, `ctx.db`, sonsuz döngü ve `try/catch`
ile kaçış denemesi, özyineleme, `java`/`Packages`/`getClass` erişimi, yükleme hataları, deploy'da sözdizimi hatası,
cron ifadeleri ve çalıştırma, `ctx.http` izin listesi), `WizardViewModelTest`.

**Uygulama notları**
- Rhino'da `FEATURE_THREAD_SAFE_OBJECTS` açık: istekler script'leri aynı anda çalıştırır; paylaşılan genel değişkenlerin
  yarış durumu script yazarının sorumluluğunda (Node'daki gibi tek iş parçacığı garantisi yok).
- Rhino'nun `java.beans` kullanımı yalnızca özellik dinleyicisi eklenince çalışan yolda; Android'de bu yol kullanılmıyor.

---

## Faz 8 — Yedekleme, metrikler, web admin paneli

### 8.1 Yedekleme
- [x] ~~SQLite: `VACUUM INTO`, Postgres: `pg_dump`~~ **Karar: motordan bağımsız mantıksal yedek.** Veritabanı kayıt kayıt
  (`db/<tablo>.jsonl`, ortak değer biçimi) tek işlemde okunur (PostgreSQL'de REPEATABLE READ: tutarlı görüntü). Gerekçe:
  `VACUUM INTO` Android 11 öncesinde yok; `pg_dump` bileşen paketine ek araç ve sürüm uyumu ister; mantıksal yedek
  SQLite ↔ PostgreSQL arasında da geri yüklenir. Yedek = `manifest.json` + `server.json` (sır değerleri yok) + veritabanı +
  `storage/` + `scripts/` + `www/` tek zip (`BackupArchive`).
- [x] Zamanlama: `"backup": { "enabled": true, "schedule": "0 4 * * *", "keep": 7 }` (v9); servisteki 30 sn'lik bakım
  döngüsü (`BackupSchedule`), yalnızca zamanlanmış yedekler sayılarak eskiler silinir.
- [x] Hedefler: uygulama klasörü (`files/backups/`) + kullanıcının seçtiği klasör (SAF ağaç; her yedeğin kopyası).
  [ ] S3 uyumlu depolama (isteğe bağlı, yapılmadı; seçilen klasör Drive vb. olabilir).
- [x] Geri yükleme: yedek baştan sona doğrulanır, mevcut durumun yedeği alınır, sunucu durur; veritabanı tek işlemde
  (hata olursa değişmez, sunucu eski haliyle açılır), klasörler ayrı klasöre açılıp yer değiştirerek; yedekteki konfigürasyon
  bu cihazın veritabanı ayarıyla kaydedilip deploy edilir.

### 8.2 Metrikler
- [x] Ktor eklentisi (`MetricsPlugin`, ilk kurulan eklenti): istek sayısı, durum kodu dağılımı, uç başına gecikme (son 512
  isteğin p50/p95), süren istek; son 60 dakikanın dakikalık serisi.
- [x] Cihaz: pil seviyesi/sıcaklığı/şarj, boş depolama, bellek. `core`'da `DeviceStats`, `app`'te `AndroidDeviceStats`
  (motor her dakika örnekler).
- [x] `GET /api/_metrics` (yönetici) JSON ve `?format=prometheus`.
- [x] Durum ekranında son 1 saatlik istek grafiği ve pil özeti; pil 45 °C'yi aşınca bildirim (yarım saatte en çok bir).

### 8.3 Web admin paneli
- [x] Tarayıcıdan yönetim: kayıtlar (tablo, filtre, JSON ile ekle/düzenle, sil), kullanıcılar, canlı loglar, metrikler
  (grafik + uç tablosu), yedekler (al, indir), konfigürasyon (düzenle, denetle, kaydet ve deploy et).
- [x] Bağımlılıksız düz JavaScript; derleme adımı yok. Dosyalar `app/assets` yerine **`core/src/main/resources/admin/`**
  altında (JVM testleri paneli de sunabilsin; APK'ya core jar'ının kaynaklarıyla girer), `/_admin/` yolunda.
- [x] Panel yalnızca yönetici kimliğiyle; giriş telefonda gösterilen tek kullanımlık 6 haneli kodla (5 dk, 5 hatalı denemede
  geçersiz), oturum `adminPanel.sessionHours` (bellekte, uygulama kapanınca biter). CSP: yalnızca kendi kaynakları,
  çerçeveye konamaz; veriler yalnızca metin olarak yazılır.
- [x] Yönetim API'leri: `/_admin/api/config` (oku / plan / deploy), `/_admin/api/logs` ve `/logs/stream` (SSE, en çok 30 dk,
  panel yeniden bağlanır), `/_admin/api/backups`. Kayıt/kullanıcı/metrik işleri normal API'yi panel oturumuyla kullanır.

**Kabul / testler:** `BackupTest` (SQLite ve PostgreSQL'den yedek, iki lehçeye geri yükleme, kimlikler ve sayaç, dosyalar,
gizli dosyalar ve sırlar yedekte değil, eksik/bozuk yedeğin reddi, başarısız geri yüklemede verinin değişmemesi, eski
yedeklerin silinmesi, zamanlama), `MetricsTest` (sayaçlar, uç adları, JSON/Prometheus, dakikalık seri), `AdminPanelTest`
(eşleştirme kodu: süre, tek kullanım, hatalı deneme; panel dosyaları ve CSP, panel API'leri, gerçek sunucuda canlı log akışı,
yedek indirme, çıkış, kapalıyken yok), `WizardViewModelTest`.

---

## Faz 9 — Erişim kolaylıkları ve kendini güncelleme

### 9.1 Dış erişim ve TLS
Ktor'un CIO motoru sunucu tarafında TLS desteklemediği için TLS uygulamadaki küçük bir ters vekilde (`TlsProxy`) sonlanır.
- [x] **Doğrudan (IP):** HTTPS ucu + kendinden imzalı sertifika (sabitleme ile), Wi-Fi'da NAT-PMP/UPnP ile otomatik port
  yönlendirme (yenileme, kapanışta kaldırma), CGNAT / çift NAT / IPv6 tespiti, DuckDNS, ağ değişince yeniden kontrol.
- [x] **Cloudflare Tunnel:** cloudflared bileşen paketi (Termux derlemesi, DNS düzeltmesi), hesapsız hızlı tünel
  (adres Durum ekranında) ve token'lı adlandırılmış tünel; sihirbazdan tek dokunuşla kurulum.
- [x] **Tailscale:** bileşen yerine Tailscale Android uygulaması (VPN izni ve oturum uygulamada); kurulu/bağlı algılama,
  tailnet adresi.
- [x] API sıkılaştırma: istemci başına istek sınırı, hatalı kimlik denemesinde geçici engel, gövde sınırı, düz HTTP
  portuna internetten gelen isteği reddetme, güvenlik başlıkları; internete açık modda kimlik doğrulama zorunlu
  (bilinçli onay hariç).
- [x] Cihazda doğrulama: cloudflared (DNS, QUIC/HTTP2), UPnP (gerçek modemler), mobil IPv6. *(Cihaz testi e1-e6.)*
- [x] Doğrudan erişimde gerçek sertifika: DuckDNS adıyla Let's Encrypt DNS-01 (tarayıcı uyarısı kalkar).
  *Durum:* `AcmeClient` (RFC 8555, ES256 JWS, nonce yenileme, Retry-After'lı yoklama), `CertificateManager` (yenileme
  bitişe 30 gün kala, başarısızlıkta 6 saat bekleme), DuckDNS TXT kaydı + DoH ile yayılma beklemesi. Sertifika HTTPS
  ucunun mevcut anahtarıyla istenir (pin değişmez). Sahte ACME sunucusuna karşı uçtan uca test (`AcmeTest`); gerçek
  Let's Encrypt ve DuckDNS ile **henüz denenmedi**. Konfigürasyon v10: `access.direct.ddns.certificate`.

### 9.2 Kendini güncelleme
- [x] GitHub Releases API'sinden son sürümü kontrol et (günde bir, kapatılabilir; ön sürümler isteğe bağlı), sürüm notlarını göster.
- [x] APK'yı indir, `.sha256` ile özeti ve imza sertifikasının mevcut uygulamayla aynı olduğunu doğrula, `PackageInstaller` ile kur
  (`REQUEST_INSTALL_PACKAGES` izni).
- [x] Güncelleme sonrası `MY_PACKAGE_REPLACED` ile sunucu otomatik geri gelir: "cihaz açılınca başlat" kapalı olsa da,
  güncelleme sırasında çalışıyorsa.
  *Not:* minSdk 26-27'de `getPackageArchiveInfo` v2 imzasını okuyamaz; orada imza denetimini Android kurulumda yapar.

### 9.3 Sürüm yayınlama
- [x] GitHub Actions (`release.yml`): `:core:test`, `:app:testDebugUnitTest`, `:app:assembleRelease`, imzalama (anahtar
  secrets'ta), `apksigner verify`, Release'e APK + sha256 yükleme. Sürüm adı/kodu etiketten (`-PappVersion`).
- [x] Bileşen deposu (`components.json` + zip'ler) için ayrı bir iş akışı (`components.yml`, Faz 3'te eklendi).
- [ ] İlk imzalı sürümün yayınlanması (anahtar ve secrets kullanıcıda).

---

## Faz 10 — Canlı abonelik, arama, e-posta, API dokümanı

**Hedef:** istemci uygulamaların ihtiyaç duyduğu dört eksik: değişiklikleri anında alma, hızlı arama, e-posta ile hesap
akışları ve makinece okunur API dokümanı. Konfigürasyon v11 (yalnızca isteğe bağlı alanlar).

### 10.1 Canlı abonelik (realtime)
- [x] `GET /api/realtime?collections=a,b` (Server-Sent Events): kayıt oluşturma/güncelleme/silme olayları. Kimlik
  `Authorization` başlığıyla veya (tarayıcıda `EventSource` başlık gönderemediği için) `?token=` ile.
- [x] Kurallar her olayda uygulanır: görüntüleme izni olmayan koleksiyon/kayıt gönderilmez (`owner` kuralında yalnızca kendi
  kayıtları). Script'lerin `ctx.db` yazmaları da yayınlanır.
- [x] Kopan bağlantı `Last-Event-ID` ile son olaylardan devam eder; tampondan çıkmışsa `reset` olayı (istemci yeniden listeler).
- [x] `realtime.enabled`, `realtime.maxConnections`; sihirbazda anahtar, API gezgininde örnekler, panelde kayıtlar canlı yenilenir.
  *Not:* ilişki yüzünden zincirleme silinen/boşaltılan kayıtlar için ayrı olay yayınlanmaz. Bağlantı en çok 30 dk açık
  kalır (süresi dolan token'la sonsuza dek açık kalmasın); istemci yeniden bağlanır. `RealtimeTest` gerçek CIO sunucusunda.

### 10.2 Arama ve indeksler
- [x] Alan başına `index` (benzersiz olmayan indeks); şema planı indeks ekleme/kaldırmayı gösterir.
- [x] Koleksiyon başına `search` alanları ve `?q=` parametresi: büyük/küçük harf ve Türkçe karakterden bağımsız
  (ı/i, ş/s, ğ/g, ü/u, ö/o, ç/c), kelimelerin hepsi geçmeli. Gizli `_search` sütununda normalize metin; arama alanları
  değişince deploy'da yeniden doldurulur. SQLite ve PostgreSQL aynı davranır.
  *Not:* Android'in SQLite'ında FTS5 garanti olmadığı ve LOWER/LIKE Türkçe harfleri bilmediği için tam metin arama
  yerine normalize sütun + LIKE seçildi; telefon ölçeğindeki veride yeterli. `SearchTest` iki lehçede.

### 10.3 E-posta: doğrulama ve parola sıfırlama
- [x] `email` bölümü: SMTP sunucusu (STARTTLS/SSL), kullanıcı, parola (sır deposunda), gönderen; hazır ayarlar (Gmail,
  Outlook, Yandex). Bağımlılıksız küçük SMTP istemcisi; uygulamadan "deneme e-postası gönder".
- [x] `POST /auth/request-reset`, `/auth/confirm-reset`, `/auth/request-verification`, `/auth/confirm-verification`.
  Belirteçler kısa ömürlü ve tek amaçlı; parola değişince sıfırlama belirteci geçersizleşir. Var olmayan e-posta için
  de aynı yanıt (hesap varlığı sızdırılmaz), adres ve IP başına sınır.
- [x] Sunucunun kendi küçük sayfaları (`/_auth/reset`, `/_auth/verify`): istemci uygulama yazmadan da akış çalışır.
- [x] İsteğe bağlı: girişte doğrulanmış e-posta zorunlu. Script'lerde `ctx.mail.send(...)`.
  *Not:* belirteçler durumsuz JWT (ayrı hedef, amaç, e-posta ve parola özeti parmak izi); ek tablo gerekmedi. Gerçek
  SMTP sağlayıcılarıyla (Gmail vb.) henüz denenmedi; `EmailTest` STARTTLS/SSL'li sahte SMTP sunucusuna karşı.

### 10.4 OpenAPI ve istemci örnekleri
- [x] `GET /api/_openapi.json` (OpenAPI 3.1): koleksiyonlar, alan tipleri, kurallar, kullanıcı uçları, dosyalar,
  realtime. İsteyenin yetkisine göre: yönetici her şeyi, diğerleri erişebildiklerini görür.
- [x] Web panelinde "API" sekmesi (uçlar, şemalar, deneme), uygulamadaki API gezgininde JavaScript (fetch/EventSource) ve
  Kotlin örnekleri.
  *Not:* panelde "deneme" yerine uç ayrıntıları ve curl; deneme için Kayıtlar sekmesi ve uygulamadaki API gezgini var.

---

## Faz 11 — Entegrasyon, raporlama, ince yetki, çoklu sunucu

**Hedef:** sunucuyu dış servislere bağlamak (webhook, sosyal giriş, push), veriyi toplu işlemek ve raporlamak, yetkiyi
inceltmek ve aynı telefonda birden çok sunucu çalıştırmak. Konfigürasyon v12 (yalnızca isteğe bağlı alanlar).

### 11.1 Webhook'lar
- [x] `webhooks[]`: ad, adres, koleksiyonlar, olaylar (create/update/delete), imza sırrı (şifreli depoda).
- [x] İmzalı POST (`X-DroidServer-Signature: sha256=<HMAC>`), zaman damgası, teslim kimliği; 10 sn zaman aşımı,
  başarısızlıkta artan aralıkla 3 yeniden deneme (bellekte; uygulama kapanırsa bekleyenler kaybolur).
- [x] Son teslimler (durum, süre, hata) uygulamada ve panelde; "deneme gönder".

### 11.2 Toplu işlem ve özetler
- [x] `POST /api/batch`: birden çok oluştur/güncelle/sil tek işlemde (hepsi ya da hiçbiri), her biri kendi kuralıyla.
- [x] `GET /api/collections/{c}/aggregate?count&sum=a&avg=b&min=..&max=..&groupBy=x` (filtre ve kurallarla birlikte).

### 11.3 Koşullu kurallar ve alan gizliliği
- [x] Kurala koşul: `rules.conditions.<işlem> = {"filter": "status=published", "owner": true}` → yalnızca koşula uyan
  kayıtlar; oluşturmada yeni kayıt, diğerlerinde mevcut kayıt denetlenir. Sahip istisnası (herkese yayınlananlar,
  sahibine hepsi) ve `@me`. Liste, tek kayıt, özet, `expand`, toplu işlem ve canlı abonelikte (bellekte, aynı sonuç) uygulanır.
- [x] Alan düzeyinde gizlilik: `fields[].visibility: owner|admin`; gizli alana göre filtre/sıralama/özet yasak,
  `admin` alanını yalnızca yönetici yazar. Sihirbazda koşul ve "Kim görür" düzenleyicileri.

### 11.4 İçe / dışa aktarma
- [x] Koleksiyonu CSV veya JSON olarak dışa aktarma (filtreyle; Excel için noktalı virgül, BOM, formül koruması),
  dosyadan içe aktarma (alan eşleme, doğrulama, önizleme, upsert, hepsi ya da hiçbiri); uygulamadan ve panelden.

### 11.5 Kapsamlı API anahtarları
- [x] Birden çok adlandırılmış anahtar (`auth.keys[]`, yalnızca SHA-256 özeti): tam yönetici, salt okunur veya belirli
  koleksiyonlar; son kullanma tarihi, son kullanım zamanı, uygulamadan iptal. Mevcut anahtarlar tam yetkili olarak kalır.

### 11.6 Sosyal giriş (OAuth2)
- [x] Google ve GitHub ile giriş (kendi OAuth istemci kimliğinizle; PKCE + state + tarayıcıya bağlı çerez), hesap
  doğrulanmış e-postayla eşleşir veya açılır; izinli dönüş adresine tek kullanımlık kod, `POST /auth/oauth/exchange`.

### 11.7 Web push bildirimleri
- [x] VAPID anahtarları telefonda üretilir; tarayıcı abonelikleri kullanıcıya bağlı saklanır (`_push_subscriptions`,
  yalnızca bilinen itme servisleri); RFC 8291 şifreleme (RFC test vektörüyle doğrulandı), RFC 8292 VAPID.
- [x] Script'lerden `ctx.push.send(kullanıcı, başlık, metin)`, yönetici `POST /push/send`; `push.notify` ile kayıt
  olayında sahibine bildirim.

### 11.8 Çoklu sunucu profili
- [x] Aynı telefonda birden çok bağımsız sunucu (ayrı konfigürasyon, port, veritabanı, dosyalar); uygulamada profil
  seçimi; mevcut kurulum "varsayılan" profil olur. Aynı anda biri çalışır (geçişte durdurulur); bileşenler ortak.

### 11.9 Uzman modu
- [x] Sihirbazda "Uzman" düğmesi: otomatik eklenen tüm uçlar (sunucu, yönetici, hesap, sosyal giriş, web push,
  koleksiyon) kapatılabilir veya yolu değiştirilebilir; sistem alanları (`id`, `created_at`, `updated_at`, kullanıcılarda
  `email`, `password`, `verified`, `role`) API'de yeniden adlandırılabilir veya kaldırılabilir (veritabanı sütunu
  değişmez); token süreleri, giriş ve istek sınırları. OpenAPI, örnekler, panel, dışa/içe aktarma, canlı abonelik ve
  webhook'lar etkin yolları/adları kullanır.

---

## Faz 12 — Veri kalitesi, güvenlik, dayanıklılık ve geliştirici araçları

**Hedef:** veriyi kaynağında doğrulamak, değişiklikleri geri alınabilir yapmak, kaynakları paylaştırmak, hesapları
ikinci adımla korumak, sunucuyu hızlandırmak, yedekleri telefon dışına taşımak ve istemci geliştirmeyi kolaylaştırmak.
Konfigürasyon v13 (yeni alan tipleri; mevcut ayarlar değişmez).

### 12.1 Alan doğrulama ve tipler
- [x] `select` ve `multiselect` tipleri (`options`), metinde `format` (e-posta, URL), `minLength`/`maxLength`,
  `pattern`; sayıda `min`/`max`. Doğrulama hataları alan alan; sihirbaz, OpenAPI ve panel bunları bilir.
  Çoklu seçim JSON dizisi metni olarak saklanır; `?alan=seçenek` "içerir" (SQLite `instr`, PostgreSQL `strpos`;
  seçeneklerde `"` ve `\` yasak olduğundan `"seçenek"` aramak tam eşleşmedir). Config v13.
- [x] Hesaplanmış alanlar: basit ifade (`adet * fiyat`, `ad + " " + soyad`), yazmada hesaplanır, istemci yazamaz.

### 12.2 Geçmiş ve çöp kutusu
- [x] Koleksiyon başına isteğe bağlı kayıt geçmişi: her değişiklikte önceki hal, kim (kullanıcı/anahtar/yönetici), ne zaman;
  `GET .../records/{id}/history`, eski sürüme dönme. Her sürüm kaydın değişiklik sonrası tam hali (`_history`); kayıt
  başına `keep` sürüm. Yalnızca yönetici/yetkili anahtar (gizli alanları da içerir).
- [x] Çöp kutusu: silinen kayıt N gün saklanır (`GET .../trash`, geri yükle, kalıcı sil); süre dolunca temizlenir.
  `_trash`'te kaydın hali; dosyalar süre dolana kadar depoda kalır; geri yükleme aynı kimlikle ve bugünkü doğrulamayla
  (AUTOINCREMENT/identity kimlikleri yeniden kullanmadığından çakışmaz).

### 12.3 Kotalar
- [x] Kullanıcı başına: koleksiyon başına en çok kayıt, toplam dosya depolama (MB), günlük istek. Aşılınca 403 (kayıt),
  413 (depolama), 429 (istek) ve açıklayıcı hata (`details.quota`); `GET /auth/me/usage`; uygulamada kullanıcı kartında
  kullanım. İstek sayacı bellekte (ServerEngine; deploy'lar arasında sürer). Yöneticiler ve anahtarlar muaf.

### 12.4 İki adımlı doğrulama
- [x] Kullanıcılar için TOTP (RFC 6238; Google Authenticator vb.), 10 yedek kod; isteğe bağlı veya zorunlu. Giriş
  `challenge` (5 dk JWT, ayrı hedef) + `/auth/2fa/verify`; anahtar `_two_factor`'da AES-GCM ile şifreli (anahtar
  kullanıcı token imza anahtarından türetilir), kod tekrarına karşı `last_step`. Zorunlu modda kurmamış kullanıcı
  `restricted` (yalnızca hesap uçları).
- [x] E-postayla tek kullanımlık kodla giriş (parolasız): uzun bağlantı belirteci yerine 8 haneli kod (mobil
  istemcide yazılabilir; isteğe bağlı istemci sayfasına bağlantı), 10 dk, 5 deneme. Web paneli girişine isteğe bağlı
  TOTP (eşleştirme kodunun deneme hakkını paylaşır).

### 12.5 Performans
- [x] Yanıtlarda ETag ve `If-None-Match` → 304; gzip/deflate sıkıştırma; tam metin arama dizini ve alaka sıralaması.
  Karar: SQLite FTS4 / PostgreSQL tsvector yerine taşınabilir kelime dizini (`_search_terms`: kelime, ilk 3 harf
  indeksli, geçme sayısı). FTS4 ile tsvector'ün kelime ayırması ve sıralaması farklı olduğundan iki veritabanı farklı
  sonuç verirdi; FTS4'te hazır alaka (bm25) yok. Kelime başı eşleşmesi `prefix = ? AND substr(term, 1, n) = ?` ile
  (PostgreSQL'de harmanlamadan bağımsız). ETag zayıf (sıkıştırmadan bağımsız), yalnızca GET metin yanıtlarında.

### 12.6 Bulut yedeği
- [x] Zamanlanmış/elle yedeklerin WebDAV (Nextcloud) veya S3 uyumlu depoya (Backblaze B2, MinIO, R2) yüklenmesi;
  parolayla istemci tarafı şifreleme (AES-GCM); uzaktaki yedekleri listeleme ve başka telefona geri yükleme.
  `.dsbk` biçimi: başlık + 1 MiB'lık GCM parçaları (nonce = önek + sıra, son parça işareti ek veride). HTTP için
  Ktor CIO istemcisi (WebDAV PROPFIND/MKCOL `HttpURLConnection`'da yok). SigV4 AWS test vektörüyle sınanır; WebDAV ve
  S3 sahte sunucularla uçtan uca.

### 12.7 Tipli istemci SDK'sı
- [x] Konfigürasyondan TypeScript ve Kotlin istemcisi: koleksiyon tipleri, CRUD, filtre, giriş/yenileme, canlı abonelik,
  dosya yükleme; uygulamadan paylaşma, panelden ve `GET /_sdk/{ts|kotlin}` ile indirme. Doğrulama: Kotlin çıktısı test
  kaynaklarında altın dosya olarak derlenip gerçek sunucuya karşı çalıştırılır; TS çıktısı Node'da (tip silme) çalıştırılıp
  `tsc --strict --erasableSyntaxOnly` ile denetlenir.

### 12.8 Panelden şema düzenleme
- [x] Web panelinde görsel koleksiyon/alan/kural düzenleyici; şema planı (değişiklik ve veri kaybı uyarıları) ve deploy.
  "Şema" sekmesi konfigürasyonun yalnızca `collections` kısmını tarayıcıda düzenler (bilinmeyen alanlar ve sır
  referansları korunur); tip değişiminde kayıtlı tipe göre `typeChange` (dönüştür/sil) sorulur, plan mevcut
  `/_admin/api/config/plan` ucuyla; yeniden adlandırmada ilişki hedefleri izlenir.

---

## Faz 13 — İşletim, güvenlik ve yaygınlaştırma

Kullanıcı seçimi: sekiz başlığın hepsi. Sıra: önce sunucu çekirdeği (denetim, güvenlik duvarı, uyarılar, kuyruk), sonra
kullanım kolaylığı (şablonlar, keşif/QR), sonra dağıtık çalışma (canlı kopya) ve en son dil desteği (her şeye dokunur).

### 13.1 Denetim kaydı
- [x] Yönetici işlemleri (deploy, konfigürasyon kaydı, anahtar oluşturma/iptal, kullanıcı rol/parola/silme, 2FA sıfırlama,
  geri yükleme, çöp kutusundan kalıcı silme, içe aktarma) kim/ne zaman/nereden; uygulamada ve panelde liste, filtre,
  CSV; saklama süresi. Karar: tablo yerine profil klasöründe `audit.jsonl` (sunucu dururken, veritabanı taşınırken ve
  geri yüklemede korunur; geri yükleme kaydı silemez). Deploy satırı eski/yeni konfigürasyon farkının özeti (sırlar yok).

### 13.2 Güvenlik duvarı
- [x] IP/CIDR izin ve engel listeleri (internet ve yerel ağ ayrı), adlandırılmış anahtar ve kullanıcı başına dakikalık
  hız sınırı, art arda başarısız kimlik doğrulamada otomatik geçici engel; engellenenler listesi ve elle kaldırma.
  Kararlar: engel izinden önce gelir; izin listesi doluyken yerel ağ varsayılan olarak yine erişir (`allowLocalNetwork`),
  telefonun kendisi hiç engellenmez. Engeller motor düzeyinde tutulur (deploy'da sürer, uygulama kapanınca sıfırlanır).
  Anahtar sınırı anahtarın kendisine (adresten bağımsız), kullanıcı sınırı hesaba sayılır.

### 13.3 Sağlık uyarıları
- [x] Disk dolmak üzere, 5xx oranı yüksek, sertifika bitiyor, pil sıcak, yedek başarısız, sunucu çöktü gibi durumlarda
  telefon bildirimi, e-posta veya Telegram; eşikler ve sessiz saatler ayarlanabilir; aynı uyarı tekrar etmez.
  Kararlar: varsayılan açık (yalnızca telefon bildirimi); durum uyarıları `repeatHours`'ta bir hatırlatılır ve düzelince
  bildirilir, olaylar aynı süre içinde tekrar etmez. Sessiz saatlerde kritikler (sunucu/bileşen durdu, disk doldu,
  çökme) yine gider. Telegram sohbet kimliği `getUpdates` ile otomatik bulunur. Eski pil ısınma bildirimi bu yapıya taşındı.

### 13.4 Arka plan iş kuyruğu
- [x] Kalıcı kuyruk (`_jobs`): script'ten `ctx.jobs.enqueue(ad, veri, {gecikme})`, kayıt olaylarından iş; yeniden deneme
  (üstel bekleme), eşzamanlılık sınırı, durum ve sonuç; panelde/uygulamada liste, yeniden çalıştır, iptal.
  Kararlar: iş türleri script'te `jobAdd(ad, işleyici, {retries, timeoutSec, concurrency})`; kayıt olaylarından iş
  `onAfter…` içinde `ctx.jobs.enqueue` ile (ayrı konfigürasyon yok). "En az bir kez" çalışma; `fail(4xx)` kalıcı hata.
  `key` ile tekilleştirme. Veri taşınırken (yazmalar kapalı) iş alınmaz. İş hatasının ayrıntısı (dosya:satır) sonuçta
  saklanır, yalnızca yönetici görür.

### 13.5 Hazır uygulama şablonları
- [x] Blog, mağaza/sipariş, randevu, görev takibi: koleksiyonlar, ilişkiler, kurallar, örnek veri ve statik site; sihirbazda
  tek dokunuşla (mevcut konfigürasyona ekle veya yeni profil).
  Kararlar: kurallar koşullu kurallarla (yayındaki yazı, onaylı yorum, satıştaki ürün; istemci sipariş/randevu durumunu
  veya yorum onayını kendisi veremez). Örnek veri ve site deploy'dan sonra kurulur (bekleyen kurulum profil klasöründe
  `template.json`); örnekler yalnızca boş koleksiyonlara. Ad çakışmasında yeniden adlandırma yapılmaz (ilişkiler ve
  site adlara bağlı), yeni profil önerilir. Görev takibinde örnek veri yok (kayıtlar kullanıcıya özel).

### 13.6 Yerel ağ keşfi ve QR
- [x] mDNS/DNS-SD ile `_droidserver._tcp` duyurusu; bağlantı bilgisini (adres, anahtar veya kısa ömürlü davet) QR ile
  paylaşma; SDK'larda keşif yardımcısı.
  Kararlar: duyuru Android `NsdManager` ile (varsayılan açık, sır yok). Kısa ömürlü davet JWT modunda süreli token;
  API anahtarı modunda ana anahtar uyarıyla (adlandırılmış anahtar önerilir). Panele QR ile giriş mevcut tek kullanımlık
  eşleştirme koduyla (`#pair=`). Keşif istemcisi "legacy unicast" sorgu (5353'ü dinlemeden); Kotlin SDK'da
  `discover()`, TS'de yalnızca `fromLink` (tarayıcılar mDNS yapamaz). QR: zxing core.

### 13.7 İkinci telefona canlı kopya
- [x] Ana sunucudaki değişikliklerin (kayıt olayları + dosyalar) başka bir DroidServer'a sürekli aktarılması; kopya salt
  okunur çalışır, durumu (gecikme) görünür; ana düşünce kopya yükseltilerek devralır.
  Kararlar: değişiklikler uygulama katmanında değil veritabanı tetikleyicileriyle yakalanır (`_changes`; içe aktarma,
  hesaplar, zincirleme silmeler kaçmaz); günlük yalnızca tablo ve kimlik tutar, kopya kaydın güncel halini çeker
  (tekrar uygulanabilir, SQLite ↔ PostgreSQL taşınabilir satır biçimi). Kopya çekerek (pull) çalışır, kaldığı sıra
  profil klasöründe; günlük kısaltılmışsa baştan eşitlenir. Sırlar dahil konfigürasyon anadan (devralmada istemciler
  çalışmaya devam etsin); oturumlar ve iş kuyruğu kopyalanmaz. Devralma elle (otomatik devralma ayrışmaya yol açar).

### 13.8 İngilizce arayüz
- [x] Uygulama, web paneli, API hata mesajları ve e-postalar için dil seçimi (Türkçe/İngilizce); çeviri kataloğu yapısı
  başka dillere açık.
  Kararlar: Türkçe kaynak metin katalog anahtarıdır (`tr("…{0}…")`, panelde `T("…")`), ayrı anahtar adı yok; tek
  etkin dil (uygulama ve sunucu aynı süreçte). Sabit listeler süreç başında çevrildiği için dil değişince uygulama
  yeniden açılır (çalışan sunucu yeniden başlatılır). Loglar ve veri değerleri çevrilmez. Panel dili sunucudan gelir
  (`/_admin/i18n.js`, CSP'ye uygun ayrı betik). Tamlık testi kaynağı tarar.

---

## Faz 14 — Ek güvenlik, veri yaşam döngüsü ve telefona özgü özellikler

Kullanıcı seçimi: sekiz başlığın hepsi. Konfigürasyon v14 (alan şifreleme eski sürümün yok sayamayacağı bir alan).
Sıra: önce yetki ve veri (roller, şifreleme, saklama süresi, görünümler), sonra gerçek zamanlı ve telefon özellikleri,
en son uygulamanın kendisi (kilit, sorun giderici).

### 14.1 Özel roller
- [x] `auth.users.roles` ile kullanıcı/yönetici dışında roller (editör, moderatör…); kullanıcıya atanır (uygulama ve
  panel), koleksiyon kurallarında işlem başına izin (`rules.roles`), script uçlarında `role:<ad>`, `ctx.auth.role`.
  Kararlar: rol kuralın üstüne ek izin verir ve o işlemi tüm kayıtlarda koşulsuz yapar (sahip sınırı yok), ama
  yönetici sayılmaz (yöneticiye özel alanlar gizli, `Kapalı` yine kapalı). Kullanıcının tek rolü olur. Rol adı
  değişince/silinince sihirbaz kurallardaki izinleri de günceller.

### 14.2 Alan şifreleme
- [x] Metin ve JSON alanlarında "Şifreli sakla": değer veritabanında, geçmişte, çöp kutusunda, yedeklerde ve canlı
  kopyada şifreli; API'de yetkiliye açık.
  Kararlar: AES-256-GCM, değer başına rastgele IV, biçim `enc:v1:<anahtar kimliği>:…`. Anahtar sihirbazda üretilir,
  telefonun şifreli deposunda durur (`database.fieldKey` = `secret:field_key`) ve yedeklere girmez; yedek manifestine
  yalnızca anahtar kimliği yazılır, farklı anahtarla geri yükleme reddedilir ("Var olan anahtarı gir"). Şifreli alan
  filtre, sıralama, özet, arama, benzersiz, indeks, koşul ve hesaplanan alanda kullanılamaz. Açıp kapatınca deploy
  mevcut değerleri (geçmiş ve çöp kutusu dahil) dönüştürür.

### 14.3 Saklama süresi
- [x] Koleksiyon başına "N günden eski kayıtları sil" (`retention`: gün, ölçülen zaman alanı, isteğe bağlı koşul);
  saatte bir bakım döngüsünde.
  Kararlar: silme normal silme yolundan geçer (ilişkilerin `onDelete`'i, dosyalar, geçmiş, çöp kutusu); bağlı kaydı
  olduğu için silinemeyen atlanıp loglanır; bir çalışmada koleksiyon başına en çok 500 kayıt. Webhook ve canlı abonelik
  olayı üretmez. Sistem tablolarının temizliği zaten vardı (çöp kutusu, iş kuyruğu `keepDays`, oturumlar, denetim kaydı,
  değişiklik günlüğü).

### 14.4 Görünümler ve raporlar
- [x] `views`: koleksiyonun seçilen sütunları (ilişki üzerinden bir adım: `customer.name`), sabit koşul (`@me` dahil),
  sıralama veya gruplu özet (`report`); `GET /views/{ad}` (JSON, `?format=csv`), `GET /views` (görülebilenler);
  sihirbazda Koleksiyonlar adımında, panelde "Görünümler" sekmesi, OpenAPI'de.
  Kararlar: SQL görünümü yerine uygulama katmanı (iki veritabanında aynı, şema değişikliğine dayanıklı); sorgu yönetici
  kapsamında çalışır, erişim görünümün kendisinde (`public`/`auth`/`admin` + roller). Yönetici dışına açık görünümde
  gizli alanlar ve kullanıcıların e-postası seçilemez; istemci yalnızca görünümdeki sütunlarla filtreleyebilir.

### 14.5 WebSocket kanalları
- [x] `realtime.channels`: `/realtime/ws` üzerinde iki yönlü kanallar (ad veya `room:*` kalıbı), katılma/gönderme
  kuralı (`public`/`auth`/`admin` + roller), çevrimiçi listesi (presence), son N ileti; script'lerden
  `ctx.channels.publish`.
  Kararlar: kanallar bellekte (kalıcı mesaj için koleksiyon + canlı abonelik var); kimlik `?token=` ile (tarayıcı
  WebSocket'i başlık gönderemez); bağlantı başına saniyede 20 ileti, ileti 16 KB; yavaş okuyan bağlantı kapatılır.
  Kayıt olayları SSE'de kalır (WebSocket'e taşınmadı: aynı kuralları iki yerde uygulamamak için).

### 14.6 Telefon özellikleri API'si
- [x] `phone`: SMS gönderme, son bilinen konum, cihaz durumu; script'lerden `ctx.sms.send`, `ctx.device.location()`,
  `ctx.device.status()`, yönetici API'si `/_phone/sms|location|status`. Her biri ayrı açılır, açılınca Android izni istenir.
  Kararlar: SMS yalnızca izinli öneklere (varsayılan `+90`; yurt dışı ücretli SMS'e karşı), günlük sınırla (sayaç diskte),
  her gönderim denetim kaydına (numara maskeli, metin yazılmaz). Konum `getLastKnownLocation` (GPS'i ayrıca
  çalıştırmaz, pil harcamaz). API yalnızca yönetici; kullanıcıya açmak isteyen script ucu yazar (`role:` ile).

### 14.7 Uygulama kilidi
- [x] Uygulama açılışta ve arka planda seçilen süreden (hemen / 1 dk / 5 dk) uzun kalınca telefonun doğrulamasını ister
  (parmak izi, yüz veya ekran kilidi PIN'i); açıkken ekran görüntüsü ve son uygulamalar önizlemesi engellenir; token,
  şifreleme anahtarı, bulut yedeği parolası, webhook sırrı gibi sırları kopyalamak ve erişim QR'ı ayrıca onay ister.
  Kararlar: ek kütüphane yerine sistemin BiometricPrompt'u (Android 10+; daha eskide ekran kilidi doğrulaması); kilidi
  açmak ve kapatmak da doğrulama ister; telefonun ekran kilidi sonradan kaldırılırsa kilit devre dışı kalır (kullanıcı
  dışarıda kalmasın). Sunucu kilitliyken de çalışır.

### 14.8 Sorun giderici
- [x] Durum → **Sorun giderici**: konfigürasyon, sunucunun yanıt verip vermediği (telefonun kendisinden `/health`),
  yerel ağ adresi, internet bağlantısı, erişim modu (tünel adresi yanıt veriyor mu, doğrudan erişim ve DuckDNS
  sorunları), sertifika süresi, bileşenler, pil optimizasyonu, bildirim izni, depolama ve etkin sağlık uyarıları; her
  bulguda önerilen çözüm düğmesi (sunucuyu başlat, pil/bildirim/Wi-Fi ayarı, sihirbazın Erişim adımı, Bileşenler, Loglar).
  Kararlar: kurallar core'da (`Diagnostics`, birim testli), girdileri app toplar. Doğrudan erişimde dış adres telefonun
  kendisinden denenmez (çoğu modem hairpin NAT yapmaz; yanlış alarm olurdu).

## Faz 15 — Ortam, kendi uygulamalarınız, bileşen kataloğu

- [x] **15.1 Ortam ve sırlar:** `.env` biçiminde ortak ortam değişkenleri (`env`), şifreli sırlar, içe/dışa aktarma.
- [x] **15.2 Kendi uygulamanızı çalıştırın:** Node.js/Python/Java/ikili uygulama, zip ile yükleme, sağlık denetimi ve
  geri dönüş, sunucunun yolu altında yayın.
- [x] **15.3 Bileşen kataloğu:** Node.js, Python, OpenJDK, Redis paketleri; tek dokunuşla kur ve çalıştır (cihazda
  doğrulama bekliyor).
- [x] **15.4 Git'ten deploy:** depo + dal, push webhook'u, sürüm geçmişi.

## Arayüz yenileme

- [x] Marka rengi ve Material You, açık/koyu seçimi; sunucu ekranında gruplu kartlar ve arama; durum panosu; boş
  durumlar ve ipuçları; sihirbaz akışı; web paneli yenileme; ekran görüntüleri.

## Faz 16 — Otomasyon, entegrasyon ve telefona özgü özellikler

Her alt fazda README (TR/EN), çeviriler ve cihaz testi adımları güncellendi; üç grup üç PR (#26, #27, #28).

- [x] **16.1 Otomasyon kuralları:** kayıt olayı veya zamanlama → koşul → e-posta, bildirim, webhook, kayıt güncelleme.
- [x] **16.2 Formlar:** koleksiyona kayıt ekleyen herkese açık sayfa, spam koruması.
- [x] **16.3 GraphQL:** koleksiyonlardan şema, sorgu/mutasyon, REST'le aynı kurallar.
- [x] **16.4 Yapay zekâ:** Claude, OpenAI, Gemini (ve OpenAI uyumlu yerel modeller); kayıtlara soru, anlamsal arama.
- [x] **16.5 Hızlı ayar kutucuğu ve widget.**
- [x] **16.6 SMS ile giriş:** numara ve istemci başına sınırlar.
- [x] **16.7 Tasker ve otomasyon uygulamaları:** anahtarlı komutlar, seçilen uygulamalara olay yayını.
- [x] **16.8 WebDAV ağ sürücüsü:** Digest kimlik, paylaşımlar, salt okunur seçenekler.
- [x] **16.9 Otomatik devralma:** tanık adresi, eski ana sunucunun devre dışı bırakılması (fence), kayıpsız geri alma,
  SDK'da yedek adres.
- [x] **16.10 Takımlar:** `team` kuralı, `/teams` uçları, Takımlar ekranı.
- [x] **16.11 Komut satırı aracı:** tek dosya Node.js, `/_cli`.
- [x] **16.12 MQTT (IoT):** gömülü MQTT 3.1.1 sunucusu, mesaj → kayıt, kayıt olayı → yayın.

---

## Faz 17 — Sağlamlaştırma, panel eşitleme, güvenli bağlantılar, ev otomasyonu ve araçlar (planlandı)

Kullanıcı seçimi: sekiz başlığın hepsi. Faz 16'daki gibi dört gruba bölünür; her grup ayrı PR. Her alt fazda README
(TR/EN), çeviriler (`en.json`, `panel-en.json`) ve cihaz testi adımları güncellenir, grup sonunda cihaz testi paketi
verilir. Sıra gerekçesi: önce sahada çıkan sorunlar ve yayın (yeni özellik yükü kullanıcıyı daha kararsız bir tabana
oturtmasın), sonra panelin eksiklerini kapatma, sonra güvenlik, en son yeni özellik alanları.

### Grup 1 — Sağlamlaştırma ve web paneli eşitleme

**17.1 Cihaz testi bulguları**
- [x] Cihaz testi sonuçlarındaki `fail` adımların hepsi: kök neden, düzeltme, ilgili JVM testi; adım metni yanlışsa metni
  düzelt. (Sonuç dosyası kullanıcıdan gelecek; bu alt faz ona göre büyüyüp küçülür.)
  *Durum (23 Ekim 2026):* 137 adımın 77'si Tamam, `fail` yok; Faz 12-16 ve yeni arayüz adımları (60) henüz denenmedi.
- [x] Ağ sürücüsü bağlanma talimatına KDE/Dolphin (Sunucu: yalnızca IP, Kapı: 8080, Klasör: /dav, şifreleme kapalı;
  ya da `webdav://ip:8080/dav`) ve Windows'un Digest/Basic davranışına göre not.
- [x] "Cihazda doğrulama bekliyor" işaretli maddeler için hedefli adımlar: cloudflared (DNS, QUIC/HTTP2), UPnP gerçek
  modem, mobil IPv6, PostgreSQL bileşeni, bileşen kataloğu.
  *Durum:* cloudflared, UPnP, IPv6 ve PostgreSQL cihazda geçti (e1-e6, p1-p7); katalog adımları zaten var (f15k1, f15r1,
  f15p1). Eksik çıkan Faz 16.5-16.12 adımları eklendi (`faz16b`, f16h1-f16m2).

**17.2 Android 14/15 uyumu ve pil**
- [x] Ön plan servis türleri (`dataSync`/`specialUse`) ve Android 15'in süre sınırları; uzun süre çalışmada servisin
  öldürülmemesi için kontroller (sorun gidericiye ek bulgular).
  *Durum:* servis zaten `specialUse` (Android 14+ türüyle başlatılıyor). Tür zorunluluğu (targetSdk 34+) ve Android 15'in
  `dataSync` süre sınırı ile açılışta ön plan servis kısıtları (targetSdk 35+) targetSdk 28'de uygulanmaz; `specialUse`'un
  süre sınırı yok. Sorun gidericiye eklendi: "Kısıtlı" pil ayarı (`isBackgroundRestricted`), bileşen varken Android 12+
  alt süreç kısıtı, sistemin servisi yeniden başlatması (Android 11+ `ApplicationExitInfo` nedeniyle).
- [ ] Kesintisiz çalışma ölçümü: 24 saatlik çalışmada pil ve bellek; sağlık uyarılarına "servis yeniden başlatıldı"
  olayı.
  *Durum:* `AlertKind.RESTART` eklendi (START_STICKY ile yeniden başlatmada; çökme uyarısı varsa onun yerine gelmez),
  Loglar'a ve sorun gidericiye de düşer. Ölçüm cihaz testi f17b3'te (kullanıcı); f17b1-f17b2 yeni denetimler için.
- [ ] Hedef SDK güncellemesi ve lint uyarılarının (WakelockTimeout dahil) gözden geçirilmesi.
  *Durum:* lint temiz (0 hata, 0 uyarı; önceden var olan 1 Compose ipucu). WakelockTimeout bilinçli olarak bastırıldı:
  süreli kilit uzun yedekte veya bakım döngüsü durursa sunucuyu uyutur. Hedef SDK açık karar: 29+ indirilen bileşenlerin
  çalıştırılmasını (W^X) engeller; bkz. 17.14 Play Store sorusu.

**17.3 İlk imzalı sürüm**
- [ ] Release iş akışının uçtan uca denenmesi (imza anahtarı ve GitHub secrets kullanıcıda), sürüm notu şablonu,
  uygulama içi güncellemenin gerçek sürümle doğrulanması.
- Karar: sürüm numaralandırması (0.x mi 1.0 mı) ve ilk sürümde hangi özelliklerin "deneysel" işaretleneceği.
  *Karar (kullanıcı, 23 Ekim 2026):* ilk sürüm **0.9.0**; cihaz testinde henüz denenmemiş her özellik "deneysel"
  (test geçtikçe listeden çıkar), targetSdk 28'de kalır.
  *Durum:* sürüm notu şablonu (`.github/release-notes/TEMPLATE.md`) ve `0.9.0.md` hazır; `release.yml` `<sürüm>.md`'yi
  notun başına koyar. Kalan (kullanıcıda): imza anahtarı ve dört secret (şu an tanımlı değil), `v0.9.0` etiketi, ardından
  `v0.9.1` ile uygulama içi güncellemenin gerçek sürümle denenmesi (cihaz testi g2'nin imzalı yolu).

**17.4 Web panelini eşitleme**
- [x] Panelde yalnızca uygulamada olan ekranlar: Takımlar, MQTT (durum, yönlendirmeler, komut), Canlı kopya ve devralma
  durumu/ayarları, Ağ sürücüsü, YZ (kullanım, dizin), Kendi uygulamalarınız (durum, deploy), Komut satırı (kurulum
  komutları), Otomasyonlar ve Formlar düzenleme.
- [x] Her ekran için önce yönetici API'si (yoksa) — panel ve CLI aynı uçları kullanır.
- Kabul: panelden yapılan değişiklik uygulamadaki ile aynı doğrulamadan geçer (aynı `ConfigValidator`), denetim kaydına
  `panel` olarak düşer; panel testleri (mevcut `AdminPanelTest` kalıbında) her ekran için.
  *Durum:* Kullanıcılar sekmesinde Takımlar; yeni **Otomasyon** (kurallar, Şimdi dene, formlar) ve **Servisler** (MQTT,
  ağ sürücüsü, canlı kopya/devralma, YZ, kendi uygulamalarınız, CLI) sekmeleri. Ayarlar `config/plan` + `config/deploy`
  yolundan (AppAdminHost → ConfigValidator, denetimde `panel`). Yeni uçlar: `GET /_admin/api/replica`,
  `POST /_admin/api/replica/takeover` (AdminHost.replica/takeover; uygulamadaki Devral ile aynı `Failover.promote`).
  Mevcut durum uçları (`/_mqtt`, `/_ai`, `/_apps`, `/_automations`, `/teams`) `/info` yollarına eklendi. Testler:
  `AdminPanelTest` (otomasyon, servisler ve takımlar; canlı kopya ekranı). Sırlar (YZ anahtarı, kopya anahtarı) ve zip
  yükleme telefonda kalır. Cihaz testi f17p1-f17p3.

### Grup 2 — Güvenli bağlantılar ve ev otomasyonu

**17.5 MQTT ve ağ sürücüsü için TLS**
- [x] MQTT'ye TLS dinleyicisi (8883), doğrudan erişimdeki sertifikayla (Let's Encrypt veya kendinden imzalı); düz 1883
  isteğe bağlı kapatılabilir.
  *Karar (kullanıcı, 24 Ekim 2026):* doğrudan erişimin sertifikası yerine **yerel kök sertifika**: cihazlar telefona IP
  adresiyle bağlandığı için o sertifikalar ad doğrulamasından geçmez. Düz 1883 varsayılan açık, kapatılabilir.
  *Durum:* `mqtt.tls`, `tlsPort`, `plain` (konfigürasyon v22). `LocalCa`: kök (CA, 2049'a kadar) ve onunla imzalı
  sunucu sertifikası (yerel IP'ler ve `localhost`, 397 gün, aynı anahtarla adres değişince ya da 30 gün kala yeniden
  imzalanır; SKID/AKID, keyUsage, serverAuth: OpenSSL `-x509_strict` ve Python'un sıkı denetiminden geçer). Bağlam her
  bağlantıda istenir. MQTT ekranında TLS bölümü (kök parmak izi, paylaş/kopyala), panelde ayarlar ve kök indirme
  (`GET /_mqtt/ca.pem`), durumda TLS'li bağlantılar. MQTT yine yalnızca yerel ağda.
- [x] Ağ sürücüsünün HTTPS adresi (doğrudan erişim/tünel) için talimat ve uygulamada ayrı adres.
  *Durum:* Ağ sürücüsü ekranında "İnternetten (HTTPS)" adresi ve kopyalama; adres hesabı `publicUrl()` (sorun giderici
  ile ortak).
- Karar: internetten MQTT'ye izin verilsin mi (yalnızca TLS ile, cihaz başına parola ile) yoksa her zaman yerel ağ mı.
  *Karar (kullanıcı, 23 Ekim 2026):* **yalnızca yerel ağ.** Sunucu internetten gelen bağlantıyı kabul etmez (genel adres
  ve telefonla aynı IPv6 /64'ten değilse; Tailscale 100.64/10 yerel sayılır); ret dakikada en çok bir kez loglanır.

**17.6 Passkey (WebAuthn) ile giriş**
- [x] Kullanıcı hesaplarında parolasız giriş: kayıt ve doğrulama uçları, birden çok anahtar, anahtar listesi ve silme;
  panel girişinde de seçenek.
- [x] SDK'da (TS) yardımcılar; tarayıcı örneği.
  *Durum:* `auth.passkeys` (`rpId`, konfigürasyon v17), bağımlılıksız WebAuthn (`WebAuthn`, `PasskeyService`; CBOR,
  ES256/RS256, attestation "none", sayaç denetimi), `_passkeys` / `_panel_passkeys`, `/auth/passkeys/...` ve
  `/_admin/api/passkeys/...`, panelde giriş düğmesi ve Panel passkey'leri kartı, sihirbazda Güvenlik → Passkey (alan adı
  önerisi ve uyarı), TS SDK `registerPasskey` / `loginWithPasskey` / `passkeys` / `deletePasskey`. Passkey girişi ikinci
  adımı istemez; giriş seçenekleri e-posta almaz (hesap sızmaz). Testler: `WebAuthnTest`, `PasskeyHttpTest`. Bu makinede
  `tsc` olmadığı için TS tip denetimi koşmadı (Node ile çalıştırma testi geçti).
- Not: WebAuthn alan adı (RP ID) ister; HTTPS + gerçek ad (DuckDNS veya tünel) gerekli, yerel IP ile çalışmaz — sihirbaz
  bunu açıkça söylemeli.

**17.7 Oturum yönetimi ve varsayılan şifreleme**
- [x] Kullanıcının açık oturumları (cihaz, IP, son kullanım) ve tek tek/hepsini kapatma; yönetici de görebilir.
  *Durum:* `_sessions`'a `device`, `ip`, `last_used_at`; yenileme aynı oturumu döndürür (kimlik sabit), erişim token'ında
  `sid`. Kapatılan oturum iptal listesine girer (erişim token'ı ömrü kadar, `_ds_meta`'da kalıcı): token hemen düşer;
  "oturum var mı" denetimi yerine liste tutuldu ki canlı kopyada ve devralmada token'lar geçerli kalsın. Uçlar
  `/auth/sessions[/{id}]`, `/auth/users/{id}/sessions`; uygulamada Kullanıcılar → Oturumlar, panelde Kullanıcılar, TS SDK.
  Kendi parolasını değiştiren kullanıcının bu oturumu kalır, diğerleri kapanır. Test: `SessionsTest`.
- [x] Bulut yedeğinde şifrelemenin varsayılan açık olması (mevcut kurulumlar için uyarı ve tek dokunuşla geçiş).
  *Durum:* zaten zorunlu: her yükleme AES-256-GCM ile şifrelenir, `backup.remote.passphrase` en az 12 karakter (doğrulayıcı)
  ve şifresiz mod yok; mevcut kurulumlar için geçiş gerekmez. (Klasöre kopya şifresiz; ayrı bir konu.)
- [x] Cihaz başına MQTT kimliği (ortak parola yerine isteğe bağlı cihaz listesi; birini iptal etmek diğerlerini etkilemez).
  *Durum:* `mqtt.devices` (ad = kullanıcı adı, parola şifreli depoda); cihaz varken ortak giriş kapatılabilir; durumda
  bağlantının kimliği; uygulamada ve panelde Cihazlar. Test: `MqttTest`.

**17.8 Ev otomasyonu (IoT 2)**
- [x] Otomasyon eylemi "MQTT mesajı gönder" (konu ve içerik şablonu kayıt alanlarıyla); otomasyon tetikleyicisi olarak
  doğrudan MQTT konusu (kayda yazmadan).
- [x] Home Assistant MQTT keşfi: seçilen koleksiyon/alanlar için `homeassistant/sensor/...` yapılandırma mesajları.
- [x] Sahneler: adlı komut grupları (birden çok konu/içerik), uygulamadan, widget'tan ve API'den tek dokunuşla.
- [x] Sensör panosu: koleksiyondaki sayısal alanlar için zaman grafiği (uygulama ve panel), eşik uyarısı (sağlık
  uyarıları kanalına).
  *Durum:* `trigger.mqtt` ve `mqtt` eylemi (uygulama ve panel düzenleyicileri); `mqtt.scenes` + `POST /_mqtt/scenes/{ad}`
  + MQTT ekranı + "DroidServer sahneler" widget'ı; tek `sensors` listesi hem pano (`GET /_sensors`, uygulamada Sensör panosu,
  panelde Servisler) hem eşik uyarısı (`AlertKind.SENSOR`) hem Home Assistant keşfi (`mqtt.homeAssistant`, `binary_sensor`
  evet/hayır için; listeden çıkan sensörün keşfi silinir) için. Testler: `AutomationTest`, `MqttTest` (ev otomasyonu).
  Ek düzeltme: MQTT ekranındaki "Gönder" yayını ana iş parçacığında yapıyordu (Android soket yazımını engeller;
  mesaj sessizce gitmiyordu), arka plana alındı.
  Ekran görüntüsü testleri: `24-sensor-panosu`, `25-sahne-widget`, `26-sahne-widget-koyu`; cihaz testi f17m1-f17h4.
- Karar: tutulan (retained) mesaj desteği eklensin mi (Home Assistant için yararlı; bellek ve kalıcılık maliyeti var).
  *Karar (kullanıcı, 23 Ekim 2026):* **eklensin.** Durum: konu başına son mesaj, yeni aboneliğe RETAIN bayrağıyla, boş
  içerik siler; `mqtt-retained.json` (en çok 5 sn'de bir yazılır), en çok 1000 konu / 4 MB; `/_mqtt/publish` `retain`.

### Grup 3 — Geliştirici ve veri araçları

**17.9 SDK'lar ve GraphQL abonelikleri**
- [x] Dart/Flutter SDK (tipli, `fallbackUrls`, takımlar, canlı abonelik) ve Swift SDK (async/await); `_sdk/{lang}` ve
  golden testleri.
  *Durum:* `_sdk/dart` (`dart:io`, Dart 3; Flutter web hariç) ve `_sdk/swift` (Swift 5.9+, Apple ve Linux; Swift 6 kipinde
  uyarısız). Tipli kısım üreticide, her sunucu için aynı çalışma zamanı `resources/sdk/base.{dart,swift}`'te. Linux'ta
  `URLSession.bytes` olmadığı için Swift'in canlı aboneliği temsilciyle (`URLSessionDataDelegate`). Altın dosyalar
  `core/src/test/sdk/`; `SdkClientTest` Kotlin senaryosunun aynısını Dart (`dart analyze --fatal-infos` + `dart run`) ve
  Swift (`swiftc -swift-version 6 -warnings-as-errors`) ile gerçek sunucuya karşı koşar (araç yoksa atlanır). Uygulamada
  API gezgini ve panelde API sekmesi dört dili de verir.
- [x] GraphQL abonelikleri (WebSocket üzerinden, `graphql-transport-ws` protokolü), REST'le aynı kurallar.
  *Durum:* `/graphql/ws` (canlı abonelik açıkken); şemada `Subscription { <koleksiyon>(id, events): <Tip>Event! }`;
  kimlik `connection_init` payload'ında (tarayıcı başlık gönderemez); her olayda görüntüleme kuralı (SSE ile aynı), silmede
  yalnızca kimlik; bağlantı başına en çok 20 abonelik; aynı soketten sorgu/mutasyon da (tek `next` + `complete`).
  HTTP'deki abonelik isteği WebSocket'e yönlendiren hatayla döner. Test: `GraphQLTest` (abonelikler).

**17.10 Panelde salt okunur SQL konsolu ve örnek veri**
- [x] Yalnızca yönetici; yalnızca `SELECT` (ayrıştırıcıyla denetim + salt okunur bağlantı/işlem), sonuç sınırı ve
  zaman aşımı, şifreli alanlar maskeli, sorgu denetim kaydına.
- [x] Koleksiyona örnek veri üretme (alan tiplerine ve doğrulama kurallarına uyan; ilişkileri gözeten).
  *Durum:* `POST /_sql` (`SqlConsole.check`: tek ifade, `select/with/values`, yazma anahtar sözcükleri ve `_ds_meta`,
  parola özetleri, `pg_read_file` gibi tehlikeli adlar yasak) + `SqlDriver.readOnly` (SQLite `query_only`, Android salt
  okunur açılış ve `CancellationSignal`, PostgreSQL `READ ONLY` + `statement_timeout`); 1000 satır, 5 sn; panelde SQL
  sekmesi. `POST /_sample/{c}` (`SampleData`): tek işlem, olay yok; benzersiz alanlar mevcut kayıt sayısından devam eder.
  Test: `DevToolsTest` (ayrıştırıcı atlansa da yazma reddedilir, iki lehçede).

**17.11 Veri araçları**
- [x] Dosya alanlarının içe aktarımı: zip içinde CSV/JSON + dosyalar (dışa aktarma da aynı biçimde).
- [x] Panelde toplu düzenleme (seçili kayıtlarda alan değiştirme) ve toplu silme; `batch` ucu üzerinden.
- [x] Kayıt geçmişinde alan bazında geri alma (tüm sürüm yerine tek alan).
- [x] Koleksiyon için CSV şablonu indirme (başlıklar, örnek satır, seçenek listeleri).
  *Durum:* `format=zip` (`data.json` + `files/<id>/<ad>`; içe aktarmada `data.csv` de); yükleme denetimi ortak
  (`FileStore.accept`); zip akışla okunur, bellekte toplanmaz (512 MB, sıkıştırılmış ve açılmış; tanınmayan öğeler de
  sayılır). Geri alınan aktarmada taşınan dosyalar silinir; güncellenen kaydın eski dosyaları ancak işlem kalıcı olunca
  (`RecordService.update(removedFiles)` — önceden iç işlem bitince siliniyordu, deneme modunda dosya kaybı olurdu).
  Toplu işlem dosya alanı olmayan koleksiyonda `/batch` (dosya alanlıyı batch kabul etmediği için tek tek).
  `revert?fields=a,b`; panelde Geçmiş → Tek alan…. Şablon `GET /collections/{c}/import/template`; ipuçları başlıkta
  parantez içinde, içe aktarma eşlemesi parantezi yok sayar. CLI `.zip` içe/dışa aktarır. Android'deki İçe / dışa
  aktarma ekranına zip eklenmedi (panel ve CLI'de). Testler: `DataTransferTest` (zip, şablon), `HistoryTrashTest`.

### Grup 4 — İletişim kanalları ve yaygınlaştırma

**17.12 Telegram ile yönetim**
- [x] Mevcut uyarı botuna komutlar: `/durum`, `/yedek`, `/loglar`, `/baslat`, `/durdur`; yalnızca kayıtlı sohbet
  kimliğinden, yıkıcı komutlarda onay.
  *Durum:* `TelegramBot` (core) uzun yoklamayla; `alerts.telegram.adminChatId` (sayı olmalı; sihirbazda bulma düğmesi).
  Başka sohbet yanıtsız, 2 dakikadan eski komut yok sayılır; `/durdur` satır içi onay düğmesiyle (belirteçli, 2 dk).
  `/durdur` sunucuyu durdurur, servis kilitleri bırakıp dinlemeye devam eder; telefondan Durdur her şeyi kapatır.
  Test: `TelegramBotTest` (sahte Telegram sunucusu).
- Karar: komutlar için ayrı bir "yönetici sohbeti" mi, yoksa uyarı sohbeti mi.
  *Karar (kullanıcı, 23 Ekim 2026):* **ayrı yönetici sohbeti** (aynı bot; komutlar yalnızca o sohbetten). `/durdur`'dan
  sonra servis yalnızca Telegram'ı dinlemek için ön planda kalır, `/baslat` çalışır.

**17.13 E-posta şablonları ve toplu bildirim**
- [x] Hesap e-postaları (doğrulama, parola sıfırlama, giriş kodu) için şablon düzenleyici (konu, metin, HTML; önizleme;
  dil başına).
- [x] Kullanıcılara toplu e-posta / web push: alıcı süzgeci (rol, takım, koşul), hız sınırı, iptal edilebilir iş
  kuyruğu görevi, abonelikten çıkma bağlantısı.
  *Durum:* `email.templates` (`verify`/`reset`/`login`, `lang`; dil `Accept-Language`'dan), yer tutucular `{app}`,
  `{email}`, `{code}`, `{link}`; sihirbazda düzenleyici ve önizleme. `POST /_broadcast` + `Broadcaster`: `_jobs`'ta
  `_broadcast` işi (kuyruk artık kullanıcılar ve e-posta/push açıkken de var), kendi çalıştırıcısı, ilerleme işin
  sonucunda (yeniden başlayınca sürer), saniyede bir iptal denetimi, art arda 10 hatada durur. `_unsubscribed` tablosu;
  `/_auth/unsubscribe` imzalı (HMAC) bağlantı, GET onay sayfası, POST çıkarır. Script iş türü adı artık harf/rakamla
  başlamalı (`_broadcast` ile çakışmasın). Panelde Kullanıcılar → Toplu bildirim. Testler: `EmailTemplatesTest`,
  `BroadcastTest`.

**17.14 Yaygınlaştırma**
- [x] İlk açılışta kısa rehber (3-4 ekran) ve örnek proje (şablonlardan biriyle hazır kurulum).
- [x] Uygulama içi yardım sayfaları (README bölümlerinden üretilen, çevrimdışı).
- [x] F-Droid için tekrarlanabilir derleme ve meta veri; Play Store için gizlilik politikası, izin gerekçeleri
  (SMS ve konum Play politikasına takılabilir — ayrı yapı türü gerekebilir).
  *Durum:* `OnboardingScreen` (3 sayfa + başlangıç: şablon veya boş sihirbaz; `Settings.onboardingDone`). README'ler
  derlemede `assets/help/`'e kopyalanır, `HelpDocs` bölümlere ayırır (geliştirici bölümleri hariç), `HelpScreen` arama ve
  küçük Markdown görüntüleyici. `fastlane/metadata/android/`, `vcsInfo` ve `dependenciesInfo` kapalı (iki temiz derleme
  aynı SHA-256), `PRIVACY.md`/`PRIVACY.en.md`, `docs/fdroid/`. Lisans GPL-3.0-or-later (`LICENSE`; kullanıcı kararı, 23 Ekim 2026).
  Açık konular (`docs/fdroid/README.md`): çalışırken indirilen bileşen ikilileri ve uygulama içi güncelleyici F-Droid
  politikasına takılabilir.
  Ekran görüntüsü testleri: `27-rehber`, `28-rehber-baslangic`, `29-yardim`, `30-yardim-bolum`.
- Karar: Play Store'a SMS izni olmayan ayrı bir sürüm mü çıkılacak.
  *Karar (kullanıcı, 23 Ekim 2026):* **şimdilik yalnız F-Droid.** Play, targetSdk 28 ve paket kurma izni yüzünden şu an
  kabul etmez; ayrı sürüm targetSdk yükseltilene kadar ertelendi.

### Kabul (her grup)
- `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug` temiz (yalnızca bilinen uyarılar).
- Yeni her uç OpenAPI'de, gerekiyorsa SDK'da (golden güncel); yeni her ekran için ekran görüntüsü testi.
- README (TR/EN) bölümü, konfigürasyon tablosu satırı, yol haritası maddesi; cihaz testi adımları ve güncel paket.

## Faz 18 — Çevrimdışı ve ölçek, konum/takvim/medya, kodsuz sayfalar, paketler, yerel YZ, ödemeler (planlandı)

Kullanıcı seçimi: sekiz başlığın hepsi; ödemelerde Stripe ve iyzico'ya ek olarak Paddle. Faz 17 bittikten sonra
başlar (panel eşitleme ve güvenli bağlantılar bunların temelidir). Dört grup, her grup ayrı PR; her alt fazda README
(TR/EN), çeviriler ve cihaz testi adımları, grup sonunda cihaz testi paketi.

### Grup 1 — Çevrimdışı istemciler ve kopyalarla ölçek

**18.1 Çevrimdışı istemci eşitleme**
- [x] Sunucu: koleksiyon başına değişiklik akışı (`/sync/{koleksiyon}?since=<imleç>`), kuralları ve alan gizliliğini
  uygulayarak; silinenler için iz (tombstone) ve saklama süresi.
- [x] SDK'lar (TS, Kotlin, Dart): yerel depo (IndexedDB / SQLite), bekleyen yazma kuyruğu, bağlanınca gönderme ve çekme,
  canlı abonelikle anında güncelleme.
- [x] Çakışma çözümü: alan bazında "son yazan kazanır" (kayıt sürümü + alan zamanları) varsayılan; koleksiyon başına
  "elle çöz" seçeneğinde çakışma kaydı ve SDK geri çağrısı.
  *Durum:* `collections[].sync` (`lww`/`manual`, `keepDays`), konfigürasyon v18. `SyncLog`: tetikleyicilerle `_sync`
  (kayıt sürümü, genel sıra, silme izi) ve `_sync_fields` (alan başına sürüm ve zaman); eski Android SQLite'ında da çalışır
  (JSON fonksiyonu, UPSERT ve pencere fonksiyonu yok), PostgreSQL'de sıra işlem kilidiyle (numara sırası = tamamlanma
  sırası). Temizlik son numaralı izi silmez (SQLite'ta numara geri gitmesin; testte yakalandı). `epoch` değişince veya
  imleç silinen izlerden eskiyse `reset`. `SyncService`: çekmede liste kuralı (görünmeyen kayıt `delete`), göndermede
  yazmalar normal yoldan (kural, kanca, olay), istemci saati `now` ile düzeltilir. SDK'larda `koleksiyon.offline(depo)`:
  takılabilir depo (bellek, dosya, tarayıcıda IndexedDB), kayıt başına birleşen kuyruk, negatif geçici kimlik,
  `onConflict`/`onRejected`, `live()`. Çakışma kaydı istemciye döner (sunucuda ayrı tablo tutulmadı). Uygulamada ve
  panelde koleksiyon ayarı. Testler: `SyncTest` (iki lehçe), `SdkClientTest` (dört dil).

- Karar: dosya alanları çevrimdışında nasıl ele alınsın (yalnızca adres mi, isteğe bağlı önbellek mi).
  *Karar (kullanıcı, 23 Ekim 2026):* **yalnızca adres**; çevrimdışıyken dosya yüklenmez. Yerel depo SDK'da takılıp
  çıkarılabilir bir arayüz (hazır: tarayıcıda IndexedDB, diğerlerinde JSON dosyası; bağımlılık yok). Eşitleme dört
  SDK'ya birden (TS, Kotlin, Dart, Swift).

**18.2 Kopyalarla ölçek**
- [x] Kopyaya gelen yazma ana sunucuya iletilir (istemcinin kimliğiyle; 503 yerine), yanıt istemciye döner; bağlantı
  koparsa açık hata.
- [x] Birden çok kopya; ana sunucuda kopya tablosu (gecikme, son temas, sürüm), panelde ve uygulamada.
- [x] Okumaların kopyalara dağıtılması: SDK'da okuma adresleri listesi ve "kendi yazdığını oku" garantisi (yazmadan sonra
  kısa süre ana sunucudan oku).
  *Durum:* `WriteForwarding` eklentisi: iletilen uçlar uç tanımlarından (uzman modu yollarıyla) türetilir; istemcinin
  yetki başlığı, kopyanın kimliği (`X-DroidServer-Replica`, ana sunucunun API anahtarı) ve istemci adresi gider; ana sunucu
  adrese yalnızca bu anahtarla güvenir ("yerel" işareti iletilmez). Ulaşılamazsa 502. `/_replication/replicas` ve
  `ReplicaRegistry.report` (uygulanan sıra, şema sürümü), panelde Canlı kopya kartında tablo, uygulamada Durum → Kopyalar.
  SDK'larda `readUrls` + `readYourWritesMs`. Testler: `ReplicationTest` (iletim, tablo), `WriteForwardingTest`,
  `SdkClientTest` (dört dilde okuma adresleri).

- Karar: kopya iletilen yazmalarda kendi kural denetimini mi yapsın, yalnızca ana sunucu mu (öneri: yalnızca ana).
  *Karar (kullanıcı, 23 Ekim 2026):* **yalnızca ana sunucu.**

### Grup 2 — Konum, takvim ve medya

**18.3 Konum**
- [x] `geo` alan tipi (enlem/boylam, isteğe bağlı doğruluk), filtre `near=lat,lng,km` ve sınırlayıcı kutu, mesafeye göre
  sıralama; SQLite ve PostgreSQL'de aynı sonuç (PostGIS gerekmeden, kutu ön süzgeci + mesafe).
- [x] Panelde ve uygulamada harita görünümü (OpenStreetMap karoları; çevrimdışı karo yok), kayıt ekranında konum seçme;
  telefonun konumuyla doldurma (mevcut telefon özellikleri).
  *Durum:* `FieldType.GEO`; API'de `{lat, lng, accuracy?}`, veritabanında `enlem,boylam[,doğruluk]` metni (`GeoPoint`).
  `alan[near]=enlem,boylam,km` (en çok 1000 km; sıralama yoksa yakından uzağa) ve `alan[box]=g,b,k,d`. SQL'de enlem/boylam
  metinden çıkarılır (SQLite `CAST`, PostgreSQL `split_part`); eski Android SQLite'ında trigonometri olmadığından uzaklık
  eşdikdörtgen yaklaşımla, kosinüs Taylor serisiyle; kural koşulları (canlı abonelik) aynı formülle. 180. boylam iki
  aralığa bölünür. Benzersiz/indeks, sıralama ve `eq` yok. CSV, yedek, kopya, tip dönüştürme (metin ↔ konum), örnek veri,
  form, otomasyon, OpenAPI, dört SDK'da `GeoPoint`. Panelde kütüphanesiz karo haritası (Kayıtlar → Harita, kayıt
  düzenlemede haritadan / telefondan / tarayıcıdan konum; CSP'ye tile.openstreetmap.org), uygulamada Sunucu → Harita
  (Compose, karolar önbellekte, tanıtıcı User-Agent). Uygulamada kayıt düzenleme ekranı olmadığından konum seçme panelde.
  Testler: `GeoTest` (iki lehçe), `SdkClientTest` (dört dil), ekran görüntüsü 31-harita.

**18.4 Takvim**
- [x] Tarih aralığı alanı ve "çakışma olmasın" kuralı (aynı kaynak için üst üste binen rezervasyon reddedilir).
- [x] iCal akışı (`.ics`, koleksiyon/görünüm başına, isteğe bağlı gizli adres) ve CalDAV/CardDAV sunucusu (WebDAV
  altyapısı üzerinde) ile telefon ve bilgisayar takvimleriyle iki yönlü eşitleme.
  *Durum:* tarih aralığı ayrı alan tipi yerine koleksiyonun `calendar` eşlemesiyle (başlangıç ve bitiş iki tarih-saat
  alanı; filtrelenebilir, sıralanabilir kalır). `noOverlap` + `resource`: RecordService yazmayla aynı işlemde denetler
  (409; PostgreSQL'de koleksiyon kilidi), bitiş < başlangıç 400. `GET /calendar/{c}.ics` (liste kuralı veya
  `?token=feedToken`; görünüm başına akış yapılmadı). `CalDav`: ağ sürücüsünün adresi ve kimliğiyle `/dav/principal/`,
  `/dav/calendars/<c>/`, `/dav/contacts/<c>/`; PROPFIND (istenen özellikler, 404 propstat), REPORT (multiget, query;
  süzgeçsiz), GET, PUT (If-Match / If-None-Match, ETag içerik özeti, PUT yanıtında ETag yok), DELETE, `.well-known`.
  İstemcinin kaynak adı ve UID'si `_ds_dav` tablosunda. Yazmalar yönetici olarak normal yoldan. `ICal` (katlama, kaçış,
  TZID, tüm gün, DURATION), `CalendarMapping` (VEVENT, vCard 3.0). Uygulamada ve panelde koleksiyon ayarı.
  Testler: `CalendarTest` (iki lehçe: çakışma, abonelik, CalDAV/CardDAV oturumu, biçim, doğrulama).
- Karar: CalDAV yazma desteği ilk sürümde olsun mu (yalnızca okuma çok daha basit).
  *Karar (kullanıcı, 23 Ekim 2026):* **okuma + yazma.** CalDAV (VEVENT) ve CardDAV (vCard) iki yönlü; temel alanlar
  (başlık, başlangıç/bitiş, açıklama, yer; ad, e-posta, telefon) koleksiyon alanlarına eşlenir, eşlenmeyen özellikler
  saklanmaz.

**18.5 Medya**
- [x] Video ve ses için `Range` istekleriyle akış (ileri sarma), içerik türü ve önbellek başlıkları.
- [x] Görsellerde WebP (ve destekleniyorsa AVIF) dönüşümü, boyut ön ayarları, EXIF konum bilgisini silme seçeneği
  (varsayılan açık).
- [x] YZ ile otomatik etiket ve görseldeki metni çıkarma (OCR) — buluttaki sağlayıcı veya 18.7'deki yerel model;
  sonuç seçilen alanlara yazılır ve anlamsal aramaya girer.
  *Durum:* Ktor `PartialContent` (206, Content-Range); dosya indirmede `Cache-Control: private, max-age=31536000,
  immutable` (adlar değişmez; RequestGuard'ın `no-store`'u dosya yoluna verilmez). Alan ayarları `convert`
  (webp/jpeg), `maxDimension`, `keepLocation`, `ai {tags, text}`; konfigürasyon v19. `Media.process` yüklemede:
  dönüşüm/küçültme telefonda (`ImageResizer.convert`, EXIF yönü uygulanır, meta veri yazılmaz), değilse `ExifGps`
  dosya boyutunu değiştirmeden GPS IFD'sini ve konumlu XMP'yi siler (JPEG, PNG eXIf/iTXt + CRC, WebP). "Boyut ön ayarı"
  mevcut küçük resim boyutları + yeni `maxDimension` olarak yorumlandı. OCR/etiket: `AiMessage.images` (OpenAI uyumlu
  `image_url`, Gemini `inlineData`, Claude base64 görsel bloğu), `ImageInsight` (JSON yanıt, çoklu seçimde yalnız
  seçenekler), kayıt yazıldıktan sonra arka planda, sonuç yönetici olarak normal güncellemeyle (anlamsal dizin dahil).
  Testler: `MediaTest` (JPEG/PNG/WebP konum silme, dönüşüm, Range, önbellek, YZ, doğrulama).
- Karar (kullanıcı, 23 Ekim 2026): etiket ve OCR şimdilik görsel destekleyen bulut/LAN sağlayıcılarıyla (OpenAI,
  Anthropic, Gemini, Ollama'da görsel model); 18.7'deki yerel model aynı arayüze takılır. F-Droid yüzünden ML Kit yok.
  Android AVIF kodlayamadığı için dönüşüm yalnızca WebP. Harita kendi küçük karo görüntüleyicimizle (kütüphane yok).

### Grup 3 — Kodsuz sayfalar ve paketler

**18.6 Sayfa ve pano yapıcı**
- [x] Koleksiyon/görünümden herkese açık veya giriş gerektiren liste ve detay sayfaları (formların okuma tarafı): alan
  seçimi, sıralama, arama, sayfalama, tema; kurallar aynen uygulanır.
- [x] Pano: sürükle-bırak kutular (sayı, grafik, tablo, harita), görünümlerden beslenir; uygulamada ve panelde,
  isteğe bağlı paylaşılabilir salt okunur adres.
  *Durum:* `pages` (`PagePages.kt`): koleksiyondan (görünüm yerine sabit süzgeç + sıralama) sunucuda üretilen liste ve
  detay, kart/tablo düzeni, tema rengi, arama ve listedeki alanlara göre sıralama; liste/görüntüleme kuralı ve alan
  gizliliği; giriş gerektiğinde `/pages/_login` (2FA dahil) ve çerezli oturum (yalnızca `/pages`, HttpOnly, Lax;
  token sessizce yenilenir), açık yönlendirme yok; dosyalar sayfa yolundan aynı kuralla (dosya gönderme
  `sendRecordFile` olarak ortaklaştı). `dashboards` (`DashboardService`): kutular görünümleri yönetici olarak çalıştırır
  (sayı ve grafik raporlu görünümden), kutu hatası panoyu düşürmez. Panelde Sayfalar ve panolar sekmesi (HTML5
  sürükle-bırak, SVG grafik, `geoMap`), uygulamada Sunucu → Sayfalar / Panolar (Compose; sıralama yukarı/aşağı).
  Paylaşım `/dashboards/<ad>?token=` betiksiz (SVG grafik, konumlandırılmış karo görselleri). Testler: `PagesTest`,
  ekran görüntüsü 32-pano, 33-sayfalar.
- Karar: sayfalar sunucu tarafında mı üretilsin (SEO, JS'siz) yoksa statik sayfa + API mi (öneri: sunucu tarafı).
  *Karar (kullanıcı, 23 Ekim 2026):* **sunucu tarafı** (JS'siz liste/detay; pano grafik ve harita için küçük bir betikle).

**18.7 Telefonda yerel YZ**
- [x] Sağlayıcı olarak "yerel": telefonun yerleşik modeli (destekleyen cihazlarda) veya indirilen küçük model; özetleme,
  sınıflandırma, otomasyonlarda YZ adımı.
- [x] Yerel gömme (embedding) modeliyle anlamsal arama — veri telefondan çıkmaz.
- [x] Model indirme ekranı (boyut, depolama, pil uyarısı), sağlık uyarılarına bellek/ısı denetimi.
  *Durum:* llama.cpp alt modülü (`app/src/main/cpp/llama.cpp`, b11843) ve küçük JNI sarmalayıcısı
  (`droidserver_llama.cpp`: yükleme, sohbet şablonu + ChatML'e geri düşüş, örnekleme, gömme; metinler UTF-8 bayt) CMake
  ile kaynaktan; yalnızca CPU, OpenMP ve KleidiAI kapalı (derlemede ağdan indirme yok), `GGML_NATIVE` kapalı (her arm64
  telefonda çalışır), `-ffile-prefix-map` ile tekrarlanabilir. Yalnızca arm64-v8a ve x86_64 derlenir (CMake
  abiFilters; diğer kitaplıklar tüm ABI'lerde kalır, 32 bit telefona kurulur). Sarmalayıcı masaüstünde de derlenip
  küçük modellerle denendi (üretim, emoji, gömme benzerliği). Core'da `AiProvider.LOCAL`, `EmbeddingProvider.LOCAL`,
  `LocalAi` arayüzü (uygulama sağlar, yoksa 503). Uygulamada `LocalModels`: SHA-256'sı sabit model kataloğu, Range ile
  sürdürülen indirme, yer/bellek/ısı (THERMAL_STATUS_SEVERE) denetimi, reddedilince `AlertKind.LOCAL_AI` sağlık uyarısı,
  5 dk boşta kalınca belleği boşaltma; Yapay zekâ → Yerel modeller ekranı (mobil veri ve pil uyarısı). Otomasyonlarda
  `ai` eylemi (her sağlayıcıyla; seçim alanında yalnız seçenekler) uygulamada ve panelde. "Telefonun yerleşik modeli"
  F-Droid kuralı yüzünden yok. Testler: `LocalAiTest`.
- Karar: hangi çalışma zamanı (cihazın yerleşik modeli mi, llama.cpp/ONNX ile indirilen model mi); APK boyutuna etkisi.
  *Karar (kullanıcı, 23 Ekim 2026):* **llama.cpp, kaynaktan** (NDK ile projede derlenir; F-Droid hazır ikili ve Play
  hizmetlerini kabul etmediği için Gemini Nano/AICore ve ONNX/MediaPipe AAR'ları elendi). Modeller (GGUF) uygulama içinden indirilir.

**18.8 Eklenti ve şablon paketleri**
- [x] Paket biçimi: koleksiyonlar, kurallar, görünümler, otomasyonlar, formlar, sayfalar, script'ler, örnek veri;
  manifest (ad, sürüm, gereken özellikler) ve imza.
- [x] İçe aktarmadan önce özet ve çakışma planı (şema planı gibi), sürüm güncellemesi, kaldırma.
- [x] Paket kaynağı: dosya, adres veya Git deposu; imzasız paket uyarısı, imzalı paketlerde yayımcı adı.
  *Durum:* zip (kökte veya tek üst klasörde): `package.json` (ad, sürüm, başlık, yayımcı, `minConfigVersion`),
  `config.json` (ServerConfig'in koleksiyon/görünüm/otomasyon/form/sayfa/pano alanları), `scripts/<ad>.js`,
  `sample/<koleksiyon>.json`, `signature.json`. İmza ECDSA P-256 / SHA-256 (Android 8'de Ed25519 yok) ile ada göre
  sıralı dosya özet listesi; değiştirilmiş paket kurulmaz. "Gereken özellikler" ayrı alan yerine planda: script
  getiren paket script'leri, kullanıcıya bağlı koleksiyon hesapları açar. `Packages.plan`: ekle/güncelle/kaldır,
  başka paketin veya elle eklenmiş tanımla çakışma, doğrulama, eski sürüm ve yayımcı anahtarı uyarısı. Kurulu paket
  `server.json`'da `packages` (getirdikleriyle). Kurulum `AdminHost.installPackage` (script'ler yazılır, konfigürasyon
  kaydedilir, deploy; örnek veri `packages/pending/` üzerinden deploy'dan sonra, `SampleSeed` şablonlarla ortak).
  Uçlar `/_packages` (liste, plan: zip / `{url}` / `{git}` → 10 dk'lık anahtar, kur, kaldır). Uygulamada Sunucu →
  Paketler, panelde Konfigürasyon → Paketler, CLI'de `package key|build|plan|install|list|remove` (Node'un yerleşik
  crypto ve zlib'iyle). Kaldırmada koleksiyon tabloları ve veriler kalır. Testler: `PackagesTest` (CLI ile imzalama dahil).

### Grup 4 — Ödemeler

**18.9 Ödemeler**
- [x] Sağlayıcılar: Stripe, iyzico ve Paddle (sağlayıcı kullanıcı seçer; birden çok açılabilir). Anahtarlar şifreli depoda.
- [x] Ödeme bağlantısı / ödeme sayfası oluşturma (tutar, para birimi, açıklama, kayda bağlama); script ve otomasyonlardan.
- [x] Sağlayıcı webhook'ları imza doğrulamalı; ödeme durumu (bekliyor, ödendi, iade, iptal) kayda yazılır, otomasyon
  tetiklenir; tekrarlanan olay bir kez işlenir.
- [x] Abonelikler (Stripe ve Paddle'ın abonelik ürünleri): kullanıcıya rol/kota bağlama (ödeyen kullanıcı rolü).
- [x] Sipariş ve rezervasyon şablonları; fatura/makbuz PDF'i (sağlayıcının faturası varsa o, yoksa basit makbuz).
- Not: kart verisi hiçbir zaman sunucudan geçmez (sağlayıcının barındırılan sayfası). Paddle "satıcı kaydı" (merchant of
  record) olduğu için vergi/fatura onda; iyzico Türkiye için, Stripe uluslararası.
- Karar: webhook'lar telefona internetten ulaşmalı — doğrudan erişim veya tünel şart; sihirbaz bunu denetlemeli.
- Durum: `payments` (konfigürasyon v21); `_payments`, `_subscriptions`, `_payment_events` tabloları. Bağlantı
  `POST /api/_payments`, `ctx.payments.create` ve otomasyonda `payment` eylemi (`{ilişki.alan}` ve hesaplı tutar);
  bildirimler `/payments/hook/<sağlayıcı>` (Stripe ve Paddle HMAC imzası ve 5 dk zaman sınırı; iyzico'da sonuç her
  zaman API'den sorulur, V3 imzası varsa doğrulanır). Durum sayfası `/payments/<token>` (bildirim gecikirse sorar),
  makbuz sağlayıcıdan ya da yerleşik PDF'ten (Türkçe harfler, gömülü yazı tipi yok). Paddle'ın ödeme sayfası
  sunucuda Paddle.js ile (`/payments/paddle`). Abonelikte rol ve plan kotası; Mağaza ve Randevu şablonları ödemeli
  (siparişte bağlantı onaydan sonra, tutar istemciden geldiği için). Konfigürasyon denetimi yerel erişimi ve hızlı
  tüneli reddeder; ekranda adres denetimi. Sağlayıcıların gerçek ortamında (sandbox) deneme cihaz testi adımlarında;
  iyzico iadeleri kendiliğinden gelmez.
- [x] Tamamlama: tam iade (yönetici API'si, uygulama ve panel; Stripe `refunds`, iyzico `v2/payment/refund`, Paddle
  `adjustments` onay bekleyebilir), iyzico panelinden yapılan iadeler altı saatte bir raporlama servisinden, kullanıcının
  aboneliğini dönem sonunda bitirmesi (Stripe `cancel_at_period_end`, Paddle `next_billing_period`; `canceling`),
  Paddle'ın süreli yönetim adresleri saklanmaz. Ödeme testleri SQLite ve PostgreSQL'de. Stripe müşteri portalı yerine
  API'yle iptal: portal her ortamda panelden ayrıca kurulmalı, iptal ise iki sağlayıcıda ayarsız çalışır.

### Kabul (her grup)
- `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug` temiz; yeni uçlar OpenAPI ve SDK'da (golden güncel); yeni
  ekranlar için ekran görüntüsü testi; SQLite ve PostgreSQL'de aynı davranış (konum ve eşitleme için parametreli test).
- Ödemelerde sağlayıcıların test/sandbox ortamına karşı sahte webhook testleri (gerçek ödeme yok).
- README (TR/EN) bölümü, konfigürasyon tablosu satırı, yol haritası maddesi; cihaz testi adımları ve güncel paket.

## Açık sorular (karar verilmesi gerekenler)

1. Script dili: JavaScript (Rhino/QuickJS) mi, Lua (LuaJ) mi? Kullanıcı kitlesine göre JS daha tanıdık.
   *(Faz 7'de JavaScript / Rhino ile başlandı; değiştirmek isterseniz `ScriptRuntime` yüzü korunarak yapılabilir.)*
2. Bileşen deposu nerede barındırılacak? (GitHub Releases önerilir: ücretsiz, sürümlü, sha256 ile birlikte.)
3. Çoklu sunucu profili: bir cihazda birden fazla bağımsız konfigürasyon (farklı port ve veritabanı) gerekli mi?
4. Yönetici erişimi için API anahtarı ve JWT ikisi birden mi kalsın, yoksa Faz 5'ten sonra yönetici JWT'si mi standart olsun?
   *(Faz 5'te ikisi de kaldı; ayrıca rolü `admin` olan kullanıcı da yönetici sayılıyor.)*
