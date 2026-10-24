package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.DirectNetwork
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant

/** Telefonun ağ durumu (app: NetworkInterface + ConnectivityManager + WifiManager). */
interface NetworkProbe {
    fun interfaces(): List<NetInterface>

    /** Wi-Fi ağ geçidinin (modemin) adresi. */
    fun gateway(): InetAddress?

    /** UPnP keşfi çoklu yayın yanıtı beklediği için Android'de Wi-Fi çoklu yayın kilidi tutulur. */
    fun <T> withMulticast(block: () -> T): T = block()
}

/** DeployOrchestrator'ın erişim katmanı (doğrudan erişim). Varsayılan: hiçbir şey yapmaz. */
interface AccessRuntime {
    /** Deploy'dan sonra, sırları çözülmüş konfigürasyonla. */
    fun apply(config: ServerConfig)

    fun stop()

    object None : AccessRuntime {
        override fun apply(config: ServerConfig) {}
        override fun stop() {}
    }
}

data class DirectStatus(
    val active: Boolean = false,
    val httpsPort: Int = 0,
    val network: DirectNetwork = DirectNetwork.WIFI,
    val fingerprint: String? = null,
    val publicKeyPin: String? = null,
    val mapping: PortMapping? = null,
    val mappingErrors: List<String> = emptyList(),
    /** Dışarıdan ulaşılabilecek adresler (`https://<bu>:<port>`). */
    val addresses: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val ddnsHost: String? = null,
    /** DuckDNS adı şu an ulaşılabilir bir adresi gösteriyor mu (yoksa kayıt temizlenmiştir). */
    val ddnsActive: Boolean = false,
    val ddnsError: String? = null,
    /** Kullanılan Let's Encrypt sertifikasının bitişi; kendinden imzalı sertifikada null. */
    val certificateUntil: Instant? = null,
    /** Let's Encrypt sertifikası alınıyor. */
    val certificateWorking: Boolean = false,
    val certificateError: String? = null,
    val checking: Boolean = false,
    val checkedAt: Instant? = null,
    val error: String? = null,
)

/**
 * İnternetten doğrudan erişim: HTTPS ucu ([TlsProxy]) + modemde otomatik port yönlendirme ([PortMapper]) +
 * ulaşılabilirlik değerlendirmesi ([Reachability]) + isteğe bağlı DuckDNS ve onun adına Let's Encrypt sertifikası
 * ([CertificateManager]). Yönlendirme süreli açılır ve yarı ömründe yenilenir; durdurulunca kaldırılır. Ağ değişince
 * (Wi-Fi ↔ mobil, yeni IP) periyodik kontrol yakalar. Let's Encrypt sertifikası yoksa veya alınamazsa kendinden imzalı
 * sertifika kullanılır (aynı anahtar: sabitlemiş istemciler her iki durumda da bağlanır).
 */
