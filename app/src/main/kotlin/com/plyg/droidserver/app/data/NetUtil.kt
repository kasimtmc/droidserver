package com.plyg.droidserver.app.data

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** Cihazın yerel ağ IPv4 adresleri (Wi-Fi, hotspot, USB tethering). */
    fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress!! }
    }.getOrDefault(emptyList())
}
