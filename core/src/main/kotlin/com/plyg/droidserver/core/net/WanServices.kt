package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

/** İnternetteki küçük yardımcı servisler; adresleri testlerde değiştirilebilir. */
class WanServices(
    private val ipv4Url: String = "https://api.ipify.org",
    private val duckDnsUrl: String = "https://www.duckdns.org/update",
    /** DNS-over-HTTPS (JSON) çözücü; TXT kaydının yayıldığını görmek için. */
    private val dohUrl: String = "https://dns.google/resolve",
    private val timeoutMs: Int = 5_000,
) {
    /** İnternetten görünen IPv4 adresi (modemin/operatörün dış adresi); bulunamazsa null. */
    fun publicIpv4(): InetAddress? = runCatching {
        val text = get(ipv4Url).trim()
        require(IPV4.matches(text)) { tr("beklenmeyen yanıt") }
        InetAddress.getByName(text)
    }.getOrNull()

    /**
     * DuckDNS kaydını tam olarak verilen adreslere ayarlar: [ipv4] A, [ipv6] AAAA kaydı. Verilmeyen ailenin kaydı
     * temizlenir (ulaşılamayan eski adres kalmasın); ikisi de null ise iki kayıt da temizlenir. DuckDNS adres
     * verilmezse isteğin geldiği IPv4'ü yazar; bu yüzden adres hiçbir zaman boş gönderilmez.
     */
    fun updateDuckDns(domain: String, token: String, ipv4: String?, ipv6: String?) {
        if (ipv4 == null || ipv6 == null) duckDns(domain, token, "&clear=true")
        when {
            ipv4 != null && ipv6 != null -> duckDns(domain, token, "&ip=${enc(ipv4)}&ipv6=${enc(ipv6)}")
            ipv4 != null || ipv6 != null -> duckDns(domain, token, "&ip=${enc(ipv4 ?: ipv6!!)}")
        }
    }

    /**
     * DuckDNS adının TXT kaydı (Let's Encrypt DNS doğrulaması). DuckDNS TXT kaydını alt adlar için de döndürür; bu yüzden
     * `_acme-challenge.<ad>.duckdns.org` sorgusu da bu değeri görür.
     */
    fun setDuckDnsTxt(domain: String, token: String, value: String) = duckDns(domain, token, "&txt=${enc(value)}")

    fun clearDuckDnsTxt(domain: String, token: String) = duckDns(domain, token, "&txt=&clear=true")

    /**
     * [name]'in TXT kaydında [value] görünene dek (en çok [timeoutMs]) bekler. Genel bir DNS çözücüsüne (DoH) sorulur;
     * çözücüye ulaşılamazsa süre dolana dek beklenir. Görünmese de döner: son sözü doğrulayan sunucu söyler.
     */
    fun awaitTxt(name: String, value: String, timeoutMs: Long = 120_000, intervalMs: Long = 5_000, sleep: (Long) -> Unit = Thread::sleep): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (txtRecords(name).contains(value)) return true
            if (System.currentTimeMillis() + intervalMs > deadline) return false
            sleep(intervalMs)
        }
    }

    /** DoH ile TXT kayıtları; sorgu başarısızsa boş. */
    fun txtRecords(name: String): List<String> = runCatching {
        val json = Json.parseToJsonElement(get("$dohUrl?name=${enc(name)}&type=TXT")).jsonObject
        (json["Answer"] as? JsonArray).orEmpty()
            .mapNotNull { (it.jsonObject["data"] as? JsonPrimitive)?.content?.trim('"') }
    }.getOrDefault(emptyList())

    private fun duckDns(domain: String, token: String, params: String) {
        val text = get("$duckDnsUrl?domains=${enc(domain)}&token=${enc(token)}$params").trim()
        if (!text.startsWith("OK")) throw IOException(tr("DuckDNS güncellemeyi reddetti (alan adı veya token yanlış)"))
    }

    private fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().take(4096).toByteArray().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        val IPV4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    }
}
