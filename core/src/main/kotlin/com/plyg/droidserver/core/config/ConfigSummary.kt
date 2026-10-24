package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sunucuya dışarıdan nasıl erişileceği (`access.mode`). */
@Serializable
enum class AccessMode(val title: String, val componentId: String?, val tokenEnv: String?) {
    /** Yalnızca aynı ağdaki cihazlar (http://<telefon-ip>:<port>). */
    @SerialName("local") LOCAL(tr("Yalnızca yerel ağ"), null, null),

    /** İnternetten doğrudan telefonun IP adresine (Wi-Fi'de modem yönlendirmesi, mobilde IPv6); HTTPS uygulamada. */
    @SerialName("direct") DIRECT(tr("İnternetten doğrudan (IP adresi)"), null, null),

    /** Cloudflare Tunnel: hesapsız hızlı tünel veya kendi alan adınızla; HTTPS Cloudflare'de. */
    @SerialName("cloudflare") CLOUDFLARE("Cloudflare Tunnel", "cloudflared", "TUNNEL_TOKEN"),

    /** Telefondaki Tailscale uygulamasıyla yalnızca kendi cihazlarınızdan; bileşen gerekmez. */
    @SerialName("tailscale") TAILSCALE(tr("Tailscale (yalnızca kendi cihazlarınız)"), null, null);

    /** Sunucu internetteki herkesin ulaşabileceği bir adreste mi? */
    val public: Boolean get() = this == DIRECT || this == CLOUDFLARE

    companion object {
        fun of(config: ServerConfig): AccessMode = config.access.mode

        /** Telefondaki Tailscale uygulamasının paket adı. */
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }
}

/**
 * Erişim modunu uygular. `cloudflared` bileşeni yalnızca Cloudflare modunda açıktır; silinmez, kapatılır ki
 * geri dönüldüğünde token'ı kaybolmasın. Eski sürümlerin `tailscale` bileşeni (paketi hiç olmadı) kapatılır.
 */
fun ServerConfig.withAccess(mode: AccessMode): ServerConfig {
    val cf = AccessMode.CLOUDFLARE.componentId!!
    val updated = components.map { c ->
        when (c.id) {
            cf -> c.copy(enabled = mode == AccessMode.CLOUDFLARE)
            LEGACY_TAILSCALE_COMPONENT -> c.copy(enabled = false)
            else -> c
        }
    }
    val needs = mode == AccessMode.CLOUDFLARE && updated.none { it.id == cf }
    return copy(
        access = access.copy(mode = mode),
        components = if (needs) updated + ComponentConfig(cf) else updated,
    )
}

/** Cloudflare adlandırılmış tünelinin token'ı (genellikle `secret:<ad>` referansı) veya yoksa null. */
fun ServerConfig.accessToken(mode: AccessMode): String? = mode.componentId?.let { id ->
    components.firstOrNull { it.id == id }?.env?.get(mode.tokenEnv)?.takeIf { it.isNotBlank() }
}

/** Cloudflare adlandırılmış tünel token'ını yazar ve tüneli "kendi alan adınızla" moduna alır. */
fun ServerConfig.withAccessToken(mode: AccessMode, token: String): ServerConfig {
    val id = requireNotNull(mode.componentId) { tr("{0} token kullanmaz", mode.title) }
    val env = requireNotNull(mode.tokenEnv)
    val base = withAccess(mode)
    return base.copy(
        access = base.access.copy(cloudflare = base.access.cloudflare.copy(tunnel = CloudflareTunnel.NAMED)),
        components = base.components.map { if (it.id == id) it.copy(env = it.env + (env to token)) else it },
    )
}

/**
 * Bileşenlerin çalıştırılacak hali: erişim ayarından türeyen argümanlar burada eklenir (konfigürasyonda tutulmaz ki
 * port değişince eskimesin). Hızlı tünel `cloudflared tunnel --url http://127.0.0.1:<port>` ile çalışır. `sharedEnv` açık
 * bileşene ortak ortam değişkenleri de verilir (kendi değişkenleri önceliklidir).
 */
fun ServerConfig.runtimeComponents(): List<ComponentConfig> = components.map { c ->
    // Elle verilmiş argümanlar her zaman önceliklidir.
    if (c.id != AccessMode.CLOUDFLARE.componentId || access.cloudflare.tunnel != CloudflareTunnel.QUICK || c.args.isNotEmpty()) c
    else c.copy(
        args = listOf("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:${server.port}"),
        env = c.env - AccessMode.CLOUDFLARE.tokenEnv!!,
    )
}.map { c -> if (c.sharedEnv) c.copy(env = env + c.env) else c }

/** Paketi hiç yayınlanmamış eski erişim bileşeni; v4'ten beri Tailscale uygulaması kullanılır. */
const val LEGACY_TAILSCALE_COMPONENT = "tailscale"

