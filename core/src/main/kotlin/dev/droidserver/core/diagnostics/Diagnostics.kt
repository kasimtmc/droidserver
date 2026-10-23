package dev.droidserver.core.diagnostics

import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.i18n.tr
import java.time.Duration
import java.time.Instant

/** Bulgunun önemi. */
enum class Level { OK, INFO, WARNING, PROBLEM }

/** Bulgunun yanında önerilen düzeltme (app bir düğmeye bağlar). */
enum class Fix { START_SERVER, BATTERY_SETTINGS, APP_SETTINGS, DEVELOPER_SETTINGS, NOTIFICATION_SETTINGS, OPEN_WIZARD, OPEN_ACCESS, OPEN_COMPONENTS, OPEN_LOGS, OPEN_WIFI_SETTINGS }

data class Finding(val id: String, val level: Level, val title: String, val detail: String, val fix: Fix? = null)

/**
 * Sorun gidericinin girdileri; app toplar (ağ istekleri, Android ayarları, bileşenler). null: denetlenemedi.
 * [localHealth]: telefonun kendisinden `http://127.0.0.1:<port>/api/health` yanıt verdi mi; [publicHealth]: dış
 * adres (tünel veya doğrudan erişim) yanıt verdi mi; [internet]: genel bir adres çözülüp bağlanılabildi mi.
 * [backgroundRestricted]: pil ayarı "Kısıtlı"; [childProcessLimit]: Android 12+ alt süreç kısıtı açık ve bileşen
 * çalışıyor; [serviceRestart]: bu süreçte sistem servisi öldürüp yeniden başlattıysa açıklaması.
 */
data class DiagnosticInput(
    val hasConfig: Boolean,
    val configErrors: List<String> = emptyList(),
    val running: Boolean,
    val failure: String? = null,
    val port: Int = 8080,
    val localHealth: Boolean? = null,
    val localAddresses: List<String> = emptyList(),
    val wifi: Boolean? = null,
    val internet: Boolean? = null,
    val access: AccessMode = AccessMode.LOCAL,
    val publicUrl: String? = null,
    val publicHealth: Boolean? = null,
    val directProblems: List<String> = emptyList(),
    val certificateUntil: Instant? = null,
    val failedComponents: List<String> = emptyList(),
    val batteryOptimized: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
    val childProcessLimit: Boolean? = null,
    val serviceRestart: String? = null,
    val notificationsAllowed: Boolean? = null,
    val freeStorageBytes: Long? = null,
    val activeAlerts: List<String> = emptyList(),
    val now: Instant = Instant.now(),
)

