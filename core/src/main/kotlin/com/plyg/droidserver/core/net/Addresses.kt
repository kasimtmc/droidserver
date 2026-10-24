package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.i18n.tr
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Bir IP adresinin internetten ulaşılabilirlik açısından türü. */
enum class AddressScope(val title: String) {
    LOOPBACK(tr("yerel döngü")),
    LINK_LOCAL(tr("bağlantı yerel")),
    /** 10/8, 172.16/12, 192.168/16: modemin arkasındaki ev ağı. */
    PRIVATE(tr("özel ağ")),
    /** 100.64/10: operatörün paylaşımlı adresi (CGNAT); internetten içeri bağlantı alınamaz. */
    SHARED(tr("operatör paylaşımlı (CGNAT)")),
    /** fc00::/7: IPv6 özel ağ. */
    UNIQUE_LOCAL(tr("IPv6 özel ağ")),
    PUBLIC(tr("genel (internet)"));

    val reachableFromInternet: Boolean get() = this == PUBLIC

    companion object {
        fun of(a: InetAddress): AddressScope {
            if (a.isLoopbackAddress) return LOOPBACK
            if (a.isLinkLocalAddress) return LINK_LOCAL
            val b = a.address.map { it.toInt() and 0xFF }
            return when (a) {
                is Inet4Address -> when {
                    a.isSiteLocalAddress -> PRIVATE
                    b[0] == 100 && b[1] in 64..127 -> SHARED
                    b[0] == 0 || b[0] >= 224 -> LINK_LOCAL // ayrılmış / çoklu yayın
                    else -> PUBLIC
                }
                is Inet6Address -> when {
                    (b[0] and 0xFE) == 0xFC -> UNIQUE_LOCAL
                    a.isSiteLocalAddress -> UNIQUE_LOCAL
                    (b[0] and 0xE0) == 0x20 -> PUBLIC // 2000::/3 genel tek noktaya yayın
                    else -> LINK_LOCAL
                }
                else -> LINK_LOCAL
            }
        }
    }
}

/** Telefonun ağ arayüzü ve adresleri (app: NetworkInterface + ConnectivityManager). */
data class NetInterface(val name: String, val kind: Kind, val addresses: List<InetAddress>) {
    enum class Kind(val title: String) { WIFI("Wi-Fi"), MOBILE(tr("mobil veri")), VPN("VPN"), ETHERNET("Ethernet"), OTHER(tr("diğer")) }

    companion object {
        /** Android arayüz adlarından türü tahmin eder (wlan0, rmnet_data0, ccmni0, tun0 ...). */
        fun kindOf(name: String): Kind = when {
            name.startsWith("wlan") || name.startsWith("swlan") -> Kind.WIFI
            listOf("rmnet", "ccmni", "rev_rmnet", "pdp", "seth", "v4-rmnet", "clat").any { name.startsWith(it) } -> Kind.MOBILE
            name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec") -> Kind.VPN
            name.startsWith("eth") -> Kind.ETHERNET
            else -> Kind.OTHER
        }
    }
}

/**
 * Doğrudan erişimin ulaşılabilirlik değerlendirmesi. Kesin sonuç ancak dışarıdan denenerek alınır; burada
 * adreslerden çıkarılabilecekler hesaplanır.
 */
object Reachability {
    data class Verdict(
        /** İnternetten ulaşılabilecek adresler (port yönlendirme yapıldıysa modemin dış IPv4'ü dahil). */
        val candidates: List<String>,
        /** Kullanıcının bilmesi gereken engeller ve öneriler. */
        val problems: List<String>,
    )