enum class CorsMode(val title: String) {
    OFF(tr("Kapalı")),
    ANY(tr("Tüm kaynaklar (*)")),
    LIST(tr("Belirli hostlar"));

    companion object {
        fun of(cors: CorsConfig): CorsMode = when {
            !cors.enabled -> OFF
            "*" in cors.allowedHosts -> ANY
            else -> LIST
        }
    }
}

/** Konfigürasyonun okunur özeti (sihirbazın son adımı, Sunucu sekmesi). */
object ConfigSummary {
    data class Line(val label: String, val value: String)

    fun lines(config: ServerConfig): List<Line> = buildList {
        add(Line(tr("Ad"), config.name))
        add(Line(tr("Adres"), tr("{0}:{1}, API yolu {2}", config.server.host, config.server.port, config.server.basePath)))
        add(Line(tr("Veritabanı"), when (config.database.engine) {
            DbEngine.SQLITE -> "SQLite (${config.database.file})"
            DbEngine.POSTGRES -> "PostgreSQL"
        }))
        add(Line(tr("Kimlik doğrulama"), when (config.auth.mode) {
            AuthMode.NONE -> tr("Yok")
            AuthMode.API_KEY -> tr("API anahtarı ({0} anahtar)", config.auth.apiKeys.size + config.auth.keys.size)
            AuthMode.JWT -> "JWT (HS256)"
        }))
        val endpoints = config.api.endpoints.size + config.collections.sumOf { it.endpoints.size }
        val systemFields = config.auth.users.systemFields.size + config.collections.sumOf { it.systemFields.size }
        if (endpoints + systemFields > 0) {
            add(Line(tr("Uzman ayarları"), listOfNotNull(
                tr("{0} uç değiştirildi", endpoints).takeIf { endpoints > 0 },
                tr("{0} sistem alanı yeniden adlandırıldı/kaldırıldı", systemFields).takeIf { systemFields > 0 },
            ).joinToString(", ")))
        }
        add(Line("CORS", when (CorsMode.of(config.server.cors)) {
            CorsMode.LIST -> config.server.cors.allowedHosts.joinToString()
            else -> CorsMode.of(config.server.cors).title
        }))
        add(Line(tr("Kullanıcı hesapları"), with(config.auth.users) {
            if (!enabled) tr("Kapalı")
            else tr("Açık (") + (if (allowSignup) tr("herkes kayıt olabilir") else tr("hesapları yönetici açar")) +
                (if (extraFields.isNotEmpty()) tr(", ek alanlar: {0}", extraFields.joinToString { it.name }) else "") +
                (if (roles.isNotEmpty()) tr(", roller: {0}", roles.joinToString { it.label }) else "") + ")"
        }))
        add(Line(tr("Koleksiyonlar"), config.collections.joinToString("\n") { c ->
            tr("{0} ({1} alan; {2}", c.name, c.fields.size, rulesSummary(c.rules)) +
                c.history?.let { tr("; geçmiş {0} sürüm", it.keep) }.orEmpty() + c.trash?.let { tr("; çöp kutusu {0} gün", it.days) }.orEmpty() +
                c.retention?.let { tr("; {0} günden eskiler silinir", it.days) }.orEmpty() + ")"
        }.ifEmpty { tr("Yok") }))
        if (config.views.isNotEmpty()) {
            add(Line(tr("Görünümler"), config.views.joinToString("\n") { v ->
                tr("{0} ({1}; {2})", v.name, v.collection, if (v.report != null) tr("rapor") else tr("{0} sütun", v.columns.size.takeIf { it > 0 } ?: tr("tüm"))) +
                    " · " + v.access.title + v.roles.takeIf { it.isNotEmpty() }?.let { tr(" + roller: {0}", it.joinToString()) }.orEmpty()
            }))
        }
        if (config.static.enabled) {
            add(Line(tr("Statik site"), "${config.static.path}" + if (config.static.spaFallback) tr(" (bulunamayan sayfada index.html)") else ""))
        }
        if (config.email.enabled) {
            add(Line(tr("E-posta"), "${config.email.from} (${config.email.host}:${config.email.port}, ${config.email.security.title})" +
                (if (config.auth.users.requireVerified) tr("; doğrulanmadan giriş yok") else "")))
        }
                if (config.webhooks.isNotEmpty()) {
            add(Line(tr("Webhook'lar"), config.webhooks.joinToString("\n") { h ->
                "${h.name} → ${h.url} (" + (if (h.collections.isEmpty()) tr("tüm koleksiyonlar") else h.collections.joinToString()) +
                    "; ${h.events.joinToString("/")})" + if (h.enabled) "" else tr(" kapalı")
            }))
        }
        if (config.realtime.enabled) {
            add(Line(tr("Canlı abonelik"), tr("{0}/realtime (en çok {1} bağlantı)", config.server.basePath.trimEnd('/'), config.realtime.maxConnections)))
        }
        add(Line(tr("Erişim"), accessSummary(config)))
        add(Line(tr("Bileşenler"), config.components.filter { it.enabled }.joinToString { it.id }.ifEmpty { tr("Yok") }))
    }

