package dev.droidserver.core.payments

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.config.PaymentProvider
import dev.droidserver.core.config.PaymentProviderConfig
import dev.droidserver.core.config.PaymentsConfig
import dev.droidserver.core.i18n.I18n
import dev.droidserver.core.i18n.Lang
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object PaymentGateways {
    /** Ayardaki sağlayıcıların istemcileri; [bases] testlerde sahte sunucunun adresi. */
    fun of(config: PaymentsConfig, bases: Map<PaymentProvider, String> = emptyMap()): Map<PaymentProvider, PaymentGateway> =
        config.providers.associate { c ->
            c.provider to when (c.provider) {
                PaymentProvider.STRIPE -> StripeGateway(c, bases[c.provider] ?: "https://api.stripe.com")
                PaymentProvider.IYZICO -> IyzicoGateway(c, bases[c.provider] ?: if (c.sandbox) "https://sandbox-api.iyzipay.com" else "https://api.iyzipay.com")
                PaymentProvider.PADDLE -> PaddleGateway(c, config.publicUrl, bases[c.provider] ?: if (c.sandbox) "https://sandbox-api.paddle.com" else "https://api.paddle.com")
            }
        }
}

/** Bildirim zaman damgasının kabul edilen sapması (yeniden oynatmaya karşı; tekrarlar ayrıca ayıklanır). */
private const val TOLERANCE_SECONDS = 300L

internal fun hmacHex(key: String, message: String): String =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        .doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }

private fun sameHex(a: String, b: String) = MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

private fun badSignature(): Nothing = throw ApiException(400, tr("Bildirim imzası geçersiz"))

