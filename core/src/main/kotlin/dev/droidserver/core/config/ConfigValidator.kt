package dev.droidserver.core.config

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.ApiNames
import dev.droidserver.core.api.ApiPaths
import dev.droidserver.core.api.CollectionEndpoint
import dev.droidserver.core.api.Endpoint
import dev.droidserver.core.api.RuleConditions
import dev.droidserver.core.api.ViewService
import dev.droidserver.core.api.resolvedFields
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.phone.PhoneService
import dev.droidserver.core.realtime.ChannelHub
import dev.droidserver.core.security.FieldCipher

/**
 * Doğrulama hatası. [path] hatanın ait olduğu alanı gösterir, örn. `server.port`,
 * `collections[0].fields[1].name`; sihirbaz hatayı ilgili adıma ve alana bu yolla eşler.
 */
data class ValidationError(val path: String, val message: String) {
    override fun toString() = "$path: $message"
}

/**
 * Deploy öncesi konfigürasyonu doğrular. Boş liste = geçerli.
 * Tablo/alan adları SQL'e doğrudan yazıldığı için buradaki ad kuralı bir güvenlik sınırıdır.
 */
object ConfigValidator {
    private val ROLE_NAME = Regex("^[a-z][a-z0-9_]{1,31}$")

    /**
     * Uzman modu: uç yolları (sözdizimi, parametreler, çakışma) ve sistem alanı adları (geçerli, benzersiz, alanlarla
     * çakışmayan).
     */
    private fun apiRules(config: ServerConfig, error: (String, String) -> Unit) {
        val paths = ApiPaths(config)
        val used = mutableMapOf<String, String>() // "GET /a/{}" -> uç adı
        fun claim(method: String, path: String?, label: String, at: String) {
            path ?: return
            val key = "$method " + path.replace(Regex("\\{[A-Za-z]+\\}"), "{}")
            used[key]?.let { other -> error(at, tr("{0} ile {1} aynı yolu kullanıyor: {2} {3}", label, other, method, path)) } ?: run { used[key] = label }
        }
        config.api.endpoints.forEach { (key, c) ->
            val e = Endpoint.of(key)
            val at = "api.endpoints.$key"
            if (e == null) { error(at, tr("Bilinmeyen uç: {0}", key)); return@forEach }
            c.path?.takeIf { it.isNotBlank() }?.let { p -> ApiPaths.problem(p, e.path)?.let { error("$at.path", "${e.title}: $it") } }
        }
        Endpoint.entries.forEach { e -> claim(e.method, paths.path(e), e.title, "api.endpoints.${e.key}.path") }
        config.collections.forEachIndexed { ci, c ->
            c.endpoints.forEach { (key, ep) ->
                val e = CollectionEndpoint.of(key)
                val at = "collections[$ci].endpoints.$key"
                if (e == null) { error(at, tr("'{0}': bilinmeyen uç: {1}", c.name, key)); return@forEach }
                ep.path?.takeIf { it.isNotBlank() }?.let { p -> ApiPaths.problem(p, e.path)?.let { error("$at.path", "'${c.name}' ${e.title.lowercase()}: $it") } }
            }
            CollectionEndpoint.entries.forEach { e -> claim(e.method, paths.path(c, e), "'${c.name}' ${e.title.lowercase()}", "collections[$ci].endpoints.${e.key}.path") }
            systemFieldRules(c.systemFields, ApiNames.COLLECTION_FIELDS, c.fields.map { it.name }, "collections[$ci].systemFields", "'${c.name}'", error)
            c.history?.let { if (it.keep !in 1..1000) error("collections[$ci].history.keep", tr("'{0}': kayıt başına 1-1000 sürüm tutulabilir", c.name)) }
            c.trash?.let { if (it.days !in 1..3650) error("collections[$ci].trash.days", tr("'{0}': çöp kutusu süresi 1-3650 gün olmalı", c.name)) }
            c.sync?.let { if (it.keepDays !in 1..365) error("collections[$ci].sync.keepDays", tr("'{0}': silinenlerin izi 1-365 gün tutulmalı", c.name)) }
            calendarRules(config, c, "collections[$ci]", error)
            c.retention?.let { r ->
                val rp = "collections[$ci].retention"
                if (r.days !in 1..3650) error("$rp.days", tr("'{0}': saklama süresi 1-3650 gün olmalı", c.name))
                r.field?.let { name ->
                    val ok = name == "created_at" || name == "updated_at" || c.fields.any { it.name == name && it.type == FieldType.DATETIME }
                    if (!ok) error("$rp.field", tr("'{0}': saklama süresi bir zaman alanına göre ölçülür (created_at, updated_at veya tarih-saat alanı): '{1}'", c.name, name))
                }
                r.filter?.let { f ->
                    if ("@me" in f) error("$rp.filter", tr("'{0}': saklama koşulunda @me kullanılamaz", c.name))
                    else try {
                        RuleConditions.parse(c, f, null)
                    } catch (e: ApiException) {
                        error("$rp.filter", tr("'{0}' saklama koşulu: {1}", c.name, e.message))
                    }
                }
            }
        }
        val users = config.auth.users
        systemFieldRules(users.systemFields, ApiNames.USER_FIELDS, users.extraFields.map { it.name }, "auth.users.systemFields", tr("Kullanıcılar"), error)
        if (users.loginAttempts !in 1..1000) error("auth.users.loginAttempts", tr("Hatalı giriş sınırı 1-1000 olmalı"))
        if (users.loginBlockMinutes !in 1..10_080) error("auth.users.loginBlockMinutes", tr("Engel süresi 1 dakika ile 7 gün arasında olmalı"))
        if (users.accessTokenMinutes !in 1..10_080) error("auth.users.accessTokenMinutes", tr("Erişim token'ı 1 dakika ile 7 gün arasında olmalı"))
        if (users.refreshTokenDays !in 1..3650) error("auth.users.refreshTokenDays", tr("Yenileme token'ı 1-3650 gün olmalı"))
        if (users.twoFactor.enabled && !users.enabled) error("auth.users.twoFactor", tr("İki adımlı doğrulama kullanıcı hesaplarıyla çalışır; önce hesapları açın"))
        if (users.twoFactor.required && !users.twoFactor.enabled) error("auth.users.twoFactor.required", tr("Zorunlu kılmak için önce iki adımlı doğrulamayı açın"))
        if (users.emailLogin && (!users.enabled || !config.email.enabled)) {
            error("auth.users.emailLogin", tr("E-postayla giriş için kullanıcı hesapları ve e-posta (SMTP) ayarı açık olmalı"))
        }
        with(users.teams) {
            if (enabled && !users.enabled) error("auth.users.teams.enabled", tr("Takımlar için kullanıcı hesapları açık olmalı"))
            if (maxTeamsPerUser !in 1..1_000) error("auth.users.teams.maxTeamsPerUser", tr("Kullanıcı başına takım sayısı 1-1000 olmalı"))
            if (maxMembers !in 2..10_000) error("auth.users.teams.maxMembers", tr("Takım başına üye sayısı 2-10000 olmalı"))
        }
        if (users.smsLogin) {
            if (!users.enabled) error("auth.users.smsLogin", tr("SMS ile giriş için kullanıcı hesapları açık olmalı"))
            if (!config.phone.sms) error("auth.users.smsLogin", tr("SMS ile giriş için Telefon özellikleri → SMS gönderme açık olmalı"))
            val f = users.extraFields.firstOrNull { it.name == users.phoneField }
            when {
                f == null -> error("auth.users.phoneField", tr("Kullanıcılara '{0}' adında bir telefon alanı ekleyin (metin, benzersiz)", users.phoneField))
                f.type != FieldType.TEXT -> error("auth.users.phoneField", tr("'{0}' telefon alanı metin olmalı", f.name))
                !f.unique -> error("auth.users.phoneField", tr("'{0}' telefon alanı benzersiz olmalı (aynı numara iki hesapta olmasın)", f.name))
                f.encrypted -> error("auth.users.phoneField", tr("'{0}' telefon alanı şifreli olamaz (numarayla hesap aranır)", f.name))
            }
        }
        with(config.auth.passkeys) {
            if (enabled) {
                if (!users.enabled && !config.adminPanel.enabled) error("auth.passkeys.enabled", tr("Passkey için kullanıcı hesapları veya web paneli açık olmalı"))
                val id = rpId.trim().lowercase()
                when {
                    id.isEmpty() -> error("auth.passkeys.rpId", tr("Passkey için alan adı gerekli (örn. ev.duckdns.org); yerel IP ile çalışmaz"))
                    id != "localhost" && !HOSTNAME.matches(id) -> error("auth.passkeys.rpId", tr("'{0}' bir alan adı değil; IP adresi, port veya https:// olmadan yazın (örn. ev.duckdns.org)", rpId))
                }
            }
        }
        users.emailLoginUrl?.takeIf { it.isNotBlank() }?.let { u ->
            if (!u.startsWith("https://") && !u.startsWith("http://")) error("auth.users.emailLoginUrl", tr("Giriş bağlantısı http:// veya https:// ile başlamalı"))
        }
        config.adminPanel.totpSecret?.takeIf { it.isNotBlank() && !ConfigSecrets.isRef(it) }?.let { secret ->
            val bytes = runCatching { dev.droidserver.core.auth.Totp.unbase32(secret) }.getOrNull()
            if (bytes == null || bytes.size < 10) error("adminPanel.totpSecret", tr("Panel doğrulayıcı anahtarı geçerli bir Base32 anahtar değil (en az 16 karakter)"))
        }
        val q = users.quotas
        if (q.isSet && !users.enabled) error("auth.users.quotas", tr("Kotalar kullanıcı hesaplarıyla çalışır; önce hesapları açın"))
        val encrypted = config.collections.any { c -> c.fields.any { it.encrypted } } || users.extraFields.any { it.encrypted }
        val fieldKey = config.database.fieldKey
        when {
            encrypted && fieldKey.isNullOrBlank() -> error("database.fieldKey", tr("Şifreli alan var ama şifreleme anahtarı yok; sihirbaz anahtarı kendisi üretir"))
            fieldKey != null && !ConfigSecrets.isRef(fieldKey) && runCatching { FieldCipher.of(fieldKey) }.isFailure ->
                error("database.fieldKey", tr("Şifreleme anahtarı 32 baytlık base64 olmalı"))
        }
        views(config) { p, m -> error(p, m) }
        with(config.phone) {
            if (sms) {
                if (smsDailyLimit !in 1..500) error("phone.smsDailyLimit", tr("Günlük SMS sınırı 1-500 olmalı"))
                if (smsPrefixes.isEmpty()) error("phone.smsPrefixes", tr("En az bir izinli önek girin (örn. +90)"))
                smsPrefixes.filter { !PhoneService.PREFIX.matches(it) }.forEach { error("phone.smsPrefixes", tr("Önek + ve rakamlardan oluşmalı (örn. +90): '{0}'", it)) }
            }
        }
        config.realtime.channels.forEachIndexed { i, ch ->
            val path = "realtime.channels[$i]"
            val base = ch.name.removeSuffix("*")
            if (ch.name.isEmpty() || (base.isNotEmpty() && !ChannelHub.NAME.matches(base)) || '*' in base) {
                error("$path.name", tr("Kanal adı harf, rakam ve _ : . - içerir; kalıpta yalnızca sonda * olur (örn. room:*): '{0}'", ch.name))
            }
            if (config.realtime.channels.count { it.name == ch.name } > 1) error("$path.name", tr("'{0}' kanalı iki kez tanımlanmış", ch.name))
            for ((key, rule) in listOf("join" to ch.join, "publish" to ch.publish)) {
                if (rule == Rule.OWNER || rule == Rule.NOBODY) error("$path.$key", tr("Kanal kuralı public, auth veya admin olmalı"))
            }
            ch.roles.filter { r -> config.auth.users.roles.none { it.name == r } }.forEach { error("$path.roles", tr("'{0}' rolü tanımlı değil (Kullanıcılar adımında ekleyin)", it)) }
            if (ch.history !in 0..500) error("$path.history", tr("Kanal geçmişi 0-500 ileti olmalı"))
        }
        if (users.roles.isNotEmpty() && !users.enabled) error("auth.users.roles", tr("Roller kullanıcı hesaplarıyla çalışır; önce hesapları açın"))
        users.roles.forEachIndexed { i, r ->
            when {
                !ROLE_NAME.matches(r.name) -> error("auth.users.roles[$i].name", tr("Rol adı küçük harfle başlamalı; küçük harf, rakam ve _ (2-32 karakter): '{0}'", r.name))
                r.name == SystemCollections.ROLE_USER || r.name == SystemCollections.ROLE_ADMIN ->
                    error("auth.users.roles[$i].name", tr("'{0}' yerleşik bir rol; başka bir ad seçin", r.name))
                users.roles.count { it.name == r.name } > 1 -> error("auth.users.roles[$i].name", tr("'{0}' rolü iki kez tanımlanmış", r.name))
            }
        }
        if (users.roles.size > 20) error("auth.users.roles", tr("En çok 20 rol tanımlanabilir"))
        q.records?.let { if (it < 0) error("auth.users.quotas.records", tr("Kayıt kotası negatif olamaz")) }
        q.storageMb?.let { if (it < 1) error("auth.users.quotas.storageMb", tr("Depolama kotası en az 1 MB olmalı")) }
        q.dailyRequests?.let { if (it < 1) error("auth.users.quotas.dailyRequests", tr("Günlük istek kotası en az 1 olmalı")) }
        q.collections.forEach { (name, limit) ->
            val c = config.collections.firstOrNull { it.name == name }
            when {
                c == null -> error("auth.users.quotas.collections.$name", tr("Kota: '{0}' koleksiyonu yok", name))
                c.ownerField == null -> error("auth.users.quotas.collections.$name", tr("Kota: '{0}' koleksiyonunun sahip alanı yok (kayıtlar kullanıcıya bağlanamıyor)", name))
                limit < 0 -> error("auth.users.quotas.collections.$name", tr("Kota: '{0}' için sınır negatif olamaz", name))
            }
        }
    }