class DirectAccess(
    private val probe: NetworkProbe,
    private val identity: () -> TlsIdentity,
    private val scope: CoroutineScope,
    private val mapper: PortMapper = PortMapper(),
    private val wan: WanServices = WanServices(),
    private val log: (String) -> Unit = {},
    private val checkIntervalMs: Long = 10 * 60_000L,
    private val certificates: CertificateManager? = null,
    /** Başarısız sertifika denemesinden sonra bekleme (Let's Encrypt saatte 5 başarısız doğrulamaya izin verir). */
    private val certificateRetryMs: Long = 6 * 3_600_000L,
) : AccessRuntime {
    private val _status = MutableStateFlow(DirectStatus())
    val status: StateFlow<DirectStatus> = _status.asStateFlow()

    private var proxy: TlsProxy? = null
    private var proxyKey: Triple<Int, Int, String>? = null
    private var certificateRetryAt = 0L
    private var job: Job? = null
    private var current: ServerConfig? = null
    private var lastDdnsIp: String? = null

    @Synchronized
    override fun apply(config: ServerConfig) {
        if (config.access.mode != AccessMode.DIRECT) {
            stop()
            return
        }
        current = config
        if (!ensureProxy(config)) return
        job?.cancel()
        // Port yönlendirme ve DNS güncelleme engelleyici ağ çağrılarıdır.
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val wait = runCatching { check() }.getOrElse { log("Doğrudan erişim kontrolü başarısız: ${it.message}"); checkIntervalMs }
                delay(wait)
            }
        }
    }

    /** DuckDNS adı ve sertifika seçeneği açıksa Let's Encrypt'in adı (`<ad>.duckdns.org`). */
    private fun certificateHost(config: ServerConfig): String? =
        config.access.direct.ddns?.takeIf { it.certificate && certificates != null }?.let { "${it.domain}.duckdns.org" }

    /** Geçerli Let's Encrypt sertifikası varsa o, yoksa kendinden imzalı kimlik. */
    private fun chooseIdentity(config: ServerConfig): TlsIdentity =
        certificateHost(config)?.let { certificates?.current(it) } ?: identity()

    /**
     * HTTPS ucunu konfigürasyona ve seçilen sertifikaya göre (yeniden) açar; port veya sertifika değiştiyse eskisi
     * kapanır. Açılamazsa false.
     */
    @Synchronized
    private fun ensureProxy(config: ServerConfig): Boolean {
        val d = config.access.direct
        val id = chooseIdentity(config)
        val key = Triple(d.port, config.server.port, id.fingerprint)
        val until = id.certificate.notAfter.toInstant().takeUnless { id.selfSigned }
        if (proxy != null && proxyKey == key) {
            _status.update { it.copy(network = d.network, certificateUntil = until) }
            return true
        }
        stopProxy()
        return try {
            proxy = startProxy(id, d.port, config.server.port)
            proxyKey = key
            _status.update {
                // Aynı uçta yalnızca sertifika değiştiyse önceki kontrolün sonuçları korunur.
                val base = if (it.active && it.httpsPort == d.port) it else DirectStatus()
                base.copy(
                    active = true, httpsPort = d.port, network = d.network, error = null,
                    fingerprint = id.fingerprint, publicKeyPin = id.publicKeyPin, certificateUntil = until,
                )
            }
            true
        } catch (t: Throwable) {
            _status.value = DirectStatus(error = tr("HTTPS ucu açılamadı (port {0}): {1}", d.port, t.message))
            log("HTTPS ucu açılamadı: ${t.message}")
            false
        }
    }

    /** Ağ değiştiğinde veya kullanıcı istediğinde hemen yeniden kontrol. */
    fun recheck() {
        val cfg = synchronized(this) { current } ?: return
        if (cfg.access.mode == AccessMode.DIRECT) apply(cfg)
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        val old = _status.value.mapping
        if (old != null) {
            val gw = probe.gateway()
            scope.launch(Dispatchers.IO) { mapper.unmap(gw, old) }
        }
        stopProxy()
        current = null
        lastDdnsIp = null
        certificateRetryAt = 0
        _status.value = DirectStatus()
    }

    /** IPv6 ve IPv4 birlikte (`::`); cihazda IPv6 kapalıysa yalnızca IPv4. */
    private fun startProxy(id: TlsIdentity, port: Int, target: Int): TlsProxy = try {
        TlsProxy(id.sslContext(), InetSocketAddress(InetAddress.getByName("::"), port), target, log).start()
    } catch (e: java.net.SocketException) {
        log("IPv6 kullanılamıyor (${e.message}); HTTPS ucu yalnızca IPv4'te")
        TlsProxy(id.sslContext(), InetSocketAddress("0.0.0.0", port), target, log).start()
    }

    private fun stopProxy() {
        proxy?.close()
        proxy = null
        proxyKey = null
    }

    /**
     * Let's Encrypt sertifikası gerekiyorsa (yok, başka ada ait veya bitişine 30 günden az var) alır ve HTTPS ucunu yeni
     * sertifikayla yeniden açar. Başarısızlıkta [certificateRetryMs] sonra yeniden denenir; bu arada kendinden imzalı
     * (veya eski) sertifika kullanılmaya devam eder.
     */
    private fun renewCertificate(cfg: ServerConfig) {
        val manager = certificates ?: return
        val host = certificateHost(cfg)
        if (host == null) {
            _status.update { it.copy(certificateError = null, certificateWorking = false) }
            return
        }
        if (!manager.needsRenewal(host) || System.currentTimeMillis() < certificateRetryAt) return
        val ddns = cfg.access.direct.ddns ?: return
        _status.update { it.copy(certificateWorking = true) }
        val error = try {
            manager.obtain(host, ddns.domain, ddns.token, identity())
            null
        } catch (e: Exception) {
            certificateRetryAt = System.currentTimeMillis() + certificateRetryMs
            log("Let's Encrypt sertifikası alınamadı: ${e.message}")
            e.message ?: e.toString()
        }
        _status.update { it.copy(certificateWorking = false, certificateError = error) }
        synchronized(this) { current?.let(::ensureProxy) }
    }

    /** Bir kontrol turu; bir sonrakine kadar beklenecek süreyi döndürür. */
    private fun check(): Long {
        val cfg = synchronized(this) { current } ?: return checkIntervalMs
        val d = cfg.access.direct
        _status.update { it.copy(checking = true) }

        val interfaces = probe.interfaces()
        val kind = if (d.network == DirectNetwork.WIFI) NetInterface.Kind.WIFI else NetInterface.Kind.MOBILE
        val lan = interfaces.filter { it.kind == NetInterface.Kind.WIFI }.flatMap { it.addresses }
            .filterIsInstance<Inet4Address>().firstOrNull { AddressScope.of(it) == AddressScope.PRIVATE }

        var mapping: PortMapping? = null
        val mappingErrors = mutableListOf<String>()
        if (d.network == DirectNetwork.WIFI && d.portMapping) {
            if (lan == null) {
                mappingErrors += tr("Telefonun Wi-Fi adresi bulunamadı")
            } else {
                val r = probe.withMulticast { mapper.map(probe.gateway(), lan, d.port) }
                mapping = r.mapping
                mappingErrors += r.errors
                if (mapping != null) log("Modemde port yönlendirme (${mapping.method}): ${mapping.externalPort} → ${lan.hostAddress}:${d.port}")
                else log("Modemde port yönlendirme yapılamadı: ${r.errors.joinToString("; ")}")
            }
        }
        val observed = if (d.network == DirectNetwork.WIFI) wan.publicIpv4() else null
        val verdict = Reachability.assess(interfaces, kind, mapping?.externalIp, observed, mappingOk = mapping != null || !d.portMapping)

        var ddnsHost: String? = null
        var ddnsActive = false
        var ddnsError: String? = null
        // Kopya adı yayınlamaz: aynı DuckDNS adı iki telefonda ayarlıysa ad ana sunucuyu gösterir, devralınca yenisini.
        d.ddns?.takeIf { cfg.replication.replicaOf == null }?.let { ddns ->
            ddnsHost = "${ddns.domain}.duckdns.org"
            // Yalnızca internetten ulaşılabilir adresler yayınlanır (CGNAT'taki modem adresi gibi özel adresler asla);
            // olmayan aile için eski kayıt temizlenir ki istemciler ulaşılamayan adresi denemesin.
            val v4 = verdict.candidates.firstOrNull { !it.startsWith("[") }
            val v6 = verdict.candidates.firstOrNull { it.startsWith("[") }?.removeSurrounding("[", "]")
            val target = listOfNotNull(v4, v6).joinToString(",")
            if (target != lastDdnsIp) {
                try {
                    wan.updateDuckDns(ddns.domain, ddns.token, v4, v6)
                    lastDdnsIp = target
                    log(if (target.isEmpty()) tr("DuckDNS kaydı temizlendi: {0} (ulaşılabilir adres yok)", ddnsHost) else tr("DuckDNS güncellendi: {0} → {1}", ddnsHost, target))
                } catch (e: Exception) {
                    ddnsError = e.message
                    log("DuckDNS güncellenemedi: ${e.message}")
                }
            }
            ddnsActive = target.isNotEmpty() && lastDdnsIp == target
        }

        _status.update {
            it.copy(
                mapping = mapping, mappingErrors = mappingErrors, addresses = verdict.candidates, problems = verdict.problems,
                ddnsHost = ddnsHost, ddnsActive = ddnsActive, ddnsError = ddnsError, checking = false, checkedAt = Instant.now(),
            )
        }
        renewCertificate(cfg)
        // Süreli yönlendirme yarı ömründe yenilenir.
        val renew = mapping?.lifetimeSec?.takeIf { it > 0 }?.let { it * 500L } ?: checkIntervalMs
        return minOf(renew, checkIntervalMs)
    }
}
