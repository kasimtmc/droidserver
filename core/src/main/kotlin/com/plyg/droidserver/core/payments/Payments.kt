package com.plyg.droidserver.core.payments

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.api.toJson
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.PaymentPlan
import com.plyg.droidserver.core.config.PaymentProvider
import com.plyg.droidserver.core.config.PaymentsConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import com.plyg.droidserver.core.i18n.I18n
import com.plyg.droidserver.core.i18n.Lang
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Instant

/** Ödeme sayfası isteği; tutar en küçük birimde (kuruş, sent). */
data class CheckoutRequest(
    val token: String,
    val amount: Long,
    val currency: String,
    val description: String,
    val email: String?,
    /** iyzico'nun istediği alıcı bilgisi (ad, soyad, kimlik no, telefon, adres, şehir, ülke); diğerleri kullanmaz. */
    val buyer: JsonObject,
    /** Alıcının ödemeden sonra döneceği sayfa (`/payments/<token>`). */
    val returnUrl: String,
    /** İsteği yapanın IP'si (iyzico zorunlu tutar). */
    val ip: String,
)

data class SubscribeRequest(val token: String, val plan: PaymentPlan, val user: Long, val email: String, val returnUrl: String)

/** Sağlayıcıdaki kimlik ve ödeme sayfasının adresi. */
data class Checkout(val external: String, val url: String)

/** Sağlayıcı bildiriminden çıkan olay. [id] tekrar ayıklamada kullanılır (sağlayıcının olay kimliği). */
sealed interface PaymentEvent {
    val id: String

    data class Payment(
        override val id: String,
        /** Bizim token'ımız (sağlayıcıya meta veri olarak verilir), sağlayıcı kimliği ya da [reference]; biri yeter. */
        val token: String?,
        val external: String?,
        val status: String,
        val reference: String? = null,
        val receipt: String? = null,
        val email: String? = null,
    ) : PaymentEvent

    data class Subscription(
        override val id: String,
        val external: String,
        val status: String,
        /** Abone olan kullanıcı (ödeme sayfasına meta veri olarak verilir). */
        val user: Long?,
        val price: String?,
        val periodEnd: Instant? = null,
        /** Dönem sonunda bitecek (iptal edildi ama süresi sürüyor); bilinmiyorsa null. */
        val canceling: Boolean? = null,
    ) : PaymentEvent
}

/** Bir sağlayıcının API'si: ödeme sayfası, imzalı bildirim ve durum sorgusu. */
interface PaymentGateway {
    fun checkout(r: CheckoutRequest): Checkout

    fun subscribe(r: SubscribeRequest): Checkout =
        throw ApiException(400, tr("Bu sağlayıcıda abonelik yok"))

    /** Bildirimi doğrular ve olaylara çevirir; imza geçersizse 400. İlgisiz olaylar boş liste. */
    fun events(header: (String) -> String?, body: String): List<PaymentEvent>

    /** Sağlayıcıdaki güncel durum (dönüş sayfasında, bildirim gecikirse); bilinmiyorsa null. */
    fun status(external: String): PaymentEvent.Payment? = null

    /** Dönüş isteğinden (iyzico ödeme formu `token` gönderir) durum; diğerleri null. */
    fun returned(form: Map<String, String>): PaymentEvent.Payment? = null

    /** Saklanmayan (süreli) fatura adresi: Paddle; diğerleri null. */
    fun receipt(external: String): String? = null

    /**
     * Ödenmiş ödemenin tam iadesi ([payment] `_payments` satırı). Sağlayıcı hemen onayladıysa iade olayı, onay
     * bekliyorsa null (sonuç bildirimle gelir).
     */
    fun refund(payment: JsonObject, ip: String): PaymentEvent.Payment?

    /** Aboneliği dönem sonunda bitirir; asıl bitiş bildirimle gelir. */
    fun cancel(subscription: String): Unit = throw ApiException(400, tr("Bu sağlayıcıda abonelik yok"))

    /** Sağlayıcının panelinden yapılan iadeyi bulmak için (iyzico; diğerleri bildirim gönderir). */
    fun refunded(payment: JsonObject): Boolean? = null
}

