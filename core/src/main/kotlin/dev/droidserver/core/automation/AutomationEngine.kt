package dev.droidserver.core.automation

import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.RuleConditions
import dev.droidserver.core.api.WriteGate
import dev.droidserver.core.config.AutomationAction
import dev.droidserver.core.config.GeoPoint
import dev.droidserver.core.config.AutomationActionType
import dev.droidserver.core.config.AutomationConfig
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.mqtt.MqttBroker
import dev.droidserver.core.realtime.RecordEvent
import dev.droidserver.core.script.CronSchedule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

/** Bir kuralın bir çalışması (uygulamada ve `/_automations` ucunda gösterilir). */
data class AutomationRun(
    val rule: String,
    /** `todos.create`, `schedule` veya `test`. */
    val trigger: String,
    val ok: Boolean,
    /** Yapılanların veya hatanın kısa açıklaması. */
    val message: String,
    val at: Instant,
    val durationMs: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rule", JsonPrimitive(rule))
        put("trigger", JsonPrimitive(trigger))
        put("ok", JsonPrimitive(ok))
        put("message", JsonPrimitive(message))
        put("at", JsonPrimitive(at.toString()))
        put("durationMs", JsonPrimitive(durationMs))
    }
}

/** Uygulamanın otomasyon ekranı için: o anki deploy'un motoru (deploy'lar arasında ServerEngine tutar). */
class AutomationHost {
    @Volatile
    var engine: AutomationEngine? = null
        internal set

    fun runs(): List<AutomationRun> = engine?.runs().orEmpty()

    /** "Şimdi dene"; kural yoksa veya sunucu çalışmıyorsa [IllegalArgumentException] / [IllegalStateException]. */
    fun runNow(name: String): AutomationRun = (engine ?: throw IllegalStateException(tr("Sunucu çalışmıyor veya etkin otomasyon kuralı yok"))).runNow(name)
}

/** Eylemlerin dış dünyaya çıkışları; kapalı olan özellik null. */
class AutomationOutputs(
    val mail: ((to: String, subject: String, text: String) -> Unit)? = null,
    val push: ((user: Long, title: String, body: String, url: String?) -> Unit)? = null,
    val webhook: ((name: String, event: String, collection: String?, record: JsonObject) -> Unit)? = null,
    /** MQTT yayını (konu, içerik, tutulsun mu); kaç cihaza gittiği. */
    val mqtt: ((topic: String, payload: String, retain: Boolean) -> Int)? = null,
    /** YZ (sistem talimatı, istem) → yanıt metni. */
    val ai: ((system: String, prompt: String) -> String)? = null,
)

/**
 * Otomasyon kurallarını çalıştırır. Kayıt olayları ([onRecord]) sırayla tek bir arka plan iş parçacığında işlenir
 * (istek beklemez, eylemler sıralı kalır); zamanlanmış kurallar dakika başında denetlenir ([start]).
 *
 * Döngü koruması: bir kuralın yaptığı yazma yeniden olay üretir ama [MAX_DEPTH] zincirden sonra kurallar artık
 * tetiklenmez; değer değiştirmeyen güncelleme hiç yazılmaz.
 */