/** Sağlayıcıya istek; (durum, gövde). Anahtarlar yalnızca başlıkta. */
private fun call(provider: PaymentProvider, method: String, url: String, headers: Map<String, String>, body: String?, contentType: String = "application/json"): JsonObject {
    val (status, text) = try {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        throw ApiException(502, tr("{0} API'sine ulaşılamadı: {1}", provider.title, e.message ?: e.toString()))
    }
    val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    if (status !in 200..299) {
        val err = json?.get("error")
        val msg = (err as? JsonObject)?.str("message") ?: (err as? JsonObject)?.str("detail") ?: json?.str("errorMessage") ?: text.take(300)
        throw ApiException(502, tr("{0} API hatası ({1}): {2}", provider.title, status, msg))
    }
    return json ?: throw ApiException(502, tr("{0} API'sinin yanıtı anlaşılamadı", provider.title))
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
private fun parse(body: String): JsonObject = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrElse { throw ApiException(400, tr("Bildirim anlaşılamadı")) }

/**
 * Stripe: Checkout oturumu (form gövdesi, Basic kimlik), `Stripe-Signature` (`t.gövde` üzerinde HMAC-SHA256).
 * Token `client_reference_id`'de ve ödeme niyetinin meta verisinde; abonelikte kullanıcı aboneliğin meta verisinde.
 */
class StripeGateway(private val c: PaymentProviderConfig, private val base: String, private val clock: () -> Instant = Instant::now) : PaymentGateway {
    private val auth = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("${c.secretKey}:".toByteArray()))

    private fun form(vararg pairs: Pair<String, Any?>) = pairs.filter { it.second != null }
        .joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v.toString(), "UTF-8") }

    private fun post(path: String, body: String) = call(PaymentProvider.STRIPE, "POST", base + path, auth, body, "application/x-www-form-urlencoded")

    private fun get(path: String) = call(PaymentProvider.STRIPE, "GET", base + path, auth, null)

    override fun checkout(r: CheckoutRequest): Checkout {
        val s = post(
            "/v1/checkout/sessions",
            form(
                "mode" to "payment",
                "line_items[0][price_data][currency]" to r.currency.lowercase(),
                "line_items[0][price_data][unit_amount]" to r.amount,
                "line_items[0][price_data][product_data][name]" to r.description,
                "line_items[0][quantity]" to 1,
                "success_url" to r.returnUrl, "cancel_url" to r.returnUrl,
                "client_reference_id" to r.token,
                "metadata[ds_token]" to r.token,
                "payment_intent_data[metadata][ds_token]" to r.token,
                "customer_email" to r.email,
            ),
        )
        return Checkout(s.str("id") ?: missing(), s.str("url") ?: missing())
    }

    override fun subscribe(r: SubscribeRequest): Checkout {
        val s = post(
            "/v1/checkout/sessions",
            form(
                "mode" to "subscription",
                "line_items[0][price]" to r.plan.price,
                "line_items[0][quantity]" to 1,
                "success_url" to r.returnUrl, "cancel_url" to r.returnUrl,
                "client_reference_id" to r.token,
                "subscription_data[metadata][ds_user]" to r.user,
                "customer_email" to r.email.ifBlank { null },
            ),
        )
        return Checkout(s.str("id") ?: missing(), s.str("url") ?: missing())
    }

    override fun events(header: (String) -> String?, body: String): List<PaymentEvent> {
        val sig = header("Stripe-Signature") ?: badSignature()
        val parts = sig.split(',').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        val t = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull() ?: badSignature()
        val expected = hmacHex(c.webhookSecret, "$t.$body")
        if (parts.none { it.first == "v1" && sameHex(it.second, expected) }) badSignature()
        if (abs(clock().epochSecond - t) > TOLERANCE_SECONDS) badSignature()
        val e = parse(body)
        val id = e.str("id") ?: return emptyList()
        val o = e.obj("data")?.obj("object") ?: return emptyList()
        return when (e.str("type")) {
            "checkout.session.completed", "checkout.session.async_payment_succeeded", "checkout.session.async_payment_failed", "checkout.session.expired" ->
                if (o.str("mode") == "payment") listOf(session(id, o, failed = e.str("type") == "checkout.session.async_payment_failed")) else emptyList()
            "charge.refunded" -> if ((o["refunded"] as? JsonPrimitive)?.booleanOrNull == true) {
                listOf(PaymentEvent.Payment(id, null, null, PaymentStatus.REFUNDED, reference = o.str("payment_intent"), receipt = o.str("receipt_url")))
            } else emptyList()
            "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
            "customer.subscription.paused", "customer.subscription.resumed" -> listOfNotNull(subscription(id, o))
            else -> emptyList()
        }
    }

    /** Checkout oturumu → ödeme olayı; ödendiyse makbuz adresi ödeme niyetinin son tahsilatından. */
    private fun session(id: String, o: JsonObject, failed: Boolean = false): PaymentEvent.Payment {
        val status = when {
            o.str("status") == "expired" -> PaymentStatus.CANCELED
            o.str("payment_status") == "paid" -> PaymentStatus.PAID
            failed -> PaymentStatus.FAILED
            else -> PaymentStatus.PENDING // ör. havale gibi gecikmeli yöntemler
        }
        val intent = o.str("payment_intent")
        val receipt = if (status == PaymentStatus.PAID && intent != null) {
            runCatching { get("/v1/payment_intents/$intent?expand[]=latest_charge").obj("latest_charge")?.str("receipt_url") }.getOrNull()
        } else null
        val email = o.obj("customer_details")?.str("email")
        return PaymentEvent.Payment(id, o.str("client_reference_id"), o.str("id"), status, reference = intent, receipt = receipt, email = email)
    }

    private fun subscription(id: String, o: JsonObject): PaymentEvent.Subscription? {
        val status = when (o.str("status")) {
            "active", "trialing" -> SubscriptionStatus.ACTIVE
            "past_due", "unpaid" -> SubscriptionStatus.PAST_DUE
            "canceled", "incomplete_expired", "paused" -> SubscriptionStatus.ENDED
            else -> return null // incomplete: ilk ödeme bekleniyor
        }
        val item = o.obj("items")?.arr("data")?.firstOrNull() as? JsonObject
        val end = (item?.get("current_period_end") ?: o["current_period_end"]) as? JsonPrimitive
        return PaymentEvent.Subscription(
            id, o.str("id") ?: return null, status, o.obj("metadata")?.str("ds_user")?.toLongOrNull(),
            item?.obj("price")?.str("id"), end?.longOrNull?.let(Instant::ofEpochSecond),
        )
    }

    override fun status(external: String): PaymentEvent.Payment = session("refresh:$external", get("/v1/checkout/sessions/$external"))

    private fun missing(): Nothing = throw ApiException(502, tr("{0} API'sinin yanıtı anlaşılamadı", "Stripe"))
}

