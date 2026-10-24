package com.plyg.droidserver.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.net.NetInterface
import com.plyg.droidserver.core.net.NetworkProbe
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Telefonun ağ durumu: arayüz türleri ConnectivityManager'dan (Wi-Fi / mobil / VPN), adresler NetworkInterface'ten.
 * UPnP keşfi için Wi-Fi çoklu yayın kilidi alınır (Android aksi halde çoklu yayın yanıtlarını süzer).
 */
class AndroidNetwork(private val context: Context) : NetworkProbe {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private data class Link(val props: LinkProperties, val kind: NetInterface.Kind)

    @Suppress("DEPRECATION") // allNetworks: API 31 öncesinde tek yol; sonrasında da çalışır
    private fun links(): List<Link> = cm.allNetworks.mapNotNull { n ->
        val props = cm.getLinkProperties(n) ?: return@mapNotNull null
        val caps = cm.getNetworkCapabilities(n)
        val kind = when {
            caps == null -> NetInterface.kindOf(props.interfaceName.orEmpty())
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetInterface.Kind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetInterface.Kind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetInterface.Kind.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetInterface.Kind.ETHERNET
            else -> NetInterface.kindOf(props.interfaceName.orEmpty())
        }
        Link(props, kind)
    }

    /** Telefon bir ağa (Wi-Fi, mobil, Ethernet) bağlı mı; otomatik devralmadan önce (kopan bu telefon olmasın). */
    fun connected(): Boolean = runCatching { cm.activeNetwork != null }.getOrDefault(true)

    override fun interfaces(): List<NetInterface> {
        val kinds = runCatching { links().associate { it.props.interfaceName to it.kind } }.getOrDefault(emptyMap())
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.map { ni ->
                // Mobil veride IPv4, 464XLAT ile ayrı bir arayüzde (v4-rmnet...) olabilir; tür adından da tahmin edilir.
                NetInterface(ni.name, kinds[ni.name] ?: NetInterface.kindOf(ni.name), ni.inetAddresses.toList())
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    override fun gateway(): InetAddress? {
        val fromRoutes = runCatching {
            links().filter { it.kind == NetInterface.Kind.WIFI }.flatMap { it.props.routes }
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
        }.getOrNull()
        if (fromRoutes != null) return fromRoutes
        val gw = wifi.dhcpInfo?.gateway ?: 0
        if (gw == 0) return null
        // dhcpInfo küçük uçlu int verir.
        return InetAddress.getByAddress(byteArrayOf(gw.toByte(), (gw shr 8).toByte(), (gw shr 16).toByte(), (gw shr 24).toByte()))
    }

    override fun <T> withMulticast(block: () -> T): T {
        val lock = runCatching { wifi.createMulticastLock("DroidServer::upnp").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            return block()
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** Etkin ağın DNS sunucuları; yoksa genel çözümleyiciler. */
    fun dnsServers(): List<InetAddress> {
        val active = runCatching { cm.activeNetwork?.let(cm::getLinkProperties)?.dnsServers }.getOrNull().orEmpty()
        return active.ifEmpty { listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").map(InetAddress::getByName) }
    }

    /**
     * cloudflared'ın (Go) DNS dosyası. Paket betiği binary'deki resolv.conf yolunu bu dosyaya çevirir
     * (tools/package-cloudflared.sh); adresler normalde Android'in çözümleyicisiyle çözülür, bu dosya yedektir.
     */
    fun writeDnsConf() {
        val servers = runCatching { dnsServers() }.getOrDefault(emptyList())
            .map { it.hostAddress.orEmpty().substringBefore('%') }.filter { it.isNotEmpty() }.take(3)
        runCatching {
            File(context.filesDir, "dns.conf").writeText(servers.joinToString("") { "nameserver $it\n" } + "options timeout:3 attempts:2\n")
        }
    }

    /** Ağ değiştiğinde (Wi-Fi ↔ mobil, yeni adres) çağrılır; iptal için dönen fonksiyon. */
    fun onChange(callback: () -> Unit): () -> Unit {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = callback()
            override fun onLost(network: Network) = callback()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = callback()
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        return runCatching {
            cm.registerNetworkCallback(request, cb)
            val unregister: () -> Unit = { runCatching { cm.unregisterNetworkCallback(cb) } }
            unregister
        }.getOrDefault {}
    }
}