    /**
     * @param routerExternalIp modemin UPnP/NAT-PMP ile bildirdiği dış IPv4 (yoksa null)
     * @param observedPublicIp dışarıdan görünen IPv4 (ipify gibi bir servisten; sorulmadıysa null)
     */
    fun assess(
        interfaces: List<NetInterface>,
        network: NetInterface.Kind,
        routerExternalIp: InetAddress?,
        observedPublicIp: InetAddress?,
        mappingOk: Boolean,
    ): Verdict {
        val problems = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        val active = interfaces.filter { it.kind == network }
        val v4 = active.flatMap { it.addresses }.filterIsInstance<Inet4Address>()
        val v6 = active.flatMap { it.addresses }.filterIsInstance<Inet6Address>().filter { AddressScope.of(it) == AddressScope.PUBLIC }

        if (active.isEmpty()) problems += tr("Telefon şu an {0} ağına bağlı değil.", network.title)

        // IPv4
        val directV4 = v4.firstOrNull { AddressScope.of(it) == AddressScope.PUBLIC }
        when {
            directV4 != null -> candidates += directV4.hostAddress
            network == NetInterface.Kind.WIFI -> {
                val ext = routerExternalIp
                when {
                    ext != null && AddressScope.of(ext) == AddressScope.SHARED ->
                        problems += tr("Modemin dış adresi ({0}) operatörün paylaşımlı adresi: operatör internet " +
                            "bağlantınızı başkalarıyla paylaştırıyor (CGNAT). Modemden port yönlendirme işe yaramaz; IPv6, " +
                            "Cloudflare Tunnel veya operatörden \"gerçek/sabit IP\" isteyin.", ext.hostAddress)
                    ext != null && !AddressScope.of(ext).reachableFromInternet ->
                        problems += tr("Modemin dış adresi ({0}) bir özel ağ adresi: ya modeminiz başka bir cihazın " +
                            "(örn. fiber/operatör modemi) arkasında ya da operatör bağlantıyı paylaştırıyor (CGNAT). İç içe iki " +
                            "modem varsa üsttekinde de port yönlendirme yapın veya onu köprü (bridge) moduna alın; CGNAT ise " +
                            "IPv6, Cloudflare Tunnel veya operatörden \"gerçek/sabit IP\" isteyin.", ext.hostAddress)
                    ext != null && observedPublicIp != null && ext != observedPublicIp ->
                        problems += tr("Modemin bildirdiği dış adres ({0}) internetten görünen adresten ({1}) " +
                            "farklı: ikinci bir modem/operatör NAT'ı var. Port yönlendirme dışarıdan erişimi sağlamayabilir.", ext.hostAddress, observedPublicIp.hostAddress)
                    ext != null && mappingOk -> candidates += ext.hostAddress
                    // Otomatik yönlendirme kapalı (elle yapıldı): dışarıdan görünen adres kullanılır.
                    ext == null && mappingOk && observedPublicIp != null && AddressScope.of(observedPublicIp).reachableFromInternet ->
                        candidates += observedPublicIp.hostAddress
                    !mappingOk -> problems += tr("Modemde port yönlendirme yapılamadı; modem arayüzünden elle yapmanız gerekiyor (adımlar aşağıda).")
                }
            }
            else -> problems += tr("Mobil veride IPv4 adresi operatörde paylaşımlı (CGNAT); internetten IPv4 ile ulaşılamaz.")
        }

        // IPv6
        if (v6.isNotEmpty()) {
            candidates += v6.map { "[${it.hostAddress.substringBefore('%')}]" }
            problems += if (network == NetInterface.Kind.WIFI) {
                tr("IPv6 adresiniz var ama çoğu modem dışarıdan gelen IPv6 bağlantılarını varsayılan olarak engeller; modemin " +
                    "güvenlik duvarında bu porta izin vermeniz gerekebilir.")
            } else {
                tr("Mobil veride IPv6 ile erişim operatöre bağlı: bazı operatörler dışarıdan gelen bağlantıları engeller. " +
                    "Erişimi başka bir ağdan deneyin.")
            }
        } else if (network == NetInterface.Kind.MOBILE) {
            problems += tr("Mobil bağlantınızda genel IPv6 adresi yok; operatör ayarlarında APN protokolünü IPv4/IPv6 yapmayı deneyin. " +
                "Olmazsa doğrudan erişim mümkün değil: Cloudflare Tunnel kullanın.")
        }
        return Verdict(candidates.distinct(), problems)
    }
}