/**
 * iyzico: Ödeme Formu (Checkout Form), IYZWSv2 kimliği. Bildirim ve dönüşte yalnızca token alınır, sonuç her zaman
 * iyzico'ya sorulur (bildirim imzası V3 hesapta açık değilse de güvenli); imza başlığı varsa ayrıca doğrulanır.
 * Tutarlar ondalık metin. Alıcı bilgisi zorunlu (bkz. [BUYER]); sepet tek sanal üründür.
 */
class IyzicoGateway(private val c: PaymentProviderConfig, private val base: String) : PaymentGateway {
    private fun post(path: String, body: JsonObject): JsonObject {
        val text = body.toString()
        val rnd = System.currentTimeMillis().toString() + UUID.randomUUID().toString().take(8)
        val signature = hmacHex(c.secretKey, rnd + path + text)
        val auth = Base64.getEncoder().encodeToString("apiKey:${c.apiKey}&randomKey:$rnd&signature:$signature".toByteArray())
        val r = call(PaymentProvider.IYZICO, "POST", base + path, mapOf("Authorization" to "IYZWSv2 $auth", "x-iyzi-rnd" to rnd), text)
        if (r.str("status") != "success") throw ApiException(502, tr("{0} API hatası ({1}): {2}", "iyzico", r.str("errorCode").orEmpty(), r.str("errorMessage").orEmpty()))
        return r
    }

    override fun checkout(r: CheckoutRequest): Checkout {
        val b = r.buyer
        fun v(k: String) = (b[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val missing = BUYER.filter { v(it) == null } + listOfNotNull("email".takeIf { r.email == null })
        if (missing.isNotEmpty()) {
            throw ApiException(400, tr("Doğrulama hatası"), missing.associate { (if (it == "email") it else "buyer.$it") to tr("iyzico için gerekli") })
        }
        val price = Money.decimal(r.amount, r.currency).toPlainString()
        val country = v("country") ?: "Turkey"
        val name = "${v("name")} ${v("surname")}"
        val address = buildJsonObject {
            put("contactName", JsonPrimitive(name)); put("city", JsonPrimitive(v("city"))); put("country", JsonPrimitive(country)); put("address", JsonPrimitive(v("address")))
        }
        val res = post("/payment/iyzipos/checkoutform/initialize/auth/ecom", buildJsonObject {
            put("locale", JsonPrimitive(if (I18n.lang == Lang.TR) "tr" else "en"))
            put("conversationId", JsonPrimitive(r.token))
            put("price", JsonPrimitive(price))
            put("paidPrice", JsonPrimitive(price))
            put("currency", JsonPrimitive(r.currency))
            put("basketId", JsonPrimitive(r.token))
            put("paymentGroup", JsonPrimitive("PRODUCT"))
            put("callbackUrl", JsonPrimitive(r.returnUrl))
            put("buyer", buildJsonObject {
                put("id", JsonPrimitive(v("id") ?: r.email!!))
                put("name", JsonPrimitive(v("name"))); put("surname", JsonPrimitive(v("surname")))
                put("identityNumber", JsonPrimitive(v("identityNumber"))); put("gsmNumber", JsonPrimitive(v("gsmNumber")))
                put("email", JsonPrimitive(r.email)); put("registrationAddress", JsonPrimitive(v("address")))
                put("city", JsonPrimitive(v("city"))); put("country", JsonPrimitive(country)); put("ip", JsonPrimitive(r.ip))
            })
            put("billingAddress", address)
            put("basketItems", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive(r.token)); put("name", JsonPrimitive(r.description.take(100)))
                    put("category1", JsonPrimitive(v("category") ?: "Genel")); put("itemType", JsonPrimitive("VIRTUAL")); put("price", JsonPrimitive(price))
                })
            })
        })
        return Checkout(res.str("token") ?: missing(), res.str("paymentPageUrl") ?: missing())
    }

    override fun events(header: (String) -> String?, body: String): List<PaymentEvent> {
        val e = parse(body)
        header("X-IYZ-SIGNATURE-V3")?.let { sig ->
            val expected = hmacHex(c.secretKey, c.secretKey + e.str("iyziEventType").orEmpty() + e.str("iyziPaymentId").orEmpty() +
                e.str("token").orEmpty() + e.str("paymentConversationId").orEmpty() + e.str("status").orEmpty())
            if (!sameHex(sig, expected)) badSignature()
        }
        val token = e.str("token") ?: return emptyList()
        val fresh = status(token)
        // Aynı ödemenin aynı durumu bir kez işlenir; denemeler (3D adımları) ayrı olaylardır.
        return listOf(fresh.copy(id = listOf(e.str("iyziPaymentId"), e.str("iyziEventType"), e.str("status"), token).joinToString(":")))
    }

    override fun status(external: String): PaymentEvent.Payment {
        val r = post("/payment/iyzipos/checkoutform/auth/ecom/detail", buildJsonObject { put("locale", JsonPrimitive("tr")); put("token", JsonPrimitive(external)) })
        val fraud = (r["fraudStatus"] as? JsonPrimitive)?.intOrNull
        val status = when (r.str("paymentStatus")) {
            "SUCCESS" -> when (fraud) { -1 -> PaymentStatus.FAILED; 0 -> PaymentStatus.PENDING; else -> PaymentStatus.PAID }
            "FAILURE" -> PaymentStatus.FAILED
            else -> PaymentStatus.PENDING
        }
        return PaymentEvent.Payment("status:$external", r.str("conversationId"), external, status, reference = r.str("paymentId"))
    }

    override fun returned(form: Map<String, String>): PaymentEvent.Payment? = form["token"]?.takeIf { it.isNotBlank() }?.let(::status)

    private fun missing(): Nothing = throw ApiException(502, tr("{0} API'sinin yanıtı anlaşılamadı", "iyzico"))

    companion object {
        /** `buyer` içinde zorunlu alanlar; ayrıca `country` (varsayılan Turkey), `id` ve `category` isteğe bağlı. */
        val BUYER = listOf("name", "surname", "identityNumber", "gsmNumber", "address", "city")
    }
}

