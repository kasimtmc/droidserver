package com.plyg.droidserver.app.data

import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.CloudflareTunnel
import com.plyg.droidserver.core.config.QuickTunnel
import com.plyg.droidserver.core.config.ServerConfig

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
