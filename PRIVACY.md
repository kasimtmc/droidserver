# Gizlilik politikası

**Türkçe** | [English](PRIVACY.en.md)

DroidServer telefonunuzda çalışan bir sunucudur. Geliştirici hiçbir veri toplamaz: uygulamada analiz, reklam, hata
raporu gönderme veya kullanım takibi yoktur. Sunucunuzun verileri (kayıtlar, dosyalar, kullanıcı hesapları, loglar,
yedekler) telefonunuzda kalır; yalnızca sizin açtığınız özellikler sizin seçtiğiniz yerlere veri gönderir.

## Uygulamanın kendiliğinden kurduğu bağlantılar

- **Güncelleme denetimi:** günde bir GitHub'daki sürüm listesine bakılır (`api.github.com`). Durum → Ayarlar →
  Güncelleme'den kapatılabilir; kapalıyken yalnızca elle denetlenir.
- **Bileşen kataloğu:** Bileşenler ekranını açtığınızda yan servislerin listesi ve seçtiğiniz paket GitHub'dan indirilir.

Bu isteklerde telefonunuz ve sunucunuz hakkında bilgi gönderilmez (GitHub, her web isteğinde olduğu gibi IP adresinizi
görür).

## Sizin açtığınız özellikler

Aşağıdakiler yalnızca siz ayarlayınca çalışır ve veriyi yalnızca ayarladığınız hizmete gönderir: internetten erişim
(Cloudflare, Tailscale, DuckDNS, Let's Encrypt), e-posta (SMTP sunucunuz), sağlık uyarıları ve komutlar (Telegram),
bulut yedeği (seçtiğiniz S3/WebDAV deposu, uçtan uca şifreli), web push (tarayıcıların push servisleri), sosyal giriş
(Google, GitHub), yapay zekâ (Claude, OpenAI, Gemini), webhook'lar ve canlı kopya (sizin sunucularınız), SMS (kendi
hattınızdan).

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
| SMS gönderme | SMS ile giriş kodu ve script/otomasyonlardan SMS (yalnızca açarsanız; izin o zaman istenir) |
| Konum | Script'lerin ve API'nin telefonun konumunu okuması (yalnızca açarsanız; izin o zaman istenir) |
| Paket kurma | Uygulama içinden güncelleme (kurulumu her seferinde siz onaylarsınız) |

Soru ve bildirimler için: GitHub'daki proje sayfasında bir konu açın.
