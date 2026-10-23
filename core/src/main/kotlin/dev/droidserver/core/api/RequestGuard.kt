package dev.droidserver.core.api

import dev.droidserver.core.config.FirewallConfig
import dev.droidserver.core.config.LimitsConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.net.Cidr
import dev.droidserver.core.webdav.DavChallenge
import dev.droidserver.core.config.StaticConfig
import dev.droidserver.core.data.DataTransfer
import dev.droidserver.core.net.AddressScope
import dev.droidserver.core.replication.REPLICA_HEADER
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * İstemci başına sınırlar ve kötüye kullanım koruması:
 *  - dakikalık istek sınırı (429 + Retry-After),
 *  - art arda hatalı kimlik denemesinde (401) geçici engel (parola/anahtar tahminine karşı),
 *  - gövde boyutu sınırı (413), Content-Length'i olmayan (chunked) gövdeler dahil,
 *  - düz HTTP portuna internetten gelen istekleri reddetme: internete açılan uç HTTPS ucu veya tüneldir,
 *  - tüm yanıtlara `X-Content-Type-Options: nosniff` ve `Cache-Control: no-store`.
 *
 * İstemci adresi: bağlantı telefonun kendisinden (loopback) geliyorsa istek HTTPS ucundan ([dev.droidserver.core.net.TlsProxy])
 * veya cloudflared'dan gelmiştir; gerçek adres `CF-Connecting-IP` / `X-Forwarded-For` başlığındadır. Bu başlıklara yalnızca
 * loopback bağlantıda güvenilir, dışarıdan gelen bağlantıda taklit edilebilecekleri için yok sayılır.
 */
class ClientGuard(
    @Volatile var limits: LimitsConfig = LimitsConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long, var count: Int)
    private class Failures(var start: Long, var count: Int, var bannedUntil: Long = 0, var reason: String = "")

    private val requests = ConcurrentHashMap<String, Window>()
    private val failures = ConcurrentHashMap<String, Failures>()
    /** Anahtar ve kullanıcı başına dakikalık pencereler (`key:ad`, `user:5`). */
    private val identities = ConcurrentHashMap<String, Window>()

    /** Engel: adres, bitiş (epoch ms) ve nedeni. */
    data class Ban(val client: String, val untilMs: Long, val reason: String)

    /** Süresi dolmamış engeller, en yeni bitiş önce. */
    fun bans(): List<Ban> {
        val now = clock()
        return failures.entries.mapNotNull { (k, f) -> synchronized(f) { Ban(k, f.bannedUntil, f.reason).takeIf { f.bannedUntil > now } } }
            .sortedByDescending { it.untilMs }
    }

    /** Elle engel (dakika). */
    fun ban(client: String, minutes: Int, reason: String) {
        val f = failures.computeIfAbsent(client) { Failures(clock(), 0) }
        synchronized(f) { f.bannedUntil = clock() + minutes * MINUTE; f.reason = reason; f.count = 0 }
    }

    /** Engeli ve sayaçları kaldırır; engel varsa true. */
    fun unban(client: String): Boolean = failures.remove(client)?.let { it.bannedUntil > clock() } == true

    /**
     * Kimlik (anahtar/kullanıcı) başına dakikalık sınır: aşıldıysa kaç saniye sonra tekrar denenebileceği, değilse null.
     */
    fun hit(identity: String, perMinute: Int?): Long? {
        if (perMinute == null || perMinute <= 0) return null
        val now = clock()
        if (identities.size >= MAX_TRACKED) identities.entries.removeIf { now - it.value.start >= MINUTE }
        val w = identities.computeIfAbsent(identity) { Window(now, 0) }
        synchronized(w) {
            if (now - w.start >= MINUTE) { w.start = now; w.count = 0 }
            w.count++
            return if (w.count > perMinute) (w.start + MINUTE - now + 999) / 1000 else null
        }
    }

    /** İsteğe izin verilmiyorsa kaç saniye sonra tekrar denenebileceği; izinliyse null. */
    fun admit(client: String): Long? {
        val now = clock()
        failures[client]?.let { f ->
            synchronized(f) { if (f.bannedUntil > now) return (f.bannedUntil - now + 999) / 1000 }
        }
        val perMinute = limits.requestsPerMinute
        if (perMinute <= 0) return null
        prune(requests.size, now)
        val w = requests.computeIfAbsent(client) { Window(now, 0) }
        synchronized(w) {
            if (now - w.start >= MINUTE) { w.start = now; w.count = 0 }
            w.count++
            return if (w.count > perMinute) (w.start + MINUTE - now + 999) / 1000 else null
        }
    }

    /** Hatalı kimlik denemesi; sınır aşılırsa engel başlar. Engellendiyse true. */
    fun authFailed(client: String): Boolean {
        if (limits.authFailures <= 0) return false
        val now = clock()
        val f = failures.computeIfAbsent(client) { Failures(now, 0) }
        synchronized(f) {
            if (now - f.start >= FAILURE_WINDOW) { f.start = now; f.count = 0 }
            f.count++
            if (f.count >= limits.authFailures) {
                f.bannedUntil = now + limits.banMinutes * MINUTE
                f.reason = tr("{0} hatalı kimlik denemesi", limits.authFailures)
                f.count = 0
                return true
            }
        }
        return false
    }

    private fun prune(size: Int, now: Long) {
        if (size < MAX_TRACKED) return
        requests.entries.removeIf { now - it.value.start >= MINUTE }
        failures.entries.removeIf { it.value.bannedUntil < now && now - it.value.start >= FAILURE_WINDOW }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val FAILURE_WINDOW = 10 * MINUTE
        private const val MAX_TRACKED = 10_000
        /** Telefonun kendisinden (uygulama) gelen, sınırlanmayan istekler. */
        const val LOCAL = "local"
    }
}

