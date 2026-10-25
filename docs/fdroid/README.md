# F-Droid gönderimi

> **Bırakıldı (25 Ekim 2026):** F-Droid yayını yapılmayacak; dağıtım GitHub ve Play Store (bkz. `PLAN.md`, Faz 19).
> Bu klasör yapılan hazırlığın kaydı olarak duruyor.

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
- Yerel YZ için llama.cpp (MIT) git alt modülü olarak kaynaktan derlenir (NDK + CMake, ağsız ve tekrarlanabilir);
  taslakta `submodules: true` ve `ndk` sürümü var.
- `com.plyg.droidserver.yml`: fdroiddata deposuna eklenecek meta verinin taslağı. Uygulama kimliği 0.9.1'den sonra
  `dev.droidserver.app`'ten `com.plyg.droidserver`'e geçti; F-Droid'e ilk gönderim yeni kimlikle çıkan ilk sürümle yapılır.

## Gönderim adımları

1. Aşağıdaki **açık konuları** kapatın.
2. Yeni kimlikle (`com.plyg.droidserver`) ilk sürümü ve imzalı APK'yı yayınlayın (release.yml).
3. İmza sertifikasının SHA-256'sını alın: `apksigner verify --print-certs droidserver-0.9.0.apk` → `AllowedAPKSigningKeys`.
4. <https://gitlab.com/fdroid/fdroiddata> deposunu çatallayın, `metadata/com.plyg.droidserver.yml` olarak taslağı ekleyin,
   `fdroid lint com.plyg.droidserver` ve `fdroid build -v -l com.plyg.droidserver` ile deneyin, birleştirme isteği açın.

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
4. **İndirilebilir YZ modelleri.** Yerel YZ ekranı modelleri Hugging Face'ten indirir (çalıştırılabilir kod değil, veri;
   SHA-256 sabit). Qwen2.5 ve all-MiniLM (Apache-2.0) ile BGE-M3 (MIT) özgür lisanslı; Gemma 3 ve EmbeddingGemma (Gemma
   kullanım şartları) ile Llama 3.2 (Llama 3.2 Community License) değil. F-Droid bunlar için bir anti-özellik
   (`NonFreeAssets` / `NonFreeNet`) isteyebilir; seçenek: F-Droid yapısında yalnızca özgür lisanslı modelleri göstermek.
5. **Özgür olmayan ağ hizmetleri.** Ödemeler (Stripe, iyzico, Paddle), bulut YZ sağlayıcıları, Cloudflare ve Telegram
   yalnızca kullanıcı açınca kullanılır; F-Droid `NonFreeNet` anti-özelliği ekleyebilir (uygulamayı engellemez).
