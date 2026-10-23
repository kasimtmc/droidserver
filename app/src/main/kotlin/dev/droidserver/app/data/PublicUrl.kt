package dev.droidserver.app.data

import dev.droidserver.app.AppGraph
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.QuickTunnel
import dev.droidserver.core.config.ServerConfig

/** Sunucunun internetten erişilen HTTPS adresi (Cloudflare tüneli veya doğrudan erişim); yoksa null. */
fun publicUrl(g: AppGraph, active: ServerConfig?): String? {
    val direct = g.direct.status.value
    return when (active?.access?.mode ?: AccessMode.LOCAL) {
        AccessMode.CLOUDFLARE -> if (active?.access?.cloudflare?.tunnel == CloudflareTunnel.QUICK) {
            QuickTunnel.urlIn(g.supervisor.logs(AccessMode.CLOUDFLARE.componentId!!))
        } else active?.access?.cloudflare?.hostname?.let { "https://$it" }
        AccessMode.DIRECT -> direct.ddnsHost?.let { "https://$it:${direct.httpsPort}" } ?: direct.addresses.firstOrNull()?.let { "https://$it:${direct.httpsPort}" }
        else -> null
    }
}