class AutomationEngine(
    private val rules: List<AutomationConfig>,
    private val collections: List<CollectionConfig>,
    private val services: Map<String, RecordService>,
    private val writes: WriteGate,
    private val outputs: AutomationOutputs,
    /** Kural yazması sonucu oluşan kayıt değişikliği: canlı abonelere, webhook'lara ve (derinlik artarak) kurallara. */
    private val changed: (collection: String, action: RecordEvent.Action, record: JsonObject, depth: Int) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    /** Testlerde true: olaylar çağıran iş parçacığında hemen işlenir. */
    private val synchronous: Boolean = false,
) : AutoCloseable {
    private val byCollection = collections.associateBy { it.name }
    private val active = rules.filter { it.enabled }
    private val schedules = active.filter { it.trigger.scheduled }.associateWith { CronSchedule.parse(it.trigger.schedule!!) }
    private val history = ArrayDeque<AutomationRun>()

    private val worker = if (synchronous || active.isEmpty()) null else ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(MAX_PENDING),
        { r -> Thread(r, "ds-automation").apply { isDaemon = true } },
        { _, _ -> log(tr("Otomasyon: bekleyen iş sınırı ({0}) doldu, olay atlandı", MAX_PENDING)) },
    )
    private var ticker: ScheduledExecutorService? = null

    fun runs(): List<AutomationRun> = synchronized(history) { history.toList().asReversed() }

    /** Kayıt olayı; eşleşen kurallar arka planda çalışır. [depth] kural zincirinde kaçıncı adım olduğu. */
    fun onRecord(collection: String, action: RecordEvent.Action, record: JsonObject, depth: Int = 0) {
        if (depth >= MAX_DEPTH) return
        val matching = active.filter { r ->
            !r.trigger.scheduled && !r.trigger.onMqtt && r.trigger.collection == collection && action.wire in r.trigger.events
        }
        if (matching.isEmpty()) return
        val task = Runnable {
            for (rule in matching) {
                if (!conditionMatches(rule, record)) continue
                execute(rule, "$collection.${action.wire}", record, depth)
            }
        }
        worker?.execute(task) ?: task.run()
    }

    /** Cihazdan gelen MQTT mesajı: konusu süzgece uyan kurallar arka planda çalışır (mesaj kayda yazılmaz). */
    fun onMqtt(topic: String, payload: ByteArray) {
        val matching = active.filter { r -> r.trigger.onMqtt && MqttBroker.matches(r.trigger.mqtt!!, topic) }
        if (matching.isEmpty()) return
        val text = payload.decodeToString()
        val rec = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val vars = mapOf("topic" to topic, "payload" to text)
        val task = Runnable { for (rule in matching) execute(rule, "mqtt:$topic", rec, 0, vars) }
        worker?.execute(task) ?: task.run()
    }

    /** Dakika başında zamanı gelen kurallar; çalıştırılanların adları. */
    fun tick(now: LocalDateTime): List<String> {
        if (writes.isPaused) return emptyList()
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        return schedules.filter { (_, s) -> s.matches(minute) }.keys.map { rule ->
            runScheduled(rule, "schedule")
            rule.name
        }
    }

    /** "Şimdi dene": zamanlanmış kural hemen çalışır; olay kuralı koşula uyan son kayıtla çalışır. */
    fun runNow(name: String): AutomationRun {
        val rule = rules.firstOrNull { it.name == name } ?: throw IllegalArgumentException(tr("'{0}' adlı otomasyon kuralı yok", name))
        if (rule.trigger.scheduled) return runScheduled(rule, "test")
        if (rule.trigger.onMqtt) return execute(rule, "test", JsonObject(emptyMap()), 0, mapOf("topic" to rule.trigger.mqtt!!, "payload" to ""))
        val collection = rule.trigger.collection!!
        val latest = services.getValue(collection).list(1, 1, "-id", conditionParams(rule)).items.firstOrNull()
            ?: return record(rule, "test", false, tr("Koşula uyan kayıt yok; denemek için önce bir kayıt ekleyin"), System.nanoTime())
        return execute(rule, "test", latest, 0)
    }

    fun start() {
        if (schedules.isEmpty() || ticker != null) return
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ds-automation-tick").apply { isDaemon = true } }.also { t ->
            val now = LocalDateTime.now()
            val delay = ChronoUnit.MILLIS.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1))
            t.scheduleAtFixedRate({ runCatching { tick(LocalDateTime.now()) } }, delay, 60_000, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        ticker?.shutdownNow()
        worker?.shutdownNow()
    }

    private fun conditionParams(rule: AutomationConfig): List<Pair<String, String>> =
        rule.condition?.split('&')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.orEmpty()

    private fun conditionMatches(rule: AutomationConfig, record: JsonObject): Boolean {
        val cond = rule.condition?.takeIf { it.isNotBlank() } ?: return true
        val c = byCollection[rule.trigger.collection] ?: return false
        return runCatching { RuleConditions.matches(RuleConditions.parse(c, cond, null), record) }.getOrDefault(false)
    }

    private fun runScheduled(rule: AutomationConfig, trigger: String): AutomationRun {
        val started = System.nanoTime()
        val collection = rule.trigger.collection
        return try {
            val matched = collection?.let { matching(it, conditionParams(rule)) }.orEmpty()
            val vars = mapOf("count" to (matched.size.toString()), "collection" to (collection ?: ""), "event" to trigger)
            val done = mutableListOf<String>()
            for (action in rule.actions) {
                done += if (action.type == AutomationActionType.UPDATE) {
                    val n = matched.count { update(rule, collection!!, it, action, vars, 0) }
                    tr("{0} kayıt güncellendi", n)
                } else {
                    perform(rule, action, collection, JsonObject(emptyMap()), vars, 0)
                }
            }
            record(rule, trigger, true, done.joinToString("; "), started)
        } catch (e: Exception) {
            failed(rule, trigger, e, started)
        }
    }

    /** Zamanlanmış güncelleme için koşula uyan kayıtlar (en çok [MAX_BULK]). */
    private fun matching(collection: String, params: List<Pair<String, String>>): List<JsonObject> {
        val svc = services.getValue(collection)
        val out = mutableListOf<JsonObject>()
        var page = 1
        while (out.size < MAX_BULK) {
            val p = svc.list(page, 500, null, params)
            out += p.items
            if (p.items.size < 500 || out.size >= p.totalItems) break
            page++
        }
        return out.take(MAX_BULK)
    }

    private fun execute(rule: AutomationConfig, trigger: String, rec: JsonObject, depth: Int, extra: Map<String, String> = emptyMap()): AutomationRun {
        val started = System.nanoTime()
        val collection = rule.trigger.collection
        return try {
            val vars = mapOf("collection" to (collection ?: ""), "event" to trigger, "count" to "1") + extra
            val done = rule.actions.map { action ->
                if (action.type == AutomationActionType.UPDATE) {
                    if (update(rule, collection!!, rec, action, vars, depth)) tr("kayıt güncellendi") else tr("değişiklik yok")
                } else {
                    perform(rule, action, collection, rec, vars, depth)
                }
            }
            record(rule, trigger, true, done.joinToString("; "), started)
        } catch (e: Exception) {
            failed(rule, trigger, e, started)
        }
    }

    /** UPDATE dışındaki eylemler; yapılanın kısa açıklaması. */
    private fun perform(rule: AutomationConfig, a: AutomationAction, collection: String?, rec: JsonObject, vars: Map<String, String>, depth: Int): String {
        fun fill(t: String?) = fill(t.orEmpty(), rule, rec, vars)
        return when (a.type) {
            AutomationActionType.EMAIL -> {
                val send = outputs.mail ?: error(tr("E-posta gönderimi kapalı"))
                val to = fill(a.to).trim()
                if (to.isEmpty() || '@' !in to) error(tr("Alıcı adresi boş veya geçersiz: '{0}'", to))
                send(to, fill(a.subject), fill(a.body))
                tr("e-posta gönderildi: {0}", mask(to))
            }
            AutomationActionType.PUSH -> {
                val send = outputs.push ?: error(tr("Web push kapalı"))
                val target = a.to?.trim().orEmpty()
                val user = if (target == AutomationAction.OWNER) {
                    val ownerField = byCollection[collection]?.ownerField
                    ownerField?.let { (rec[it] as? JsonPrimitive)?.longOrNull } ?: error(tr("Kaydın sahibi yok"))
                } else {
                    fill(target).trim().toLongOrNull() ?: error(tr("Kullanıcı kimliği geçersiz: '{0}'", fill(target)))
                }
                send(user, fill(a.subject), fill(a.body), a.url?.let(::fill)?.ifBlank { null })
                tr("bildirim gönderildi (kullanıcı {0})", user)
            }
            AutomationActionType.WEBHOOK -> {
                val send = outputs.webhook ?: error(tr("Webhook'lar kapalı"))
                send(a.webhook!!, "automation.${rule.name}", collection, rec)
                tr("webhook kuyruğa alındı: {0}", a.webhook)
            }
            AutomationActionType.CREATE -> {
                val target = a.collection!!
                val c = byCollection.getValue(target)
                val created = writes.write { services.getValue(target).create(values(c, a.values, rule, rec, vars)) }
                changed(target, RecordEvent.Action.CREATE, created, depth + 1)
                tr("'{0}' koleksiyonunda kayıt oluşturuldu (#{1})", target, created["id"])
            }
            AutomationActionType.LOG -> {
                log(tr("Otomasyon '{0}': {1}", rule.name, fill(a.body)))
                tr("loga yazıldı")
            }
            AutomationActionType.MQTT -> {
                val send = outputs.mqtt ?: error(tr("MQTT kapalı"))
                val topic = fill(a.topic).trim()
                if (topic.isEmpty() || '+' in topic || '#' in topic) error(tr("MQTT konusu geçersiz: '{0}'", topic))
                val n = send(topic, fill(a.body), a.retain)
                tr("MQTT: {0} ({1} cihaz)", topic, n)
            }
            AutomationActionType.AI -> {
                val ask = outputs.ai ?: error(tr("Yapay zekâ kapalı"))
                val c = byCollection[collection] ?: error(tr("Koleksiyon yok"))
                val f = c.fields.firstOrNull { it.name == a.field } ?: error(tr("Alan yok: {0}", a.field))
                val id = (rec["id"] as? JsonPrimitive)?.longOrNull ?: error(tr("Kaydın kimliği yok"))
                val options = f.options.orEmpty()
                val system = when (f.type) {
                    FieldType.SELECT -> tr("Yalnızca şu seçeneklerden birini aynen yaz, başka hiçbir şey yazma: {0}", options.joinToString(", "))
                    FieldType.MULTISELECT -> tr("Yalnızca şu seçeneklerden uyanları virgülle ayırarak aynen yaz, başka hiçbir şey yazma: {0}", options.joinToString(", "))
                    else -> tr("Yalnızca istenen metni yaz; giriş, açıklama veya tırnak ekleme.")
                }
                val answer = ask(system, fill(a.body)).trim().trim('"', '\'', '.', ' ')
                val value: JsonElement = when (f.type) {
                    FieldType.SELECT -> JsonPrimitive(options.firstOrNull { it.equals(answer, ignoreCase = true) } ?: options.firstOrNull { answer.contains(it, ignoreCase = true) }
                        ?: error(tr("YZ yanıtı seçeneklerden biri değil: {0}", answer.take(80))))
                    FieldType.MULTISELECT -> JsonArray(options.filter { answer.contains(it, ignoreCase = true) }.map(::JsonPrimitive))
                    FieldType.TEXT -> JsonPrimitive(f.maxLength?.let { answer.take(it) } ?: answer)
                    else -> typed(f.type, answer)
                }
                val updated = writes.write { services.getValue(collection!!).update(id, JsonObject(mapOf(f.name to value))) }
                changed(collection!!, RecordEvent.Action.UPDATE, updated, depth + 1)
                tr("YZ: '{0}' alanı yazıldı", f.name)
            }
            AutomationActionType.UPDATE -> error("UPDATE")
        }
    }

    /** Kaydı günceller; değerler zaten aynıysa yazmaz. Yazıldıysa true. */
    private fun update(rule: AutomationConfig, collection: String, rec: JsonObject, a: AutomationAction, vars: Map<String, String>, depth: Int): Boolean {
        val c = byCollection.getValue(collection)
        val id = (rec["id"] as? JsonPrimitive)?.longOrNull ?: error(tr("Kaydın kimliği yok"))
        val body = values(c, a.values, rule, rec, vars)
        if (body.all { (k, v) -> same(rec[k], v) }) return false
        val updated = writes.write { services.getValue(collection).update(id, body) }
        changed(collection, RecordEvent.Action.UPDATE, updated, depth + 1)
        return true
    }

    private fun same(a: JsonElement?, b: JsonElement): Boolean {
        if ((a == null || a is JsonNull) && b is JsonNull) return true
        if (a is JsonPrimitive && b is JsonPrimitive) return a.content == b.content
        return a == b
    }

    /** Şablonları doldurup alan tiplerine çevirir; boş metin null olur. */
    private fun values(c: CollectionConfig, templates: Map<String, String>, rule: AutomationConfig, rec: JsonObject, vars: Map<String, String>): JsonObject =
        JsonObject(templates.mapValues { (field, template) ->
            val text = fill(template, rule, rec, vars)
            val type = c.fields.firstOrNull { it.name == field }?.type
            typed(type, text)
        })

    private fun fill(template: String, rule: AutomationConfig, rec: JsonObject, vars: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m ->
            val key = m.groupValues[1]
            when (key) {
                "now" -> clock().truncatedTo(ChronoUnit.SECONDS).toString()
                "rule" -> rule.name
                in vars -> vars.getValue(key)
                else -> (rec[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: (rec[key]?.takeIf { it !is JsonNull }?.toString() ?: "")
            }
        }

    private fun record(rule: AutomationConfig, trigger: String, ok: Boolean, message: String, started: Long): AutomationRun {
        val run = AutomationRun(rule.name, trigger, ok, message, clock(), (System.nanoTime() - started) / 1_000_000)
        synchronized(history) {
            history.addLast(run)
            while (history.size > HISTORY) history.removeFirst()
        }
        return run
    }

    private fun failed(rule: AutomationConfig, trigger: String, e: Exception, started: Long): AutomationRun {
        val message = e.message ?: e.javaClass.simpleName
        log(tr("Otomasyon '{0}' çalışmadı ({1}): {2}", rule.name, trigger, message))
        return record(rule, trigger, false, message, started)
    }

    companion object {
        const val MAX_DEPTH = 3
        const val MAX_PENDING = 1_000
        const val MAX_BULK = 1_000
        const val HISTORY = 100
        private val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

        fun typed(type: FieldType?, text: String): JsonElement {
            if (text.isEmpty()) return JsonNull
            return when (type) {
                FieldType.INTEGER, FieldType.RELATION -> text.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
                FieldType.REAL -> text.trim().replace(',', '.').toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
                FieldType.BOOLEAN -> when (text.trim().lowercase()) {
                    "true", "1", "evet", "yes" -> JsonPrimitive(true)
                    "false", "0", "hayır", "no" -> JsonPrimitive(false)
                    else -> JsonPrimitive(text)
                }
                FieldType.JSON, FieldType.MULTISELECT -> runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                // "41.01,28.97" veya JSON nesne; uymazsa metin kalır (kayıt servisi reddeder).
                FieldType.GEO -> GeoPoint.parse(text)?.json() ?: runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                else -> JsonPrimitive(text)
            }
        }

        private fun mask(email: String): String {
            val at = email.indexOf('@')
            return if (at <= 1) email else email.take(1) + "***" + email.substring(at)
        }
    }
}
