# Nasıl kullanılır?

Bu rehber DroidServer'ı ilk kez kuranlar içindir: kurulumdan sunucunuzu internete açmaya kadar temel adımları anlatır.
Ayrıntılı teknik belgeler GitHub'daki [proje sayfasında](https://github.com/kasimtmc/droidserver) bulunur; uygulamadaki
**Yardım** simgesi de aynı belgeleri çevrimdışı açar.

## 1. Kurulum

DroidServer Android 8.0 ve üstünde çalışır; root gerekmez.

- **Google Play:** Play Store'da DroidServer'ı arayıp kurun. İlk açılıştan itibaren bir hafta ücretsiz denersiniz,
  sonra tek seferlik satın almayla devam edersiniz.
- **GitHub:** [Releases](https://github.com/kasimtmc/droidserver/releases) sayfasından `droidserver-<sürüm>.apk`
  dosyasını indirip kurun. Uygulama yeni sürümleri kendisi bulur (Ayarlar → Güncelleme).

Sunucunun ekran kapalıyken de çalışabilmesi için telefonu mümkünse şarjda ve Wi-Fi'da tutun.

## 2. İlk açılış

Kısa tanıtımdan sonra **Nasıl başlamak istersiniz?** sayfası gelir:

- **Hazır bir örnek:** Blog, Mağaza ve sipariş, Randevu veya Görev takibi. Koleksiyonlar, erişim kuralları, örnek
  kayıtlar ve basit bir site kendiliğinden kurulur. Denemek için en hızlı yol budur.
- **Boş başla (sihirbaz):** her şeyi adım adım siz seçersiniz.

![Karşılama ekranı](../img/01-karsilama.png)

## 3. Sihirbaz

Sihirbaz sunucunuzun ne yapacağını yedi adımda sorar. Her adımda yalnızca ihtiyacınız olanı doldurun; daha sonra
**Sunucu → Sihirbazla düzenle** ile her şeyi değiştirebilirsiniz.

1. **Genel:** sunucunun adı, portu (varsayılan 8080), API yolu ve isterseniz statik site.
2. **Veritabanı:** SQLite (varsayılan, ek kurulum yok) ya da PostgreSQL; zamanlanmış yedek ve bulut yedeği.
3. **Kullanıcılar:** uygulamanızın kullanıcıları için e-posta ve parolayla hesaplar (isteğe bağlı).
4. **Koleksiyonlar:** verilerinizin tabloları ve alanları; her koleksiyon için REST ve GraphQL uçları kendiliğinden
   oluşur. Kimin okuyup yazabileceğini burada seçersiniz.
5. **Güvenlik:** API anahtarı veya JWT, web yönetim paneli, hız sınırları ve güvenlik duvarı.
6. **Erişim:** sunucuya nereden ulaşılacağı (aşağıda "İnternetten erişim").
7. **Özet ve deploy:** değişikliklerin özeti ve şema planı. **Kaydet ve deploy et** ile sunucu yeni ayarlarla açılır.

![Sihirbaz, Genel adımı](../img/tr/sihirbaz-genel.jpg)

## 4. Sunucuyu başlatma

**Durum** ekranında **Başlat**'a dokunun. Sunucu çalışınca adresi görünür (örneğin `http://192.168.1.20:8080`).
Aynı Wi-Fi'daki bir bilgisayardan veya telefondan bu adrese gidebilirsiniz.

Durum ekranında son bir saatin istekleri ve hataları, pil, çalışan süreçler ve uyarılar özetlenir. Bir şey yolunda
gitmiyorsa **Sorun giderici** bağlantıyı, erişimi ve izinleri tek ekranda denetler, her bulgu için ne yapılacağını
söyler.

![Durum ekranı](../img/tr/durum.jpg)

## 5. Uygulamanızı bağlama

Uygulamanız veya betiğiniz sunucuya API anahtarıyla bağlanır:

- **Durum → İstemci erişimi / token** anahtarı panoya kopyalar.
- **Durum → Bağlan (QR)** adresi ve erişim bilgisini QR kod olarak gösterir.

Örneğin `todos` adında bir koleksiyonunuz varsa kayıtları şöyle okursunuz:

```
curl -H "Authorization: Bearer <anahtar>" http://192.168.1.20:8080/api/collections/todos/records
```

Kayıt eklemek için aynı adrese `POST` ile JSON gönderin. Sunucu `/api/_openapi.json` adresinde bütün uçların
açıklamasını verir; **Sunucu → API gezgini** ile istekleri uygulamanın içinden de deneyebilirsiniz.

![Durum ekranında Bağlan (QR) ve İstemci erişimi](../img/tr/durum-araclar.jpg)

## 6. Site yayınlama

Sitenizin dosyalarını (`index.html`, CSS, JavaScript, görseller) bir zip dosyasına sıkıştırın, **Sunucu → Statik site →
Zip yükle** ile seçin. Yükleme bitince site yayındadır; sunucuyu yeniden başlatmak gerekmez. Kodsuz liste ve detay
sayfaları için **Sunucu → Sayfalar**, sayı ve grafikli panolar için **Sunucu → Panolar** kullanılabilir.

![Sunucu ekranında statik site](../img/tr/sunucu.jpg)

## 7. Web paneli

Bilgisayarın tarayıcısından kayıtları, kullanıcıları, logları ve ayarları yönetmek için:

1. **Sihirbaz → Güvenlik → Web yönetim paneli**'ni açıp kaydedin.
2. Tarayıcıda `http://<telefonun-adresi>:8080/_admin` adresini açın.
3. Telefonda **Durum → Web paneli → Eşleştirme kodu oluştur**'a dokunup gösterilen kodu panele girin.

Panel internetten de açılabilir durumdaysa (tünel veya alan adı) aynı sihirbaz adımındaki doğrulayıcı kodunu da açın.

## 8. İnternetten erişim

Sunucu başlangıçta yalnızca ev ağınızdan erişilir. **Sihirbaz → Erişim**'de:

- **Cloudflare Tunnel:** en kolayı; mobil veride de çalışır, modemde port açmak gerekmez. Hesap olmadan hızlı tünel
  ya da kendi alan adınızla kalıcı adres.
- **Tailscale:** sunucu internete açılmaz; yalnızca Tailscale'e giriş yaptığınız kendi cihazlarınız ulaşır.
- **İnternetten doğrudan (IP adresi):** telefonun internet adresiyle HTTPS; Wi-Fi'da modemde port açılır.

![Sihirbaz, Erişim adımı](../img/tr/sihirbaz-erisim.jpg)

Sunucuyu internete açmadan önce koleksiyonların erişim kurallarını ve API anahtarınızı gözden geçirin.

## 9. Bileşenler

**Bileşenler** sekmesinden PostgreSQL, Redis, Cloudflare tüneli gibi yan servisleri ve kendi Node.js, Python veya Java
uygulamalarınız için çalışma ortamlarını kurabilirsiniz. **Kur ve çalıştır** bileşeni indirir (Play sürümünde Google
Play'den) ve sunucuyla birlikte yönetir.

![Bileşenler](../img/tr/bilesenler.jpg)

## 10. Yedekler

**Sunucu → Yedekler → Şimdi yedekle** veritabanını, yüklenen dosyaları, script'leri ve siteyi tek bir zip'e yazar.
Sihirbazın Veritabanı adımında zamanlanmış yedek ve şifreli bulut yedeği açılabilir; **Otomatik kopya klasörü** her
yedeğin bir kopyasını seçtiğiniz klasöre (örneğin Google Drive) de yazar.

Güvenlik için sırlar (parolalar, API anahtarları, tünel anahtarı) yedeğe girmez. Yedeği başka telefona taşıyacaksanız
şunları ayrıca saklayın:

- Şifreli alanlarınız varsa **Sihirbaz → Veritabanı → Alan şifreleme → Anahtarı kopyala**; yeni telefonda **Var olan
  anahtarı gir**.
- **Sunucu → Ortam ve sırlar (.env) → Dışa aktar**; yeni telefonda **İçe aktar**.
- Tünel, Telegram ve yapay zekâ anahtarlarını yeni telefonda yeniden girin.

## 11. Arka planda çalışma

Android pil tasarrufu için arka plandaki uygulamaları durdurabilir. Sunucunun kesintisiz çalışması için:

- **Ayarlar → Pil optimizasyonu**'ndan DroidServer'ı muaf tutun.
- Xiaomi, Huawei, Oppo gibi telefonlarda uygulama ayarlarından **Otomatik başlatma**'yı açın ve pil ayarını "Kısıtlama
  yok" yapın.
- Telefon yeniden başlayınca sunucunun açılması için **Ayarlar → Cihaz açılınca başlat**'ı açın.

**Sorun giderici** pil optimizasyonu gibi eksik Android ayarlarını gösterir; üreticinin otomatik başlatma ayarını
kendiniz denetleyin.

![Ayarlar: arka planda çalışma](../img/tr/ayarlar.jpg)

## 12. Uyarılar ve uzaktan yönetim

**Sihirbaz → Genel → Sağlık uyarıları**: disk dolmak üzereyken, hatalar artınca, pil ısınınca veya yedek alınamayınca
telefon bildirimi, e-posta ya da Telegram mesajı alırsınız. Telegram'da yönetici sohbetini ayarlarsanız bottan
`/durum`, `/yedek`, `/loglar`, `/baslat` ve `/durdur` komutlarıyla sunucuyu uzaktan yönetebilirsiniz.

## Yardım ve destek

Sık sorulan sorular için [SSS](sss.html) sayfasına bakın. Bir sorun bildirmek için GitHub'da
[bir konu açın](https://github.com/kasimtmc/droidserver/issues); mümkünse **Loglar** ekranının görüntüsünü ekleyin.
