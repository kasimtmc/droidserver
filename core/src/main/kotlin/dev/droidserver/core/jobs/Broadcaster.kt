package dev.droidserver.core.jobs

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.Scope
import dev.droidserver.core.api.WriteGate
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.mail.MailSender
import dev.droidserver.core.mail.Mailer
import dev.droidserver.core.push.PushService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder

/** Toplu bildirim işlerinin kuyruktaki türü (script iş türleri `_` ile başlayamaz). */
const val BROADCAST_JOB = "_broadcast"

/**
 * Kullanıcılara toplu e-posta veya web push. Her gönderim kalıcı kuyrukta bir iştir ([BROADCAST_JOB]): alıcılar kimlik
 * sırasıyla, dakikada en çok `perMinute` kişiye gönderilir; ilerleme (son kimlik, gönderilen, başarısız) işin sonucunda
 * tutulur, böylece sunucu yeniden başlayınca kaldığı yerden sürer. İş iptal edilince sıradaki alıcıdan önce durur.
 * Alıcı süzgeci: rol, takım, yalnızca doğrulanmış adresler ve `_users` alanlarına liste filtresi. E-postada abonelikten
 * çıkanlar atlanır ve her e-postaya çıkma bağlantısı eklenir; push'ta aboneliği olmayanlar sayılmaz.
 */