    /** Örn. "Herkes: listeleme, görüntüleme · Kaydın sahibi: güncelleme, silme". */
    fun rulesSummary(rules: Rules): String {
        val byRule = Rules.Op.entries.groupBy { rules[it] }
        val base = if (byRule.size == 1) tr("{0}: tüm işlemler", byRule.keys.single().title)
        else byRule.entries.joinToString(" · ") { (rule, ops) -> "${rule.title}: ${ops.joinToString { it.title.lowercase() }}" }
        val conditions = Rules.Op.entries.mapNotNull { op ->
            rules.condition(op)?.let { tr("{0} yalnızca {1}", op.title.lowercase(), it.filter) + if (it.owner) tr(" (sahibi hepsini)") else "" }
        }
        val withConditions = if (conditions.isEmpty()) base else tr("{0} · koşul: {1}", base, conditions.joinToString("; "))
        val roles = Rules.Op.entries.mapNotNull { op ->
            rules.roles(op).takeIf { it.isNotEmpty() }?.let { "${op.title.lowercase()}: ${it.joinToString()}" }
        }
        return if (roles.isEmpty()) withConditions else tr("{0} · roller: {1}", withConditions, roles.joinToString("; "))
    }

    private fun accessSummary(config: ServerConfig): String {
        val a = config.access
        return when (a.mode) {
            AccessMode.LOCAL, AccessMode.TAILSCALE -> a.mode.title
            AccessMode.CLOUDFLARE -> when (a.cloudflare.tunnel) {
                CloudflareTunnel.QUICK -> tr("Cloudflare hızlı tünel (adres her başlatmada değişir)")
                CloudflareTunnel.NAMED -> "Cloudflare Tunnel" + (a.cloudflare.hostname?.let { " (https://$it)" } ?: "")
            }
            AccessMode.DIRECT -> tr("Doğrudan IP, {0}, HTTPS port {1}", a.direct.network.title, a.direct.port) +
                (a.direct.ddns?.let { " (${it.domain}.duckdns.org" + (if (it.certificate) tr(", Let's Encrypt sertifikası)") else ")") } ?: "")
        }
    }

    /** Deploy'u engellemeyen ama açıkça gösterilmesi gereken güvenlik uyarıları. */
    fun securityWarnings(config: ServerConfig): List<String> = buildList {
        val mode = AccessMode.of(config)
        if (config.auth.mode == AuthMode.NONE) {
            add(
                if (!mode.public) {
                    tr("Kimlik doğrulama kapalı: {0} " +
                        "herkes kayıtları okuyabilir ve değiştirebilir.", if (mode == AccessMode.TAILSCALE) tr("Tailscale ağınızdaki") else tr("yerel ağdaki"))
                } else {
                    tr("Kimlik doğrulama kapalı ve sunucu internete açık: herkes kayıtları okuyabilir ve değiştirebilir!")
                },
            )
        }
        if (config.auth.mode != AuthMode.NONE) {
            for (c in config.collections) {
                val open = listOf(Rules.Op.CREATE, Rules.Op.UPDATE, Rules.Op.DELETE).filter { c.rules[it] == Rule.PUBLIC }
                if (open.isNotEmpty()) {
                    add(tr("'{0}': {1} herkese açık; giriş yapmamış biri de kayıtları değiştirebilir.", c.name, open.joinToString { it.title.lowercase() }))
                }
            }
        }
        if (mode == AccessMode.DIRECT) {
            add(
                tr("Doğrudan erişimde HTTPS sertifikası telefonda üretilir (kendinden imzalı): tarayıcılar uyarı gösterir; " +
                    "istemcilerde sertifika parmak izini sabitleyin (Durum ekranında)."),
            )
        }
        if (mode == AccessMode.CLOUDFLARE && config.access.cloudflare.tunnel == CloudflareTunnel.QUICK) {
            add(tr("Hızlı tünelin adresi her başlatmada değişir ve Cloudflare bunu yalnızca deneme için önerir; sürekli kullanım için kendi alan adınızla tünel kurun."))
        }
    }
}

/** cloudflared hızlı tünelinin adresi; her başlatmada değişir ve yalnızca bileşen günlüğünde yazar. */
object QuickTunnel {
    private val URL = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")

    /** Günlükteki en son hızlı tünel adresi (yeniden başlatmalarda yenisi yazılır). */
    fun urlIn(lines: List<String>): String? = lines.asReversed().firstNotNullOfOrNull { URL.find(it)?.value }
}