/** İsteğin kaynağı: [key] sınırlar için istemci kimliği; [internet] düz HTTP portuna internetten mi gelindi. */
internal data class Origin(val key: String, val internet: Boolean)

/** Kopyadan iletilen istekte kopyanın kimliğini doğrular (ana sunucu kurar); doğruysa `X-Forwarded-For` istemcidir. */
internal val ReplicaTrust = AttributeKey<(String) -> Boolean>("droidserver.replicaTrust")

internal fun origin(request: ApplicationRequest): Origin {
    forwardedClient(request.header(REPLICA_HEADER), request.header(HttpHeaders.XForwardedFor), request.call.application.attributes.getOrNull(ReplicaTrust))
        ?.let { return Origin(it, internet = false) }
    return origin(request.local.remoteAddress, request::header, LocalNetworks::contains)
}

/**
 * Kopyanın ilettiği istemci adresi: kopyanın kimliği ([REPLICA_HEADER]) doğrulanırsa `X-Forwarded-For`'daki son adres.
 * Yalnızca IP adresi kabul edilir ("yerel" işareti iletilemez: o istek kopyanın kendisinden sayılır).
 */
internal fun forwardedClient(token: String?, forwardedFor: String?, trust: ((String) -> Boolean)?): String? {
    if (token == null || trust == null || !trust(token)) return null
    return forwardedFor?.substringAfterLast(',')?.trim()?.let(::parseIp)?.hostAddress?.substringBefore('%')
}

internal fun origin(remoteAddress: String, header: (String) -> String?, sameNetwork: (InetAddress) -> Boolean): Origin {
    val remote = parseIp(remoteAddress)
    if (remote == null || remote.isLoopbackAddress) {
        val forwarded = header("CF-Connecting-IP")?.trim()
            ?: header(HttpHeaders.XForwardedFor)?.substringAfterLast(',')?.trim()
        return Origin(forwarded?.takeIf { it.isNotEmpty() } ?: ClientGuard.LOCAL, internet = false)
    }
    val key = remote.hostAddress.substringBefore('%')
    return Origin(key, internet = AddressScope.of(remote) == AddressScope.PUBLIC && !sameNetwork(remote))
}

