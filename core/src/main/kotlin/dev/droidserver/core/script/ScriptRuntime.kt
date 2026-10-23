package dev.droidserver.core.script

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.ScriptsConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.phone.PhoneService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException
import java.io.File

/** Script kaynağı: ad (hata iletilerinde `ad:satır`) ve metin. */
data class ScriptSource(val name: String, val text: String)

/** Yükleme hataları (sözdizimi, kayıt hataları); deploy'u reddeder. */
class ScriptLoadException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** Script'in ürettiği hata: `fail(status, mesaj)`, süre aşımı (500) veya yakalanmamış istisna (500). */
class ScriptException(val status: Int, message: String) : RuntimeException(message)

/** Script'e açılan kayıt işlemleri (`ctx.db`); yönetici yetkisiyle çalışır, kayıt olaylarını tetiklemez. */
interface ScriptDb {
    fun list(collection: String, query: JsonObject): JsonObject
    fun get(collection: String, id: Long): JsonObject
    fun create(collection: String, data: JsonObject): JsonObject
    fun update(collection: String, id: Long, data: JsonObject): JsonObject
    fun delete(collection: String, id: Long)
}

enum class HookEvent(val function: String) {
    BEFORE_CREATE("onBeforeCreate"), AFTER_CREATE("onAfterCreate"),
    BEFORE_UPDATE("onBeforeUpdate"), AFTER_UPDATE("onAfterUpdate"),
    BEFORE_DELETE("onBeforeDelete"), AFTER_DELETE("onAfterDelete"),
}

/** `routerAdd(yöntem, yol, işleyici, erişim)` ile tanımlanan uç; yol basePath'e görelidir (`/hello/:name`). */
/** [role]: `routerAdd(..., "role:<ad>")` ile yalnızca bu roldeki kullanıcı (ve yönetici); [access] o zaman [Rule.AUTH]. */
class ScriptRoute internal constructor(
    val method: String,
    val path: String,
    val access: Rule,
    val source: String,
    internal val handler: Function,
    val role: String? = null,
)

class CronJob internal constructor(val schedule: CronSchedule, val source: String, internal val handler: Function)

/**
 * `jobAdd(ad, işleyici, {retries, timeoutSec, concurrency})` ile tanımlanan iş türü. İşleyici `(data, ctx, job)` alır;
 * dönüşü işin sonucu olur. `fail(4xx, …)` kalıcı hatadır (yeniden denenmez), diğer hatalar yeniden denenir.
 */
class JobType internal constructor(
    val name: String,
    val source: String,
    /** Toplam deneme hakkı (ilk çalışma dahil). */
    val attempts: Int,
    val timeoutSec: Int,
    /** Bu türden aynı anda en çok kaç iş. */
    val concurrency: Int,
    internal val handler: Function,
)

/** Script'in kuyruğa iş eklemesi (`ctx.jobs.enqueue`); HTTP katmanı bağlar. */
fun interface JobSink {
    fun enqueue(name: String, data: JsonElement, delaySec: Long, attempts: Int, key: String?): Long
}

/** Özel uca gelen istek. */
data class ScriptRequest(
    val method: String,
    val path: String,
    val params: Map<String, String>,
    val query: Map<String, List<String>>,
    val headers: Map<String, String>,
    val body: JsonElement?,
)

data class ScriptReply(val status: Int, val body: JsonElement, val headers: Map<String, String> = emptyMap())

/**
 * JavaScript script'leri (Mozilla Rhino, yorumlama kipi: Android'de bytecode üretilemez, komut sayacı da yalnızca bu
 * kipte çalışır). Güvenlik:
 *  - yalnızca güvenli standart nesneler (`java`, `Packages` yok) ve her Java sınıfını reddeden `ClassShutter`;
 *  - her çağrının süre sınırı: komut gözlemcisi süreyi aşan script'i JS'in yakalayamayacağı bir hatayla durdurur
 *    (sonsuz döngü), yığın derinliği sınırlı (sonsuz özyineleme);
 *  - `ctx.http` yalnızca izinli adreslere, yönlendirmeleri izlemeden;
 *  - script hatası isteğe 500 olarak döner ve loga yazılır, sunucuyu durdurmaz.
 * Nesneler iş parçacığı güvenli kipte: istekler script'leri aynı anda çalıştırabilir.
 */