/**
 * Paddle Billing: katalog dışı ürünle işlem (transaction); ödeme sayfası sunucunun `/payments/paddle` adresinde
 * Paddle.js ile açılır (Paddle'da varsayılan ödeme bağlantısı bu adres olmalı). `Paddle-Signature`
 * (`ts:gövde` üzerinde HMAC-SHA256). Token ve abonede kullanıcı `custom_data`'da.
 */
class PaddleGateway(private val c: PaymentProviderConfig, publicUrl: String, private val base: String, private val clock: () -> Instant = Instant::now) : PaymentGateway {
    private val auth = mapOf("Authorization" to "Bearer ${c.secretKey}", "Paddle-Version" to "1")
    private val page = publicUrl.trimEnd('/') + PAGE

    private fun transaction(body: JsonObject): Checkout {
        val d = call(PaymentProvider.PADDLE, "POST", "$base/transactions", auth, body.toString()).obj("data") ?: missing()
        return Checkout(d.str("id") ?: missing(), d.obj("checkout")?.str("url") ?: missing())
    }

    override fun checkout(r: CheckoutRequest): Checkout = transaction(buildJsonObject {
        put("items", buildJsonArray {
            add(buildJsonObject {
                put("quantity", JsonPrimitive(1))
                put("price", buildJsonObject {
                    put("description", JsonPrimitive(r.description.padEnd(2, ' ').take(500)))
                    put("name", JsonPrimitive(r.description.take(150)))
                    put("unit_price", buildJsonObject { put("amount", JsonPrimitive(r.amount.toString())); put("currency_code", JsonPrimitive(r.currency)) })
                    put("product", buildJsonObject { put("name", JsonPrimitive(r.description.take(200))); put("tax_category", JsonPrimitive("standard")) })
                })
            })
        })
        put("currency_code", JsonPrimitive(r.currency))
        put("custom_data", buildJsonObject { put("ds_token", JsonPrimitive(r.token)) })
        put("checkout", buildJsonObject { put("url", JsonPrimitive(page)) })
    })

    override fun subscribe(r: SubscribeRequest): Checkout = transaction(buildJsonObject {
        put("items", buildJsonArray { add(buildJsonObject { put("price_id", JsonPrimitive(r.plan.price)); put("quantity", JsonPrimitive(1)) }) })
        put("custom_data", buildJsonObject { put("ds_user", JsonPrimitive(r.user.toString())) })
        put("checkout", buildJsonObject { put("url", JsonPrimitive(page)) })
    })