    /** Takvim ve kişi eşlemesi: alanlar var ve tipleri uygun; CalDAV/CardDAV için ağ sürücüsü açık. */
    private fun calendarRules(config: ServerConfig, c: CollectionConfig, at: String, error: (String, String) -> Unit) {
        val fields = c.fields.associateBy { it.name }
        fun field(path: String, name: String?, types: Set<FieldType>, what: String) {
            name ?: return
            val f = fields[name]
            when {
                f == null -> error(path, tr("'{0}': {1} alanı '{2}' yok", c.name, what, name))
                f.type !in types -> error(path, tr("'{0}': {1} alanı {2} tipinde olmalı ('{3}' {4})", c.name, what, types.joinToString("/") { it.name.lowercase() }, name, f.type.name.lowercase()))
                f.encrypted -> error(path, tr("'{0}': {1} alanı şifreli olamaz ('{2}')", c.name, what, name))
            }
        }
        val text = setOf(FieldType.TEXT)
        c.calendar?.let { cal ->
            val p = "$at.calendar"
            field("$p.title", cal.title, text, tr("başlık"))
            field("$p.start", cal.start, setOf(FieldType.DATETIME), tr("başlangıç"))
            field("$p.end", cal.end, setOf(FieldType.DATETIME), tr("bitiş"))
            field("$p.description", cal.description, text, tr("açıklama"))
            field("$p.location", cal.location, setOf(FieldType.TEXT, FieldType.GEO), tr("yer"))
            field("$p.allDay", cal.allDay, setOf(FieldType.BOOLEAN), tr("tüm gün"))
            field("$p.recurrence", cal.recurrence, text, tr("tekrar"))
            field("$p.resource", cal.resource, setOf(FieldType.TEXT, FieldType.SELECT, FieldType.INTEGER, FieldType.RELATION), tr("kaynak"))
            if (cal.end != null && cal.end == cal.start) error("$p.end", tr("'{0}': bitiş, başlangıçla aynı alan olamaz", c.name))
            if (cal.noOverlap && cal.end == null) error("$p.noOverlap", tr("'{0}': çakışma kuralı için bitiş alanı gerekli", c.name))
            if (cal.resource != null && !cal.noOverlap) error("$p.resource", tr("'{0}': kaynak alanı yalnızca çakışma kuralıyla kullanılır", c.name))
            cal.feedToken?.let { t -> if (!cal.feed || t.length < 16 || !t.all { it.isLetterOrDigit() || it == '-' || it == '_' }) error("$p.feedToken", tr("'{0}': gizli abonelik anahtarı en az 16 harf/rakam olmalı ve abonelik açık olmalı", c.name)) }
            if (cal.dav && !config.webdav.enabled) error("$p.dav", tr("'{0}': takvim eşitlemesi (CalDAV) için ağ sürücüsünü (WebDAV) açın", c.name))
        }
        c.contacts?.let { con ->
            val p = "$at.contacts"
            field("$p.name", con.name, text, tr("ad"))
            field("$p.email", con.email, text, tr("e-posta"))
            field("$p.phone", con.phone, text, tr("telefon"))
            field("$p.organization", con.organization, text, tr("kurum"))
            field("$p.note", con.note, text, tr("not"))
            if (!config.webdav.enabled) error(p, tr("'{0}': kişi eşitlemesi (CardDAV) için ağ sürücüsünü (WebDAV) açın", c.name))
        }
    }

    private fun systemFieldRules(
        map: Map<String, SystemFieldConfig>,
        allowed: List<String>,
        fieldNames: List<String>,
        path: String,
        label: String,
        error: (String, String) -> Unit,
    ) {
        val taken = mutableMapOf<String, String>()
        allowed.forEach { k -> if (map[k]?.removed != true) taken[map[k]?.name?.trim()?.takeIf { it.isNotEmpty() } ?: k] = k }
        map.forEach { (key, c) ->
            val at = "$path.$key"
            if (key !in allowed) { error(at, tr("{0}: '{1}' bir sistem alanı değil ({2})", label, key, allowed.joinToString())); return@forEach }
            val name = c.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (c.removed) return@forEach
            if (!Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$").matches(name)) error("$at.name", tr("{0}: '{1}' geçerli bir alan adı değil (harf, rakam, _)", label, name))
            if (name in fieldNames) error("$at.name", tr("{0}: '{1}' adı bir alanla çakışıyor", label, name))
            if (taken[name] != key) error("$at.name", tr("{0}: '{1}' adı başka bir sistem alanında da kullanılıyor", label, name))
            if (name in setOf("expand", "q", "page", "perPage", "sort", "fields")) error("$at.name", tr("{0}: '{1}' ayrılmış bir ad", label, name))
        }
    }

    /** Görünümler ve raporlar (bkz. ViewService). */
    private fun views(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.views.size > 50) error("views", tr("En çok 50 görünüm tanımlanabilir"))
        val roleNames = config.auth.users.roles.map { it.name }.toSet()
        config.views.forEachIndexed { i, v ->
            val path = "views[$i]"
            when {
                !IDENT.matches(v.name) -> error("$path.name", tr("Görünüm adı küçük harfle başlamalı; küçük harf, rakam ve _: '{0}'", v.name))
                config.views.count { it.name == v.name } > 1 -> error("$path.name", tr("'{0}' görünümü iki kez tanımlanmış", v.name))
            }
            val c = config.collections.firstOrNull { it.name == v.collection }
            if (c == null) {
                error("$path.collection", tr("'{0}' görünümünün koleksiyonu '{1}' yok", v.name, v.collection))
                return@forEachIndexed
            }
            if (v.access == Rule.OWNER || v.access == Rule.NOBODY) error("$path.access", tr("Görünüm erişimi public, auth veya admin olmalı"))
            if (v.access == Rule.AUTH && !config.auth.users.enabled && config.auth.mode == AuthMode.NONE) error("$path.access", tr("Giriş yapmış kullanıcı için önce kullanıcı hesaplarını açın"))
            v.roles.filter { it !in roleNames }.forEach { error("$path.roles", tr("'{0}' rolü tanımlı değil (Kullanıcılar adımında ekleyin)", it)) }
            v.columns.forEachIndexed { ci, col -> ViewService.columnProblem(config, v, col)?.let { error("$path.columns[$ci]", it) } }
            if (v.columns.size > 50) error("$path.columns", tr("En çok 50 sütun"))
            val fieldNames = c.fields.associateBy { it.name }
            fun known(name: String) = name in setOf("id", "created_at", "updated_at") || name in fieldNames
            v.filter?.let { f ->
                if ("@me" in f && v.access == Rule.PUBLIC) error("$path.filter", tr("@me yalnızca giriş gerektiren görünümde kullanılır"))
                try {
                    RuleConditions.parse(c, f, 1L)
                } catch (e: ApiException) {
                    error("$path.filter", e.message ?: "")
                }
            }
            v.sort?.split(',')?.map { it.trim().removePrefix("-").removePrefix("+") }?.filter { it.isNotEmpty() }?.forEach { name ->
                if (!known(name)) error("$path.sort", tr("Sıralanamayan alan: {0}", name))
                else if (fieldNames[name]?.encrypted == true) error("$path.sort", tr("{0}: '{1}' alanı şifreli; filtre, sıralama ve özette kullanılamaz", "sort", name))
            }
            v.report?.let { r ->
                val numbers = setOf(FieldType.INTEGER, FieldType.REAL)
                (r.sum + r.avg).filter { fieldNames[it]?.type !in numbers }.forEach { error("$path.report", tr("'{0}': toplam ve ortalama yalnızca sayı alanlarında", it)) }
                (r.min + r.max + listOfNotNull(r.groupBy?.substringBefore(':'))).filter { !known(it) }.forEach { error("$path.report", tr("'{0}': bilinmeyen alan", it)) }
                (r.sum + r.avg + r.min + r.max + listOfNotNull(r.groupBy?.substringBefore(':'))).filter { fieldNames[it]?.encrypted == true }
                    .forEach { error("$path.report", tr("{0}: '{1}' alanı şifreli; filtre, sıralama ve özette kullanılamaz", "report", it)) }
            }
        }
    }

    private val IDENT = Regex("^[a-z][a-z0-9_]{0,62}$")
    private val DB_FILE = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val DDNS_DOMAIN = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val HOSTNAME = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")
    private val TELEGRAM_CHAT = Regex("^(-?[0-9]{1,20}|@[A-Za-z][A-Za-z0-9_]{4,31})$")
    private val TELEGRAM_ADMIN_CHAT = Regex("^-?[0-9]{1,20}$")
    private val TEMPLATE_VAR = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
    private val CLOCK = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    /** pgjdbc'nin her sertifikaya güvenen SSL fabrikası; bağlantıyı ortadaki adam saldırısına açar. */
    private val INSECURE_SSL = Regex("[?&]sslfactory=[^&]*NonValidating", RegexOption.IGNORE_CASE)

    /** Her tabloya otomatik eklenen sistem alanları. */
    val SYSTEM_FIELDS = setOf("id", "created_at", "updated_at")

    fun validate(config: ServerConfig): List<String> = validateDetailed(config).map { it.message }

