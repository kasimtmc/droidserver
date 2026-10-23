package dev.droidserver.core.alerts

import dev.droidserver.core.config.AlertsConfig
import dev.droidserver.core.config.QuietHours
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.metrics.DeviceSnapshot
import dev.droidserver.core.metrics.Metrics
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Uyarı türleri; [critical] olanlar sessiz saatlerde de gönderilir. */
enum class AlertKind(val title: String, val critical: Boolean = false, val event: Boolean = false) {
    DISK(tr("Depolama dolmak üzere")),
    DISK_FULL(tr("Depolama doldu"), critical = true),
    ERRORS(tr("Sunucu hataları arttı")),
    CERTIFICATE(tr("Sertifika bitiyor")),
    HEAT(tr("Pil çok sıcak")),
    SERVER(tr("Sunucu çalışmıyor"), critical = true),
    COMPONENT(tr("Bileşen durdu"), critical = true),
    REPLICA(tr("Kopya ana sunucuya ulaşamıyor")),
    BACKUP(tr("Yedek alınamadı"), event = true),
    FAILOVER(tr("Sunucu rolü değişti (devralma)"), critical = true, event = true),
    CRASH(tr("Uygulama beklenmedik şekilde kapandı"), critical = true, event = true),
    RESTART(tr("Sistem sunucuyu yeniden başlattı"), event = true),
    SENSOR(tr("Sensör eşik dışında"), event = true),
    LOCAL_AI(tr("Yerel YZ çalıştırılamadı (bellek veya ısı)"), event = true),
    TEST(tr("Deneme uyarısı"), critical = true, event = true),
}

/** Gönderilen uyarı; [resolved] true ise koşul düzeldi bildirimi. */
@Serializable
data class Alert(
    val kind: AlertKind,
    val subject: String,
    val title: String,
    val message: String,
    val at: String,
    val resolved: Boolean = false,
    val critical: Boolean = false,
) {
    /** Kanallarda kullanılan tek satırlık metin. */
    fun text(server: String): String = "$server: $title\n$message"
}

/** Uyarının gideceği yer (telefon bildirimi, e-posta, Telegram). Hata fırlatabilir; diğer kanallar etkilenmez. */
fun interface AlertChannel {
    fun send(alert: Alert)
}

/** İzleyicinin her turda baktığı durum (app toplar). */
data class HealthInput(
    val device: DeviceSnapshot? = null,
    val minutes: List<Metrics.Minute> = emptyList(),
    /** Let's Encrypt sertifikasının bitişi (kendinden imzalıda null). */
    val certificateUntil: Instant? = null,
    /** Sunucu başlatılamadıysa nedeni. */
    val serverFailure: String? = null,
    /** Yeniden başlatma sınırını aşıp duran bileşenler. */
    val failedComponents: List<String> = emptyList(),
    /** Kopya sunucu uzun süredir eşitlenemiyorsa nedeni. */
    val replicaProblem: String? = null,
)

/**
 * Sağlık uyarıları: [check] durum koşullarını değerlendirir, [event] tek seferlik olayları (yedek başarısız, çökme)
 * alır. Aynı koşul sürdükçe `repeatHours` saatte bir hatırlatılır; düzelince bir kez "düzeldi" gider (ilk uyarı
 * gerçekten gönderildiyse). Sessiz saatlerde kritik olmayan uyarılar gönderilmez; koşul sürerse sessiz saat bitince
 * gider. Deploy'lar arasında sürer (motor düzeyinde).
 */
