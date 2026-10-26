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

Geliştirici sunucusu yok; uygulama analiz, reklam ya da hata raporu göndermez. Ama Play'in tanımında uygulamanın
cihazdan dışarı gönderdiği her veri "toplama", üçüncü tarafa gönderdiği "paylaşma" sayılır (geliştiriciye hiçbir şey
gelmese de). Aşağıdakiler yalnızca kullanıcı ilgili özelliği açarsa olur. Play'in tanımları gönderimde yeniden okunmalı.

**Veri toplama ve güvenlik**

| Soru | Yanıt | Not |
|---|---|---|
| Gerekli veri türlerinden birini topluyor ya da paylaşıyor mu? | Evet | İsteğe bağlı özellikler (aşağıda) |
| Toplanan tüm veriler aktarımda şifreleniyor mu? | Hayır | Sağlayıcılara HTTPS; yerel ağdaki API varsayılan olarak düz HTTP (ör. konum) |
| Hesap oluşturma yöntemleri | Uygulama hesap oluşturmaya izin vermiyor | Geliştiriciyle hesap yok; sunucunun kullanıcı hesapları sahibin kendi sunucusunda |

**Veri türleri** (hepsi isteğe bağlı, amaç yalnızca "Uygulama işlevselliği", geçici işleme: Hayır)

| Tür | Toplanır | Paylaşılır | Ne zaman, kime |
|---|---|---|---|
| Konum → Yaklaşık, Kesin | Evet | Hayır | Konum açıksa sahibin kendi uygulamaları API'den okur |
| Kişisel bilgiler → Telefon numarası | Hayır | Evet | SMS sağlayıcısı açıksa alıcı numarası Netgsm / İleti Merkezi / Twilio'ya |
| Mesajlar → SMS ya da MMS | Hayır | Evet | Aynı: SMS metni sağlayıcıya |
| Kişisel bilgiler → E-posta adresi | Hayır | Evet | Ödemede alıcı e-postası Stripe / iyzico / Paddle'a; e-posta açıksa sahibin SMTP sunucusuna |
| Uygulama içeriği → Kullanıcının oluşturduğu diğer içerik | Hayır | Evet | YZ açıksa istemler ve kayıtlar (sahibin anahtarıyla) Claude / OpenAI / Gemini'ye |

Bildirilmeyenler: bulut yedeği (uçtan uca şifreli; Play toplama saymaz), kart bilgisi (telefondan geçmez), harita
karoları (yalnızca görüntülenen bölge), Telegram uyarıları ve webhook'lar (sunucu durumu, sahibin kendi verisi), onaylı
SIM kipi (numara ve metin telefonda kalır, kullanıcı kendisi gönderir).

Gizlilik politikası adresi: `https://github.com/kasimtmc/droidserver/blob/master/PRIVACY.en.md`.
