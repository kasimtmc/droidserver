package dev.droidserver.core.audit

import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.location
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

/** Denetim kaydı satırı. [actor]: `admin`, `key:ad`, `user:5`, `uygulama`; [ip]: istemci adresi (uygulamada yok). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuditEntry(
    val at: String,
    val actor: String,
    val action: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val target: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val detail: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ip: String? = null,
)

/**
 * Yönetici işlemlerinin kaydı (kim, ne, ne zaman, nereden). Veritabanında değil, profilin klasöründe `audit.jsonl`
 * dosyasında durur: sunucu dururken, veritabanı taşınırken veya yedekten geri yüklenirken de korunur ve geri yükleme
 * eski kaydı silemez. Dosya yalnızca sonuna yazılır; [keepDays]'ten eski satırlar ve [MAX_LINES] üstü günde bir atılır.
 */
class AuditLog(private val file: File?, private val clock: () -> Instant = Instant::now) {
    @Volatile var keepDays: Int = 365
    private val memory = ArrayList<AuditEntry>()
    private var lastTrim: Instant = Instant.EPOCH
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Synchronized
    fun record(actor: String, action: String, target: String? = null, detail: String? = null, ip: String? = null) {
        val e = AuditEntry(clock().toString(), actor, action, target?.take(300), detail?.take(2000), ip)
        val f = file
        if (f == null) {
            memory += e
            if (memory.size > MAX_LINES) memory.subList(0, memory.size - MAX_LINES).clear()
            return
        }
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(json.encodeToString(AuditEntry.serializer(), e) + "\n")
            if (Duration.between(lastTrim, clock()) > Duration.ofDays(1)) trim()
        }
    }

    private fun all(): List<AuditEntry> = if (file == null) memory.toList() else if (!file.isFile) emptyList()
    else file.readLines().mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(AuditEntry.serializer(), it) }.getOrNull() } }

    /** Eski satırları atar (dosya yeniden yazılır). */
    @Synchronized
    fun trim() {
        lastTrim = clock()
        val f = file ?: return
        if (!f.isFile) return
        val cutoff = clock().minus(Duration.ofDays(keepDays.toLong())).toString()
        val kept = all().filter { it.at >= cutoff }.takeLast(MAX_LINES)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(kept.joinToString("") { json.encodeToString(AuditEntry.serializer(), it) + "\n" })
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Yeniden eskiye; [query] işlem, kim, hedef veya ayrıntıda geçen metin; [action] işlem öneki (`user.`). */
    @Synchronized
    fun list(limit: Int = 200, action: String? = null, actor: String? = null, query: String? = null): List<AuditEntry> =
        all().asReversed().asSequence()
            .filter { action.isNullOrBlank() || it.action.startsWith(action) }
            .filter { actor.isNullOrBlank() || it.actor == actor }
            .filter { e -> query.isNullOrBlank() || listOfNotNull(e.action, e.actor, e.target, e.detail, e.ip).any { it.contains(query, ignoreCase = true) } }
            .take(limit.coerceIn(1, MAX_LINES))
            .toList()

    companion object {
        const val MAX_LINES = 20_000
        /** Telefondaki uygulamadan yapılan işlem. */
        const val APP = "uygulama"
        /** Sunucunun kendiliğinden yaptığı işlem (güvenlik duvarı engeli, devralma). */
        const val SYSTEM = "sistem"

        fun csv(entries: List<AuditEntry>): String = buildString {
            append("zaman,kim,islem,hedef,ayrinti,ip\r\n")
            for (e in entries) append(listOf(e.at, e.actor, e.action, e.target, e.detail, e.ip).joinToString(",") { cell(it) }).append("\r\n")
        }

        private fun cell(v: String?): String {
            val s = v.orEmpty()
            // Hesap tablosu formül enjeksiyonuna karşı (=, +, -, @ ile başlayan hücre).
            val safe = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }

        /** Deploy'da konfigürasyon farkının kısa özeti (denetim kaydı için); fark yoksa boş. */
        fun changes(old: ServerConfig?, new: ServerConfig): List<String> = buildList {
            if (old == null) { add(tr("ilk deploy")); return@buildList }
            val oc = old.collections.associateBy { it.name }
            val nc = new.collections.associateBy { it.name }
            (nc.keys - oc.keys).forEach { add(tr("koleksiyon eklendi: {0}", it)) }
            (oc.keys - nc.keys).forEach { add(tr("koleksiyon kaldırıldı: {0}", it)) }
            for ((name, c) in nc) {
                val o = oc[name] ?: continue
                val of = o.fields.associateBy { it.name }
                val nf = c.fields.associateBy { it.name }
                (nf.keys - of.keys).forEach { add(tr("{0}: alan eklendi: {1}", name, it)) }
                (of.keys - nf.keys).forEach { add(tr("{0}: alan kaldırıldı: {1}", name, it)) }
                nf.forEach { (fn, f) -> of[fn]?.takeIf { it.type != f.type }?.let { add("$name.$fn: tip ${it.type.name.lowercase()} → ${f.type.name.lowercase()}") } }
                if (o.rules != c.rules) add(tr("{0}: kurallar değişti", name))
            }
            if (old.auth.mode != new.auth.mode) add(tr("kimlik doğrulama: {0} → {1}", old.auth.mode.name.lowercase(), new.auth.mode.name.lowercase()))
            if (old.auth.apiKeys.size != new.auth.apiKeys.size) add(tr("API anahtarı sayısı: {0} → {1}", old.auth.apiKeys.size, new.auth.apiKeys.size))
            val ok = old.auth.keys.map { it.name }.toSet()
            val nk = new.auth.keys.map { it.name }.toSet()
            (nk - ok).forEach { add(tr("anahtar oluşturuldu: {0}", it)) }
            (ok - nk).forEach { add(tr("anahtar iptal edildi: {0}", it)) }
            if (old.auth.users.enabled != new.auth.users.enabled) add(tr("kullanıcı hesapları: {0}", if (new.auth.users.enabled) tr("açıldı") else tr("kapatıldı")))
            if (old.adminPanel != new.adminPanel) add(tr("web paneli ayarları değişti"))
            if (old.access != new.access) add(tr("erişim ayarları değişti"))
            if (old.database.location() != new.database.location()) add(tr("veritabanı değişti"))
            if (old.webhooks.map { it.name } != new.webhooks.map { it.name }) add(tr("webhook'lar değişti"))
            if (old.scripts != new.scripts) add(tr("script'ler değişti"))
            if (old.firewall != new.firewall) add(tr("güvenlik duvarı değişti"))
            if (old.replication != new.replication) add(
                when {
                    old.replication.replicaOf != null && new.replication.replicaOf == null -> tr("kopya modundan çıkıldı (devralındı)")
                    new.replication.replicaOf != null && old.replication.replicaOf == null -> tr("kopya olarak ayarlandı: {0}", new.replication.replicaOf.url)
                    else -> tr("canlı kopya ayarları değişti")
                },
            )
        }
    }
}