    fun validateDetailed(config: ServerConfig): List<ValidationError> = buildList {
        fun error(path: String, message: String) = add(ValidationError(path, message))

        /**
         * Alanın değeriyle ilgili kurallar (varsayılan, tip değişikliği, kısıtlar, hesaplanmış alan); koleksiyon ve
         * kullanıcı alanlarında ortak. [siblings] hesaplanmış alanın başvurabileceği alanlar.
         */
        fun fieldValueRules(path: String, label: String, f: FieldConfig, siblings: List<FieldConfig>) {
            val defaultProblem = FieldDefaults.problem(f.type, f.default)
            defaultProblem?.let { error("$path.default", tr("{0} varsayılan değeri: {1}", label, it)) }
            FieldRules.problems(f, siblings).forEach { (sub, msg) ->
                if (sub != "default" || defaultProblem == null) error("$path.$sub", "$label: $msg")
            }
            f.typeChange?.let { tc ->
                if (tc.action == TypeChangeAction.CONVERT && !TypeConversions.canConvert(tc.from, f.type)) {
                    error("$path.typeChange", tr("{0}: {1} → {2} dönüştürülemez; boşaltmayı seçin", label, tc.from.name.lowercase(), f.type.name.lowercase()))
                }
            }
        }

        /** Dosya alanı ayarları; başka tipte bu ayarlar kullanılamaz. */
        fun fileRules(path: String, label: String, f: FieldConfig) {
            if (f.type != FieldType.FILE) {
                if (f.maxSizeMb != null || f.mimeTypes != null || f.multiple != null || f.thumbs != null ||
                    f.convert != null || f.maxDimension != null || f.keepLocation || f.ai != null
                ) {
                    error("$path.type", tr("{0} dosya alanı değil; boyut, tip, çoklu ve küçük resim ayarları yalnızca file tipinde kullanılır", label))
                }
                return
            }
            f.maxSizeMb?.let { if (it !in 1..FileLimits.MAX_MB) error("$path.maxSizeMb", tr("{0}: dosya boyutu sınırı 1-{1} MB olmalı", label, FileLimits.MAX_MB)) }
            f.mimeTypes?.forEachIndexed { i, m ->
                if (!FileLimits.MIME_PATTERN.matches(m)) error("$path.mimeTypes[$i]", tr("{0}: '{1}' içerik tipi değil (örn. image/*, application/pdf)", label, m))
            }
            if (f.mimeTypes?.isEmpty() == true) error("$path.mimeTypes", tr("{0}: en az bir içerik tipi seçin (hepsi için alanı kaldırın)", label))
            f.thumbs?.forEachIndexed { i, t ->
                val m = FileLimits.THUMB.matchEntire(t)
                val w = m?.groupValues?.get(1)?.toInt()
                val h = m?.groupValues?.get(2)?.toInt()
                if (w == null || h == null || w > FileLimits.MAX_THUMB || h > FileLimits.MAX_THUMB || w + h == 0) {
                    error("$path.thumbs[$i]", tr("{0}: küçük resim boyutu GENİŞLİKxYÜKSEKLİK olmalı, örn. 200x200 veya 400x0 (en çok {1})", label, FileLimits.MAX_THUMB))
                }
            }
            if (f.unique) error("$path.unique", tr("{0}: dosya alanı benzersiz olamaz (adlar zaten rastgele)", label))
            f.maxDimension?.let { if (it !in 64..8192) error("$path.maxDimension", tr("{0}: en büyük kenar 64-8192 piksel olmalı", label)) }
        }

        if (config.name.isBlank()) error("name", tr("Sunucu adı boş olamaz"))

        with(config.server) {
            if (port !in 1024..65535) error("server.port", tr("server.port 1024-65535 aralığında olmalı (root olmadan alt portlar açılamaz)"))
            if (!basePath.startsWith("/") || basePath.endsWith("/") && basePath.length > 1) {
                error("server.basePath", tr("server.basePath '/' ile başlamalı ve '/' ile bitmemeli, örn. /api"))
            }
            if (limits.maxBodyKb !in 1..1_048_576) error("server.limits.maxBodyKb", tr("Gövde sınırı 1 KB ile 1 GB arasında olmalı"))
            if (limits.requestsPerMinute < 0) error("server.limits.requestsPerMinute", tr("İstek sınırı negatif olamaz (0: sınırsız)"))
            if (limits.authFailures < 0) error("server.limits.authFailures", tr("Deneme sınırı negatif olamaz (0: kapalı)"))
            if (limits.banMinutes !in 1..10_080) error("server.limits.banMinutes", tr("Engel süresi 1 dakika ile 7 gün arasında olmalı"))
            if (cors.enabled && cors.allowedHosts.none { it.isNotBlank() }) {
                error("server.cors.allowedHosts", tr("CORS açıkken en az bir host girilmeli (tümü için *)"))
            }
        }

        with(config.auth) {
            when (mode) {
                AuthMode.NONE -> {}
                AuthMode.API_KEY -> {
                    if (apiKeys.isEmpty() && keys.none { it.isFull && it.enabled }) {
                        error("auth.apiKeys", tr("auth.mode=api_key için en az bir auth.apiKeys veya tam yetkili adlandırılmış anahtar girilmeli"))
                    }
                    apiKeys.forEachIndexed { i, key ->
                        if (key.length < 16) error("auth.apiKeys[$i]", tr("API anahtarları en az 16 karakter olmalı"))
                    }
                }
                AuthMode.JWT -> if ((jwtSecret?.length ?: 0) < 32) {
                    error("auth.jwtSecret", tr("auth.mode=jwt için auth.jwtSecret en az 32 karakter olmalı"))
                }
            }
            if (keys.size > 50) error("auth.keys", tr("En çok 50 adlandırılmış anahtar"))
            val keyNames = mutableSetOf<String>()
            keys.forEachIndexed { i, k ->
                val path = "auth.keys[$i]"
                if (mode == AuthMode.NONE) error(path, tr("Kimlik doğrulama kapalıyken anahtarlar kullanılmaz"))
                if (!Regex("^[A-Za-z0-9_-]{1,40}$").matches(k.name)) error("$path.name", tr("Anahtar adı harf, rakam, - ve _ olmalı (en çok 40)"))
                else if (!keyNames.add(k.name)) error("$path.name", tr("'{0}' adı iki kez kullanılmış", k.name))
                if (!Regex("^[0-9a-f]{64}$").matches(k.hash)) error("$path.hash", tr("'{0}': anahtar özeti geçersiz; anahtarı yeniden oluşturun", k.name))
                k.collections.filter { c -> config.collections.none { it.name == c } }.forEach { error("$path.collections", tr("'{0}': '{1}' koleksiyonu yok", k.name, it)) }
                k.expiresAt?.let { e ->
                    if (runCatching { java.time.Instant.parse(e) }.isFailure) error("$path.expiresAt", tr("'{0}': son kullanma zamanı ISO-8601 olmalı", k.name))
                }
            }
            if (users.enabled) {
                if (mode == AuthMode.NONE) {
                    error(
                        "auth.mode",
                        tr("Kullanıcı hesapları açık: yönetici kimliği (API anahtarı veya JWT) seçin; kimlik doğrulama yokken herkes yönetici sayılır"),
                    )
                }
                if (users.minPasswordLength !in 6..128) error("auth.users.minPasswordLength", tr("En kısa parola uzunluğu 6-128 arasında olmalı"))
                if (users.accessTokenMinutes !in 1..1440) error("auth.users.accessTokenMinutes", tr("Erişim token'ı ömrü 1 dakika ile 1 gün arasında olmalı"))
                if (users.refreshTokenDays !in 1..365) error("auth.users.refreshTokenDays", tr("Yenileme token'ı ömrü 1-365 gün olmalı"))
            }
            val extraNames = mutableSetOf<String>()
            users.extraFields.forEachIndexed { i, f ->
                val path = "auth.users.extraFields[$i]"
                val label = tr("Kullanıcı alanı '{0}'", f.name)
                if (!IDENT.matches(f.name)) error("$path.name", tr("{0}: ad geçersiz (küçük harf, rakam, _ ; harfle başlamalı)", label))
                if (f.name in SYSTEM_FIELDS || f.name in SystemCollections.USER_FIELDS) {
                    error("$path.name", tr("{0}: hazır alanlarla çakışıyor ({1})", label, (SYSTEM_FIELDS + SystemCollections.USER_FIELDS).joinToString()))
                }
                if (!extraNames.add(f.name)) error("$path.name", tr("{0} tekrar ediyor", label))
                if (f.type == FieldType.RELATION || f.collection != null || f.onDelete != null) {
                    error("$path.type", tr("{0}: kullanıcı alanları ilişki olamaz", label))
                }
                if (f.type == FieldType.FILE) error("$path.type", tr("{0}: kullanıcı alanları dosya olamaz", label))
                fieldValueRules(path, label, f, users.extraFields)
            }
            users.publicFields.forEachIndexed { i, name ->
                if (name !in extraNames) error("auth.users.publicFields[$i]", tr("Herkese görünen alan '{0}' kullanıcı alanlarında yok", name))
            }
        }

        with(config.database) {
            when (engine) {
                DbEngine.SQLITE -> if (!DB_FILE.matches(file)) {
                    error("database.file", tr("Veritabanı dosya adı yalnızca harf, rakam, '.', '_' ve '-' içerebilir"))
                }
                DbEngine.POSTGRES -> {
                    if (url.isNullOrBlank()) error("database.url", tr("database.engine=postgres için database.url gerekli"))
                    else if (!url.startsWith("jdbc:postgresql://")) error("database.url", tr("database.url jdbc:postgresql:// ile başlamalı"))
                    else if (INSECURE_SSL.containsMatchIn(url)) {
                        error("database.url", tr("Sertifikayı doğrulamayan SSL (NonValidatingFactory) kullanılamaz; sslfactory parametresini kaldırın"))
                    }
                    else if (createIfMissing) {
                        val name = PostgresUrl.databaseName(url)
                        if (name == null || !IDENT.matches(name)) {
                            error(
                                "database.name",
                                tr("Oluşturulacak veritabanının adı küçük harf, rakam ve _ içerebilir, harfle başlamalı (adreste: '{0}')", name.orEmpty()),
                            )
                        }
                    }
                    if (user.isNullOrBlank()) error("database.user", tr("database.engine=postgres için database.user gerekli"))
                }
            }
        }

        with(config.backup) {
            if (enabled) {
                runCatching { dev.droidserver.core.script.CronSchedule.parse(schedule) }.exceptionOrNull()?.let {
                    error("backup.schedule", tr("Yedek zamanı geçersiz: {0}", it.message))
                }
            }
            if (keep !in 1..365) error("backup.keep", tr("Saklanacak yedek sayısı 1-365 arasında olmalı"))
            remote?.let { r ->
                val at = "backup.remote"
                if (!r.url.startsWith("https://") && !r.url.startsWith("http://")) error("$at.url", tr("Depo adresi http:// veya https:// ile başlamalı"))
                if (r.username.isBlank()) error("$at.username", if (r.kind == RemoteKind.S3) tr("Erişim anahtarı kimliği gerekli") else tr("Kullanıcı adı gerekli"))
                if (r.password.isBlank()) error("$at.password", if (r.kind == RemoteKind.S3) tr("Gizli anahtar gerekli") else tr("Parola (uygulama parolası) gerekli"))
                if (r.kind == RemoteKind.S3 && r.bucket.isNullOrBlank()) error("$at.bucket", tr("Kova adı gerekli"))
                if (!ConfigSecrets.isRef(r.passphrase) && r.passphrase.length < 12) error("$at.passphrase", tr("Şifreleme parolası en az 12 karakter olmalı"))
                if (r.keep !in 1..1000) error("$at.keep", tr("Depoda tutulacak yedek sayısı 1-1000 olmalı"))
            }
        }
        with(config.firewall) {
            allow.forEachIndexed { i, a -> if (dev.droidserver.core.net.Cidr.parse(a) == null) error("firewall.allow[$i]", tr("Geçersiz adres veya aralık: '{0}' (örn. 203.0.113.7 veya 192.168.1.0/24)", a)) }
            deny.forEachIndexed { i, a -> if (dev.droidserver.core.net.Cidr.parse(a) == null) error("firewall.deny[$i]", tr("Geçersiz adres veya aralık: '{0}' (örn. 203.0.113.7 veya 192.168.1.0/24)", a)) }
            userRequestsPerMinute?.let { if (it < 1) error("firewall.userRequestsPerMinute", tr("Kullanıcı başına sınır en az 1 olmalı")) }
        }
        config.auth.keys.forEachIndexed { i, k -> k.requestsPerMinute?.let { if (it < 1) error("auth.keys[$i].requestsPerMinute", tr("'{0}': dakika sınırı en az 1 olmalı", k.name)) } }
        if (config.audit.keepDays !in 7..3650) error("audit.keepDays", tr("Denetim kaydı 7-3650 gün saklanabilir"))
        with(config.replication) {
            if (keepDays !in 1..90) error("replication.keepDays", tr("Değişiklik günlüğü 1-90 gün saklanabilir"))
            replicaOf?.let { r ->
                if (source) error("replication.replicaOf", tr("Bir sunucu hem kopya hem kopyalanabilir kaynak olamaz"))
                if (!(r.url.startsWith("http://") || r.url.startsWith("https://")) || runCatching { java.net.URI(r.url).host }.getOrNull().isNullOrBlank()) {
                    error("replication.replicaOf.url", tr("Ana sunucunun API adresi, örn. http://192.168.1.20:8080/api"))
                }
                if (r.key.isBlank()) error("replication.replicaOf.key", tr("Ana sunucunun yönetici anahtarı gerekli"))
                if (r.intervalSec !in 1..3_600) error("replication.replicaOf.intervalSec", tr("Eşitleme aralığı 1 sn - 1 saat olmalı"))
            }
            with(failover) {
                if (afterSec !in 30..86_400) error("replication.failover.afterSec", tr("Devralma süresi 30 sn - 1 gün olmalı"))
                witness?.let { w ->
                    if (!(w.startsWith("http://") || w.startsWith("https://")) || runCatching { java.net.URI(w).host }.getOrNull().isNullOrBlank()) {
                        error("replication.failover.witness", tr("Tanık adresi http:// veya https:// ile başlamalı, örn. http://192.168.1.1"))
                    }
                }
                if (auto && replicaOf == null && !source) error("replication.failover.auto", tr("Otomatik devralma yalnızca kopyada veya kopyalanabilir sunucuda anlamlıdır"))
            }
        }
        with(config.alerts) {
            email.forEachIndexed { i, a ->
                if (!dev.droidserver.core.mail.SmtpClient.EMAIL.matches(a)) error("alerts.email[$i]", tr("Geçersiz e-posta adresi: '{0}'", a))
            }
            if (enabled && email.isNotEmpty() && !config.email.enabled) error("alerts.email", tr("E-postayla uyarı için önce e-posta (SMTP) ayarlarını açın"))
            telegram?.let { t ->
                if (t.botToken.isBlank()) error("alerts.telegram.botToken", tr("Bot token'ı gerekli (@BotFather verir)"))
                if (!TELEGRAM_CHAT.matches(t.chatId)) error("alerts.telegram.chatId", tr("Sohbet kimliği sayı (örn. 123456789) veya @kanal adı olmalı"))
                // Komut gönderen sohbetin kimliği her zaman sayıdır (kanala komut yazılamaz).
                if (t.adminChatId != null && !TELEGRAM_ADMIN_CHAT.matches(t.adminChatId)) {
                    error("alerts.telegram.adminChatId", tr("Yönetici sohbetinin kimliği sayı olmalı (örn. 123456789)"))
                }
            }
            if (diskFreeMb !in 50..1_000_000) error("alerts.diskFreeMb", tr("Disk eşiği 50 MB - 1 TB olmalı"))
            if (errorRatePercent !in 1..100) error("alerts.errorRatePercent", tr("Hata oranı eşiği 1-100 olmalı"))
            if (certificateDays !in 1..60) error("alerts.certificateDays", tr("Sertifika eşiği 1-60 gün olmalı"))
            if (batteryTempC !in 30..70) error("alerts.batteryTempC", tr("Pil sıcaklığı eşiği 30-70 °C olmalı"))
            if (repeatHours !in 1..168) error("alerts.repeatHours", tr("Hatırlatma aralığı 1 saat - 7 gün olmalı"))
            quietHours?.let { q ->
                if (!CLOCK.matches(q.from)) error("alerts.quietHours.from", tr("Saat SS:dd biçiminde olmalı, örn. 23:00"))
                if (!CLOCK.matches(q.to)) error("alerts.quietHours.to", tr("Saat SS:dd biçiminde olmalı, örn. 07:00"))
            }
        }
        if (config.adminPanel.sessionHours !in 1..720) error("adminPanel.sessionHours", tr("Panel oturumu 1 saat ile 30 gün arasında olmalı"))

        with(config.scripts) {
            if (timeoutMs !in 10..60_000) error("scripts.timeoutMs", tr("Script süre sınırı 10 ms ile 60 sn arasında olmalı"))
            if (cronTimeoutSec !in 1..3_600) error("scripts.cronTimeoutSec", tr("Zamanlanmış görev süre sınırı 1 sn ile 1 saat arasında olmalı"))
            if (jobs.concurrency !in 1..16) error("scripts.jobs.concurrency", tr("Aynı anda çalışan iş sayısı 1-16 olmalı"))
            if (jobs.keepDays !in 1..365) error("scripts.jobs.keepDays", tr("Biten işler 1-365 gün saklanabilir"))
            allowedHosts.forEachIndexed { i, h ->
                if (!HOSTNAME.matches(h.removePrefix("*.")) && h != "localhost") {
                    error("scripts.allowedHosts[$i]", tr("'{0}' adres değil; örn. api.example.com veya *.example.com", h))
                }
            }
            env.keys.forEach { k ->
                if (!Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(k)) error("scripts.env", tr("Ortam değişkeni adı geçersiz: '{0}'", k))
            }
        }

        with(config.email) {
            if (enabled) {
                if (!HOSTNAME.matches(host) && !Regex("^[0-9.]+$").matches(host)) error("email.host", tr("SMTP sunucusu adı geçersiz, örn. smtp.gmail.com"))
                if (port !in 1..65535) error("email.port", tr("Port 1-65535 aralığında olmalı"))
                if (!dev.droidserver.core.mail.SmtpClient.EMAIL.matches(from)) error("email.from", tr("Gönderen adresi geçersiz, örn. evim@gmail.com"))
                if (!username.isNullOrBlank() && password.isNullOrBlank()) error("email.password", tr("SMTP parolası gerekli (Gmail/Outlook'ta uygulama şifresi)"))
                if (!username.isNullOrBlank() && security == SmtpSecurity.NONE) error("email.security", tr("Parola şifresiz bağlantıda gönderilmez; STARTTLS veya SSL seçin"))
                publicUrl?.takeIf { it.isNotBlank() }?.let { u ->
                    if (!Regex("^https?://[^\\s/]+(:\\d+)?(/\\S*)?$").matches(u)) error("email.publicUrl", tr("Adres http:// veya https:// ile başlamalı, örn. https://evim.duckdns.org:8443"))
                }
            }
            templates.forEachIndexed { i, t ->
                val at = "email.templates[$i]"
                if (t.kind !in EmailTemplate.KINDS) error("$at.kind", tr("Şablon türü {0} olmalı", EmailTemplate.KINDS.joinToString()))
                if (t.lang != null && !Regex("^[a-z]{2}$").matches(t.lang)) error("$at.lang", tr("Dil iki harfli kod olmalı (tr, en) veya boş"))
                if (t.subject.isBlank() || '\n' in t.subject || '\r' in t.subject) error("$at.subject", tr("Konu tek satır ve boş olmamalı"))
                if (t.text.isBlank()) error("$at.text", tr("Metin boş olmamalı"))
                if (t.text.length > 20_000 || (t.html?.length ?: 0) > 100_000) error("$at.text", tr("Şablon çok uzun"))
                listOfNotNull(t.subject, t.text, t.html).flatMap { TEMPLATE_VAR.findAll(it).map { m -> m.groupValues[1] } }
                    .firstOrNull { it !in EmailTemplate.PLACEHOLDERS }
                    ?.let { error("$at.text", tr("Bilinmeyen yer tutucu {{0}}; kullanılabilenler: {1}", it, EmailTemplate.PLACEHOLDERS.joinToString { p -> "{$p}" })) }
            }
            templates.groupBy { it.kind to it.lang }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { (kind, lang) ->
                error("email.templates", tr("'{0}' türü için aynı dilde ({1}) iki şablon var", kind, lang ?: tr("varsayılan")))
            }
        }
        if (config.auth.users.requireVerified && !(config.email.enabled && config.auth.users.enabled)) {
            error("auth.users.requireVerified", tr("E-posta doğrulamasını zorunlu kılmak için e-posta gönderimini açın"))
        }
        with(config.auth.users.oauth) {
            val path = "auth.users.oauth"
            if (providers.isNotEmpty() && !config.auth.users.enabled) error("$path.providers", tr("Sosyal giriş için kullanıcı hesaplarını açın"))
            if (enabled && !Regex("^https?://[^\\s/?#]+(/[^\\s?#]*)?$").matches(publicUrl.trim())) {
                error("$path.publicUrl", tr("Sunucunun tarayıcıdan erişilen adresi gerekli, örn. https://evim.duckdns.org:8443"))
            }
            val seen = mutableSetOf<OAuthProvider>()
            providers.forEachIndexed { i, p ->
                if (!seen.add(p.provider)) error("$path.providers[$i]", tr("{0} iki kez eklenmiş", p.provider.title))
                if (p.enabled && p.clientId.isBlank()) error("$path.providers[$i].clientId", tr("{0} istemci kimliği (Client ID) gerekli", p.provider.title))
                if (p.enabled && p.clientSecret.isBlank()) error("$path.providers[$i].clientSecret", tr("{0} istemci sırrı (Client secret) gerekli", p.provider.title))
            }
            redirectUrls.forEachIndexed { i, u ->
                if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s#]+$").matches(u)) {
                    error("$path.redirectUrls[$i]", tr("Dönüş adresi şema içermeli ve # içermemeli, örn. https://uygulamam.com/giris veya uygulamam://giris"))
                }
            }
        }
        with(config.push) {
            if (enabled) {
                if (!config.auth.users.enabled) error("push.enabled", tr("Web push kullanıcıya gider: önce kullanıcı hesaplarını açın"))
                if (!Regex("^(mailto:\\S+@\\S+|https://\\S+)$").matches(subject.trim())) {
                    error("push.subject", tr("İletişim adresi gerekli: mailto:siz@example.com veya https:// adresi"))
                }
                val keyOk = runCatching { java.util.Base64.getUrlDecoder().decode(publicKey.trimEnd('=')).size == 65 }.getOrDefault(false)
                if (!keyOk || privateKey.isBlank()) error("push.publicKey", tr("VAPID anahtarları eksik veya geçersiz; uygulamada yeniden üretin"))
            }
            notify.forEachIndexed { i, r ->
                val path = "push.notify[$i]"
                val c = config.collections.firstOrNull { it.name == r.collection }
                when {
                    c == null -> error("$path.collection", tr("'{0}' koleksiyonu yok", r.collection))
                    c.ownerField == null -> error("$path.collection", tr("'{0}': bildirim kaydın sahibine gider; koleksiyonun sahip alanını seçin", r.collection))
                }
                if (r.events.isEmpty() || r.events.any { it !in WebhookConfig.EVENTS }) error("$path.events", tr("Olaylar: {0}", WebhookConfig.EVENTS.joinToString()))
                if (r.title.isBlank()) error("$path.title", tr("Bildirim başlığı gerekli"))
            }
            if (notify.isNotEmpty() && !enabled) error("push.notify", tr("Bildirim kuralları için web push'u açın"))
        }
        apiRules(config, ::error)
        if (config.webhooks.size > 20) error("webhooks", tr("En çok 20 webhook"))
        val hookNames = mutableSetOf<String>()
        config.webhooks.forEachIndexed { i, h ->
            val path = "webhooks[$i]"
            if (!Regex("^[A-Za-z0-9_-]{1,40}$").matches(h.name)) error("$path.name", tr("Webhook adı harf, rakam, - ve _ olmalı (en çok 40)"))
            else if (!hookNames.add(h.name)) error("$path.name", tr("'{0}' adı iki kez kullanılmış", h.name))
            if (!Regex("^https?://[^\\s/?#]+(/\\S*)?$").matches(h.url)) error("$path.url", tr("Adres http:// veya https:// ile başlamalı"))
            h.collections.filter { c -> config.collections.none { it.name == c } }.forEach { error("$path.collections", tr("'{0}' koleksiyonu yok", it)) }
            if (h.events.isEmpty() || h.events.any { it !in WebhookConfig.EVENTS }) error("$path.events", tr("Olaylar: {0}", WebhookConfig.EVENTS.joinToString()))
        }
        if (config.realtime.maxConnections !in 1..1000) error("realtime.maxConnections", tr("Canlı bağlantı sınırı 1-1000 aralığında olmalı"))
        with(config.static) {
            if (enabled) {
                if (!path.startsWith("/") || path.length > 1 && path.endsWith("/") || path.contains("..") || path.contains("//")) {
                    error("static.path", tr("Site yolu '/' ile başlamalı ve '/' ile bitmemeli, örn. / veya /site"))
                } else {
                    val base = config.server.basePath
                    if (StaticConfig.underPath(path, base)) error("static.path", tr("Site yolu API yolunun ({0}) altında olamaz", base))
                }
            }
        }