/** Yalnızca IP adresi yazımlarını çözer (DNS sorgusu yapmaz). */
private fun parseIp(s: String): InetAddress? {
    val t = s.removePrefix("[").removeSuffix("]")
    if (t.isEmpty() || !(t.contains(':') || t.all { it.isDigit() || it == '.' })) return null
    return runCatching { InetAddress.getByName(t) }.getOrNull()
}

/**
 * Telefonun bağlı olduğu IPv6 ağları (/64). Ev ağındaki cihazlar da genel IPv6 adresi kullanabildiği için, aynı /64'ten
 * gelen bağlantı internetten sayılmaz.
 */
internal object LocalNetworks {
    @Volatile private var cached: List<ByteArray> = emptyList()
    @Volatile private var at = 0L

    fun contains(a: InetAddress): Boolean {
        if (a !is Inet6Address) return false
        val prefix = a.address.copyOf(8)
        return prefixes().any { it.contentEquals(prefix) }
    }

    private fun prefixes(): List<ByteArray> {
        val now = System.currentTimeMillis()
        if (now - at > 60_000) {
            cached = runCatching {
                NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet6Address>().filter { AddressScope.of(it) == AddressScope.PUBLIC }
                    .map { it.address.copyOf(8) }
            }.getOrDefault(emptyList())
            at = now
        }
        return cached
    }
}

/** İzin/engel listeleri; [blocked] engel nedenini döndürür (engel yoksa null). */
class Firewall(private val config: FirewallConfig) {
    private val allow = config.allow.mapNotNull(Cidr::parse)
    private val deny = config.deny.mapNotNull(Cidr::parse)

    fun blocked(client: String): String? {
        if (client == ClientGuard.LOCAL || (allow.isEmpty() && deny.isEmpty())) return null
        val address = parseIp(client) ?: return null
        deny.firstOrNull { it.contains(address) }?.let { return tr("engel listesinde ({0})", it) }
        if (allow.isEmpty() || allow.any { it.contains(address) }) return null
        if (config.allowLocalNetwork && (AddressScope.of(address) != AddressScope.PUBLIC || LocalNetworks.contains(address))) return null
        return tr("izin listesinde değil")
    }
}

internal class RequestGuardSettings {
    var limits: LimitsConfig = LimitsConfig()
    var firewall: FirewallConfig = FirewallConfig()

    /** Deploy'lar arasında süren koruma (engeller korunur); yoksa yenisi. */
    var guard: ClientGuard? = null

    /** Otomatik engel başladığında (adres, dakika). */
    var onBan: (String, Int) -> Unit = { _, _ -> }

    /** `Cache-Control: no-store` yalnızca API yanıtlarına; statik site kendi önbellek başlığını verir. */
    var basePath: String = "/api"

    /** Dosya indirme yolu öneki (API yoluyla, örn. `/api/files/`): kendi önbellek başlığını verir; null: yok. */
    var filesPrefix: String? = null

    /** Uygulama yolları: gövdeleri burada biriktirilmez, sınırı vekil akış sırasında uygular ([AppProxy]). */
    var appPaths: List<String> = emptyList()
}

/**
 * Dosya yüklemeleri (multipart/form-data) gövde sınırına tabi değildir: dosya alanının kendi sınırı yükleme sırasında,
 * akış içinde uygulanır (bkz. droidServerModule); diğer parçalar gövde sınırıyla sınırlanır.
 */
private fun ApplicationRequest.isMultipart() =
    header(HttpHeaders.ContentType)?.startsWith("multipart/form-data", ignoreCase = true) == true