class ScriptRuntime private constructor(
    private val config: ScriptsConfig,
    private val log: (String) -> Unit,
    private val scope: ScriptableObject,
    val routes: List<ScriptRoute>,
    private val hooks: Map<HookEvent, List<Pair<String, Function>>>,
    val crons: List<CronJob>,
    val jobTypes: Map<String, JobType> = emptyMap(),
) {
    /** `ctx.jobs.enqueue`; iş kuyruğu yoksa null. */
    @Volatile
    var jobs: JobSink? = null

    /** `ctx.channels.publish(kanal, veri)`: WebSocket kanalına sunucudan ileti; kanal yoksa false. Kanallar kapalıysa null. */
    @Volatile
    var channels: ((String, JsonElement) -> Boolean)? = null

    /** `ctx.sms.send`, `ctx.device.location()`, `ctx.device.status()`; telefon özellikleri kapalıysa null. */
    @Volatile
    var phone: PhoneService? = null

    /** `ctx.db`'yi HTTP katmanı bağlar (kayıt servisleri sunucuyla birlikte kurulur). */
    @Volatile
    var db: ScriptDb? = null

    /** `ctx.mail.send(to, subject, text[, html])`; e-posta kapalıysa null (bkz. Mailer.custom). */
    @Volatile
    var mail: ((to: String, subject: String, text: String, html: String?) -> Unit)? = null

    /** `ctx.push.send(kullanıcı, başlık, metin[, adres])`; push kapalıysa null. Cihaz sayısını döndürür. */
    @Volatile
    var push: ((user: Long, title: String, body: String?, url: String?) -> Int)? = null

    private val http = ScriptHttp(config.allowedHosts)

    fun hasHook(event: HookEvent, collection: String): Boolean = handlers(event, collection).isNotEmpty()

    private fun handlers(event: HookEvent, collection: String) =
        hooks[event].orEmpty().filter { it.first == collection || it.first == "*" }.map { it.second }

    /** Özel ucu çalıştırır; işleyicinin dönüşü JSON gövde, `reply(status, gövde, başlıklar)` ile durum kodu seçilir. */
    fun runRoute(route: ScriptRoute, request: ScriptRequest, caller: Caller): ScriptReply = call(route.source, config.timeoutMs.toLong()) { cx ->
        val req = toJs(cx, buildJsonObject {
            put("method", JsonPrimitive(request.method))
            put("path", JsonPrimitive(request.path))
            put("params", JsonObject(request.params.mapValues { JsonPrimitive(it.value) }))
            put("query", JsonObject(request.query.mapValues { (_, v) -> JsonPrimitive(v.firstOrNull().orEmpty()) }))
            put("headers", JsonObject(request.headers.mapValues { JsonPrimitive(it.value) }))
            put("body", request.body ?: JsonNull)
        })
        val result = route.handler.call(cx, scope, scope, arrayOf(req, context(cx, caller, null)))
        val json = fromJs(cx, result)
        val reply = (json as? JsonObject)?.takeIf { it[REPLY_MARK] != null }
        if (reply == null) ScriptReply(200, json)
        else ScriptReply(
            status = (reply["status"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it in 100..599 } ?: 200,
            body = reply["body"] ?: JsonNull,
            headers = (reply["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty(),
        )
    }

    /**
     * `onBefore…` olayları: kayıt nesnesi işleyicide değiştirilebilir (ör. `record.title = record.title.trim()`),
     * `fail()` işlemi durdurur. Değişmiş kaydı döndürür. [current] güncelleme ve silmede mevcut kayıttır.
     */
    fun before(event: HookEvent, collection: String, record: JsonObject, current: JsonObject?, caller: Caller): JsonObject {
        var out = record
        for (fn in handlers(event, collection)) {
            out = call("${event.function}($collection)", config.timeoutMs.toLong()) { cx ->
                val rec = toJs(cx, out)
                fn.call(cx, scope, scope, arrayOf(rec, context(cx, caller, current)))
                fromJs(cx, rec) as? JsonObject ?: out
            }
        }
        return out
    }

    /** `onAfter…` olayları: kayıt yazıldıktan sonra; hata yanıtı değiştirmez, yalnızca loga yazılır. */
    fun after(event: HookEvent, collection: String, record: JsonObject, caller: Caller) {
        for (fn in handlers(event, collection)) {
            try {
                call("${event.function}($collection)", config.timeoutMs.toLong()) { cx ->
                    fn.call(cx, scope, scope, arrayOf(toJs(cx, record), context(cx, caller, null)))
                }
            } catch (e: Exception) {
                log("[script] ${event.function}($collection) hatası (kayıt yazıldı): ${e.message}")
            }
        }
    }

    /** Zamanlanmış görev; hata loga yazılır. */
    fun runCron(job: CronJob) {
        try {
            call("cron ${job.schedule.expression}", config.cronTimeoutSec * 1000L) { cx ->
                job.handler.call(cx, scope, scope, arrayOf(context(cx, Caller.Admin, null)))
            }
        } catch (e: Exception) {
            log("[script] zamanlanmış görev (${job.schedule.expression}, ${job.source}) hatası: ${e.message}")
        }
    }

    /**
     * Kuyruktaki işi çalıştırır (süre sınırı türün `timeoutSec`'i); dönüş sonuç olarak saklanır. Hatalar
     * [ScriptException] (`fail()` durumu korunur).
     */
    fun runJob(type: JobType, id: Long, data: JsonElement, attempt: Int): JsonElement =
        call(tr("iş {0}#{1}", type.name, id), type.timeoutSec * 1000L, detail = true) { cx ->
            val job = toJs(cx, buildJsonObject {
                put("id", JsonPrimitive(id))
                put("name", JsonPrimitive(type.name))
                put("attempt", JsonPrimitive(attempt))
                put("attempts", JsonPrimitive(type.attempts))
            })
            fromJs(cx, type.handler.call(cx, scope, scope, arrayOf(toJs(cx, data), context(cx, Caller.Admin, null), job)))
        }

    /** Script'i süre sınırıyla çalıştırır; hataları [ScriptException] / [ApiException]'a çevirir. */
    private fun <T> call(what: String, timeoutMs: Long, detail: Boolean = false, block: (Context) -> T): T {
        /** İsteğe genel ileti (ayrıntı loglarda); işlerde ayrıntı sonuçta saklanır (yalnızca yönetici görür). */
        fun hidden(message: String) = ScriptException(500, if (detail) message else tr("Script hatası; ayrıntılar sunucu loglarında"))
        val cx = FACTORY.enterContext()
        try {
            cx.putThreadLocal(DEADLINE, System.nanoTime() + timeoutMs * 1_000_000)
            return block(cx)
        } catch (e: ScriptTimeout) {
            log("[script] $what süre sınırını (${timeoutMs} ms) aştı ve durduruldu")
            throw ScriptException(500, tr("Script süre sınırını aştı"))
        } catch (e: ScriptFail) {
            failed(what, e)
        } catch (e: WrappedException) {
            when (val w = e.wrappedException) {
                is ScriptFail -> failed(what, w)
                is ApiException -> throw w
                is ScriptException -> throw w
                else -> {
                    log("[script] $what: ${w.javaClass.simpleName}: ${w.message} (${e.sourceName()}:${e.lineNumber()})")
                    throw hidden("${w.javaClass.simpleName}: ${w.message} (${e.sourceName()}:${e.lineNumber()})")
                }
            }
        } catch (e: RhinoException) {
            log("[script] $what: ${e.details()} (${e.sourceName()}:${e.lineNumber()})\n${e.scriptStackTrace.trim()}")
            throw hidden("${e.details()} (${e.sourceName()}:${e.lineNumber()})")
        } catch (e: StackOverflowError) {
            log("[script] $what: yığın taştı (sonsuz özyineleme?)")
            throw hidden(tr("yığın taştı (sonsuz özyineleme?)"))
        } finally {
            Context.exit()
        }
    }

    /** `fail()` (4xx, istemcinin hatası) yalnızca yanıt olur; sunucu tarafı hatalar (`ctx.http` vb.) loga da yazılır. */
    private fun failed(what: String, e: ScriptFail): Nothing {
        if (e.status >= 500) log("[script] $what: ${e.message}")
        throw ScriptException(e.status, e.message ?: "")
    }

    /** `ctx`: auth, env, log, db, http ve (güncelleme/silmede) current. */
    private fun context(cx: Context, caller: Caller, current: JsonObject?): Scriptable {
        val ctx = toJs(cx, buildJsonObject {
            put("auth", buildJsonObject {
                when (caller) {
                    Caller.Anonymous -> put("type", JsonPrimitive("anonymous"))
                    Caller.Admin -> put("type", JsonPrimitive("admin"))
                    is Caller.User -> {
                        put("type", JsonPrimitive("user"))
                        put("id", JsonPrimitive(caller.id))
                        put("role", JsonPrimitive(caller.role))
                    }
                    is Caller.Key -> {
                        put("type", JsonPrimitive("key"))
                        put("key", JsonPrimitive(caller.name))
                    }
                }
                put("admin", JsonPrimitive(caller.isAdmin))
            })
            put("env", JsonObject(config.env.mapValues { JsonPrimitive(it.value) }))
            current?.let { put("current", it) }
        }) as Scriptable
        ScriptableObject.putProperty(ctx, "log", fn("log") { _, args -> log("[script] " + args.joinToString(" ") { Context.toString(it) }); null })

        val db = cx.newObject(scope)
        fun store() = this.db ?: throw ScriptException(503, tr("ctx.db hazır değil"))
        ScriptableObject.putProperty(db, "list", fn("list") { c, a -> toJs(c, store().list(str(a, 0), obj(c, a, 1))) })
        ScriptableObject.putProperty(db, "get", fn("get") { c, a -> toJs(c, store().get(str(a, 0), id(a, 1))) })
        ScriptableObject.putProperty(db, "create", fn("create") { c, a -> toJs(c, store().create(str(a, 0), obj(c, a, 1))) })
        ScriptableObject.putProperty(db, "update", fn("update") { c, a -> toJs(c, store().update(str(a, 0), id(a, 1), obj(c, a, 2))) })
        ScriptableObject.putProperty(db, "delete", fn("delete") { _, a -> store().delete(str(a, 0), id(a, 1)); null })
        ScriptableObject.putProperty(ctx, "db", db)

        val http = cx.newObject(scope)
        ScriptableObject.putProperty(http, "get", fn("get") { c, a -> toJs(c, this.http.request("GET", str(a, 0), null, obj(c, a, 1))) })
        ScriptableObject.putProperty(http, "post", fn("post") { c, a -> toJs(c, this.http.request("POST", str(a, 0), fromJs(c, a.getOrNull(1)), obj(c, a, 2))) })
        ScriptableObject.putProperty(http, "request", fn("request") { c, a ->
            toJs(c, this.http.request(str(a, 0).uppercase(), str(a, 1), fromJs(c, a.getOrNull(2)), obj(c, a, 3)))
        })
        ScriptableObject.putProperty(ctx, "http", http)

        val mail = cx.newObject(scope)
        ScriptableObject.putProperty(mail, "send", fn("send") { _, a ->
            val send = this.mail ?: throw ScriptException(503, tr("ctx.mail: e-posta gönderimi kapalı (Sihirbaz → Kullanıcılar → E-posta)"))
            try {
                send(str(a, 0), str(a, 1), str(a, 2), a.getOrNull(3)?.takeIf { it is CharSequence }?.toString())
            } catch (e: IllegalArgumentException) {
                throw ScriptException(400, "ctx.mail: ${e.message}")
            } catch (e: IllegalStateException) {
                throw ScriptException(429, "ctx.mail: ${e.message}")
            } catch (e: java.io.IOException) {
                throw ScriptException(502, tr("ctx.mail: gönderilemedi: {0}", e.message))
            }
            null
        })
        ScriptableObject.putProperty(ctx, "mail", mail)

        val push = cx.newObject(scope)
        ScriptableObject.putProperty(push, "send", fn("send") { _, a ->
            val send = this.push ?: throw ScriptException(503, tr("ctx.push: web push kapalı (Sihirbaz → Kullanıcılar → Web push)"))
            val user = (a.getOrNull(0) as? Number)?.toLong() ?: str(a, 0).toLongOrNull() ?: throw ScriptException(400, tr("ctx.push.send: kullanıcı kimliği gerekli"))
            try {
                send(user, str(a, 1), a.getOrNull(2)?.takeIf { it is CharSequence }?.toString(), a.getOrNull(3)?.takeIf { it is CharSequence }?.toString())
            } catch (e: ApiException) {
                throw ScriptException(e.status, "ctx.push: ${e.message}")
            }
        })
        ScriptableObject.putProperty(ctx, "push", push)

        val jobs = cx.newObject(scope)
        ScriptableObject.putProperty(jobs, "enqueue", fn("enqueue") { c, a ->
            val sink = this.jobs ?: throw ScriptException(503, tr("ctx.jobs: iş kuyruğu hazır değil"))
            val name = str(a, 0)
            val type = jobTypes[name] ?: throw ScriptException(400, tr("ctx.jobs.enqueue: '{0}' iş türü tanımlı değil (jobAdd)", name))
            val options = obj(c, a, 2)
            fun num(key: String) = (options[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val delay = num("delaySec")?.toLong()?.coerceIn(0, 30L * 86_400) ?: 0
            val attempts = num("retries")?.toInt()?.let { it + 1 } ?: type.attempts
            val key = (options["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(200)
            sink.enqueue(name, fromJs(c, a.getOrNull(1)), delay, attempts, key).toDouble()
        })
        ScriptableObject.putProperty(ctx, "jobs", jobs)

        val channels = cx.newObject(scope)
        ScriptableObject.putProperty(channels, "publish", fn("publish") { c, a ->
            val publish = this.channels ?: throw ScriptException(503, tr("ctx.channels: WebSocket kanalları kapalı (Sihirbaz → Genel → Canlı abonelik)"))
            publish(str(a, 0), fromJs(c, a.getOrNull(1)))
        })
        ScriptableObject.putProperty(ctx, "channels", channels)

        fun phone(what: String) = this.phone ?: throw ScriptException(503, tr("{0}: telefon özellikleri kapalı (Sihirbaz → Genel → Telefon özellikleri)", what))
        fun phoneCall(what: String, block: () -> JsonObject): JsonObject = try {
            block()
        } catch (e: ApiException) {
            throw ScriptException(e.status, "$what: ${e.message}")
        }
        val sms = cx.newObject(scope)
        ScriptableObject.putProperty(sms, "send", fn("send") { c, a ->
            toJs(c, phoneCall("ctx.sms.send") { phone("ctx.sms").sendSms(str(a, 0), str(a, 1), "script") })
        })
        ScriptableObject.putProperty(ctx, "sms", sms)
        val device = cx.newObject(scope)
        ScriptableObject.putProperty(device, "location", fn("location") { c, _ -> toJs(c, phoneCall("ctx.device.location") { phone("ctx.device").location() }) })
        ScriptableObject.putProperty(device, "status", fn("status") { c, _ -> toJs(c, phoneCall("ctx.device.status") { phone("ctx.device").status() }) })
        ScriptableObject.putProperty(ctx, "device", device)
        return ctx
    }

    private fun fn(name: String, impl: (Context, Array<Any?>) -> Any?) = HostFunction(scope, name, impl)

    private fun toJs(cx: Context, json: JsonElement): Any = toJs(cx, scope, json)

    private fun fromJs(cx: Context, value: Any?): JsonElement = fromJs(cx, scope, value)

    private fun obj(cx: Context, args: Array<Any?>, i: Int): JsonObject = fromJs(cx, args.getOrNull(i)) as? JsonObject ?: JsonObject(emptyMap())

    companion object {
        private const val REPLY_MARK = "@reply"
        private val DEADLINE = Any()
        private val RESERVED = setOf("collections", "files", "auth", "health", "realtime", "batch", "_schema", "_metrics", "_openapi.json", "_webhooks", "push", "_audit", "_firewall", "_alerts", "_jobs", "views", "_phone", "_apps", "_automations")
        private val PATH = Regex("^(/(:?[A-Za-z0-9_.~-]+))+$|^/$")
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        private val JOB_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

        private val FACTORY = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1 // yorumlama kipi
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 10_000
                maximumInterpreterStackDepth = 1_000
                setClassShutter { false } // hiçbir Java sınıfı görünmez
            }

            override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
                Context.FEATURE_THREAD_SAFE_OBJECTS -> true
                Context.FEATURE_LOCATION_INFORMATION_IN_ERROR -> true
                Context.FEATURE_ENHANCED_JAVA_ACCESS -> false
                else -> super.hasFeature(cx, featureIndex)
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val deadline = cx.getThreadLocal(DEADLINE) as Long? ?: return
                if (System.nanoTime() > deadline) throw ScriptTimeout()
            }
        }

        /** `files/scripts` klasöründeki .js dosyaları (ada göre sırayla). */
        fun sources(dir: File?): List<ScriptSource> =
            dir?.listFiles { f -> f.isFile && f.name.endsWith(".js") }.orEmpty().sortedBy { it.name }
                .map { ScriptSource(it.name, it.readText()) }

        /**
         * Script'leri derler ve üst düzey kodlarını çalıştırır (uç, olay ve görev kayıtları). Sözdizimi hatası, kayıt
         * hatası veya süre aşımı [ScriptLoadException]. [collections]: olayların bağlanabileceği koleksiyonlar.
         */
        fun load(sources: List<ScriptSource>, config: ScriptsConfig, collections: Set<String>, log: (String) -> Unit = {}, roles: Set<String> = emptySet()): ScriptRuntime {
            val errors = mutableListOf<String>()
            val routes = mutableListOf<ScriptRoute>()
            val hooks = linkedMapOf<HookEvent, MutableList<Pair<String, Function>>>()
            val crons = mutableListOf<CronJob>()
            val jobTypes = linkedMapOf<String, JobType>()
            val cx = FACTORY.enterContext()
            try {
                val scope = cx.initSafeStandardObjects()
                var current = ""
                var loading = true
                fun define(name: String, impl: (Context, Array<Any?>) -> Any?) =
                    ScriptableObject.putProperty(scope, name, HostFunction(scope, name, impl))
                fun registering(what: String) { if (!loading) throw ScriptFail(500, tr("{0} yalnızca script'in üst düzeyinde çağrılabilir", what)) }
                fun problem(message: String) { errors += "$current: $message" }

                define("routerAdd") { _, a ->
                    registering("routerAdd")
                    val method = str(a, 0).uppercase()
                    val path = str(a, 1)
                    val handler = a.getOrNull(2) as? Function
                    val access = a.getOrNull(3)?.takeIf { it !is Undefined }?.let { Context.toString(it) } ?: "public"
                    val role = access.removePrefix("role:").takeIf { access.startsWith("role:") }
                    val rule = if (role != null) Rule.AUTH.takeIf { role in roles }
                    else Rule.entries.firstOrNull { it.name.lowercase() == access && it != Rule.OWNER && it != Rule.TEAM && it != Rule.NOBODY }
                    val first = path.trim('/').substringBefore('/')
                    when {
                        method !in METHODS -> problem(tr("routerAdd: yöntem {0} olmalı ('{1}')", METHODS.joinToString("/"), method))
                        !PATH.matches(path) -> problem(tr("routerAdd: yol '/' ile başlamalı, harf/rakam ve :parametre içermeli ('{0}')", path))
                        first in RESERVED -> problem(tr("routerAdd: '/{0}' sunucunun kendi uçlarına ayrılmış ('{1}')", first, path))
                        handler == null -> problem(tr("routerAdd({0} {1}): üçüncü değer işleyici fonksiyon olmalı", method, path))
                        rule == null && role != null -> problem(tr("routerAdd({0} {1}): '{2}' rolü tanımlı değil", method, path, role))
                        rule == null -> problem(tr("routerAdd({0} {1}): erişim public, auth, admin veya role:<ad> olmalı ('{2}')", method, path, access))
                        routes.any { it.method == method && it.path == path } -> problem(tr("routerAdd: {0} {1} iki kez tanımlandı", method, path))
                        else -> routes += ScriptRoute(method, path, rule, current, handler, role)
                    }
                    null
                }
                for (event in HookEvent.entries) {
                    define(event.function) { _, a ->
                        registering(event.function)
                        val collection = str(a, 0)
                        val handler = a.getOrNull(1) as? Function
                        when {
                            collection != "*" && collection !in collections -> problem(tr("{0}: '{1}' koleksiyonu yok", event.function, collection))
                            handler == null -> problem(tr("{0}({1}): ikinci değer fonksiyon olmalı", event.function, collection))
                            else -> hooks.getOrPut(event) { mutableListOf() } += collection to handler
                        }
                        null
                    }
                }
                define("cronAdd") { _, a ->
                    registering("cronAdd")
                    val expr = str(a, 0)
                    val handler = a.getOrNull(1) as? Function
                    try {
                        val schedule = CronSchedule.parse(expr)
                        if (handler == null) problem(tr("cronAdd({0}): ikinci değer fonksiyon olmalı", expr)) else crons += CronJob(schedule, current, handler)
                    } catch (e: IllegalArgumentException) {
                        problem("cronAdd: ${e.message}")
                    }
                    null
                }
                define("jobAdd") { c, a ->
                    registering("jobAdd")
                    val name = str(a, 0)
                    val handler = a.getOrNull(1) as? Function
                    val options = fromJs(c, scope, a.getOrNull(2)) as? JsonObject ?: JsonObject(emptyMap())
                    fun num(key: String) = (options[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
                    val retries = num("retries") ?: 2
                    val timeout = num("timeoutSec") ?: config.cronTimeoutSec
                    val concurrency = num("concurrency") ?: 1
                    when {
                        !JOB_NAME.matches(name) -> problem(tr("jobAdd: ad harf veya rakamla başlamalı, harf/rakam/_/- içermeli ('{0}')", name))
                        name in jobTypes -> problem(tr("jobAdd: '{0}' iki kez tanımlandı", name))
                        handler == null -> problem(tr("jobAdd({0}): ikinci değer işleyici fonksiyon olmalı", name))
                        retries !in 0..19 -> problem(tr("jobAdd({0}): retries 0-19 olmalı", name))
                        timeout !in 1..3_600 -> problem(tr("jobAdd({0}): timeoutSec 1-3600 olmalı", name))
                        concurrency !in 1..16 -> problem(tr("jobAdd({0}): concurrency 1-16 olmalı", name))
                        else -> jobTypes[name] = JobType(name, current, retries + 1, timeout, concurrency, handler)
                    }
                    null
                }
                define("fail") { _, a ->
                    val status = a.getOrNull(0)?.let { Context.toNumber(it).toInt() }?.takeIf { it in 400..599 } ?: 400
                    throw ScriptFail(status, a.getOrNull(1)?.takeIf { it !is Undefined }?.let { Context.toString(it) } ?: tr("İstek reddedildi"))
                }
                define("reply") { c, a ->
                    val o = c.newObject(scope)
                    ScriptableObject.putProperty(o, REPLY_MARK, true)
                    ScriptableObject.putProperty(o, "status", a.getOrNull(0) ?: 200)
                    ScriptableObject.putProperty(o, "body", a.getOrNull(1) ?: null)
                    a.getOrNull(2)?.takeIf { it is Scriptable }?.let { ScriptableObject.putProperty(o, "headers", it) }
                    o
                }
                val console = cx.newObject(scope)
                ScriptableObject.putProperty(console, "log", HostFunction(scope, "log") { _, a ->
                    log("[script] " + a.joinToString(" ") { Context.toString(it) }); null
                })
                ScriptableObject.putProperty(scope, "console", console)

                for (src in sources) {
                    current = src.name
                    try {
                        val script = cx.compileString(src.text, src.name, 1, null)
                        cx.putThreadLocal(DEADLINE, System.nanoTime() + config.timeoutMs * 1_000_000L)
                        script.exec(cx, scope)
                    } catch (e: EvaluatorException) {
                        errors += "${e.sourceName() ?: src.name}:${e.lineNumber()}: ${e.details()}"
                    } catch (e: ScriptTimeout) {
                        errors += tr("{0}: üst düzey kod süre sınırını ({1} ms) aştı", src.name, config.timeoutMs)
                    } catch (e: ScriptFail) {
                        errors += "${src.name}: ${e.message}"
                    } catch (e: WrappedException) {
                        errors += "${src.name}:${e.lineNumber()}: ${e.wrappedException.message}"
                    } catch (e: RhinoException) {
                        errors += "${e.sourceName() ?: src.name}:${e.lineNumber()}: ${e.details()}"
                    }
                }
                loading = false
                if (errors.isNotEmpty()) throw ScriptLoadException(errors)
                log("[script] ${sources.size} dosya yüklendi: ${routes.size} uç, ${hooks.values.sumOf { it.size }} olay, ${crons.size} zamanlanmış görev, ${jobTypes.size} iş türü")
                return ScriptRuntime(config, log, scope, routes, hooks, crons, jobTypes)
            } finally {
                Context.exit()
            }
        }

        /** Yalnızca sözdizimi denetimi (uygulamadaki düzenleyici); hatalar `ad:satır: ileti`. */
        fun check(source: ScriptSource): List<String> {
            val cx = FACTORY.enterContext()
            return try {
                cx.compileString(source.text, source.name, 1, null)
                emptyList()
            } catch (e: EvaluatorException) {
                listOf("${source.name}:${e.lineNumber()}: ${e.details()}")
            } finally {
                Context.exit()
            }
        }

        internal fun toJs(cx: Context, scope: Scriptable, json: JsonElement): Any {
            val jsonObject = ScriptableObject.getProperty(scope, "JSON") as Scriptable
            return ScriptableObject.callMethod(cx, jsonObject, "parse", arrayOf(json.toString()))
        }

        internal fun fromJs(cx: Context, scope: Scriptable, value: Any?): JsonElement {
            if (value == null || value is Undefined) return JsonNull
            if (value is Function) return JsonNull
            val text = org.mozilla.javascript.NativeJSON.stringify(cx, scope, value, null, null)
            if (text is Undefined) return JsonNull
            return Json.parseToJsonElement(text.toString())
        }

        private fun str(args: Array<Any?>, i: Int): String = args.getOrNull(i)?.takeIf { it !is Undefined }?.let { Context.toString(it) }
            ?: throw ScriptFail(500, tr("{0}. değer eksik", i + 1))

        private fun id(args: Array<Any?>, i: Int): Long = args.getOrNull(i)?.takeIf { it !is Undefined }?.let { Context.toNumber(it) }
            ?.takeIf { !it.isNaN() && it > 0 }?.toLong() ?: throw ScriptFail(500, tr("{0}. değer kayıt kimliği olmalı", i + 1))
    }
}

/** Komut gözlemcisinin süre aşımında attığı hata: `Error` olduğu için script'in try/catch'i yakalayamaz. */
internal class ScriptTimeout : Error(tr("script süre sınırını aştı"))

/** `fail(status, mesaj)`. */
internal class ScriptFail(val status: Int, message: String) : RuntimeException(message)

/** Script'e açılan fonksiyon; Java nesnesi değil, Rhino fonksiyonu olduğu için Java sınıflarına kapı açmaz. */
internal class HostFunction(scope: Scriptable, private val name: String, private val impl: (Context, Array<Any?>) -> Any?) :
    BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any = impl(cx, args) ?: Undefined.instance

    override fun getFunctionName(): String = name
}