        val access = config.access
        if (access.mode.public && config.auth.mode == AuthMode.NONE && !access.allowNoAuth) {
            error(
                "auth.mode",
                tr("Sunucu internete açık ({0}): kimlik doğrulama seçin ya da Erişim adımında " +
                    "kimlik doğrulamasız açmayı bilerek onaylayın", access.mode.title),
            )
        }
        if (access.mode == AccessMode.DIRECT) {
            val port = access.direct.port
            if (port !in 1024..65535) error("access.direct.port", tr("HTTPS portu 1024-65535 aralığında olmalı"))
            else if (port == config.server.port) error("access.direct.port", tr("HTTPS portu sunucu portundan ({0}) farklı olmalı", config.server.port))
            access.direct.ddns?.let { d ->
                if (!DDNS_DOMAIN.matches(d.domain)) error("access.direct.ddns.domain", tr("DuckDNS adı yalnızca küçük harf, rakam ve '-' içerebilir (örn. evim için evim.duckdns.org)"))
                if (d.token.isBlank()) error("access.direct.ddns.token", tr("DuckDNS token'ı gerekli"))
            }
        }
        if (access.mode == AccessMode.CLOUDFLARE) {
            access.cloudflare.hostname?.takeIf { it.isNotBlank() }?.let { h ->
                if (!HOSTNAME.matches(h)) error("access.cloudflare.hostname", tr("Adres alan adı olmalı, örn. api.example.com (https:// olmadan)"))
            }
        }