internal val RequestGuard = createApplicationPlugin("DroidServerGuard", ::RequestGuardSettings) {
    val limits = pluginConfig.limits
    val guard = (pluginConfig.guard ?: ClientGuard()).also { it.limits = limits }
    val firewall = Firewall(pluginConfig.firewall)
    val onBan = pluginConfig.onBan
    val maxBody = limits.maxBodyKb * 1024L
    val base = pluginConfig.basePath
    val appPaths = pluginConfig.appPaths
    val filesPrefix = pluginConfig.filesPrefix
    fun appPath(path: String) = appPaths.any { StaticConfig.underPath(path, it) }
    // İçe aktarılan dosya genel gövde sınırından büyük olabilir (yalnızca yönetici kullanır). Zip bellekte toplanmaz;
    // sınırı DataTransfer.importZip okurken uygular.
    fun ApplicationRequest.zipImport() = DataTransfer.isImportPath(local.uri.substringBefore('?')) && queryParameters["format"]?.lowercase() == "zip"
    fun limitFor(path: String) = if (DataTransfer.isImportPath(path)) maxOf(maxBody, DataTransfer.MAX_IMPORT_BYTES) else maxBody
    fun limitFor(request: ApplicationRequest) = if (request.zipImport()) DataTransfer.MAX_ZIP_BYTES else limitFor(request.local.uri.substringBefore('?'))

    onCall { call ->
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        val path = call.request.local.uri.substringBefore('?')
        if (StaticConfig.underPath(path, base) && filesPrefix?.let { path.startsWith(it) } != true) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        }
        val o = origin(call.request)
        if (o.internet) {
            call.respond(
                HttpStatusCode.Forbidden,
                apiError(403, tr("Bu port yalnızca yerel ağ içindir; internetten HTTPS adresini veya tüneli kullanın")),
            )
            return@onCall
        }
        if (o.key == ClientGuard.LOCAL) return@onCall
        firewall.blocked(o.key)?.let {
            call.respond(HttpStatusCode.Forbidden, apiError(403, tr("Bu adresten erişim engellendi")))
            return@onCall
        }
        guard.admit(o.key)?.let { retry ->
            call.response.headers.append(HttpHeaders.RetryAfter, retry.toString())
            call.respond(HttpStatusCode.TooManyRequests, apiError(429, tr("Çok fazla istek; {0} saniye sonra tekrar deneyin", retry)))
            return@onCall
        }
        val length = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (length != null && length > limitFor(call.request) && !call.request.isMultipart() && !appPath(path)) {
            call.respond(HttpStatusCode.PayloadTooLarge, apiError(413, tr("İstek gövdesi çok büyük (en çok {0} KB)", limitFor(call.request) / 1024)))
        }
    }

    // Content-Length'i olmayan gövdeler okunurken sınırlanır.
    onCallReceive { call ->
        transformBody { data: ByteReadChannel ->
            if (origin(call.request).key == ClientGuard.LOCAL || call.request.isMultipart() || call.request.zipImport() || appPath(call.request.local.uri.substringBefore('?'))) return@transformBody data
            val limit = limitFor(call.request.local.uri.substringBefore('?'))
            val packet = data.readRemaining(limit + 1)
            val bytes = packet.readByteArray()
            if (bytes.size > limit) throw ApiException(413, tr("İstek gövdesi çok büyük (en çok {0} KB)", limit / 1024))
            ByteReadChannel(bytes)
        }
    }

    on(ResponseSent) { call ->
        // Ağ sürücüsünün kimliksiz ilk isteği (meydan okuma) hatalı deneme sayılmaz.
        if (call.response.status() == HttpStatusCode.Unauthorized && !call.attributes.contains(DavChallenge)) {
            val o = origin(call.request)
            if (o.key != ClientGuard.LOCAL && guard.authFailed(o.key)) {
                call.application.environment.log.warn(tr("{0} çok sayıda hatalı kimlik denemesi yaptı; {1} dk engellendi", o.key, limits.banMinutes))
                onBan(o.key, limits.banMinutes)
            }
        }
    }
}
