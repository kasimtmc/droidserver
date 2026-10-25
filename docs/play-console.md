# Play Console beyanları (taslak)

Play sürümünün (`play` türü) mağaza beyanları için hazır metinler (PLAN Faz 19, 19.6). Console'a İngilizce girilir;
Türkçe açıklamalar bu belge içindir. Gönderim (Grup 4) sırasında Play'in o günkü sorularıyla karşılaştırılmalı.

## Ön plan servisi: `specialUse`

Manifestteki alt tür açıklaması (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) ile aynı. Console → Uygulama içeriği → Ön plan
servisi izinleri → "Special use".

**Açıklama (Console'a):**

> DroidServer turns the user's phone into a self-hosted server: an HTTP/API server (database, REST/GraphQL API, file
> storage, web panel) that the user's own apps, websites and devices on the local network or through the user's tunnel
> call. The server only runs after the user starts it, shows a persistent notification with a Stop action, and must keep
> answering incoming requests while the screen is off. None of the other foreground service types fit: it is not media,
> location, data sync, a phone call or messaging; it is a long-running request listener started by the user.

**Ertelenirse ya da kesilirse kullanıcıya etkisi:**

> Requests from the user's apps and devices would fail (connection refused) until the user opens the app again; for
> example a home dashboard or a small shop's order form backed by the phone would stop working.

**Video taslağı (30–45 sn, ekran kaydı):**

1. Uygulamayı aç; Durum ekranında "Başlat".
2. Bildirim panelini indir: "Çalışıyor: http://…" bildirimi ve "Durdur" düğmesi görünsün.
3. Telefonun ekranını kilitle.
4. Bilgisayarda tarayıcıdan `http://<telefon>:8080/api/health` aç; yanıt gelsin (ekran kilitliyken).
5. Kilidi aç, bildirimden "Durdur"; tarayıcıda yenile, bağlantı reddedilsin.

## Kısıtlı izinler

Play sürümünde aşağıdakiler yok (manifestten kaldırılır, `PlayManifestTest` denetler); beyan formu gerekmez:

| İzin | Play sürümünde yerine |
|---|---|
| `SEND_SMS` | SMS sağlayıcısı ya da her SMS'te kullanıcı onayı (bildirim → SMS uygulaması, `ACTION_SENDTO`) |
| `REQUEST_INSTALL_PACKAGES` | Güncellemeyi Play yapar |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Android'in pil iyileştirmesi listesi açılır, kullanıcı muaf tutar |
| `ACCESS_BACKGROUND_LOCATION` | Hiç istenmez; konum yalnızca uygulama ekrandayken okunur |

Kalan izinler: internet, ağ ve Wi-Fi durumu, Wi-Fi çoklu yayın, ön plan servisi (`specialUse`), uyandırma kilidi,
açılışta başlatma, bildirim, biyometrik, konum (yalnızca açıkken; kullanıcı açarsa sorulur).

## Açık bilgilendirme (prominent disclosure)

Onaylı SMS kipi seçilirken tam sayfa gösterilir; kabul edilmeden kip açılmaz (Sihirbaz → Genel → Telefon özellikleri →
"Her SMS'te onayımla"). Metin: hangi veri (alıcı numarası, SMS metni), ne için (SMS'i kullanıcının kendisinin
göndermesi), nereye gitmediği, nasıl kapatılacağı. Kodda: `PhoneSection.kt` → `SmsDisclosure`.

## Veri güvenliği formu

Geliştirici sunucusu yok; uygulama analiz, reklam ya da hata raporu göndermez. Veri yalnızca kullanıcının açtığı ve
ayarladığı hizmetlere gider. Önerilen yanıtlar (Play'in tanımları gönderimde yeniden okunmalı):

| Soru | Yanıt | Not |
|---|---|---|
| Veri topluyor ya da paylaşıyor mu? | Evet (isteğe bağlı özellikler) | Aşağıdaki türler yalnızca kullanıcı ilgili özelliği açarsa |
| Kişisel bilgi → Telefon numarası | Paylaşılır, isteğe bağlı | SMS sağlayıcısı açıksa alıcı numarası sağlayıcıya (Netgsm, İleti Merkezi, Twilio); amaç: uygulama işlevi |
| Mesajlar → SMS ya da MMS | Paylaşılır, isteğe bağlı | Aynı: SMS metni sağlayıcıya |
| Konum → Kesin / yaklaşık konum | Toplanır, isteğe bağlı | Kullanıcının kendi uygulamaları sunucunun API'sinden okur; telefondan geliştiriciye gitmez; amaç: uygulama işlevi |
| Aktarımda şifreleme | Evet | Sağlayıcılara HTTPS; yerel ağ istekleri kullanıcının ayarına bağlı (HTTPS/Tünel) |
| Veri silme isteği | Uygulanmaz | Veri telefonda; kullanıcı uygulamadan siler, kaldırınca gider |
| Onaylı SIM kipi | Toplama yok | Numara ve metin yalnızca bildirimde ve SMS uygulamasında, telefondan çıkmaz (kullanıcı kendisi gönderir) |

Gizlilik politikası adresi: `PRIVACY.en.md` (GitHub'daki sayfası).