        val names = mutableSetOf<String>()
        config.collections.forEachIndexed { ci, c ->
            val path = "collections[$ci]"
            if (!IDENT.matches(c.name)) error("$path.name", tr("Koleksiyon adı geçersiz: '{0}' (küçük harf, rakam, _ ; harfle başlamalı)", c.name))
            if (!names.add(c.name)) error("$path.name", tr("Koleksiyon adı tekrar ediyor: '{0}'", c.name))
            if (c.fields.isEmpty()) error("$path.fields", tr("'{0}' koleksiyonunda en az bir alan olmalı", c.name))

            val fieldNames = mutableSetOf<String>()
            c.fields.forEachIndexed { fi, f ->
                val fieldPath = "$path.fields[$fi]"
                if (!IDENT.matches(f.name)) error("$fieldPath.name", tr("'{0}.{1}' alan adı geçersiz", c.name, f.name))
                if (f.name in SYSTEM_FIELDS) error("$fieldPath.name", tr("'{0}.{1}' sistem alanı adıyla çakışıyor ({2})", c.name, f.name, SYSTEM_FIELDS))
                if (!fieldNames.add(f.name)) error("$fieldPath.name", tr("'{0}.{1}' alanı tekrar ediyor", c.name, f.name))
                val label = "'${c.name}.${f.name}'"
                fieldValueRules(fieldPath, label, f, c.fields)
                if (f.type == FieldType.RELATION) {
                    val target = f.collection
                    when {
                        target.isNullOrBlank() -> error("$fieldPath.collection", tr("{0} ilişki alanı için hedef koleksiyon seçin", label))
                        target == SystemCollections.USERS -> if (!config.auth.users.enabled) {
                            error("$fieldPath.collection", tr("{0} kullanıcılara bağlı; önce kullanıcı hesaplarını açın", label))
                        }
                        target == SystemCollections.TEAMS -> if (!config.auth.users.enabled || !config.auth.users.teams.enabled) {
                            error("$fieldPath.collection", tr("{0} takımlara bağlı; önce takımları açın (Kullanıcılar adımı)", label))
                        }
                        config.collections.none { it.name == target } ->
                            error("$fieldPath.collection", tr("{0}: hedef koleksiyon '{1}' yok", label, target))
                    }
                    if (f.required && f.onDelete == OnDelete.SET_NULL) {
                        error("$fieldPath.onDelete", tr("{0} zorunlu; hedef silinince boşaltılamaz (cascade veya restrict seçin)", label))
                    }
                } else if (f.collection != null || f.onDelete != null) {
                    error("$fieldPath.collection", tr("{0} ilişki alanı değil; hedef koleksiyon ve silme davranışı yalnızca relation tipinde kullanılır", label))
                }
                fileRules(fieldPath, label, f)
                f.ai?.takeIf { f.type == FieldType.FILE }?.let { a ->
                    if (!config.ai.enabled) error("$fieldPath.ai", tr("{0}: görsel etiketleme ve OCR için Yapay zekâ'yı açın", label))
                    if (a.tags == null && a.text == null) error("$fieldPath.ai", tr("{0}: etiket veya metin alanından birini seçin", label))
                    a.tags?.let { t ->
                        val target = c.fields.firstOrNull { it.name == t }
                        if (target == null || target.type !in setOf(FieldType.TEXT, FieldType.MULTISELECT, FieldType.JSON) || target.encrypted || target.computed != null) {
                            error("$fieldPath.ai.tags", tr("{0}: etiketler metin, çoklu seçim veya JSON alanına yazılır ('{1}')", label, t))
                        }
                    }
                    a.text?.let { t ->
                        val target = c.fields.firstOrNull { it.name == t }
                        if (target == null || target.type != FieldType.TEXT || target.encrypted || target.computed != null) {
                            error("$fieldPath.ai.text", tr("{0}: görseldeki metin bir metin alanına yazılır ('{1}')", label, t))
                        }
                    }
                }
                if (f.index && (f.type == FieldType.FILE || f.type == FieldType.JSON)) {
                    error("$fieldPath.index", tr("{0}: dosya ve JSON alanları indekslenemez", label))
                }
            }

            c.search.forEachIndexed { i, name ->
                val f = c.fields.firstOrNull { it.name == name }
                when {
                    f == null -> error("$path.search[$i]", tr("'{0}': arama alanı '{1}' yok", c.name, name))
                    f.type != FieldType.TEXT -> error("$path.search[$i]", tr("'{0}': yalnızca metin alanlarında aranabilir ('{1}' {2})", c.name, name, f.type.name.lowercase()))
                    f.encrypted -> error("$path.search[$i]", tr("'{0}': şifreli alanda aranamaz ('{1}')", c.name, name))
                }
            }
            if (c.search.size > 10) error("$path.search", tr("'{0}': en çok 10 arama alanı", c.name))
            if (c.fullText && c.search.isEmpty()) error("$path.fullText", tr("'{0}': tam metin dizini için önce aranabilir alan seçin", c.name))

            Rules.Op.entries.forEach { op ->
                val rule = c.rules[op]
                if ((rule == Rule.AUTH || rule == Rule.OWNER || rule == Rule.TEAM) && !config.auth.users.enabled) {
                    error(
                        "$path.rules.${op.key}",
                        tr("'{0}' {1} kuralı \"{2}\" kullanıcı hesabı ister; önce kullanıcı hesaplarını açın", c.name, op.title.lowercase(), rule.title),
                    )
                }
            }
            if (Rule.TEAM in c.rules.all && c.teamField == null) {
                error("$path.teamField", tr("'{0}': \"{1}\" kuralı için kaydın takımını tutan alanı seçin", c.name, Rule.TEAM.title))
            }
            c.teamField?.let { team ->
                val f = c.fields.firstOrNull { it.name == team }
                if (f == null || f.type != FieldType.RELATION || f.collection != SystemCollections.TEAMS) {
                    error("$path.teamField", tr("'{0}': takım alanı '{1}' takımlara ({2}) bağlı bir ilişki alanı olmalı", c.name, team, SystemCollections.TEAMS))
                }
            }
            if (Rule.OWNER in c.rules.all && c.ownerField == null) {
                error("$path.ownerField", tr("'{0}': \"{1}\" kuralı için kaydın sahibini tutan alanı seçin", c.name, Rule.OWNER.title))
            }
            c.ownerField?.let { owner ->
                val f = c.fields.firstOrNull { it.name == owner }
                if (f == null || f.type != FieldType.RELATION || f.collection != SystemCollections.USERS) {
                    error("$path.ownerField", tr("'{0}': sahip alanı '{1}' kullanıcılara ({2}) bağlı bir ilişki alanı olmalı", c.name, owner, SystemCollections.USERS))
                }
            }
            val roleNames = config.auth.users.roles.map { it.name }.toSet()
            c.rules.roles.forEach { (key, names) ->
                val rolePath = "$path.rules.roles.$key"
                if (Rules.Op.entries.none { it.key == key }) error(rolePath, tr("'{0}': bilinmeyen işlem '{1}' ({2})", c.name, key, Rules.Op.entries.joinToString { it.key }))
                names.filter { it !in roleNames }.forEach { error(rolePath, tr("'{0}': '{1}' rolü tanımlı değil (Kullanıcılar adımında ekleyin)", c.name, it)) }
            }
            c.rules.conditions.forEach { (key, cond) ->
                val op = Rules.Op.entries.firstOrNull { it.key == key }
                val condPath = "$path.rules.conditions.$key"
                if (op == null) {
                    error(condPath, tr("'{0}': bilinmeyen işlem '{1}' ({2})", c.name, key, Rules.Op.entries.joinToString { it.key }))
                    return@forEach
                }
                val label = tr("'{0}' {1} koşulu", c.name, op.title.lowercase())
                if (c.rules[op] !in setOf(Rule.PUBLIC, Rule.AUTH, Rule.OWNER)) {
                    error(condPath, tr("{0} yalnızca \"{1}\", \"{2}\" veya \"{3}\" kuralında kullanılır (yönetici koşula bağlı değildir)", label, Rule.PUBLIC.title, Rule.AUTH.title, Rule.OWNER.title))
                }
                try {
                    RuleConditions.parse(c, cond.filter, 1L)
                } catch (e: ApiException) {
                    error(condPath, "$label: ${e.message}")
                }
                if (cond.owner) {
                    if (op == Rules.Op.CREATE) error(condPath, tr("{0}: oluşturmada sahip istisnası olmaz (yeni kaydın sahibi zaten istekteki kullanıcı)", label))
                    else if (c.ownerField == null) error(condPath, tr("{0}: sahip istisnası için kaydın sahibini tutan alanı seçin", label))
                }
            }
            c.fields.forEachIndexed { fi, f ->
                val v = f.visibility ?: return@forEachIndexed
                val fieldPath = "$path.fields[$fi].visibility"
                val label = "'${c.name}.${f.name}'"
                when {
                    f.name == c.ownerField -> error(fieldPath, tr("{0} kaydın sahibi alanı; gizlenemez", label))
                    v == FieldVisibility.OWNER && c.ownerField == null -> error(fieldPath, tr("{0} yalnızca sahibine görünecekse koleksiyonun sahip alanını seçin", label))
                }
                if (f.name in c.search) error(fieldPath, tr("{0} gizli alan aranabilir olamaz (arama sonucu değeri ele verir)", label))
                val nonAdminCreate = c.rules.create in setOf(Rule.PUBLIC, Rule.AUTH, Rule.OWNER)
                if (v == FieldVisibility.ADMIN && f.required && f.default == null && f.type != FieldType.FILE && nonAdminCreate) {
                    error(fieldPath, tr("{0} yalnızca yöneticiye görünüyor ve zorunlu: kullanıcılar yazamayacağı için varsayılan değer girin", label))
                }
            }
        }

        val componentIds = mutableSetOf<String>()
        config.components.forEachIndexed { i, comp ->
            if (!IDENT.matches(comp.id.replace('-', '_'))) error("components[$i].id", tr("Bileşen kimliği geçersiz: '{0}'", comp.id))
            if (!componentIds.add(comp.id)) error("components[$i].id", tr("Bileşen tekrar ediyor: '{0}'", comp.id))
            comp.env.keys.forEach { k -> if (!EnvFile.NAME.matches(k)) error("components[$i].env", tr("Ortam değişkeni adı geçersiz: '{0}'", k)) }
        }

