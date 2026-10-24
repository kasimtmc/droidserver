package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.i18n.tr
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer

/** Modemde açılan port yönlendirmesi. */
data class PortMapping(
    val method: String,
    val externalPort: Int,
    val internalPort: Int,
    /** Saniye; 0 = kalıcı. Süre bitmeden yenilenmeli. */
    val lifetimeSec: Int,
    /** Modemin dış IPv4 adresi (bildirdiyse). */
    val externalIp: InetAddress?,
    /** Silerken gereken bilgi (UPnP denetim adresi). */
    val upnp: UpnpGateway? = null,
)

/** UPnP IGD bağlantı servisi: SOAP denetim adresi ve servis tipi. */
data class UpnpGateway(val controlUrl: String, val serviceType: String)

/**
 * Modemde otomatik port yönlendirme: önce NAT-PMP (Apple ve birçok modem; hızlı, ağ geçidi adresine UDP), olmazsa
 * UPnP IGD (çoğu ev modemi). İkisi de modemde kapalı olabilir; o zaman kullanıcıya elle yönlendirme adımları gösterilir.
 */
class PortMapper(
    private val natPmpPort: Int = 5351,
    private val ssdpTarget: InetSocketAddress = InetSocketAddress("239.255.255.250", 1900),
    private val timeoutMs: Int = 2_000,
    private val log: (String) -> Unit = {},
) {
    data class Result(val mapping: PortMapping?, val errors: List<String>)

    fun map(gateway: InetAddress?, localIp: InetAddress, port: Int, lifetimeSec: Int = 3600): Result {
        val errors = mutableListOf<String>()
        if (gateway != null) {
            try {
                return Result(natPmpMap(gateway, port, lifetimeSec), errors)
            } catch (e: IOException) {
                errors += "NAT-PMP: ${e.message}"
            }
        }
        try {
            val gw = upnpDiscover() ?: throw IOException(tr("modem UPnP ile yanıt vermedi (kapalı olabilir)"))
            return Result(upnpMap(gw, localIp, port, lifetimeSec), errors)
        } catch (e: IOException) {
            errors += "UPnP: ${e.message}"
        }
        return Result(null, errors)
    }

    fun unmap(gateway: InetAddress?, m: PortMapping) {
        runCatching {
            when (m.method) {
                NAT_PMP -> natPmpRequest(gateway ?: return, mapRequest(m.internalPort, 0, 0))
                UPNP -> m.upnp?.let { soap(it, "DeletePortMapping", deleteArgs(m.externalPort)) }
            }
        }.onFailure { log("Port yönlendirmesi kaldırılamadı: ${it.message}") }
    }

    // ---- NAT-PMP (RFC 6886)

    private fun natPmpMap(gateway: InetAddress, port: Int, lifetime: Int): PortMapping {
        val ext = natPmpRequest(gateway, byteArrayOf(0, 0)).let { r ->
            check(r.size >= 12 && r[1].toInt() and 0xFF == 128) { tr("geçersiz yanıt") }
            val code = r.u16(2)
            if (code != 0) throw IOException(tr("dış adres alınamadı (kod {0})", code))
            InetAddress.getByAddress(r.copyOfRange(8, 12))
        }
        val r = natPmpRequest(gateway, mapRequest(port, port, lifetime))
        if (r.size < 16 || r[1].toInt() and 0xFF != 130) throw IOException(tr("geçersiz yanıt"))
        val code = r.u16(2)
        if (code != 0) throw IOException(NAT_PMP_ERRORS[code] ?: tr("hata kodu {0}", code))
        return PortMapping(NAT_PMP, externalPort = r.u16(10), internalPort = r.u16(8), lifetimeSec = r.u32(12), externalIp = ext)
    }

    private fun mapRequest(internal: Int, external: Int, lifetime: Int): ByteArray =
        ByteBuffer.allocate(12).put(0).put(2).putShort(0).putShort(internal.toShort()).putShort(external.toShort()).putInt(lifetime).array()

    /** RFC'deki 250 ms'den başlayan yeniden deneme; toplam süre [timeoutMs] ile sınırlı. */
    private fun natPmpRequest(gateway: InetAddress, payload: ByteArray): ByteArray = DatagramSocket().use { s ->
        var wait = 250
        var spent = 0
        while (spent < timeoutMs) {
            s.send(DatagramPacket(payload, payload.size, gateway, natPmpPort))
            s.soTimeout = wait
            val buf = ByteArray(32)
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
                return buf.copyOf(p.length)
            } catch (_: SocketTimeoutException) {
                spent += wait
                wait *= 2
            }
        }
        throw IOException(tr("modem yanıt vermedi"))
    }

    // ---- UPnP IGD

    private fun upnpDiscover(): UpnpGateway? {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { s ->
            s.soTimeout = 400
            for (st in SEARCH_TARGETS) {
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                val bytes = msg.toByteArray()
                s.send(DatagramPacket(bytes, bytes.size, ssdpTarget))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    if (locations.isNotEmpty()) break else continue
                }
                LOCATION.find(String(buf, 0, p.length))?.groupValues?.get(1)?.trim()?.let(locations::add)
            }
        }
        for (location in locations) {
            val xml = runCatching { http("GET", location, null, emptyMap()) }.getOrNull() ?: continue
            val base = URL_BASE.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: location
            for (svc in SERVICE.findAll(xml).map { it.groupValues[1] }) {
                val type = SERVICE_TYPE.find(svc)?.groupValues?.get(1)?.trim() ?: continue
                if ("WANIPConnection" !in type && "WANPPPConnection" !in type) continue
                val control = CONTROL_URL.find(svc)?.groupValues?.get(1)?.trim() ?: continue
                return UpnpGateway(URI(base).resolve(control).toString(), type)
            }
        }
        return null
    }

    private fun upnpMap(gw: UpnpGateway, localIp: InetAddress, port: Int, lifetime: Int): PortMapping {
        fun add(lease: Int) = soap(
            gw, "AddPortMapping",
            listOf(
                "NewRemoteHost" to "", "NewExternalPort" to "$port", "NewProtocol" to "TCP", "NewInternalPort" to "$port",
                "NewInternalClient" to localIp.hostAddress, "NewEnabled" to "1",
                "NewPortMappingDescription" to "DroidServer", "NewLeaseDuration" to "$lease",
            ),
        )
        val lease = try {
            add(lifetime); lifetime
        } catch (e: UpnpError) {
            // 725: modem yalnızca süresiz yönlendirme kabul ediyor.
            if (e.code != 725) throw e
            add(0); 0
        }
        val ext = runCatching {
            EXTERNAL_IP.find(soap(gw, "GetExternalIPAddress", emptyList()))?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }?.let(InetAddress::getByName)
        }.getOrNull()
        return PortMapping(UPNP, port, port, lease, ext, gw)
    }

    private fun deleteArgs(port: Int) = listOf("NewRemoteHost" to "", "NewExternalPort" to "$port", "NewProtocol" to "TCP")

    private class UpnpError(val code: Int, msg: String) : IOException(msg)

    private fun soap(gw: UpnpGateway, action: String, args: List<Pair<String, String>>): String {
        val body = buildString {
            append("<?xml version=\"1.0\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(gw.serviceType).append("\">")
            for ((k, v) in args) append('<').append(k).append('>').append(xmlEscape(v)).append("</").append(k).append('>')
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        return try {
            http("POST", gw.controlUrl, body, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPAction" to "\"${gw.serviceType}#$action\""))
        } catch (e: HttpError) {
            val code = ERROR_CODE.find(e.body)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val desc = ERROR_DESC.find(e.body)?.groupValues?.get(1)?.trim() ?: "HTTP ${e.status}"
            throw UpnpError(code, tr("{0} reddedildi: {1}", action, desc) + if (code > 0) " ($code)" else "")
        }
    }

    private class HttpError(val status: Int, val body: String) : IOException("HTTP $status")

    private fun http(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = method
        c.useCaches = false
        headers.forEach(c::setRequestProperty)
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().take(256 * 1024).toByteArray().decodeToString() }.orEmpty()
            if (status >= 400) throw HttpError(status, text)
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val NAT_PMP = "NAT-PMP"
        const val UPNP = "UPnP"

        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )
        private val NAT_PMP_ERRORS = mapOf(
            1 to tr("desteklenmeyen sürüm"), 2 to tr("modem yetki vermedi (NAT-PMP kapalı olabilir)"),
            3 to tr("ağ hatası"), 4 to tr("modemin kaynakları yetersiz"), 5 to tr("desteklenmeyen işlem"),
        )

        // Basit belgeler için XML yerine kalıplar (dış varlık çözümleme riski de olmaz). ICU uyumu: süslü parantez yok.
        private val LOCATION = Regex("(?im)^location:\\s*(\\S+)")
        private val URL_BASE = Regex("<URLBase>([^<]*)</URLBase>", RegexOption.IGNORE_CASE)
        private val SERVICE = Regex("<service>(.*?)</service>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SERVICE_TYPE = Regex("<serviceType>([^<]*)</serviceType>", RegexOption.IGNORE_CASE)
        private val CONTROL_URL = Regex("<controlURL>([^<]*)</controlURL>", RegexOption.IGNORE_CASE)
        private val EXTERNAL_IP = Regex("<(?:\\w+:)?NewExternalIPAddress>([^<]*)<", RegexOption.IGNORE_CASE)
        private val ERROR_CODE = Regex("<(?:\\w+:)?errorCode>\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val ERROR_DESC = Regex("<(?:\\w+:)?errorDescription>([^<]*)<", RegexOption.IGNORE_CASE)

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun ByteArray.u16(i: Int) = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
        private fun ByteArray.u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
    }
}