    override fun events(header: (String) -> String?, body: String): List<PaymentEvent> {
        val sig = header("Paddle-Signature") ?: badSignature()
        val parts = sig.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        val ts = parts.firstOrNull { it.first == "ts" }?.second?.toLongOrNull() ?: badSignature()
        val expected = hmacHex(c.webhookSecret, "$ts:$body")
        if (parts.none { it.first == "h1" && sameHex(it.second, expected) }) badSignature()
        if (abs(clock().epochSecond - ts) > TOLERANCE_SECONDS) badSignature()
        val e = parse(body)
        val id = e.str("event_id") ?: return emptyList()
        val d = e.obj("data") ?: return emptyList()
        val type = e.str("event_type").orEmpty()
        return when {
            type == "transaction.completed" || type == "transaction.payment_failed" || type == "transaction.canceled" -> {
                // Aboneliğin işlemleri (ilk ve yenilemeler) ödeme tablosuna yazılmaz; abonelik olaylarından izlenir.
                val token = d.obj("custom_data")?.str("ds_token") ?: return emptyList()
                listOf(PaymentEvent.Payment(id, token, d.str("id"), transactionStatus(type.removePrefix("transaction.")), reference = d.str("invoice_number") ?: d.str("id")))
            }
            type == "adjustment.created" || type == "adjustment.updated" ->
                if (d.str("action") == "refund" && d.str("status") == "approved") {
                    listOf(PaymentEvent.Payment(id, null, d.str("transaction_id"), PaymentStatus.REFUNDED))
                } else emptyList()
            type.startsWith("subscription.") -> {
                val status = when (d.str("status")) {
                    "active", "trialing" -> SubscriptionStatus.ACTIVE
                    "past_due" -> SubscriptionStatus.PAST_DUE
                    "canceled", "paused" -> SubscriptionStatus.ENDED
                    else -> return emptyList()
                }
                val item = d.arr("items")?.firstOrNull() as? JsonObject
                listOf(
                    PaymentEvent.Subscription(
                        id, d.str("id") ?: return emptyList(), status, d.obj("custom_data")?.str("ds_user")?.toLongOrNull(),
                        item?.obj("price")?.str("id"), d.obj("current_billing_period")?.str("ends_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        d.obj("management_urls")?.str("cancel"),
                    ),
                )
            }
            else -> emptyList()
        }
    }

    private fun transactionStatus(s: String) = when (s) {
        "completed", "paid" -> PaymentStatus.PAID
        "payment_failed" -> PaymentStatus.FAILED
        "canceled" -> PaymentStatus.CANCELED
        else -> PaymentStatus.PENDING
    }

    override fun status(external: String): PaymentEvent.Payment {
        val d = call(PaymentProvider.PADDLE, "GET", "$base/transactions/$external", auth, null).obj("data") ?: missing()
        return PaymentEvent.Payment("status:$external", d.obj("custom_data")?.str("ds_token"), external, transactionStatus(d.str("status").orEmpty()), reference = d.str("invoice_number"))
    }

    /** Paddle'ın faturası (adres bir saat geçerli; her istekte yeniden alınır). */
    override fun receipt(external: String): String? =
        call(PaymentProvider.PADDLE, "GET", "$base/transactions/$external/invoice", auth, null).obj("data")?.str("url")

    private fun missing(): Nothing = throw ApiException(502, tr("{0} API'sinin yanıtı anlaşılamadı", "Paddle"))

    companion object {
        const val PAGE = PaymentsConfig.PATH + "/paddle"

        /** Paddle.js sayfası: adresteki `_ptxn` işlemini açar, bitince durum sayfasına döner. */
        fun page(c: PaymentProviderConfig): String {
            val token = Json.encodeToString(JsonElement.serializer(), JsonPrimitive(c.clientToken))
            return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
                |<title>Checkout</title></head><body>
                |<script src="https://cdn.paddle.com/paddle/v2/paddle.js"></script>
                |<script>${if (c.sandbox) "Paddle.Environment.set(\"sandbox\");" else ""}
                |var txn = new URLSearchParams(location.search).get("_ptxn");
                |Paddle.Initialize({token: $token, eventCallback: function (e) {
                |  if (e.name === "checkout.completed" && txn) location.href = "${PaymentsConfig.PATH}/paddle/done?txn=" + encodeURIComponent(txn);
                |}});</script></body></html>""".trimMargin()
        }
    }
}