class Broadcaster(
    private val db: SqlDriver,
    private val config: ServerConfig,
    private val queue: JobQueue,
    private val mail: MailSender?,
    private val push: PushService?,
    /** Adresin "abonelikten çık" bağlantısı. */
    private val unsubscribeLink: (String) -> String,
    private val writes: WriteGate = WriteGate(),
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    data class Request(
        val channel: String,
        val subject: String,
        val text: String,
        val html: String?,
        val url: String?,
        val role: String?,
        val team: Long?,
        val verifiedOnly: Boolean,
        val where: String?,
        val perMinute: Int,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("channel", JsonPrimitive(channel))
            put("subject", JsonPrimitive(subject))
            put("text", JsonPrimitive(text))
            html?.let { put("html", JsonPrimitive(it)) }
            url?.let { put("url", JsonPrimitive(it)) }
            role?.let { put("role", JsonPrimitive(it)) }
            team?.let { put("team", JsonPrimitive(it)) }
            put("verifiedOnly", JsonPrimitive(verifiedOnly))
            where?.let { put("where", JsonPrimitive(it)) }
            put("perMinute", JsonPrimitive(perMinute))
        }
    }

    private data class Progress(val total: Int = 0, val sent: Int = 0, val failed: Int = 0, val skipped: Int = 0, val lastId: Long = 0) {
        fun toJson() = buildJsonObject {
            put("total", JsonPrimitive(total))
            put("sent", JsonPrimitive(sent))
            put("failed", JsonPrimitive(failed))
            put("skipped", JsonPrimitive(skipped))
            put("lastId", JsonPrimitive(lastId))
        }

        companion object {
            fun of(e: JsonElement?): Progress? = (e as? JsonObject)?.let { o ->
                fun n(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0
                Progress(n("total").toInt(), n("sent").toInt(), n("failed").toInt(), n("skipped").toInt(), n("lastId"))
            }
        }
    }

    private val users = SystemCollections.users(config.auth.users)
    private val usersService = RecordService(db, users, collections = config.schemaCollections())
    private val lock = Object()
    private var woken = false
    @Volatile private var stopped = false
    private var loop: Thread? = null

    /** İsteği denetler; geçersizse 400. */
    fun parse(body: JsonObject): Request {
        fun text(k: String) = (body[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val errors = linkedMapOf<String, String>()
        val channel = text("channel") ?: EMAIL
        when (channel) {
            EMAIL -> when {
                mail == null || !config.email.enabled -> errors["channel"] = tr("E-posta gönderimi kapalı")
                config.email.publicUrl.isNullOrBlank() -> errors["channel"] = tr("Abonelikten çıkma bağlantısı için e-posta ayarlarında \"Bağlantılardaki adres\" gerekli")
            }
            PUSH -> if (push == null) errors["channel"] = tr("Web push kapalı")
            else -> errors["channel"] = tr("email veya push olmalı")
        }
        val subject = text("subject")
        if (subject == null || subject.length > 200 || '\n' in subject || '\r' in subject) errors["subject"] = tr("tek satır, 1-200 karakter")
        val body2 = text("text")
        if (body2 == null || body2.length > 20_000) errors["text"] = tr("1-20000 karakter")
        val html = text("html")?.takeIf { channel == EMAIL }
        if ((html?.length ?: 0) > 100_000) errors["html"] = tr("en çok 100000 karakter")
        val url = text("url")?.takeIf { channel == PUSH }
        if (url != null && !Regex("^https?://\\S+$").matches(url)) errors["url"] = tr("http:// veya https:// ile başlamalı")
        val team = body["team"]?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.longOrNull ?: run { errors["team"] = tr("takım kimliği sayı olmalı"); null } }
        if (team != null && !config.auth.users.teams.enabled) errors["team"] = tr("takımlar kapalı")
        val perMinute = (body["perMinute"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_PER_MINUTE
        if (perMinute !in 1..MAX_PER_MINUTE) errors["perMinute"] = tr("1-{0} arasında olmalı", MAX_PER_MINUTE)
        val where = text("where")
        if (errors.isNotEmpty()) throw ApiException(400, tr("Doğrulama hatası"), errors)
        val r = Request(
            channel, subject!!, body2!!, html, url, text("role"), team,
            verifiedOnly = (body["verifiedOnly"] as? JsonPrimitive)?.booleanOrNull ?: (channel == EMAIL),
            where = where, perMinute = perMinute,
        )
        selection(r, 0) // süzgeç hatası (bilinmeyen alan, geçersiz değer) burada 400
        return r
    }

    /** Süzgece uyan alıcı sayısı (abonelikten çıkanlar ve push aboneliği olmayanlar hariç). */
    fun count(r: Request): Int {
        val (where, args) = selection(r, 0)
        return (db.query("SELECT COUNT(*) AS n FROM ${quoteIdent(users.name)} $where", args).first()["n"] as Number).toInt()
    }

    /** İşi kuyruğa ekler; iş kimliği ve alıcı sayısı. */
    fun enqueue(r: Request): Pair<Long, Int> {
        val total = count(r)
        val id = writes.write { queue.enqueue(BROADCAST_JOB, r.toJson(), maxAttempts = 1) }
        wake()
        return id to total
    }

    @Synchronized
    fun start() {
        if (loop != null) return
        runCatching { writes.write { queue.recover(only = BROADCAST_JOB) } }
        loop = Thread({
            while (!stopped) {
                runCatching { tick() }.onFailure { if (!stopped) log(tr("Toplu bildirim: {0}", it.message)) }
                synchronized(lock) {
                    if (!woken && !stopped) lock.wait(POLL_MS)
                    woken = false
                }
            }
        }, "ds-broadcast").apply { isDaemon = true; start() }
    }

    fun wake() = synchronized(lock) { woken = true; lock.notifyAll() }

    @Synchronized
    fun stop() {
        stopped = true
        wake()
        loop?.join(2_000)
        loop = null
    }

    /** Sıradaki işi (varsa) sonuna kadar çalıştırır; iş bulunduysa true. */
    fun tick(): Boolean {
        if (writes.isPaused) return false
        val job = writes.write { queue.claim(setOf(BROADCAST_JOB), 1) }.firstOrNull() ?: return false
        try {
            run(job)
        } catch (e: Exception) {
            runCatching { writes.write { queue.fail(job, e.message ?: e.javaClass.simpleName, retry = false) } }
            log(tr("Toplu bildirim #{0} durdu: {1}", job.id, e.message))
        }
        return true
    }

    private fun run(job: Job) {
        val r = parse(job.data as? JsonObject ?: JsonObject(emptyMap()))
        var p = Progress.of(job.result) ?: Progress(total = count(r))
        val pause = 60_000L / r.perMinute
        var consecutive = 0
        while (true) {
            val (where, args) = selection(r, p.lastId)
            val batch = db.query("SELECT \"id\", \"email\" FROM ${quoteIdent(users.name)} $where ORDER BY \"id\" LIMIT $BATCH", args)
            if (batch.isEmpty()) break
            for (row in batch) {
                if (stopped || !running(job.id)) return // durdu: açılışta sürer; iptal: dokunulmaz
                val id = (row["id"] as Number).toLong()
                val email = row["email"] as String
                val outcome = runCatching { deliver(r, id, email) }
                p = when {
                    outcome.isFailure -> p.copy(failed = p.failed + 1)
                    outcome.getOrThrow() -> p.copy(sent = p.sent + 1)
                    else -> p.copy(skipped = p.skipped + 1)
                }.copy(lastId = id)
                consecutive = if (outcome.isFailure) consecutive + 1 else 0
                if (!writes.write { queue.progress(job.id, p.toJson()) }) return
                if (consecutive >= MAX_CONSECUTIVE_FAILURES) {
                    val reason = outcome.exceptionOrNull()?.message
                    writes.write { queue.fail(job, tr("Art arda {0} gönderim başarısız: {1}", consecutive, reason), retry = false) }
                    log(tr("Toplu bildirim #{0} durduruldu: art arda {1} gönderim başarısız ({2})", job.id, consecutive, reason))
                    return
                }
                wait(pause, job.id)
            }
        }
        writes.write { queue.complete(job.id, p.toJson()) }
        log(tr("Toplu bildirim #{0} bitti: {1} gönderildi, {2} başarısız", job.id, p.sent, p.failed))
    }

    /** Gönderildiyse true; push aboneliği yoksa false. */
    private fun deliver(r: Request, id: Long, email: String): Boolean {
        if (r.channel == PUSH) return push!!.sendToUser(id, push.message(r.subject, r.text, r.url, null)) > 0
        val link = unsubscribeLink(email)
        val text = r.text + "\n\n—\n" + tr("Bu e-postaları almak istemiyorsanız: {0}", link)
        val html = r.html?.let { it + "<p style=\"font-size:12px;color:#888\"><a href=\"${esc(link)}\">${esc(tr("Abonelikten çık"))}</a></p>" }
        mail!!.send(email, r.subject, text, html)
        log(tr("Toplu e-posta gönderildi: {0}", Mailer.mask(email)))
        return true
    }

    /** Bekleme; iptal edilince erken çıkar (saniyede bir bakılır). */
    private fun wait(ms: Long, jobId: Long) {
        var left = ms
        while (left > 0 && !stopped) {
            val step = minOf(left, 1_000L)
            sleep(step)
            left -= step
            if (left > 0 && !running(jobId)) return
        }
    }

    private fun running(id: Long) = runCatching { queue.get(id).status == Job.RUNNING }.getOrDefault(false)

    /** Alıcı süzgecinin WHERE'i: [afterId]'den büyük kimlikler. */
    private fun selection(r: Request, afterId: Long): Pair<String, List<Any?>> {
        val params = r.where?.split('&')?.filter { it.isNotBlank() }?.map { part ->
            val k = URLDecoder.decode(part.substringBefore('='), "UTF-8").trim()
            if (k.substringBefore('[') in HIDDEN) throw ApiException(400, tr("Doğrulama hatası"), mapOf("where" to tr("'{0}' alanına göre süzülemez", k.substringBefore('['))))
            k to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.orEmpty()
        val (base, baseArgs) = try {
            usersService.whereClause(params, Scope.ADMIN)
        } catch (e: ApiException) {
            throw ApiException(400, tr("Doğrulama hatası"), mapOf("where" to (e.message ?: "")))
        }
        val d = db.dialect
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        base.removePrefix("WHERE ").takeIf { it.isNotBlank() }?.let { where += "($it)"; args.addAll(baseArgs) }
        where += "\"id\" > ?"
        args += afterId
        r.role?.let { where += "\"role\" = ?"; args += it }
        if (r.verifiedOnly) { where += "${d.comparable(FieldType.BOOLEAN, "\"verified\"")} = ${d.comparable(FieldType.BOOLEAN, "?")}"; args += d.encode(FieldType.BOOLEAN, true) }
        r.team?.let { where += "\"id\" IN (SELECT \"user\" FROM ${quoteIdent(SystemCollections.TEAM_MEMBERS)} WHERE \"team\" = ?)"; args += d.encode(FieldType.RELATION, it) }
        if (r.channel == EMAIL) where += "LOWER(\"email\") NOT IN (SELECT \"email\" FROM ${quoteIdent(SystemCollections.UNSUBSCRIBED)})"
        if (r.channel == PUSH) where += "\"id\" IN (SELECT \"user\" FROM ${quoteIdent(SystemCollections.PUSH_SUBSCRIPTIONS)})"
        return "WHERE " + where.joinToString(" AND ") to args
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val EMAIL = "email"
        const val PUSH = "push"
        const val DEFAULT_PER_MINUTE = 30
        const val MAX_PER_MINUTE = 600
        private const val BATCH = 50
        private const val POLL_MS = 5_000L
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private val HIDDEN = setOf("password_hash")
    }
}