        envRules(config, ::error)
        apps(config, ::error)
        automations(config, ::error)
        forms(config, ::error)
        pages(config, ::error)
        dashboards(config, ::error)
        payments(config, ::error)
        ai(config, ::error)
        webdav(config, ::error)
        mqtt(config, ::error)
        sensors(config, ::error)
    }

    private fun sensors(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.sensors.size > 50) error("sensors", tr("En çok 50 sensör olabilir"))
        val byName = config.collections.associateBy { it.name }
        val keys = mutableSetOf<String>()
        config.sensors.forEachIndexed { i, s ->
            val at = "sensors[$i]"
            val f = byName[s.collection]?.fields?.firstOrNull { it.name == s.field }
            when {
                s.collection !in byName -> error("$at.collection", tr("'{0}' koleksiyonu yok", s.collection))
                f == null -> error("$at.field", tr("'{0}' koleksiyonunda '{1}' alanı yok", s.collection, s.field))
                f.type !in setOf(FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN) -> error("$at.field", tr("Sensör alanı sayı veya evet/hayır olmalı: {0}", s.key))
                f.encrypted -> error("$at.field", tr("Şifreli alan sensör olamaz: {0}", s.key))
            }
            if (!keys.add(s.key)) error("$at.field", tr("'{0}' sensörü iki kez eklenmiş", s.key))
            if (s.min != null && s.max != null && s.min >= s.max) error("$at.max", tr("Üst eşik alt eşikten büyük olmalı"))
        }
        val ha = config.mqtt.homeAssistant
        if (ha.enabled) {
            if (!config.mqtt.enabled) error("mqtt.homeAssistant.enabled", tr("Home Assistant keşfi için MQTT'yi açın"))
            if (!dev.droidserver.core.mqtt.MqttBroker.validFilter(ha.prefix) || '+' in ha.prefix || '#' in ha.prefix) {
                error("mqtt.homeAssistant.prefix", tr("Keşif öneki joker içermeyen bir konu olmalı, örn. homeassistant"))
            }
        }
    }

    private fun mqtt(config: ServerConfig, error: (String, String) -> Unit) {
        val m = config.mqtt
        if (!m.enabled) return
        if (m.port !in 1..65_535) error("mqtt.port", tr("Port 1-65535 olmalı"))
        val taken = listOfNotNull(config.server.port, config.access.direct.port.takeIf { config.access.mode == AccessMode.DIRECT })
        if (m.port in taken) error("mqtt.port", tr("MQTT portu sunucunun portuyla aynı olamaz ({0})", m.port))
        if (m.tls) {
            if (m.tlsPort !in 1..65_535) error("mqtt.tlsPort", tr("Port 1-65535 olmalı"))
            else if (m.tlsPort in taken || (m.plain && m.tlsPort == m.port)) error("mqtt.tlsPort", tr("MQTT TLS portu başka bir portla aynı olamaz ({0})", m.tlsPort))
        }
        if (!m.plain && !m.tls) error("mqtt.plain", tr("Düz bağlantıyı kapatmak için önce TLS'i açın"))
        if (!Regex("^[A-Za-z0-9._-]{1,32}$").matches(m.username)) error("mqtt.username", tr("Kullanıcı adı harf, rakam, . _ - olmalı (en çok 32)"))
        // Cihaz listesi varken ortak parola boş bırakılabilir (ortak girişi kapatır).
        if ((m.devices.isEmpty() || m.password.isNotEmpty()) && !ConfigSecrets.isRef(m.password) && m.password.length < 8) {
            error("mqtt.password", tr("Parola en az 8 karakter olmalı"))
        }
        val seen = mutableSetOf<String>()
        m.devices.forEachIndexed { i, d ->
            val path = "mqtt.devices[$i]"
            if (!Regex("^[A-Za-z0-9._-]{1,32}$").matches(d.name)) error("$path.name", tr("Cihaz adı harf, rakam, . _ - olmalı (en çok 32)"))
            if (!seen.add(d.name) || (d.name == m.username && m.password.isNotEmpty())) error("$path.name", tr("'{0}' adı birden çok kez kullanılmış", d.name))
            if (!ConfigSecrets.isRef(d.password) && d.password.length < 8) error("$path.password", tr("Parola en az 8 karakter olmalı"))
        }
        if (m.devices.size > 200) error("mqtt.devices", tr("En çok 200 cihaz eklenebilir"))
        if (m.scenes.size > 50) error("mqtt.scenes", tr("En çok 50 sahne olabilir"))
        val scenes = mutableSetOf<String>()
        m.scenes.forEachIndexed { i, sc ->
            val path = "mqtt.scenes[$i]"
            if (!Regex("^[\\p{L}\\p{N}_ -]{1,32}$").matches(sc.name) || sc.name.isBlank()) error("$path.name", tr("Sahne adı harf, rakam, boşluk, - ve _ olmalı (en çok 32)"))
            else if (!scenes.add(sc.name)) error("$path.name", tr("'{0}' adı birden çok kez kullanılmış", sc.name))
            if (sc.messages.isEmpty() || sc.messages.size > 20) error("$path.messages", tr("{0}: sahnede 1-20 mesaj olmalı", sc.name))
            sc.messages.forEachIndexed { j, msg ->
                if (msg.topic.isBlank() || '+' in msg.topic || '#' in msg.topic) error("$path.messages[$j].topic", tr("{0}: konu boş veya joker içeriyor", sc.name))
            }
        }
        if (m.maxClients !in 1..1_000) error("mqtt.maxClients", tr("Bağlantı sınırı 1-1000 olmalı"))
        if (!dev.droidserver.core.mqtt.MqttBroker.validFilter(m.eventPrefix) || '+' in m.eventPrefix || '#' in m.eventPrefix) {
            error("mqtt.eventPrefix", tr("Olay öneki joker içermeyen bir konu olmalı, örn. droidserver"))
        }
        val byName = config.collections.associateBy { it.name }
        m.events.forEachIndexed { i, c -> if (c !in byName) error("mqtt.events[$i]", tr("'{0}' koleksiyonu yok", c)) }
        m.routes.forEachIndexed { i, r ->
            val path = "mqtt.routes[$i]"
            if (!dev.droidserver.core.mqtt.MqttBroker.validFilter(r.topic)) error("$path.topic", tr("Geçersiz konu: '{0}' (# yalnızca sonda; örn. sensors/+/temp)", r.topic))
            val c = byName[r.collection]
            if (c == null) {
                error("$path.collection", tr("'{0}' koleksiyonu yok", r.collection))
                return@forEachIndexed
            }
            for ((field, key) in listOf(r.valueField to "valueField", r.topicField to "topicField")) {
                val f = field?.let { n -> c.fields.firstOrNull { it.name == n } }
                if (field != null && (f == null || f.type == FieldType.FILE || f.type == FieldType.RELATION)) {
                    error("$path.$key", tr("'{0}.{1}' alanı yok veya dosya/ilişki alanı", c.name, field))
                }
            }
        }
    }

    private fun webdav(config: ServerConfig, error: (String, String) -> Unit) {
        val dav = config.webdav
        if (!dav.enabled) return
        if (!WebDavConfig.PATH.matches(dav.path)) error("webdav.path", tr("Yol / ile başlamalı ve harf, rakam, . _ ~ - içermeli; örn. /dav"))
        else {
            val reserved = buildList {
                add(config.server.basePath.trimEnd('/').ifEmpty { "/" } to tr("API yolu"))
                add("/_admin" to tr("web paneli"))
                add("/_auth" to tr("hesap sayfaları"))
                add(FormConfig.PATH to tr("formlar"))
                config.apps.forEach { add(it.publicPath to tr("'{0}' uygulaması", it.name)) }
            }
            reserved.firstOrNull { (p, _) -> StaticConfig.underPath(dav.path, p) || StaticConfig.underPath(p, dav.path) }?.let { (_, what) ->
                error("webdav.path", tr("{0} yolu {1} ile çakışıyor", dav.path, what))
            }
        }
        if (!Regex("^[A-Za-z0-9._-]{1,32}$").matches(dav.username)) error("webdav.username", tr("Kullanıcı adı harf, rakam, . _ - olmalı (en çok 32)"))
        if (dav.password.length < 8) error("webdav.password", tr("Parola en az 8 karakter olmalı"))
        if (dav.maxFileMb !in 1..20_480) error("webdav.maxFileMb", tr("Dosya boyutu sınırı 1-20480 MB olmalı"))
    }

    private fun ai(config: ServerConfig, error: (String, String) -> Unit) {
        val ai = config.ai
        if (!ai.enabled) return
        if (ai.apiKey.isBlank() && !ai.customUrl && ai.provider != AiProvider.LOCAL) error("ai.apiKey", tr("{0} API anahtarı girin ({1})", ai.provider.title, ai.provider.keyUrl.substringAfter("://").substringBefore('/')))
        val model = ai.effectiveModel
        when (ai.provider) {
            AiProvider.CLAUDE -> if (!Regex("^claude-[a-z0-9.-]{1,60}$").matches(model)) error("ai.model", tr("Model kimliği {0} ile başlamalı, örn. {1}", "claude-", ai.provider.defaultModel))
            AiProvider.GEMINI -> if (!Regex("^gemini-[a-z0-9.-]{1,60}$").matches(model)) error("ai.model", tr("Model kimliği {0} ile başlamalı, örn. {1}", "gemini-", ai.provider.defaultModel))
            AiProvider.OPENAI -> if (!Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$").matches(model)) error("ai.model", tr("Model kimliği geçersiz: {0}", model))
            AiProvider.LOCAL -> if (!LOCAL_MODEL.matches(model)) error("ai.model", tr("Telefonda kullanılacak modeli seçin (Yapay zekâ → Yerel modeller)"))
        }
        if (ai.url.isNotBlank()) {
            if (ai.provider != AiProvider.OPENAI) error("ai.url", tr("Uç adresi yalnızca OpenAI uyumlu sağlayıcıda kullanılır"))
            else if (!Regex("^https?://[^\\s/?#]+(/\\S*)?$").matches(ai.url)) error("ai.url", tr("Adres http:// veya https:// ile başlamalı"))
        }
        if (ai.dailyLimit !in 0..100_000) error("ai.dailyLimit", tr("Günlük sınır 0-100000 olmalı (0: sınırsız)"))
        if (ai.maxTokens !in 256..64_000) error("ai.maxTokens", tr("Yanıt uzunluğu 256-64000 token olmalı"))
        if (ai.system.length > 4000) error("ai.system", tr("Sistem talimatı en çok 4000 karakter olabilir"))
        ai.embeddings?.let { e ->
            if (e.url.isNotBlank() && !Regex("^https?://[^\\s/?#]+(/\\S*)?$").matches(e.url)) error("ai.embeddings.url", tr("Adres http:// veya https:// ile başlamalı"))
            if (e.provider == EmbeddingProvider.VOYAGE && e.apiKey.isBlank()) error("ai.embeddings.apiKey", tr("Voyage AI anahtarı girin"))
            if (e.provider == EmbeddingProvider.LOCAL && !LOCAL_MODEL.matches(e.model)) error("ai.embeddings.model", tr("Telefonda kullanılacak gömme modelini seçin (Yapay zekâ → Yerel modeller)"))
        }
        if (ai.semantic.isNotEmpty() && ai.embeddings == null) error("ai.embeddings", tr("Anlamsal arama için gömme servisini seçin"))
        if (ai.semantic.size > 10) error("ai.semantic", tr("En çok 10 koleksiyonda anlamsal arama olabilir"))
        val byName = config.collections.associateBy { it.name }
        val seen = mutableSetOf<String>()
        ai.semantic.forEachIndexed { i, idx ->
            val at = "ai.semantic[$i]"
            val c = byName[idx.collection]
            if (c == null) {
                error("$at.collection", tr("'{0}' koleksiyonu yok", idx.collection))
                return@forEachIndexed
            }
            if (!seen.add(c.name)) error("$at.collection", tr("'{0}' iki kez eklenmiş", c.name))
            if (idx.fields.isEmpty()) error("$at.fields", tr("{0}: en az bir alan seçin", c.name))
            idx.fields.forEach { name ->
                val f = c.fields.firstOrNull { it.name == name }
                when {
                    f == null -> error("$at.fields", tr("'{0}' koleksiyonunda '{1}' alanı yok", c.name, name))
                    f.type !in SEMANTIC_TYPES -> error("$at.fields", tr("{0}.{1}: yalnızca metin ve seçim alanları dizinlenebilir", c.name, name))
                    f.encrypted -> error("$at.fields", tr("{0}.{1}: şifreli alan dizinlenemez (metni dış servise gider)", c.name, name))
                    f.visibility != null -> error("$at.fields", tr("{0}.{1}: görünürlüğü kısıtlı alan dizinlenemez (arama sonucu içeriğini sızdırır)", c.name, name))
                }
            }
        }
    }

    private val SEMANTIC_TYPES = setOf(FieldType.TEXT, FieldType.SELECT, FieldType.MULTISELECT)

    private fun forms(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.forms.size > 30) error("forms", tr("En çok 30 form olabilir"))
        val names = mutableSetOf<String>()
        val byName = config.collections.associateBy { it.name }
        val base = config.server.basePath.trimEnd('/')
        if (config.forms.isNotEmpty() && (base == FormConfig.PATH || base.startsWith(FormConfig.PATH + "/"))) {
            error("server.basePath", tr("API yolu {0} olamaz (formlar orada yayınlanır)", FormConfig.PATH))
        }
        config.forms.forEachIndexed { i, f ->
            val at = "forms[$i]"
            if (!FormConfig.NAME.matches(f.name)) error("$at.name", tr("Form adı küçük harf, rakam ve - olmalı (en çok 40): '{0}'", f.name))
            else if (!names.add(f.name)) error("$at.name", tr("'{0}' adı iki kez kullanılmış", f.name))
            if (f.title.isBlank()) error("$at.title", tr("{0}: form başlığı boş", f.name))
            if (f.maxPerTenMinutes !in 1..1000) error("$at.maxPerTenMinutes", tr("{0}: gönderim sınırı 1-1000 olmalı", f.name))
            f.redirect?.takeIf { it.isNotBlank() }?.let { r ->
                val ok = Regex("^https?://[^\\s/?#]+(/\\S*)?$").matches(r) || (r.startsWith("/") && !r.startsWith("//") && r.none { it.isWhitespace() })
                if (!ok) error("$at.redirect", tr("{0}: yönlendirme adresi https:// ile veya / ile başlamalı", f.name))
            }
            val c = byName[f.collection]
            if (c == null) {
                error("$at.collection", tr("{0}: '{1}' koleksiyonu yok", f.name, f.collection))
                return@forEachIndexed
            }
            val seen = mutableSetOf<String>()
            f.fields.forEach { ff ->
                val field = c.fields.firstOrNull { it.name == ff.name }
                when {
                    !seen.add(ff.name) -> error("$at.fields", tr("{0}: '{1}' alanı iki kez eklenmiş", f.name, ff.name))
                    field == null -> error("$at.fields", tr("{0}: '{1}' koleksiyonunda '{2}' alanı yok", f.name, c.name, ff.name))
                    !dev.droidserver.core.api.formWritable(field) -> error("$at.fields", tr("{0}: '{1}' alanı formda olamaz (hesaplanan veya dosya alanı)", f.name, ff.name))
                }
            }
            val inForm = f.resolvedFields(c).map { it.first.name }.toSet()
            if (inForm.isEmpty()) error("$at.fields", tr("{0}: formda en az bir alan olmalı", f.name))
            c.fields.filter { it.required && it.default == null && it.computed == null && it.name !in inForm && it.name != c.ownerField }.forEach {
                error("$at.fields", tr("{0}: zorunlu '{1}' alanı formda yok; gönderimler kaydedilemez", f.name, it.name))
            }
        }
    }

    private fun pages(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.pages.size > 30) error("pages", tr("En çok 30 sayfa olabilir"))
        val base = config.server.basePath.trimEnd('/')
        if (config.pages.isNotEmpty() && (base == PageConfig.PATH || base.startsWith(PageConfig.PATH + "/"))) {
            error("server.basePath", tr("API yolu {0} olamaz (sayfalar orada yayınlanır)", PageConfig.PATH))
        }
        val names = mutableSetOf<String>()
        val byName = config.collections.associateBy { it.name }
        config.pages.forEachIndexed { i, p ->
            val at = "pages[$i]"
            if (!PageConfig.NAME.matches(p.name) || p.name == "_login" || p.name == "_logout") error("$at.name", tr("Sayfa adı küçük harf, rakam ve - olmalı (en çok 40): '{0}'", p.name))
            else if (!names.add(p.name)) error("$at.name", tr("'{0}' adı iki kez kullanılmış", p.name))
            if (p.title.isBlank()) error("$at.title", tr("{0}: sayfa başlığı boş", p.name))
            if (p.perPage !in 1..100) error("$at.perPage", tr("{0}: sayfa başına kayıt 1-100 olmalı", p.name))
            p.accent?.let { if (!PageConfig.COLOR.matches(it)) error("$at.accent", tr("{0}: renk #RRGGBB biçiminde olmalı", p.name)) }
            if (p.login && !config.auth.users.enabled) error("$at.login", tr("{0}: girişli sayfa için kullanıcı hesaplarını açın", p.name))
            val c = byName[p.collection]
            if (c == null) {
                error("$at.collection", tr("{0}: '{1}' koleksiyonu yok", p.name, p.collection))
                return@forEachIndexed
            }
            val known = c.fields.map { it.name }.toSet() + setOf("id", "created_at", "updated_at")
            (p.fields + p.detailFields).distinct().filter { it !in known }.forEach { error("$at.fields", tr("{0}: '{1}' koleksiyonunda '{2}' alanı yok", p.name, c.name, it)) }
            p.filter?.takeIf { it.isNotBlank() }?.let { f ->
                runCatching { RuleConditions.parse(c, f, null) }.onFailure { error("$at.filter", tr("{0}: süzgeç hatalı: {1}", p.name, it.message)) }
            }
        }
    }

    private fun payments(config: ServerConfig, error: (String, String) -> Unit) {
        val p = config.payments
        if (p.providers.isEmpty()) {
            if (p.plans.isNotEmpty()) error("payments.plans", tr("Abonelik planı için önce bir ödeme sağlayıcısı ekleyin"))
            return
        }
        val base = config.server.basePath.trimEnd('/')
        if (base == PaymentsConfig.PATH || base.startsWith(PaymentsConfig.PATH + "/")) {
            error("server.basePath", tr("API yolu {0} olamaz (ödeme sayfaları orada)", PaymentsConfig.PATH))
        }
        if (p.enabled) {
            // Sağlayıcı bildirimleri telefona internetten, değişmeyen bir HTTPS adresinden ulaşmalı.
            val access = config.access
            when {
                !access.mode.public -> error("payments", tr("Ödeme bildirimleri telefona internetten ulaşmalı: Erişim'de doğrudan erişim veya Cloudflare Tunnel seçin"))
                access.mode == AccessMode.CLOUDFLARE && access.cloudflare.tunnel == CloudflareTunnel.QUICK ->
                    error("payments", tr("Hızlı tünelin adresi her başlatmada değişir, ödeme bildirimleri kaybolur: kendi alan adınızla tünel kurun"))
            }
            if (!Regex("^https://[^\\s/?#]+(/[^\\s?#]*)?$").matches(p.publicUrl.trim())) {
                error("payments.publicUrl", tr("Sunucunun internetten erişilen HTTPS adresi gerekli, örn. https://ornek.duckdns.org"))
            }
        }
        val types = mutableSetOf<PaymentProvider>()
        p.providers.forEachIndexed { i, c ->
            val at = "payments.providers[$i]"
            if (!types.add(c.provider)) error("$at.provider", tr("{0} iki kez eklenmiş", c.provider.title))
            if (c.secretKey.isBlank()) error("$at.secretKey", tr("{0}: gizli anahtar gerekli", c.provider.title))
            if (c.provider == PaymentProvider.IYZICO && c.apiKey.isBlank()) error("$at.apiKey", tr("iyzico: API anahtarı gerekli"))
            if (c.provider == PaymentProvider.PADDLE && c.clientToken.isBlank()) error("$at.clientToken", tr("Paddle: istemci token'ı gerekli (Paddle → Developer tools → Authentication)"))
            if (c.provider != PaymentProvider.IYZICO && c.webhookSecret.isBlank()) {
                error("$at.webhookSecret", tr("{0}: bildirim (webhook) imza sırrı gerekli; sağlayıcının panelinde bildirim adresini ekleyince verilir", c.provider.title))
            }
        }
        val users = config.auth.users
        if (!IDENT.matches(p.statusField)) error("payments.statusField", tr("Alan adı geçersiz: '{0}'", p.statusField))
        if (p.plans.isNotEmpty() && !users.enabled) error("payments.plans", tr("Abonelikler kullanıcı hesaplarıyla çalışır; önce hesapları açın"))
        val plans = mutableSetOf<String>()
        p.plans.forEachIndexed { i, plan ->
            val at = "payments.plans[$i]"
            if (!PaymentsConfig.PLAN_NAME.matches(plan.name)) error("$at.name", tr("Plan adı küçük harf, rakam, - ve _ olmalı (en çok 40): '{0}'", plan.name))
            else if (!plans.add(plan.name)) error("$at.name", tr("'{0}' adı iki kez kullanılmış", plan.name))
            if (plan.title.isBlank()) error("$at.title", tr("{0}: plan başlığı boş", plan.name))
            if (!plan.provider.subscriptions) error("$at.provider", tr("{0}: {1} ile abonelik yok (Stripe veya Paddle)", plan.name, plan.provider.title))
            else if (plan.provider !in types) error("$at.provider", tr("{0}: {1} sağlayıcısı eklenmemiş", plan.name, plan.provider.title))
            val prefix = if (plan.provider == PaymentProvider.PADDLE) "pri_" else "price_"
            if (!plan.price.startsWith(prefix) || plan.price.length > 100) error("$at.price", tr("{0}: sağlayıcıdaki fiyat kimliği ({1}…)", plan.name, prefix))
            if (users.roles.none { it.name == plan.role }) error("$at.role", tr("'{0}' rolü tanımlı değil (Kullanıcılar adımında ekleyin)", plan.role))
        }
    }

    private fun dashboards(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.dashboards.size > 20) error("dashboards", tr("En çok 20 pano olabilir"))
        val names = mutableSetOf<String>()
        val views = config.views.associateBy { it.name }
        config.dashboards.forEachIndexed { i, d ->
            val at = "dashboards[$i]"
            if (!PageConfig.NAME.matches(d.name)) error("$at.name", tr("Pano adı küçük harf, rakam ve - olmalı (en çok 40): '{0}'", d.name))
            else if (!names.add(d.name)) error("$at.name", tr("'{0}' adı iki kez kullanılmış", d.name))
            if (d.title.isBlank()) error("$at.title", tr("{0}: pano başlığı boş", d.name))
            if (d.widgets.size > 24) error("$at.widgets", tr("{0}: en çok 24 kutu olabilir", d.name))
            d.shareToken?.let { t -> if (t.length < 16 || !t.all { it.isLetterOrDigit() || it == '-' || it == '_' }) error("$at.shareToken", tr("{0}: paylaşım anahtarı en az 16 harf/rakam olmalı", d.name)) }
            d.widgets.forEachIndexed { wi, w ->
                val wp = "$at.widgets[$wi]"
                val v = views[w.view]
                if (v == null) { error("$wp.view", tr("{0}: '{1}' görünümü yok", d.name, w.view)); return@forEachIndexed }
                if (w.width !in 1..3) error("$wp.width", tr("{0}: kutu genişliği 1-3 olmalı", d.name))
                if (w.limit !in 1..100) error("$wp.limit", tr("{0}: satır sayısı 1-100 olmalı", d.name))
                when (w.type) {
                    WidgetType.NUMBER, WidgetType.CHART -> {
                        val r = v.report
                        if (r == null) error("$wp.view", tr("{0}: sayı ve grafik kutusu özetli (rapor) bir görünüm ister: '{1}'", d.name, v.name))
                        else {
                            if (w.type == WidgetType.CHART && r.groupBy == null) error("$wp.view", tr("{0}: grafik kutusu gruplu bir rapor ister: '{1}'", d.name, v.name))
                            val metrics = setOf("count") + r.sum.map { "sum_$it" } + r.avg.map { "avg_$it" } + r.min.map { "min_$it" } + r.max.map { "max_$it" }
                            if (w.metric !in metrics) error("$wp.metric", tr("{0}: '{1}' değeri raporda yok (olanlar: {2})", d.name, w.metric, metrics.joinToString()))
                        }
                    }
                    WidgetType.TABLE -> if (v.report != null) error("$wp.view", tr("{0}: tablo kutusu raporsuz bir görünüm ister: '{1}'", d.name, v.name))
                    WidgetType.MAP -> {
                        val c = config.collections.firstOrNull { it.name == v.collection }
                        val columns = v.columns.ifEmpty { ViewService.defaultColumns(config, v) }
                        if (v.report != null || w.field !in columns || c?.fields?.any { it.name == w.field && it.type == FieldType.GEO } != true) {
                            error("$wp.field", tr("{0}: harita kutusu raporsuz görünüm ve görünümün sütunlarında bir konum alanı ister", d.name))
                        }
                    }
                }
            }
        }
    }

    private fun automations(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.automations.size > 50) error("automations", tr("En çok 50 otomasyon kuralı olabilir"))
        val names = mutableSetOf<String>()
        val byName = config.collections.associateBy { it.name }
        fun writable(c: CollectionConfig, values: Map<String, String>, at: String, rule: String) {
            if (values.isEmpty()) error("$at.values", tr("{0}: yazılacak en az bir alan seçin", rule))
            for (field in values.keys) {
                val f = c.fields.firstOrNull { it.name == field }
                when {
                    f == null -> error("$at.values", tr("{0}: '{1}' koleksiyonunda '{2}' alanı yok", rule, c.name, field))
                    f.computed != null || f.type == FieldType.FILE -> error("$at.values", tr("{0}: '{1}' alanına otomasyonla yazılamaz (hesaplanan veya dosya alanı)", rule, field))
                }
            }
        }
        config.automations.forEachIndexed { i, a ->
            val at = "automations[$i]"
            if (!Regex("^[A-Za-z0-9_-]{1,40}$").matches(a.name)) error("$at.name", tr("Kural adı harf, rakam, - ve _ olmalı (en çok 40)"))
            else if (!names.add(a.name)) error("$at.name", tr("'{0}' adı iki kez kullanılmış", a.name))
            val t = a.trigger
            val collection = t.collection?.let { name -> byName[name] ?: null.also { error("$at.trigger.collection", tr("{0}: '{1}' koleksiyonu yok", a.name, name)) } }
            if (t.onMqtt) {
                if (!config.mqtt.enabled) error("$at.trigger.mqtt", tr("{0}: MQTT tetikleyicisi için MQTT'yi açın", a.name))
                if (!dev.droidserver.core.mqtt.MqttBroker.validFilter(t.mqtt!!)) error("$at.trigger.mqtt", tr("{0}: geçersiz konu süzgeci: '{1}'", a.name, t.mqtt))
                if (t.scheduled) error("$at.trigger.mqtt", tr("{0}: MQTT tetikleyicisi zamanlamayla birlikte olamaz", a.name))
                if (!a.condition.isNullOrBlank()) error("$at.condition", tr("{0}: MQTT tetikleyicisinde koşul kullanılamaz", a.name))
            } else if (t.scheduled) {
                runCatching { dev.droidserver.core.script.CronSchedule.parse(t.schedule!!) }.exceptionOrNull()?.let {
                    error("$at.trigger.schedule", tr("{0}: zamanlama geçersiz: {1}", a.name, it.message))
                }
            } else {
                if (t.collection == null) error("$at.trigger.collection", tr("{0}: kayıt olayı için koleksiyon seçin (veya zamanlama girin)", a.name))
                if (t.events.isEmpty() || t.events.any { it !in WebhookConfig.EVENTS }) error("$at.trigger.events", tr("Olaylar: {0}", WebhookConfig.EVENTS.joinToString()))
            }
            a.condition?.takeIf { it.isNotBlank() }?.let { cond ->
                when {
                    collection == null -> error("$at.condition", tr("{0}: koşul için koleksiyon seçin", a.name))
                    RuleConditions.ME in cond -> error("$at.condition", tr("{0}: otomasyon koşulunda @me kullanılamaz", a.name))
                    else -> try {
                        RuleConditions.parse(collection, cond, null)
                    } catch (e: ApiException) {
                        error("$at.condition", tr("{0} koşulu: {1}", a.name, e.message))
                    }
                }
            }
            if (a.actions.isEmpty()) error("$at.actions", tr("{0}: en az bir eylem ekleyin", a.name))
            if (a.actions.size > 10) error("$at.actions", tr("{0}: en çok 10 eylem olabilir", a.name))
            a.actions.forEachIndexed { j, act ->
                val ap = "$at.actions[$j]"
                when (act.type) {
                    AutomationActionType.EMAIL -> {
                        if (!config.email.enabled) error(ap, tr("{0}: e-posta eylemi için e-posta gönderimini açın", a.name))
                        if (act.to.isNullOrBlank()) error("$ap.to", tr("{0}: alıcıyı girin (adres veya {e-posta alanı})", a.name))
                        if (act.subject.isNullOrBlank()) error("$ap.subject", tr("{0}: e-posta konusu boş", a.name))
                    }
                    AutomationActionType.PUSH -> {
                        if (!config.push.enabled || config.auth.users == null) error(ap, tr("{0}: bildirim eylemi için kullanıcı hesaplarını ve web push'u açın", a.name))
                        val to = act.to?.trim().orEmpty()
                        if (to.isEmpty()) error("$ap.to", tr("{0}: bildirimin gideceği kullanıcıyı seçin", a.name))
                        else if (to == AutomationAction.OWNER && t.scheduled) error("$ap.to", tr("{0}: zamanlanmış kuralda 'kaydın sahibi' kullanılamaz; bir kullanıcı kimliği girin", a.name))
                        else if (to == AutomationAction.OWNER && collection?.ownerField == null) error("$ap.to", tr("{0}: 'kaydın sahibi' için koleksiyonda sahip alanı olmalı", a.name))
                        if (act.subject.isNullOrBlank()) error("$ap.subject", tr("{0}: bildirim başlığı boş", a.name))
                    }
                    AutomationActionType.WEBHOOK -> {
                        val hook = act.webhook
                        if (hook.isNullOrBlank() || config.webhooks.none { it.name == hook }) error("$ap.webhook", tr("{0}: tanımlı bir webhook seçin", a.name))
                    }
                    AutomationActionType.MQTT -> {
                        if (!config.mqtt.enabled) error(ap, tr("{0}: MQTT eylemi için MQTT'yi açın", a.name))
                        val topic = act.topic.orEmpty()
                        if (topic.isBlank() || '+' in topic || '#' in topic) error("$ap.topic", tr("{0}: MQTT konusu boş veya joker içeriyor", a.name))
                    }
                    AutomationActionType.UPDATE -> when {
                        t.onMqtt -> error(ap, tr("{0}: MQTT tetikleyicisinde kayıt güncellenemez; kayıt oluşturun", a.name))
                        collection == null -> error(ap, tr("{0}: kayıt güncellemek için koleksiyon seçin", a.name))
                        !t.scheduled && t.events == listOf("delete") -> error(ap, tr("{0}: silinen kayıt güncellenemez", a.name))
                        else -> writable(collection, act.values, ap, a.name)
                    }
                    AutomationActionType.CREATE -> {
                        val target = act.collection?.let { byName[it] }
                        if (target == null) error("$ap.collection", tr("{0}: kaydın oluşturulacağı koleksiyonu seçin", a.name))
                        else writable(target, act.values, ap, a.name)
                    }
                    AutomationActionType.LOG -> if (act.body.isNullOrBlank()) error("$ap.body", tr("{0}: log metni boş", a.name))
                    AutomationActionType.AI -> {
                        if (!config.ai.enabled) error(ap, tr("{0}: YZ eylemi için Yapay zekâ'yı açın", a.name))
                        if (act.body.isNullOrBlank()) error("$ap.body", tr("{0}: YZ istemi boş", a.name))
                        val f = collection?.fields?.firstOrNull { it.name == act.field }
                        when {
                            t.scheduled || t.onMqtt || collection == null -> error(ap, tr("{0}: YZ eylemi yalnızca kayıt olayında çalışır", a.name))
                            t.events == listOf("delete") -> error(ap, tr("{0}: silinen kayda yazılamaz", a.name))
                            f == null || f.computed != null || f.type !in setOf(FieldType.TEXT, FieldType.SELECT, FieldType.MULTISELECT, FieldType.INTEGER, FieldType.REAL, FieldType.BOOLEAN) ->
                                error("$ap.field", tr("{0}: YZ yanıtının yazılacağı metin, seçim veya sayı alanını seçin", a.name))
                        }
                    }
                    AutomationActionType.PAYMENT -> {
                        if (!config.payments.enabled) error(ap, tr("{0}: ödeme eylemi için Ödemeler'de bir sağlayıcı açın", a.name))
                        else act.provider?.let { if (config.payments.provider(it) == null) error("$ap.provider", tr("{0}: {1} sağlayıcısı eklenmemiş", a.name, it.title)) }
                        if (act.amount.isNullOrBlank()) error("$ap.amount", tr("{0}: tutar boş (sayı ya da {alan})", a.name))
                        act.currency?.let { if (!PaymentsConfig.CURRENCY.matches(it)) error("$ap.currency", tr("{0}: üç harfli para birimi kodu, örn. TRY", a.name)) }
                        // Güncellemede her yazma yeni bağlantı üretmesin: koşul, adresin yazılacağı alanın boş olmasını istemeli.
                        val guarded = act.field != null && a.condition.orEmpty().split('&').any { it.trim() == "${act.field}[null]=true" }
                        when {
                            t.scheduled || t.onMqtt || collection == null || "delete" in t.events -> error(ap, tr("{0}: ödeme bağlantısı yalnızca kayıt oluşturulunca veya güncellenince üretilir", a.name))
                            "update" in t.events && !guarded -> error(ap, tr("{0}: güncellemede bağlantı bir kez üretilsin diye koşula {1}[null]=true ekleyin ve adresi o alana yazın", a.name, act.field ?: "payment_url"))
                        }
                        act.field?.let { name ->
                            val f = collection?.fields?.firstOrNull { it.name == name }
                            if (f == null || f.type != FieldType.TEXT || f.computed != null) error("$ap.field", tr("{0}: ödeme adresinin yazılacağı metin alanını seçin", a.name))
                        }
                    }
                }
            }
        }
    }

    private fun envRules(config: ServerConfig, error: (String, String) -> Unit) {
        if (config.env.size > EnvFile.MAX_VARS) error("env", tr("En çok {0} ortak ortam değişkeni olabilir", EnvFile.MAX_VARS))
        config.env.forEach { (k, v) ->
            if (!EnvFile.NAME.matches(k)) error("env", tr("Ortam değişkeni adı geçersiz: '{0}'", k))
            if (v.length > EnvFile.MAX_VALUE) error("env", tr("{0} çok uzun (en çok {1} karakter)", k, EnvFile.MAX_VALUE))
        }
    }

    private val APP_PATH = Regex("^(/[A-Za-z0-9._~-]+)+$")
    private val GIT_BRANCH = Regex("^[A-Za-z0-9._/-]{1,100}$")
    private val GIT_REPO = Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){1,4}$")
    /** Yerel modelin kimliği (indirilen dosyanın adı, uzantısız). */
    val LOCAL_MODEL = Regex("^[a-z0-9][a-z0-9._-]{0,79}$")

    private val SAFE_SUBDIR = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")

    private fun apps(config: ServerConfig, error: (String, String) -> Unit) {
        val names = mutableSetOf<String>()
        val ports = mutableMapOf<Int, String>()
        val paths = mutableListOf<Pair<String, String>>()
        val reserved = buildList {
            add(config.server.basePath.trimEnd('/').ifEmpty { "/" } to tr("API yolu"))
            add("/_admin" to tr("web paneli"))
            add("/_auth" to tr("hesap sayfaları"))
            if (config.forms.isNotEmpty()) add(FormConfig.PATH to tr("formlar"))
            if (config.pages.isNotEmpty()) add(PageConfig.PATH to tr("sayfalar"))
            if (config.dashboards.any { it.shareToken != null }) add(DashboardConfig.PATH to tr("panolar"))
            if (config.payments.providers.isNotEmpty()) add(PaymentsConfig.PATH to tr("ödeme sayfaları"))
        }
        config.apps.forEachIndexed { i, app ->
            val at = "apps[$i]"
            if (!AppConfig.NAME.matches(app.name)) error("$at.name", tr("Uygulama adı geçersiz: '{0}' (küçük harf, rakam ve -, harfle başlar, en çok 32)", app.name))
            else if (!names.add(app.name)) error("$at.name", tr("Uygulama adı tekrar ediyor: '{0}'", app.name))
            if (app.command.isEmpty() || app.command.first().isBlank()) error("$at.command", tr("{0}: başlatma komutu boş", app.name))
            app.runtime?.let { if (!IDENT.matches(it.replace('-', '_'))) error("$at.runtime", tr("Çalışma ortamı kimliği geçersiz: '{0}'", it)) }
            if (app.build.size > 5 || app.build.any { it.isEmpty() || it.first().isBlank() }) error("$at.build", tr("{0}: en çok 5 kurulum komutu olabilir; boş komut olamaz", app.name))
            when {
                app.port !in 1024..65535 -> error("$at.port", tr("{0}: port 1024-65535 arasında olmalı", app.name))
                app.port == config.server.port -> error("$at.port", tr("{0}: port sunucunun portuyla ({1}) aynı olamaz", app.name, app.port))
                config.access.mode == AccessMode.DIRECT && app.port == config.access.direct.port -> error("$at.port", tr("{0}: port HTTPS portuyla ({1}) aynı olamaz", app.name, app.port))
                else -> ports.put(app.port, app.name)?.let { other -> error("$at.port", tr("{0}: port {1} '{2}' uygulamasında da kullanılıyor", app.name, app.port, other)) }
            }
            val path = app.publicPath
            if (!APP_PATH.matches(path)) {
                error("$at.path", tr("{0}: yol / ile başlamalı ve harf, rakam, . _ ~ - içermeli; örn. /apps/{0}", app.name))
            } else {
                reserved.firstOrNull { (p, _) -> StaticConfig.underPath(path, p) || StaticConfig.underPath(p, path) }?.let { (_, what) ->
                    error("$at.path", tr("{0}: {1} yolu {2} ile çakışıyor", app.name, path, what))
                }
                paths.firstOrNull { (p, _) -> StaticConfig.underPath(path, p) || StaticConfig.underPath(p, path) }?.let { (_, other) ->
                    error("$at.path", tr("{0}: {1} yolu '{2}' uygulamasının yoluyla çakışıyor", app.name, path, other))
                }
                paths += path to app.name
            }
            app.health?.let { if (!it.startsWith("/") || it.any { c -> c.isWhitespace() }) error("$at.health", tr("{0}: sağlık denetimi yolu / ile başlamalı", app.name)) }
            if (app.startTimeoutSec !in 5..600) error("$at.startTimeoutSec", tr("{0}: başlama süresi 5-600 sn olmalı", app.name))
            if (app.keepReleases !in 1..10) error("$at.keepReleases", tr("{0}: saklanan sürüm sayısı 1-10 olmalı", app.name))
            if (app.maxBodyMb !in 1..1024) error("$at.maxBodyMb", tr("{0}: gövde sınırı 1-1024 MB olmalı", app.name))
            app.env.keys.forEach { k -> if (!EnvFile.NAME.matches(k)) error("$at.env", tr("Ortam değişkeni adı geçersiz: '{0}'", k)) }
            if (app.env.size > EnvFile.MAX_VARS) error("$at.env", tr("En çok {0} ortam değişkeni olabilir", EnvFile.MAX_VARS))
            app.git?.let { git -> gitRules(git, "$at.git", app.name, error) }
        }
    }

    private fun gitRules(git: GitSource, at: String, app: String, error: (String, String) -> Unit) {
        when (git.provider) {
            GitProvider.URL -> if (!git.repo.startsWith("https://") || git.repo.any { it.isWhitespace() }) error("$at.repo", tr("{0}: zip adresi https:// ile başlamalı", app))
            else -> if (!GIT_REPO.matches(git.repo)) error("$at.repo", tr("{0}: depo sahip/depo biçiminde olmalı", app))
        }
        if (git.provider == GitProvider.GITEA && git.host.isNullOrBlank()) error("$at.host", tr("{0}: Gitea/Forgejo için sunucu adını girin", app))
        git.host?.let { if (!HOSTNAME.matches(it.substringBefore(':')) && it.substringBefore(':') != "localhost") error("$at.host", tr("{0}: sunucu adı geçersiz: '{1}'", app, it)) }
        if (!GIT_BRANCH.matches(git.branch) || ".." in git.branch) error("$at.branch", tr("{0}: dal adı geçersiz: '{1}'", app, git.branch))
        git.subdir?.let { if (!SAFE_SUBDIR.matches(it) || it.split('/').any { s -> s == "." || s == ".." }) error("$at.subdir", tr("{0}: alt klasör geçersiz: '{1}'", app, it)) }
        git.webhookSecret?.let { if (!ConfigSecrets.isRef(it) && it.length < 16) error("$at.webhookSecret", tr("{0}: webhook sırrı en az 16 karakter olmalı", app)) }
    }
}