/** Ödeme durumları; sağlayıcıların durumları bunlara çevrilir. */
object PaymentStatus {
    const val PENDING = "pending"
    const val PAID = "paid"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
    const val REFUNDED = "refunded"

    /** Geri dönülmeyen geçişler: ödenmiş ödeme bekliyor/başarısız olmaz (olaylar sırasız gelebilir). */
    fun allowed(from: String, to: String): Boolean = from != to && when (from) {
        PENDING, FAILED -> true
        PAID -> to == REFUNDED
        else -> false
    }
}

/** Abonelik durumları: [ACTIVE] ve [PAST_DUE] rolü korur; [ENDED] eski rolü geri verir. */
object SubscriptionStatus {
    const val ACTIVE = "active"
    const val PAST_DUE = "past_due"
    const val ENDED = "canceled"
}

/** Tutarlar: API'de ondalık (149.90), içeride en küçük birim (14990). */
object Money {
    /** Küsuratsız para birimleri (Stripe listesi). */
    private val ZERO_DECIMAL = setOf("BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF")

    fun digits(currency: String) = if (currency in ZERO_DECIMAL) 0 else 2

    fun parse(v: JsonElement?, currency: String): Long {
        val text = (v as? JsonPrimitive)?.contentOrNull?.trim()?.replace(',', '.')
        val d = text?.toBigDecimalOrNull() ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("amount" to tr("tutar sayı olmalı, örn. 149.90")))
        if (d.signum() <= 0) throw ApiException(400, tr("Doğrulama hatası"), mapOf("amount" to tr("tutar sıfırdan büyük olmalı")))
        if (d.scale() > digits(currency)) throw ApiException(400, tr("Doğrulama hatası"), mapOf("amount" to tr("{0} için en çok {1} ondalık", currency, digits(currency))))
        return d.movePointRight(digits(currency)).longValueExact()
    }

    fun decimal(minor: Long, currency: String): BigDecimal = BigDecimal.valueOf(minor, digits(currency))

    /** Gösterim: Türkçede `149,90 TRY`, diğer dillerde `149.90 TRY`. */
    fun format(minor: Long, currency: String): String {
        val s = decimal(minor, currency).toPlainString()
        return (if (I18n.lang == Lang.TR) s.replace('.', ',') else s) + " " + currency
    }
}

/** Uygulamanın çalışan sunucudaki ödemelere eriştiği yer (iade, son ödemeler). */
class PaymentHost {
    @Volatile
    private var current: Pair<PaymentService, WriteGate>? = null

    internal fun bind(service: PaymentService?, writes: WriteGate) { current = service?.let { it to writes } }

    internal fun unbind(service: PaymentService?) { if (current?.first === service) current = null }

    /** Tam iade (yönetici); ödemeler kurulu değilse [IllegalStateException]. Bloklayıcıdır. */
    fun refund(id: Long): JsonObject {
        val (svc, writes) = current ?: throw IllegalStateException(tr("Sunucu çalışmıyor veya ödemeler kapalı"))
        return writes.write { svc.refund(id, "127.0.0.1") }
    }
}

/**
 * Ödemeler ve abonelikler: bağlantı üretir, sağlayıcı bildirimlerini işler (bir kez), durumu `_payments`'a ve bağlı
 * kaydın [PaymentsConfig.statusField] alanına yazar; abonelikte kullanıcının rolünü plana göre değiştirir.
 */
