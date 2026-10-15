package dev.droidserver.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr

/**
 * Sunucuyu yerel ağda mDNS/DNS-SD ile duyurur (`_droidserver._tcp`): ad, port ve TXT'de API yolu (`path`), varsa
 * HTTPS portu (`https`) ve sürüm (`v`). Sır yazılmaz. Konfigürasyon değişince yeniden kaydolur.
 */
class AndroidDiscovery(context: Context, private val log: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private var announced: Triple<String, Int, Map<String, String>>? = null

    @Synchronized
    fun announce(config: ServerConfig) {
        if (!config.access.discovery) return stop()
        val attributes = buildMap {
            put("path", config.server.basePath)
            put("v", "1")
            if (config.access.mode == AccessMode.DIRECT) put("https", config.access.direct.port.toString())
        }
        val key = Triple(config.name, config.server.port, attributes)
        if (key == announced && listener != null) return
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = config.name.take(60).ifBlank { "DroidServer" }
            serviceType = TYPE
            port = config.server.port
            attributes.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = log(tr("Yerel ağda duyuruldu: {0} ({1})", info.serviceName, TYPE))
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = log(tr("Yerel ağ duyurusu başarısız (kod {0})", errorCode))
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onSuccess { listener = l; announced = key }
            .onFailure { log(tr("Yerel ağ duyurusu başarısız: {0}", it.message)) }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
        announced = null
    }

    companion object {
        const val TYPE = "_droidserver._tcp"
    }
}
