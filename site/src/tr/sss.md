# Sık sorulan sorular

## Genel

### Root gerekir mi?

Hayır. DroidServer sıradan bir Android uygulamasıdır; Android 8.0 ve üstünde çalışır.

### Verilerim nerede?

Telefonunuzda. Kayıtlar, dosyalar, kullanıcı hesapları, loglar ve yedekler telefonda kalır; geliştirici hiçbir veri
toplamaz. Veriler yalnızca sizin açtığınız özelliklerle (örneğin bulut yedeği veya bir yapay zekâ sağlayıcısı) sizin
seçtiğiniz yerlere gider. Ayrıntılar [gizlilik politikasında](gizlilik.html).

### İnternet olmadan çalışır mı?

Evet. Sunucu aynı Wi-Fi'daki cihazlara internet bağlantısı olmadan da hizmet verir. İnternetten erişim için Cloudflare
tüneli, Tailscale veya alan adıyla doğrudan erişim seçilebilir.

### Telefon kapanırsa ne olur?

Sunucu ve siteleriniz telefon açık olduğu sürece erişilebilir. Telefon kapanırsa ya da internet bağlantısını
kaybederse erişilemez; **Ayarlar → Cihaz açılınca başlat** açıksa telefon yeniden açılınca sunucu kendiliğinden başlar.

### Sunucu arka planda kapanıyor, ne yapmalıyım?

Android ve bazı üreticiler pil tasarrufu için arka plandaki uygulamaları durdurur. DroidServer'ı pil optimizasyonundan
muaf tutun; Xiaomi, Huawei, Oppo gibi telefonlarda uygulama ayarlarından **Otomatik başlatma**'yı açın ve pil ayarını
"Kısıtlama yok" yapın. **Durum → Sorun giderici** eksik ayarları gösterir.

### Pil ve ısınma için ne önerirsiniz?

Telefonu şarjda tutun; telefonunuz destekliyorsa pil sağlığı için şarjı %80'de sınırlayın. Sağlık uyarıları pil
ısındığında haber verir.

## Satın alma ve deneme (Play sürümü)

### Deneme nasıl işliyor?

Google Play sürümü ilk açılıştan itibaren 7 gün ücretsiz denenir. Kalan gün **Ayarlar → Google Play → Tam sürüm**'de
görünür; deneme bitmeden de oradan satın alabilirsiniz.

### Deneme bitince ne olur?

Sunucunuz, siteleriniz ve otomasyonlarınız çalışmaya devam eder, verileriniz silinmez. Uygulama açıldığında satın alma
penceresi çıkar; tam sürümü satın alınca uygulama kaldığı yerden açılır.

### Abonelik mi?

Hayır, tek seferlik bir satın almadır. Fiyat satın almadan önce Google Play'de gösterilir.

### Yeni telefona geçtim, yeniden ödemem gerekir mi?

Hayır. Satın alma Google hesabınıza bağlıdır: aynı hesapla kurduğunuz telefonda satın alma penceresindeki **Satın
alımı geri yükle**'ye dokunun.

### İade alabilir miyim?

İadeler Google Play'in iade politikasına göre yapılır; isteğinizi Google Play üzerinden iletebilirsiniz. İade edilen
satın alımda tam sürüm kapanır.

### Uygulamayı kaldırıp kurarsam deneme yenilenir mi?

Hayır. Denemenin başladığı an Google hesabınızla bağlantılı olarak saklanır.

## Sürümler

### Google Play ve GitHub sürümleri arasındaki fark nedir?

İkisi de aynı sunucuyu çalıştırır. Play sürümü güncellemeleri ve bileşenleri Google Play'den alır, bir haftalık
denemeden sonra tek seferlik satın alma ister. GitHub sürümü APK olarak kurulur, güncellemelerini ve bileşenlerini
GitHub'dan indirir, SIM'den doğrudan SMS gönderebilir; satın alma ve deneme yoktur. Aynı telefonda ikisi birlikte
kurulamaz.

### Play sürümünde kullanıcılarıma kodları ücretsiz nasıl gönderirim?

Google'ın kuralları gereği Play sürümü SIM'den kendiliğinden SMS gönderemez. Ücretsiz ve otomatik yol Telegram:
Sihirbaz → Kullanıcılar → **Telegram**'da kendi botunuzu ekleyin; kullanıcılar hesaplarını bir kez bağlar, giriş kodları
ve bildirimler bu botla gider. E-postayla kod, doğrulayıcı uygulama (iki adımlı doğrulama) ve SMS sağlayıcıları (Netgsm,
İleti Merkezi, Twilio) da kullanılabilir.

### Kaynak kodu açık mı?

Evet. DroidServer GPL-3.0-or-later lisansıyla yayınlanır; kaynak kodu
[GitHub'da](https://github.com/kasimtmc/droidserver).

## Yedek ve taşıma

### Sunucumu başka telefona nasıl taşırım?

Eski telefonda **Sunucu → Yedekler → Şimdi yedekle** ile bir yedek alıp dışa aktarın, yeni telefonda **Dosyadan geri
yükle** ile yükleyin. Sırlar güvenlik nedeniyle yedeğe girmez: şifreli alanlarınız varsa alan şifreleme anahtarını,
**Ortam ve sırlar (.env)** dosyasını ayrıca taşıyın; tünel, Telegram ve yapay zekâ anahtarlarını yeniden girin.
Ayrıntılar [Nasıl kullanılır?](nasil-kullanilir.html) rehberinde.

## Destek

### Bir sorun buldum, nasıl bildiririm?

GitHub'da [bir konu açın](https://github.com/kasimtmc/droidserver/issues). Telefon modelinizi, uygulama sürümünü
(Ayarlar'da) ve mümkünse **Loglar** ekranının görüntüsünü ekleyin.