class PaymentService(
    private val db: SqlDriver,
    private val config: PaymentsConfig,
    private val gateways: Map<PaymentProvider, PaymentGateway>,
    private val collections: Map<String, CollectionConfig>,
    private val records: Map<String, RecordService>,
    /** Kullanıcılar açıksa `_users` (abonelikte rol). */
    private val users: RecordService?,
    /** Bağlı kaydı yönetici olarak günceller (otomasyonlar, canlı aboneler ve webhook'lar duysun diye sunucunun yolundan). */
    private val updateRecord: (collection: String, id: Long, body: JsonObject) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val payments = RecordService(db, SystemCollections.payments)
    private val subscriptions = RecordService(db, SystemCollections.subscriptions)
    private val events = RecordService(db, SystemCollections.paymentEvents)
    private val random = SecureRandom()
    private val base = config.publicUrl.trimEnd('/')

    fun returnUrl(token: String) = "$base${PaymentsConfig.PATH}/$token"

    fun hookUrl(p: PaymentProvider) = "$base${PaymentsConfig.PATH}/hook/${p.wire}"

    private fun gateway(p: PaymentProvider?): Pair<PaymentProvider, PaymentGateway> {
        val c = config.provider(p) ?: throw ApiException(400, if (p == null) tr("Ödeme sağlayıcısı ayarlanmamış") else tr("{0} ayarlanmamış", p.title))
        return c.provider to gateways.getValue(c.provider)
    }

    private fun newToken() = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * Ödeme bağlantısı: `amount` (ondalık), `currency` (varsayılan TRY), `description`, isteğe bağlı `provider`,
     * `collection` + `record` (bağlı kayıt), `email`, `buyer` (iyzico alıcı bilgisi), `data` (serbest meta veri).
     * [writeBack] false ise bağlı kayda bekliyor durumu yazılmaz (otomasyon kendi yazar).
     */
    fun create(body: JsonObject, ip: String, writeBack: Boolean = true): JsonObject {
        val allowed = setOf("amount", "currency", "description", "provider", "collection", "record", "email", "buyer", "data", "user")
        (body.keys - allowed).firstOrNull()?.let { throw ApiException(400, tr("Doğrulama hatası"), mapOf(it to tr("bilinmeyen alan"))) }
        val currency = str(body, "currency")?.uppercase() ?: "TRY"
        if (!PaymentsConfig.CURRENCY.matches(currency)) throw ApiException(400, tr("Doğrulama hatası"), mapOf("currency" to tr("üç harfli para birimi kodu, örn. TRY")))
        val amount = Money.parse(body["amount"], currency)
        val description = str(body, "description")?.take(200) ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("description" to tr("gerekli")))
        val provider = str(body, "provider")?.let { w ->
            PaymentProvider.entries.firstOrNull { it.wire == w } ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("provider" to tr("bilinmeyen sağlayıcı")))
        }
        val collection = str(body, "collection")
        val record = (body["record"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        if ((collection == null) != (record == null)) throw ApiException(400, tr("Doğrulama hatası"), mapOf("record" to tr("koleksiyon ve kayıt birlikte verilir")))
        if (collection != null) {
            val svc = records[collection] ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("collection" to tr("bilinmeyen koleksiyon")))
            svc.get(record!!)
        }
        val (type, gw) = gateway(provider)
        val token = newToken()
        val row = payments.create(buildJsonObject {
            put("token", JsonPrimitive(token))
            put("provider", JsonPrimitive(type.wire))
            put("amount", JsonPrimitive(amount))
            put("currency", JsonPrimitive(currency))
            put("description", JsonPrimitive(description))
            put("status", JsonPrimitive(PaymentStatus.PENDING))
            collection?.let { put("collection", JsonPrimitive(it)); put("record", JsonPrimitive(record)) }
            (body["user"] as? JsonPrimitive)?.longOrNull?.let { put("user", JsonPrimitive(it)) }
            str(body, "email")?.let { put("email", JsonPrimitive(it)) }
            (body["data"] as? JsonObject)?.let { put("data", it) }
        })
        val id = (row["id"] as JsonPrimitive).long()
        val checkout = try {
            gw.checkout(CheckoutRequest(token, amount, currency, description, str(body, "email"), body["buyer"] as? JsonObject ?: JsonObject(emptyMap()), returnUrl(token), ip))
        } catch (e: Exception) {
            payments.delete(id)
            throw e
        }
        val saved = payments.update(id, buildJsonObject {
            put("external", JsonPrimitive(checkout.external))
            put("url", JsonPrimitive(checkout.url))
        })
        if (collection != null && writeBack) writeStatus(collection, record!!, PaymentStatus.PENDING)
        return view(saved)
    }

    /** Abonelik ödeme sayfası: plan sağlayıcıda fiyat; kullanıcı kimliği meta veride, rol bildirim gelince. */
    fun subscribe(user: Long, planName: String): JsonObject {
        val plan = config.plans.firstOrNull { it.name == planName } ?: throw ApiException(404, tr("Plan bulunamadı: {0}", planName))
        val (_, gw) = gateway(plan.provider)
        val u = users?.get(user) ?: throw ApiException(400, tr("Kullanıcı hesapları kapalı"))
        val email = (u["email"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val token = newToken()
        val checkout = gw.subscribe(SubscribeRequest(token, plan, user, email, "$base${PaymentsConfig.PATH}/subscribed"))
        return buildJsonObject {
            put("plan", JsonPrimitive(plan.name))
            put("url", JsonPrimitive(checkout.url))
        }
    }

    fun plans(): List<JsonObject> = config.plans.filter { config.provider(it.provider) != null }.map { p ->
        buildJsonObject {
            put("name", JsonPrimitive(p.name))
            put("title", JsonPrimitive(p.title))
            put("provider", JsonPrimitive(p.provider.wire))
            put("role", JsonPrimitive(p.role))
        }
    }

    fun subscriptionsOf(user: Long): List<JsonObject> =
        db.query("SELECT \"id\" FROM ${quoteIdent(SystemCollections.SUBSCRIPTIONS)} WHERE \"user\" = ? ORDER BY \"id\" DESC", listOf(user))
            .map { JsonObject(subscriptions.get((it["id"] as Number).toLong()) - "previous_role") }

    /** Sağlayıcı bildirimi: imza doğrulanır, her olay bir kez işlenir. İşlenen olay sayısı. */
    fun hook(provider: PaymentProvider, header: (String) -> String?, body: String): Int {
        val gw = gateways[provider]?.takeIf { config.provider(provider) != null } ?: throw ApiException(404, tr("{0} ayarlanmamış", provider.title))
        var handled = 0
        for (e in gw.events(header, body)) {
            val key = "${provider.wire}:${e.id}".take(200)
            if (db.query("SELECT 1 AS x FROM ${quoteIdent(SystemCollections.PAYMENT_EVENTS)} WHERE \"key\" = ?", listOf(key)).isNotEmpty()) continue
            when (e) {
                is PaymentEvent.Payment -> apply(e)
                is PaymentEvent.Subscription -> apply(provider, e)
            }
            runCatching { events.create(JsonObject(mapOf("key" to JsonPrimitive(key)))) }
            handled++
        }
        return handled
    }

    /** Tam iade (yönetici): yalnızca ödenmiş ödeme. Güncel kayıt döner (sağlayıcı onaylamadıysa durum hâlâ `paid`). */
    fun refund(id: Long, ip: String): JsonObject {
        val row = payments.get(id)
        if (str(row, "status") != PaymentStatus.PAID) throw ApiException(409, tr("Yalnızca ödenmiş ödeme iade edilir"))
        val gw = PaymentProvider.entries.firstOrNull { it.wire == str(row, "provider") }?.let { gateways[it] }
            ?: throw ApiException(400, tr("{0} ayarlanmamış", str(row, "provider").orEmpty()))
        gw.refund(row, ip)?.let { apply(it.copy(token = str(row, "token"))) }
        return get(id)
    }

    /** Kullanıcının aboneliğini dönem sonunda bitirir; rol dönem bitene kadar kalır. */
    fun cancelSubscription(user: Long, id: Long): JsonObject {
        val row = runCatching { subscriptions.get(id) }.getOrNull()?.takeIf { (it["user"] as? JsonPrimitive)?.longOrNull == user }
            ?: throw ApiException(404, tr("Abonelik bulunamadı"))
        if (str(row, "status") == SubscriptionStatus.ENDED) throw ApiException(409, tr("Abonelik zaten bitmiş"))
        val provider = PaymentProvider.entries.firstOrNull { it.wire == str(row, "provider") }
        val gw = provider?.let { gateways[it] }?.takeIf { config.provider(provider) != null } ?: throw ApiException(400, tr("{0} ayarlanmamış", str(row, "provider").orEmpty()))
        gw.cancel(str(row, "external").orEmpty())
        return JsonObject(subscriptions.update(id, JsonObject(mapOf("canceling" to JsonPrimitive(true)))) - "previous_role")
    }

    /**
     * Sağlayıcı panelinden yapılan iadeler (bildirim göndermeyen sağlayıcılar, iyzico): son [days] günde ödenmiş en çok
     * [limit] ödeme sorulur. İade bulunan sayısı.
     */
    fun syncRefunds(days: Long = 90, limit: Int = 100): Int {
        val since = Instant.now().minusSeconds(days * 86_400)
        var found = 0
        for ((type, gw) in gateways) {
            if (config.provider(type) == null) continue
            val rows = payments.list(1, limit, "-id", listOf("provider" to type.wire, "status" to PaymentStatus.PAID, "paid_at[gte]" to since.toString())).items
            for (row in rows) {
                val refunded = runCatching { gw.refunded(row) }.onFailure { log(tr("İade durumu sorgulanamadı: {0}", it.message ?: it.toString())) }.getOrNull() ?: break
                if (refunded) {
                    apply(PaymentEvent.Payment("sync", str(row, "token"), null, PaymentStatus.REFUNDED))
                    found++
                }
            }
        }
        return found
    }

    /** Dönüş sayfası: ödeme bekliyorsa sağlayıcıya sorulur (bildirim gecikmiş olabilir). */
    fun refresh(token: String, form: Map<String, String> = emptyMap()): JsonObject? {
        val row = byToken(token) ?: return null
        val status = (row["status"] as JsonPrimitive).content
        if (status != PaymentStatus.PENDING) return row
        val gw = PaymentProvider.entries.firstOrNull { it.wire == str(row, "provider") }?.let { gateways[it] } ?: return row
        val fresh = runCatching { gw.returned(form) ?: str(row, "external")?.let(gw::status) }
            .onFailure { log(tr("Ödeme durumu sorgulanamadı: {0}", it.message ?: it.toString())) }.getOrNull()
        if (fresh != null) apply(fresh.copy(token = token))
        return byToken(token)
    }

    fun byToken(token: String): JsonObject? = by("token", token)

    private fun by(column: String, value: String): JsonObject? =
        db.query("SELECT \"id\" FROM ${quoteIdent(SystemCollections.PAYMENTS)} WHERE ${quoteIdent(column)} = ? ORDER BY \"id\" DESC", listOf(value)).firstOrNull()
            ?.let { payments.get((it["id"] as Number).toLong()) }

    /** Paddle'ın dönüşü: işlem kimliğinden durum sayfasının token'ı. */
    fun tokenOf(external: String): String? = by("external", external)?.let { str(it, "token") }

    /** Makbuz: sağlayıcının saklanan ya da o an alınan faturası; yoksa null (sunucu kendi PDF'ini üretir). */
    fun providerReceipt(row: JsonObject): String? = str(row, "receipt") ?: run {
        val gw = PaymentProvider.entries.firstOrNull { it.wire == str(row, "provider") }?.let { gateways[it] } ?: return null
        str(row, "external")?.let { ext -> runCatching { gw.receipt(ext) }.onFailure { log(tr("Fatura alınamadı: {0}", it.message ?: it.toString())) }.getOrNull() }
    }

    fun get(id: Long): JsonObject = view(payments.get(id))

    /** Yeniden eskiye; [params] liste süzgeci (`status=paid`, `collection=orders`). */
    fun list(page: Int, perPage: Int, params: List<Pair<String, String>>): JsonObject =
        payments.list(page, perPage, "-id", params).let { it.copy(items = it.items.map(::view)) }.toJson()

    /** API'deki hali: tutar ondalık metin (`"149.90"`; kayan nokta yuvarlaması olmasın). */
    private fun view(row: JsonObject): JsonObject {
        val currency = str(row, "currency") ?: "TRY"
        val amount = (row["amount"] as? JsonPrimitive)?.longOrNull ?: 0
        return JsonObject(row + mapOf("amount" to JsonPrimitive(Money.decimal(amount, currency).toPlainString()), "page" to JsonPrimitive(returnUrl(str(row, "token").orEmpty()))))
    }

    private fun apply(e: PaymentEvent.Payment) {
        val row = e.token?.let(::byToken) ?: e.external?.let { by("external", it) } ?: e.reference?.let { by("reference", it) }
            ?: return log(tr("Ödeme bildirimi eşleşmedi: {0}", e.token ?: e.external ?: e.reference.orEmpty()))
        val id = (row["id"] as JsonPrimitive).long()
        val from = str(row, "status").orEmpty()
        val changes = buildJsonObject {
            if (PaymentStatus.allowed(from, e.status)) put("status", JsonPrimitive(e.status))
            if (e.status == PaymentStatus.PAID && from != PaymentStatus.PAID) put("paid_at", JsonPrimitive(Instant.now().toString()))
            e.reference?.let { put("reference", JsonPrimitive(it)) }
            e.receipt?.let { put("receipt", JsonPrimitive(it)) }
            if (str(row, "email") == null) e.email?.let { put("email", JsonPrimitive(it)) }
        }
        if (changes.isEmpty()) return
        payments.update(id, changes)
        val status = (changes["status"] as? JsonPrimitive)?.content ?: return
        val collection = str(row, "collection") ?: return
        val record = (row["record"] as? JsonPrimitive)?.longOrNull ?: return
        writeStatus(collection, record, status)
    }

    /** Koleksiyonda ödeme durumu alanı varsa adı. */
    fun statusField(collection: String): String? = config.statusField.takeIf { n -> collections[collection]?.fields?.any { it.name == n } == true }

    /** Bağlı kayıtta durum alanı varsa yazar (kayıt silinmişse sessizce geçer). */
    private fun writeStatus(collection: String, record: Long, status: String) {
        if (statusField(collection) == null) return
        runCatching { updateRecord(collection, record, JsonObject(mapOf(config.statusField to JsonPrimitive(status)))) }
            .onFailure { log(tr("Ödeme durumu kayda yazılamadı ({0}/{1}): {2}", collection, record, it.message ?: it.toString())) }
    }

    private fun apply(provider: PaymentProvider, e: PaymentEvent.Subscription) {
        val existing = db.query("SELECT \"id\" FROM ${quoteIdent(SystemCollections.SUBSCRIPTIONS)} WHERE \"external\" = ?", listOf(e.external)).firstOrNull()
            ?.let { subscriptions.get((it["id"] as Number).toLong()) }
        val plan = config.plans.firstOrNull { it.provider == provider && it.price == e.price }
            ?: existing?.let { r -> config.plans.firstOrNull { it.name == str(r, "plan") } }
            ?: return log(tr("Abonelik bildirimi bilinen bir plana uymuyor: {0}", e.price.orEmpty()))
        val user = e.user ?: (existing?.get("user") as? JsonPrimitive)?.longOrNull ?: return log(tr("Abonelik bildiriminde kullanıcı yok: {0}", e.external))
        val u = users ?: return
        val role = (runCatching { u.get(user) }.getOrNull()?.get("role") as? JsonPrimitive)?.content ?: return log(tr("Abonenin hesabı yok: {0}", user))
        val previous = existing?.let { str(it, "previous_role") }
        val active = e.status == SubscriptionStatus.ACTIVE || e.status == SubscriptionStatus.PAST_DUE
        val fields = buildJsonObject {
            put("user", JsonPrimitive(user))
            put("plan", JsonPrimitive(plan.name))
            put("provider", JsonPrimitive(provider.wire))
            put("external", JsonPrimitive(e.external))
            put("status", JsonPrimitive(e.status))
            put("period_end", e.periodEnd?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            (if (active) e.canceling else false)?.let { put("canceling", JsonPrimitive(it)) }
            // Etkinleşince önceki rol saklanır, bitince geri verilip silinir.
            if (active && previous == null && role != plan.role) put("previous_role", JsonPrimitive(role))
            if (!active) put("previous_role", JsonNull)
        }
        if (existing == null) subscriptions.create(fields) else subscriptions.update((existing["id"] as JsonPrimitive).long(), fields)
        // Yönetici rolü abonelikle değişmez.
        if (role == SystemCollections.ROLE_ADMIN) return
        if (active && role != plan.role) u.update(user, JsonObject(mapOf("role" to JsonPrimitive(plan.role))))
        if (!active && role == plan.role) u.update(user, JsonObject(mapOf("role" to JsonPrimitive(previous ?: SystemCollections.ROLE_USER))))
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonPrimitive.long() = content.toLong()
}