/** Durumdan bulgular: önce sorunlar, sonra uyarılar ve bilgiler, en sonda yolunda gidenler. */
object Diagnostics {
    fun run(i: DiagnosticInput): List<Finding> = buildList {
        if (!i.hasConfig) {
            add(Finding("config", Level.PROBLEM, tr("Henüz sunucu tasarlanmadı"), tr("Sihirbazla koleksiyonları ve erişimi seçin."), Fix.OPEN_WIZARD))
            return@buildList
        }
        if (i.configErrors.isNotEmpty()) {
            add(Finding("config", Level.PROBLEM, tr("Konfigürasyonda {0} hata var", i.configErrors.size), i.configErrors.take(3).joinToString("\n"), Fix.OPEN_WIZARD))
        } else {
            add(Finding("config", Level.OK, tr("Konfigürasyon geçerli"), ""))
        }

        when {
            i.failure != null -> add(Finding("server", Level.PROBLEM, tr("Sunucu başlatılamadı"), i.failure, Fix.OPEN_LOGS))
            !i.running -> add(Finding("server", Level.PROBLEM, tr("Sunucu çalışmıyor"), tr("Durum ekranından başlatın; telefon açılınca kendiliğinden başlaması için ayarı açın."), Fix.START_SERVER))
            i.localHealth == false -> add(
                Finding("server", Level.PROBLEM, tr("Sunucu yanıt vermiyor"), tr("{0} portunda istek yanıtlanmadı; port başka bir uygulamada olabilir. Loglara bakın.", i.port), Fix.OPEN_LOGS),
            )
            else -> add(Finding("server", Level.OK, tr("Sunucu çalışıyor"), tr("{0} portu yanıt veriyor.", i.port)))
        }

        when {
            i.wifi == false && i.localAddresses.isEmpty() ->
                add(Finding("lan", Level.WARNING, tr("Yerel ağ bağlantısı yok"), tr("Aynı ağdaki cihazlar bağlanamaz; Wi-Fi'ye bağlanın."), Fix.OPEN_WIFI_SETTINGS))
            i.localAddresses.isNotEmpty() ->
                add(Finding("lan", Level.OK, tr("Yerel ağ adresi"), i.localAddresses.joinToString { "http://$it:${i.port}" }))
        }

        if (i.internet == false) {
            add(Finding("internet", Level.WARNING, tr("İnternet bağlantısı yok"), tr("Ad çözümü veya bağlantı başarısız; tünel, DuckDNS, e-posta ve bulut yedeği çalışmaz.")))
        }

        when (i.access) {
            AccessMode.LOCAL -> add(Finding("access", Level.INFO, tr("Yalnızca yerel ağ"), tr("İnternetten erişim kapalı; açmak için Sihirbaz → Erişim."), Fix.OPEN_ACCESS))
            AccessMode.TAILSCALE -> add(Finding("access", Level.INFO, tr("Tailscale"), tr("Telefondaki Tailscale uygulaması açık ve bağlı olmalı; diğer cihazlar da aynı hesapta.")))
            AccessMode.DIRECT, AccessMode.CLOUDFLARE -> {
                i.directProblems.forEach { add(Finding("access", Level.WARNING, tr("Doğrudan erişim sorunu"), it, Fix.OPEN_ACCESS)) }
                when (i.publicHealth) {
                    true -> add(Finding("access", Level.OK, tr("İnternetten erişilebiliyor"), i.publicUrl.orEmpty()))
                    false -> add(
                        Finding(
                            "access", Level.PROBLEM, tr("İnternet adresi yanıt vermiyor"),
                            (i.publicUrl?.let { tr("{0} adresine ulaşılamadı. ", it) }.orEmpty()) + when (i.access) {
                                AccessMode.CLOUDFLARE -> tr("Tünel bileşeni çalışıyor mu, token doğru mu? Bileşenler ve Loglar'a bakın.")
                                else -> tr("Modemde port yönlendirmesi ve operatörün CGNAT kullanıp kullanmadığını denetleyin (Erişim adımı).")
                            },
                            if (i.access == AccessMode.CLOUDFLARE) Fix.OPEN_COMPONENTS else Fix.OPEN_ACCESS,
                        ),
                    )
                    null -> if (i.publicUrl == null) add(Finding("access", Level.WARNING, tr("İnternet adresi henüz yok"), tr("Erişim hazırlanıyor veya başarısız; birkaç saniye sonra yeniden deneyin."), Fix.OPEN_ACCESS))
                }
            }
        }

        i.certificateUntil?.let { until ->
            val days = Duration.between(i.now, until).toDays()
            when {
                until.isBefore(i.now) -> add(Finding("certificate", Level.PROBLEM, tr("Sertifikanın süresi dolmuş"), tr("Tarayıcılar bağlanmaz; Erişim adımından yenileyin."), Fix.OPEN_ACCESS))
                days < 14 -> add(Finding("certificate", Level.WARNING, tr("Sertifika {0} gün içinde bitiyor", days), tr("Kendiliğinden yenilenmesi beklenir; olmazsa Loglar'a bakın."), Fix.OPEN_LOGS))
            }
        }

        if (i.failedComponents.isNotEmpty()) {
            add(Finding("components", Level.PROBLEM, tr("Bileşen çalışmıyor: {0}", i.failedComponents.joinToString()), tr("Bileşenler ekranında logları ve yeniden başlatmayı deneyin."), Fix.OPEN_COMPONENTS))
        }

        when (i.batteryOptimized) {
            true -> add(
                Finding("battery", Level.WARNING, tr("Pil optimizasyonu açık"), tr("Android sunucuyu arka planda durdurabilir; DroidServer'ı muaf tutun."), Fix.BATTERY_SETTINGS),
            )
            false -> add(Finding("battery", Level.OK, tr("Pil optimizasyonundan muaf"), ""))
            null -> {}
        }
        if (i.backgroundRestricted == true) {
            add(Finding("background", Level.PROBLEM, tr("Arka plan kullanımı kısıtlı"), tr("Uygulamanın pil ayarı \"Kısıtlı\"; Android sunucuyu arka planda durdurur. Uygulama bilgisi → Pil → Kısıtlanmamış seçin."), Fix.APP_SETTINGS))
        }
        if (i.childProcessLimit == true) {
            add(
                Finding(
                    "processes", Level.WARNING, tr("Alt süreç kısıtı açık"),
                    tr("Android 12+ bileşenleri (PostgreSQL, cloudflared, uygulamalar) uyarısız durdurabilir. Geliştirici seçenekleri → \"Alt süreç kısıtlamalarını devre dışı bırak\" (Android 14+) veya README'deki ADB komutu."),
                    Fix.DEVELOPER_SETTINGS,
                ),
            )
        }
        i.serviceRestart?.let {
            add(Finding("restart", Level.WARNING, tr("Sistem sunucuyu yeniden başlattı"), it + "\n" + tr("Sık oluyorsa pil optimizasyonundan muaf tutun ve üreticinin pil ayarlarına bakın (dontkillmyapp.com)."), Fix.BATTERY_SETTINGS))
        }
        if (i.notificationsAllowed == false) {
            add(Finding("notifications", Level.WARNING, tr("Bildirimler kapalı"), tr("Sunucu bildirimi ve sağlık uyarıları görünmez."), Fix.NOTIFICATION_SETTINGS))
        }

        i.freeStorageBytes?.let { free ->
            val mb = free / (1024 * 1024)
            when {
                mb < 200 -> add(Finding("storage", Level.PROBLEM, tr("Depolama neredeyse dolu ({0} MB)", mb), tr("Veritabanı yazamayabilir; eski yedekleri ve dosyaları silin.")))
                mb < 1024 -> add(Finding("storage", Level.WARNING, tr("Depolama azalıyor ({0} MB)", mb), tr("Eski yedekleri silmeyi veya bulut yedeğini düşünün.")))
            }
        }

        i.activeAlerts.forEach { add(Finding("alert", Level.WARNING, tr("Sağlık uyarısı"), it)) }
    }.sortedBy { order(it.level) }

    private fun order(l: Level) = when (l) {
        Level.PROBLEM -> 0
        Level.WARNING -> 1
        Level.INFO -> 2
        Level.OK -> 3
    }
}
