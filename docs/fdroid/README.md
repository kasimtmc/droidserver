# F-Droid gönderimi

F-Droid uygulamayı kaynaktan kendisi derler. Derleme tekrarlanabilir olduğu için F-Droid, kendi derlediği APK'yı
GitHub'daki imzalı APK ile karşılaştırıp **bizim imzamızla** yayınlayabilir; böylece F-Droid'den ve GitHub'dan kurulan
sürümler birbirinin üzerine güncellenir.

## Depoda hazır olanlar

- `fastlane/metadata/android/{tr-TR,en-US}/`: başlık, kısa ve uzun açıklama, sürüm notu (`changelogs/<versionCode>.txt`),
  ekran görüntüleri (yalnızca Türkçe arayüzden; `tr-TR/images/phoneScreenshots/`).
- Tekrarlanabilir yayın derlemesi (`app/build.gradle.kts`): `vcsInfo.include = false`, `dependenciesInfo` kapalı. Aynı
  makinede iki temiz derleme (`./gradlew :app:clean :app:assembleRelease --no-build-cache -PappVersion=…`) aynı SHA-256'yı
  verir. Farklı makinede doğrulama F-Droid'in derleme sunucusunda olur.
- Gizlilik politikası ve izin gerekçeleri: `PRIVACY.md`, `PRIVACY.en.md`.
- Lisans: GPL-3.0-or-later (`LICENSE`).
- `dev.droidserver.app.yml`: fdroiddata deposuna eklenecek meta verinin taslağı.

## Gönderim adımları

1. Aşağıdaki **açık konuları** kapatın.
2. `v0.9.0` etiketini ve imzalı APK'yı yayınlayın (release.yml).
3. İmza sertifikasının SHA-256'sını alın: `apksigner verify --print-certs droidserver-0.9.0.apk` → `AllowedAPKSigningKeys`.
4. <https://gitlab.com/fdroid/fdroiddata> deposunu çatallayın, `metadata/dev.droidserver.app.yml` olarak taslağı ekleyin,
   `fdroid lint dev.droidserver.app` ve `fdroid build -v -l dev.droidserver.app` ile deneyin, birleştirme isteği açın.

## Açık konular (göndermeden önce karar gerekir)

1. **Çalışırken indirilen ikili dosyalar.** Bileşen kataloğu (PostgreSQL, Node.js, Python, OpenJDK, Redis, cloudflared)
   ve kendi uygulamalarınızın çalışma ortamları GitHub'dan hazır ikili olarak indirilir. F-Droid'in dahil etme politikası,
   F-Droid'in kaynaktan derlemediği çalıştırılabilir kodun indirilmesine genellikle izin vermez. Seçenekler: F-Droid
   yapısında bileşen kataloğunu kapatmak (yalnızca SQLite ve yerleşik özellikler) veya incelemede `NonFreeAdd` /
   `NonFreeNet` gibi bir anti-özellikle kabul edilmeyi istemek.
2. **Uygulama içi güncelleme.** Güncelleyici GitHub'dan APK indirip kurar (`REQUEST_INSTALL_PACKAGES`). F-Droid yapıları
   güncellemeyi F-Droid'e bırakır; F-Droid yapısında güncelleyiciyi kapatan bir derleme seçeneği gerekebilir.
3. **targetSdk 28.** F-Droid'de zorunlu bir alt sınır yok ama eski hedef SDK uyarısı gösterilebilir; README'deki gerekçe
   (indirilen bileşenlerin uygulama klasöründen çalışması) 1. maddeyle birlikte değerlendirilmeli.
