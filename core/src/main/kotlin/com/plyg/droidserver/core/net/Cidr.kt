package com.plyg.droidserver.core.net

import java.net.InetAddress

/** IP adresi veya aralığı (`203.0.113.7`, `192.168.1.0/24`, `2001:db8::/32`); DNS sorgusu yapılmaz. */
class Cidr private constructor(private val network: ByteArray, private val bits: Int, val text: String) {
    fun contains(address: InetAddress): Boolean {
        val a = address.address
        if (a.size != network.size) return false
        val full = bits / 8
        for (i in 0 until full) if (a[i] != network[i]) return false
        val rest = bits % 8
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (a[full].toInt() and mask) == (network[full].toInt() and mask)
    }

    override fun toString() = text

    companion object {
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

        /** Geçersizse null. */
        fun parse(s: String): Cidr? {
            val t = s.trim()
            val ip = t.substringBefore('/')
            if (!IPV4.matches(ip) && !(':' in ip && IPV6.matches(ip))) return null
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return null
            val max = addr.address.size * 8
            val bits = if ('/' in t) t.substringAfter('/').toIntOrNull() ?: return null else max
            if (bits !in 0..max) return null
            return Cidr(addr.address, bits, t)
        }

        /** Yalnızca IP adresi yazımı (alan adı çözülmez); geçersizse null. */
        fun address(s: String): InetAddress? = parse(s.substringBefore('%'))?.let { runCatching { InetAddress.getByName(s.substringBefore('%')) }.getOrNull() }
    }
}
