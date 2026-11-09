# Gizlilik politikası

**Türkçe** | [English](PRIVACY.en.md)

Yayıncı: plyGround · Son güncelleme: 9 Kasım 2026

DroidServer telefonunuzda çalışan bir sunucudur. Geliştirici hiçbir veri toplamaz: uygulamada analiz, reklam, hata
raporu gönderme veya kullanım takibi yoktur. Sunucunuzun verileri (kayıtlar, dosyalar, kullanıcı hesapları, loglar,
yedekler) telefonunuzda kalır; yalnızca sizin açtığınız özellikler sizin seçtiğiniz yerlere veri gönderir.

## Uygulamanın kendiliğinden kurduğu bağlantılar

- **Güncelleme denetimi (GitHub sürümü):** günde bir GitHub'daki sürüm listesine bakılır (`api.github.com`). Durum →
  Ayarlar → Güncelleme'den kapatılabilir; kapalıyken yalnızca elle denetlenir. Play sürümünde güncellemeyi Google Play
  yapar, bu denetim yoktur.
- **Bileşen kataloğu:** GitHub sürümünde Bileşenler ekranını açtığınızda yan servislerin listesi ve seçtiğiniz paket
  GitHub'dan indirilir. Play sürümünde liste uygulamayla gelir, seçtiğiniz bileşen Google Play'den indirilir.
- **Tam sürüm (Play sürümü):** uygulama Google Play'e DroidServer'ın satın alınıp alınmadığını ve fiyatını sorar.
  Ödemeyi ve satın alma kaydını tamamen Google Play yönetir; ödeme bilgileriniz uygulamaya ya da geliştiriciye
  gelmez. Bir haftalık denemenin başladığı an Google'ın Block Store'unda (Google hesabınıza bağlı, cihaz destekliyorsa
  uçtan uca şifreli yedek) saklanır; uygulamayı kaldırıp kursanız da deneme yenilenmez. Bu kayıtta yalnızca bir zaman
  damgası vardır. GitHub sürümünde satın alma ve deneme yoktur.
- **Harita:** konumlu kayıtları haritada gösterdiğinizde (uygulamada Harita ve panolar, web panelinde) harita karoları
  OpenStreetMap'ten (`tile.openstreetmap.org`) yüklenir. Paylaştığınız bir panoda harita varsa panoyu açanın tarayıcısı
  da karoları oradan yükler.

Bu isteklerde telefonunuz ve sunucunuz hakkında bilgi gönderilmez (GitHub ve OpenStreetMap, her web isteğinde olduğu
gibi IP adresinizi görür; karo istekleri haritada bakılan bölgeyi belli eder).

## Sizin açtığınız özellikler

Aşağıdakiler yalnızca siz ayarlayınca çalışır ve veriyi yalnızca ayarladığınız hizmete gönderir: internetten erişim
(Cloudflare, Tailscale, DuckDNS, Let's Encrypt), e-posta (SMTP sunucunuz), sağlık uyarıları, komutlar ve
kullanıcılarınıza mesajlar (Telegram), bulut yedeği (seçtiğiniz S3/WebDAV deposu, uçtan uca şifreli), web push
(tarayıcıların push servisleri), sosyal giriş (Google, GitHub), yapay zekâ (Claude, OpenAI, Gemini; görsel etiketleme ve
OCR açıksa görseller de), webhook'lar ve canlı kopya (sizin sunucularınız), SMS (aşağıya bakın), paketler (verdiğiniz
adres veya GitHub deposu).

- **SMS:** üç yol vardır ve hangisinin açık olduğunu siz seçersiniz. SMS sağlayıcısı (Netgsm, İleti Merkezi, Twilio):
  alıcının numarası ve SMS metni sağlayıcıya gönderilir. SIM'den doğrudan (yalnızca GitHub sürümü): SMS kendi
  hattınızdan gider. SIM'den onayınızla: her SMS için bir bildirim çıkar, "SMS'i gönder"e dokunursanız telefonunuzun SMS
  uygulaması numara ve metinle açılır ve SMS'i siz gönderirsiniz; bu kipte numara ve metin yalnızca bildirimde ve SMS
  uygulamanızda görünür, başka yere gönderilmez. Denetim kaydında numaranın son dört hanesi gizlenir, metin yazılmaz.
