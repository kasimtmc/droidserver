# Play'de yayın: adım adım

Play sürümünü (`play` türü) Google Play'e çıkarmak için sizin yapacaklarınız (PLAN Faz 19, 19.7). Beyan metinleri ve veri
güvenliği yanıtları: [play-console.md](play-console.md). Console menü adları değişebilir; sıra aynı kalır.

## 1. Geliştirici hesabı

1. [Play Console](https://play.google.com/console)'da geliştirici hesabı açın (bir kerelik ücret, kimlik doğrulaması;
   kişisel hesap ya da kuruluş). Doğrulama birkaç gün sürebilir.
2. Kasım 2023'ten sonra açılan **kişisel** hesaplarda üretime çıkmadan önce kapalı testte en az **12 test kullanıcısı 14
   gün** boyunca kayıtlı kalmalı (adım 7). Kuruluş hesabında bu şart yok.

## 2. Uygulamayı oluşturma ve imza

1. Uygulama oluştur: ad **DroidServer**, varsayılan dil **Türkçe (tr-TR)**, uygulama, ücretsiz.
2. Test ve yayın → Uygulama bütünlüğü → **Play Uygulama İmzalama**: GitHub sürümüyle aynı anahtarı kullanmak için
   "Mevcut uygulama imzalama anahtarını kullan" → "Java anahtar deposundan dışa aktar ve yükle". Console'un verdiği
   `pepk.jar` ve şifreleme anahtarıyla:

   ```bash
   java -jar pepk.jar --keystore=release.jks --alias=droidserver --output=play-anahtar.zip \
     --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```

   `play-anahtar.zip`'i yükleyin. Yükleme anahtarı da aynı anahtar olabilir (iş akışı AAB'yi onunla imzalar); isterseniz
   ayrı bir yükleme anahtarı da kaydedebilirsiniz.

## 3. İlk paket (elle)

Play, yeni bir uygulamanın ilk paketini API'den kabul etmez; ilk AAB'yi Console'dan yükleyin.

1. Sürüm çıkarın (`gh workflow run release.yml -f version=0.9.3`); bitince Actions → çalıştırma → Artifacts →
   `droidserver-0.9.3-play` (içinde `app-play-release.aab`).
2. Test → **Dahili test** → Yeni sürüm → AAB'yi yükleyin, test kullanıcısı olarak kendinizi ekleyin, yayınlayın.
3. Dahili test bağlantısıyla telefona Play'den kurun ve cihaz testi `faz19b`'yi yapın (bileşenlerin Play'den inmesi,
   onay penceresi, güncelleme sonrası).

## 4. Mağaza kaydı

Ana mağaza kaydı → metinler ve görseller depoda, `fastlane/metadata/android/<dil>/`:

| Alan | Dosya |
|---|---|
| Uygulama adı | `title.txt` |
| Kısa açıklama (80) | `short_description.txt` |
| Tam açıklama (4000) | `full_description.txt` |
| Uygulama simgesi 512×512 | `images/icon.png` |
| Öne çıkan görsel 1024×500 | `images/featureGraphic.png` |
| Telefon ekran görüntüleri (en az 2) | `tr-TR/images/phoneScreenshots/` |

İngilizce (en-US) çeviri olarak eklenir; ekran görüntüsü vermezseniz varsayılan dilinkiler kullanılır. Görselleri
değiştirmek için kaynaklar `fastlane/graphics/*.svg`: `rsvg-convert -w 1024 -h 500 feature-tr-TR.svg -o …`.
Ekran görüntüleri `./gradlew :app:testPlayDebugUnitTest -Pscreenshots --tests ScreenshotTest` ile
`build/screenshots/play`'e üretilir; Play kenar oranı en çok 2:1 olsun diye iki yandan boşlukla 1290×2580'e getirildi.

Kategori: **Araçlar**. İletişim e-postası ve web sitesi (GitHub sayfası) zorunlu.

## 5. Uygulama içeriği

| Bölüm | Yanıt |
|---|---|
| Gizlilik politikası | `https://github.com/kasimtmc/droidserver/blob/master/PRIVACY.en.md` |
| Reklamlar | Reklam yok |
| Uygulama erişimi | Kısıtlama yok, aşağıdaki talimatla |
| İçerik derecelendirmesi | Anket: araç/üretkenlik; kullanıcılar arası iletişim yok, kullanıcının kendi sunucusu |
| Hedef kitle | 18 yaş ve üstü (geliştirici aracı) |
| Veri güvenliği | `play-console.md` → Veri güvenliği formu |
| Ön plan servisi izinleri | `play-console.md` → `specialUse` açıklaması ve video |
| Finansal özellikler, sağlık, haber, devlet | Yok (ödemeler kullanıcının kendi satışları için sağlayıcı bağlantısı; uygulama finansal hizmet sunmaz) |

**Uygulama erişimi talimatı (İngilizce, inceleyici için):**

> No account or login is needed. Open the app, tap "Skip" on the introduction, then tap "Start" on the Status screen:
> the local server starts and its address (http://<phone-ip>:8080) is shown; opening http://<phone-ip>:8080/api/health
> from another device on the same Wi-Fi returns {"status":"ok"}. Stop it from the notification. Optional components
> (Components tab) are downloaded from Play as on-demand modules. Sending SMS is off by default; if enabled, each SMS
> needs the user's confirmation in a notification and opens the user's SMS app (the app has no SMS permission).

## 6. Otomatik yükleme (servis hesabı)

İlk paketten sonra iş akışı her sürümü Play'e yükler: kararlı sürüm **kapalı test (alpha)**, ön sürüm (`-beta.1`)
**dahili test**. Üretime geçiş her zaman Console'dan elle.

1. Google Cloud'da bir proje → **Google Play Android Developer API**'yi etkinleştirin → Hizmet hesabı oluşturun →
   Anahtarlar → JSON anahtarı indirin.
2. Play Console → Kullanıcılar ve izinler → hizmet hesabının e-postasını davet edin; DroidServer için "Sürümleri test
   kanallarına yayınla" ve "Uygulama bilgilerini görüntüle" izinleri.
3. JSON'u GitHub secret'ı yapın; dosyayı sonra silin:

   ```bash
   gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-servis-hesabi.json
   ```

Secret yoksa iş akışı Play adımını atlar (AAB yine Artifacts'ta).

## 7. Kapalı test ve üretim

1. Test → **Kapalı test** → Alpha: test kullanıcıları (e-posta listesi ya da Google Grubu); kişisel hesapta en az 12 kişi,
   14 gün kayıtlı kalmalı. Katılma bağlantısını paylaşın.
2. 14 gün dolunca Console'dan üretim erişimi başvurusu (birkaç soru: test nasıl yapıldı, geri bildirimler).
3. Onaydan sonra kapalı testteki sürümü üretime yükseltin (Sürümü tanıt → Üretim), aşamalı kullanıma sunma oranıyla.
