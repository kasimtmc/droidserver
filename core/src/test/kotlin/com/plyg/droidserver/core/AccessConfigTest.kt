package com.plyg.droidserver.core

import com.plyg.droidserver.core.config.AccessConfig
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CloudflareTunnel
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.DdnsConfig
import com.plyg.droidserver.core.config.DirectAccess
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.accessToken
import com.plyg.droidserver.core.config.runtimeComponents
import com.plyg.droidserver.core.config.withAccess
import com.plyg.droidserver.core.config.withAccessToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessConfigTest {
    private val secured = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("0123456789abcdef")))

    @Test
    fun `cloudflare hizli tunel portu calisma aninda alir, adlandirilmis tunel token kullanir`() {
        val quick = secured.withAccess(AccessMode.CLOUDFLARE).copy(server = HttpConfig(port = 9090))
        val cf = quick.components.single()
        assertTrue(cf.enabled && cf.args.isEmpty(), "argümanlar konfigürasyonda tutulmaz")
        assertEquals(listOf("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:9090"), quick.runtimeComponents().single().args)

        val named = quick.withAccessToken(AccessMode.CLOUDFLARE, "secret:t")
        assertEquals(CloudflareTunnel.NAMED, named.access.cloudflare.tunnel)
        assertEquals("secret:t", named.accessToken(AccessMode.CLOUDFLARE))
        assertTrue(named.runtimeComponents().single().args.isEmpty(), "adlandırılmış tünel manifestteki komutla çalışır")

        // Hızlı tünele dönülünce token korunur ama çalışırken verilmez.
        val back = named.copy(access = named.access.copy(cloudflare = named.access.cloudflare.copy(tunnel = CloudflareTunnel.QUICK)))
        assertEquals("secret:t", back.components.single().env["TUNNEL_TOKEN"])
        assertFalse("TUNNEL_TOKEN" in back.runtimeComponents().single().env)

        val local = named.withAccess(AccessMode.LOCAL)
        assertFalse(local.components.single().enabled)
        assertEquals(AccessMode.LOCAL, AccessMode.of(local))
    }

    @Test
    fun `internete acik modda kimlik dogrulama gerekir, bilerek acilabilir`() {
        val open = ServerConfig().withAccess(AccessMode.DIRECT)
        assertEquals(listOf("auth.mode"), ConfigValidator.validateDetailed(open).map { it.path })
        assertTrue(ConfigValidator.validateDetailed(open.copy(access = open.access.copy(allowNoAuth = true))).isEmpty())
        assertTrue(ConfigValidator.validateDetailed(ServerConfig().withAccess(AccessMode.TAILSCALE)).isEmpty(), "Tailscale internete açık değil")
        assertTrue(ConfigValidator.validateDetailed(secured.withAccess(AccessMode.CLOUDFLARE)).isEmpty())
    }

    @Test
    fun `dogrudan erisim ayarlari dogrulanir`() {
        fun errors(direct: DirectAccess) =
            ConfigValidator.validateDetailed(secured.copy(access = AccessConfig(AccessMode.DIRECT, direct = direct))).map { it.path }
        assertTrue(errors(DirectAccess()).isEmpty())
        assertEquals(listOf("access.direct.port"), errors(DirectAccess(port = 8080)), "sunucu portuyla aynı olamaz")
        assertEquals(listOf("access.direct.port"), errors(DirectAccess(port = 443)))
        assertEquals(
            listOf("access.direct.ddns.domain", "access.direct.ddns.token"),
            errors(DirectAccess(ddns = DdnsConfig("Kötü.ad", ""))),
        )
        assertTrue(errors(DirectAccess(ddns = DdnsConfig("evim-sunucu", "secret:d"))).isEmpty())

        val badHost = secured.withAccess(AccessMode.CLOUDFLARE).let {
            it.copy(access = it.access.copy(cloudflare = it.access.cloudflare.copy(hostname = "https://api.example.com")))
        }
        assertEquals(listOf("access.cloudflare.hostname"), ConfigValidator.validateDetailed(badHost).map { it.path })
    }

    @Test
    fun `hizli tunel adresi gunlukten okunur`() {
        val logs = listOf(
            "2026-10-13T10:00:00Z INF Requesting new quick Tunnel on trycloudflare.com...",
            "2026-10-13T10:00:01Z INF |  https://eski-adres-bir.trycloudflare.com  |",
            "2026-10-13T10:05:01Z INF |  https://yeni-adres-iki.trycloudflare.com                                     |",
            "2026-10-13T10:05:02Z INF Registered tunnel connection connIndex=0",
        )
        assertEquals("https://yeni-adres-iki.trycloudflare.com", com.plyg.droidserver.core.config.QuickTunnel.urlIn(logs))
        assertEquals(null, com.plyg.droidserver.core.config.QuickTunnel.urlIn(listOf("api.trycloudflare.com hatası")))
    }
}