- **Telegram (kullanıcılarınız):** kullanıcıların Telegram hesabını bağlamasını açarsanız, bağlayan kullanıcının Telegram
  sohbet kimliği ve kullanıcı adı sunucunuzda saklanır. Giriş kodları ve script'lerinizin ya da otomasyonlarınızın
  mesajları sizin botunuzla Telegram üzerinden gider; Telegram bu mesajları görür. Kullanıcı bağı istediği an
  kaldırabilir (uygulamadan veya botta `/ayril`).
- **Telefonda yerel YZ:** seçtiğiniz model Hugging Face'ten (`huggingface.co`) indirilir; model telefonda çalışır,
  istemler ve kayıtlarınız dışarı gönderilmez.
- **Ödemeler (Stripe, iyzico, Paddle):** alıcı kart bilgisini sağlayıcının kendi sayfasına girer; kart verisi
  telefondan geçmez. Sunucu sağlayıcıya tutarı, açıklamayı ve (verdiyseniz) alıcının e-postasını gönderir; iyzico
  ayrıca alıcının adını, kimlik numarasını, telefonunu, adresini ve IP adresini ister. Paddle'ın ödeme penceresi
  alıcının tarayıcısında Paddle'ın betiğiyle (`cdn.paddle.com`) açılır. Sağlayıcıdan gelen ödeme bildirimleri
  telefonunuzdaki ödeme kayıtlarına yazılır.
- **Takvim ve kişiler (CalDAV/CardDAV):** kayıtlar yalnızca sizin bağladığınız takvim ve rehber uygulamalarıyla
  eşitlenir; üçüncü bir hizmete gönderilmez.

Sırlar (API anahtarları, parolalar, token'lar) telefonun şifreli deposunda tutulur ve konfigürasyon dosyasına ya da
yedeklere yazılmaz.

## İzinler

| İzin | Neden |
|---|---|
| İnternet, ağ ve Wi-Fi durumu | Sunucunun istek alması, adresinin gösterilmesi, ağ değişince yeniden ayarlanması |
| Wi-Fi çoklu yayın | Yerel ağda sunucunun bulunabilmesi (mDNS, isteğe bağlı) |
| Ön plan servisi, uyandırma kilidi, pil iyileştirmesinden muafiyet | Ekran kapalıyken sunucunun çalışmaya devam etmesi |
| Açılışta başlatma | Telefon yeniden başlayınca sunucunun açılması (isteğe bağlı) |
| Bildirim | Sunucu durumu, sağlık uyarıları ve güncellemeler |
| Biyometrik | Uygulama kilidi (isteğe bağlı) |
| SMS gönderme (yalnızca GitHub sürümü) | SMS ile giriş kodu ve script/otomasyonlardan SMS, SIM'den doğrudan (yalnızca açarsanız; izin o zaman istenir). Play sürümünde bu izin yoktur |
| Konum | Script'lerin ve API'nin telefonun son bilinen konumunu okuması (yalnızca açarsanız; izin o zaman istenir). Arka planda konum izni istenmez; Play sürümünde konum yalnızca uygulama ekrandayken okunur |
| Paket kurma (yalnızca GitHub sürümü) | Uygulama içinden güncelleme (kurulumu her seferinde siz onaylarsınız) |
| Google Play satın alma (yalnızca Play sürümü) | Tam sürümün tek seferlik satın alması |

Play sürümünde pil iyileştirmesinden muafiyet izni de yoktur; muafiyeti Android'in pil ayarlarından siz verirsiniz.

Soru ve bildirimler için: GitHub'daki proje sayfasında bir konu açın.