class HealthMonitor(
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: (String) -> Unit = {},
) {
    @Volatile var config: AlertsConfig = AlertsConfig()
        private set

    @Volatile private var channels: Map<String, AlertChannel> = emptyMap()
    @Volatile private var server: String = "DroidServer"

    private class Active(val alert: Alert, var sentAt: Instant?)

    private val active = LinkedHashMap<String, Active>()
    private val lastEvent = HashMap<String, Instant>()
    private val recent = ArrayDeque<Alert>()

    /** Ayarlar ve kanallar (her turda yenilenebilir; ad -> kanal). */
    fun configure(config: AlertsConfig, channels: Map<String, AlertChannel>, serverName: String) {
        this.config = config
        this.channels = channels
        this.server = serverName
    }

    /** Durum koşullarını değerlendirir; gönderilen uyarıları döndürür. */
    @Synchronized
    fun check(input: HealthInput): List<Alert> {
        val cfg = config
        if (!cfg.enabled) { active.clear(); return emptyList() }
        val now = clock()
        val current = conditions(cfg, input, now).associateBy { key(it.kind, it.subject) }
        val sent = mutableListOf<Alert>()
        // Düzelenler.
        active.entries.removeIf { (k, a) ->
            if (k in current) return@removeIf false
            if (a.sentAt != null) {
                val ok = a.alert.copy(title = a.alert.kind.title + tr(" — düzeldi"), message = tr("Durum normale döndü."), at = now.toString(), resolved = true, critical = false)
                if (!quiet(cfg, now)) deliver(ok)?.let(sent::add)
            }
            true
        }
        for ((k, c) in current) {
            // Ağırlaşan koşul (disk azaldı -> doldu) hatırlatma süresini beklemez.
            val a = active[k]?.takeIf { it.alert.kind == c.kind } ?: Active(c, null).also { active[k] = it }
            val due = a.sentAt == null || Duration.between(a.sentAt, now) >= Duration.ofHours(cfg.repeatHours.toLong())
            if (!due || (quiet(cfg, now) && !c.critical)) continue
            val alert = c.copy(at = now.toString())
            if (deliver(alert) != null) { a.sentAt = now; sent += alert }
        }
        return sent
    }

    /** Tek seferlik olay; aynı tür ve konu `repeatHours` içinde tekrar gönderilmez. Gönderildiyse uyarı. */
    @Synchronized
    fun event(kind: AlertKind, message: String, subject: String = ""): Alert? {
        val cfg = config
        if (!cfg.enabled && kind != AlertKind.TEST) return null
        val now = clock()
        val k = key(kind, subject)
        if (kind != AlertKind.TEST) {
            lastEvent[k]?.let { if (Duration.between(it, now) < Duration.ofHours(cfg.repeatHours.toLong())) return null }
            if (quiet(cfg, now) && !kind.critical) return null
        }
        val alert = Alert(kind, subject, kind.title, message, now.toString(), critical = kind.critical)
        return deliver(alert)?.also { lastEvent[k] = now }
    }

    /** Süren koşullar (henüz gönderilmemişler dahil). */
    @Synchronized
    fun active(): List<Alert> = active.values.map { it.alert }

    /** Son gönderilenler (en yeni önce). */
    @Synchronized
    fun recent(): List<Alert> = recent.reversed()

    /** Tüm kanallara gönderir; en az biri başardıysa uyarı (geçmişe eklenir), hiçbiri yoksa/başaramadıysa null. */
    private fun deliver(alert: Alert): Alert? {
        var ok = false
        channels.forEach { (name, ch) ->
            runCatching { ch.send(alert) }
                .onSuccess { ok = true }
                .onFailure { log("Uyarı $name kanalına gönderilemedi: ${it.message}") }
        }
        if (!ok) return null
        recent.addLast(alert)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        log("Uyarı: ${alert.title}${if (alert.resolved) "" else " — " + alert.message}")
        return alert
    }

    private fun quiet(cfg: AlertsConfig, now: Instant): Boolean = cfg.quietHours?.let { inQuiet(it, LocalTime.ofInstant(now, zone())) } == true

    companion object {
        const val MAX_RECENT = 100

        /** Disk uyarısının iki düzeyi aynı koşuldur (biri düzelirken diğeri "düzeldi" demesin). */
        private fun key(kind: AlertKind, subject: String) = if (kind == AlertKind.DISK_FULL) "DISK:$subject" else "${kind.name}:$subject"

        /** Saat sessiz aralıkta mı ([from] > [to] ise gece yarısını aşar; eşitse hiç sessiz değil). */
        fun inQuiet(q: QuietHours, t: LocalTime): Boolean {
            val from = runCatching { LocalTime.parse(q.from) }.getOrNull() ?: return false
            val to = runCatching { LocalTime.parse(q.to) }.getOrNull() ?: return false
            return when {
                from == to -> false
                from < to -> t >= from && t < to
                else -> t >= from || t < to
            }
        }

        /** Şu an geçerli koşullar (gönderim/tekrar kuralları olmadan). */
        fun conditions(cfg: AlertsConfig, input: HealthInput, now: Instant): List<Alert> = buildList {
            fun add(kind: AlertKind, message: String, subject: String = "") = add(Alert(kind, subject, kind.title, message, now.toString(), critical = kind.critical))
            input.device?.freeStorageBytes?.let { free ->
                val mb = free / (1024 * 1024)
                when {
                    mb < CRITICAL_DISK_MB -> add(AlertKind.DISK_FULL, tr("Boş alan {0} MB. Yazmalar ve yedekler başarısız olabilir; eski yedekleri veya dosyaları silin.", mb))
                    mb < cfg.diskFreeMb -> add(AlertKind.DISK, tr("Boş alan {0} MB (eşik {1} MB). Eski yedekleri, dosyaları veya çöp kutusunu temizleyin.", mb, cfg.diskFreeMb))
                }
            }
            val window = input.minutes.filter { it.startMs >= now.toEpochMilli() - ERROR_WINDOW_MS }
            val requests = window.sumOf { it.requests }
            val errors = window.sumOf { it.errors }
            if (requests >= MIN_REQUESTS && errors * 100 >= cfg.errorRatePercent * requests) {
                add(AlertKind.ERRORS, tr("Son 5 dakikada {0} isteğin {1} tanesi sunucu hatasıyla (5xx) bitti. Loglara bakın.", requests, errors))
            }
            input.certificateUntil?.let { until ->
                val days = Duration.between(now, until).toDays()
                if (days < cfg.certificateDays) {
                    add(AlertKind.CERTIFICATE, if (days < 0) tr("HTTPS sertifikasının süresi doldu; tarayıcılar uyarı gösterir.") else tr("HTTPS sertifikasının bitmesine {0} gün kaldı ve yenilenemedi. DuckDNS ayarlarını ve ağı denetleyin.", days))
                }
            }
            input.device?.batteryTempC?.let { t ->
                if (t >= cfg.batteryTempC) add(AlertKind.HEAT, String.format(Locale.ROOT, tr("Pil %.1f °C. Telefonu serin bir yere koyun, kılıfını çıkarın; sürekli şarjdaysa şarjı sınırlamayı düşünün."), t))
            }
            input.serverFailure?.let { add(AlertKind.SERVER, tr("Sunucu başlatılamadı: {0}", it)) }
            input.failedComponents.forEach { add(AlertKind.COMPONENT, tr("'{0}' tekrar tekrar çöktü ve yeniden başlatma sınırı aşıldı.", it), it) }
            input.replicaProblem?.let { add(AlertKind.REPLICA, tr("{0} Ana sunucu çöktüyse Durum → Canlı kopya → Devral ile bu sunucuyu ana sunucu yapabilirsiniz.", it)) }
        }

        private const val CRITICAL_DISK_MB = 100L
        private const val ERROR_WINDOW_MS = 5 * 60_000L
        private const val MIN_REQUESTS = 20L
    }
}
