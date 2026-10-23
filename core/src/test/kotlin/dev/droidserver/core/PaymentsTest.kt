package dev.droidserver.core

import com.sun.net.httpserver.HttpServer
import dev.droidserver.core.api.SdkGenerator
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AccessConfig
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.AutomationAction
import dev.droidserver.core.config.AutomationActionType
import dev.droidserver.core.config.AutomationConfig
import dev.droidserver.core.config.AutomationTrigger
import dev.droidserver.core.config.CloudflareAccess
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.PaymentPlan
import dev.droidserver.core.config.PaymentProvider
import dev.droidserver.core.config.PaymentProviderConfig
import dev.droidserver.core.config.PaymentsConfig
import dev.droidserver.core.config.QuotaConfig
import dev.droidserver.core.config.RoleConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.payments.PaymentGateways
import dev.droidserver.core.payments.ReceiptPdf
import dev.droidserver.core.payments.hmacHex
import dev.droidserver.core.templates.AppTemplate
import io.ktor.client.request.request
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 18.9: ödemeler. Sağlayıcıların API'leri sahte sunucuda (gerçek ödeme yok); bildirimler gerçek imzayla. */
class PaymentsTest {
    private val key = "yonetici-anahtari-1234567890"
    private val orders = CollectionConfig(
        "orders",
        listOf(
            FieldConfig("item", FieldType.TEXT, required = true),
            FieldConfig("total", FieldType.REAL, required = true),
            FieldConfig("email", FieldType.TEXT),
            FieldConfig("payment_status", FieldType.SELECT, options = listOf("pending", "paid", "failed", "canceled", "refunded")),
            FieldConfig("payment_url", FieldType.TEXT),
        ),
    )
    private val stripe = PaymentProviderConfig(PaymentProvider.STRIPE, "sk_test_abc", webhookSecret = "whsec_test")
    private val iyzico = PaymentProviderConfig(PaymentProvider.IYZICO, "sandbox-gizli", apiKey = "sandbox-api", sandbox = true)
    private val paddle = PaymentProviderConfig(PaymentProvider.PADDLE, "pdl_sdbx_apikey_x", webhookSecret = "pdl_ntfset_sir", clientToken = "test_abc", sandbox = true)
    private val config = ServerConfig(
        auth = AuthConfig(
            AuthMode.API_KEY, apiKeys = listOf(key),
            users = UsersConfig(enabled = true, sendVerification = false, roles = listOf(RoleConfig("pro"))),
        ),
        access = AccessConfig(AccessMode.CLOUDFLARE, cloudflare = CloudflareAccess(tunnel = CloudflareTunnel.NAMED, hostname = "ornek.example.com")),
        collections = listOf(orders),
        payments = PaymentsConfig(
            providers = listOf(stripe, iyzico, paddle),
            publicUrl = "https://ornek.example.com",
            plans = listOf(PaymentPlan("pro", "Pro", PaymentProvider.PADDLE, "pri_pro", "pro", quotas = QuotaConfig(dailyRequests = 1000))),
            merchant = "Örnek Şirket",
        ),
        automations = listOf(
            AutomationConfig(
                "siparis-odeme", AutomationTrigger(collection = "orders", events = listOf("create")),
                actions = listOf(AutomationAction(AutomationActionType.PAYMENT, amount = "{total}", subject = "Sipariş: {item}", to = "{email}", field = "payment_url", provider = PaymentProvider.STRIPE)),
            ),
        ),
    )
    private lateinit var db: SqlDriver
    private lateinit var fake: HttpServer

    /** Sahte sağlayıcı: gelen istekler (yol, başlıklar, gövde) ve sıradaki yanıtlar. */
    private class Seen(val path: String, val headers: Map<String, String>, val body: String)
    private val seen = CopyOnWriteArrayList<Seen>()
    private var iyzicoStatus = "SUCCESS"
    private var paddleStatus = "completed"

    @Before
    fun open() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val path = ex.requestURI.toString()
                seen += Seen(path, ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }, body)
                val reply = when {
                    path.startsWith("/stripe/v1/checkout/sessions/") -> """{"id":"cs_1","object":"checkout.session","mode":"payment","status":"complete","payment_status":"paid","payment_intent":"pi_1","client_reference_id":null}"""
                    path == "/stripe/v1/checkout/sessions" -> """{"id":"cs_${seen.size}","url":"https://checkout.stripe.test/cs_${seen.size}"}"""
                    path.startsWith("/stripe/v1/payment_intents/pi_1") -> """{"id":"pi_1","latest_charge":{"id":"ch_1","receipt_url":"https://pay.stripe.test/receipts/ch_1"}}"""
                    path.endsWith("/initialize/auth/ecom") -> """{"status":"success","token":"iyz-token","paymentPageUrl":"https://sandbox-cpp.iyzipay.test?token=iyz-token"}"""
                    path.endsWith("/auth/ecom/detail") -> """{"status":"success","paymentStatus":"$iyzicoStatus","fraudStatus":1,"paymentId":"9001","token":"iyz-token"}"""
                    path == "/paddle/transactions" -> """{"data":{"id":"txn_1","status":"draft","checkout":{"url":"https://ornek.example.com/payments/paddle?_ptxn=txn_1"}}}"""
                    path == "/paddle/transactions/txn_1/invoice" -> """{"data":{"url":"https://paddle.test/invoice.pdf"}}"""
                    path.startsWith("/paddle/transactions/") -> """{"data":{"id":"txn_1","status":"$paddleStatus"}}"""
                    else -> """{"error":{"message":"yok"}}"""
                }
                val bytes = reply.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(if (reply.contains("\"yok\"")) 404 else 200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After
    fun close() {
        fake.stop(0)
        db.close()
    }

    private fun gateways(c: ServerConfig = config) = PaymentGateways.of(
        c.payments,
        mapOf(
            PaymentProvider.STRIPE to "http://127.0.0.1:${fake.address.port}/stripe",
            PaymentProvider.IYZICO to "http://127.0.0.1:${fake.address.port}/iyzico",
            PaymentProvider.PADDLE to "http://127.0.0.1:${fake.address.port}/paddle",
        ),
    )

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun form(body: String) = body.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun stripeSig(body: String, t: Long = Instant.now().epochSecond) = "t=$t,v1=${hmacHex("whsec_test", "$t.$body")}"
    private fun paddleSig(body: String, t: Long = Instant.now().epochSecond) = "ts=$t;h1=${hmacHex("pdl_ntfset_sir", "$t:$body")}"

    @Test
    fun `stripe baglanti bildirim tekrar ve makbuz`() = testApplication {
        application { droidServerModule(config.copy(automations = emptyList()), db, paymentGateways = gateways()) }
        val order = json(client.post("/api/collections/orders/records") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"item":"Kitap","total":149.9}""") }.bodyAsText())
        val r = client.post("/api/_payments") {
            bearerAuth(key); contentType(ContentType.Application.Json)
            setBody("""{"amount":"149.90","currency":"TRY","description":"Kitap","provider":"stripe","collection":"orders","record":${order.s("id")},"email":"ali@example.com"}""")
        }
        assertEquals(201, r.status.value, r.bodyAsText())
        val p = json(r.bodyAsText())
        assertEquals("149.90", p.s("amount"))
        assertTrue(p.s("url").startsWith("https://checkout.stripe.test/"))
        val token = p.s("token")
        val sent = form(seen.last().body)
        assertEquals("14990", sent["line_items[0][price_data][unit_amount]"])
        assertEquals("try", sent["line_items[0][price_data][currency]"])
        assertEquals(token, sent["client_reference_id"])
        assertEquals("https://ornek.example.com/payments/$token", sent["success_url"])
        assertEquals("Basic " + Base64.getEncoder().encodeToString("sk_test_abc:".toByteArray()), seen.last().headers["authorization"])
        assertEquals("pending", json(client.get("/api/collections/orders/records/${order.s("id")}") { bearerAuth(key) }.bodyAsText()).s("payment_status"))

        val event = """{"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"${p.s("external")}","object":"checkout.session","mode":"payment","status":"complete","payment_status":"paid","payment_intent":"pi_1","client_reference_id":"$token","customer_details":{"email":"ali@example.com"}}}}"""
        assertEquals(400, client.post("/payments/hook/stripe") { header("Stripe-Signature", "t=1,v1=00"); setBody(event) }.status.value, "imzasız")
        assertEquals(400, client.post("/payments/hook/stripe") { header("Stripe-Signature", stripeSig(event, Instant.now().epochSecond - 3600)); setBody(event) }.status.value, "eski zaman damgası")
        val ok = client.post("/payments/hook/stripe") { header("Stripe-Signature", stripeSig(event)); setBody(event) }
        assertEquals("1", json(ok.bodyAsText()).s("received"))
        assertEquals("0", json(client.post("/payments/hook/stripe") { header("Stripe-Signature", stripeSig(event)); setBody(event) }.bodyAsText()).s("received"), "tekrar gelen olay")

        val paid = json(client.get("/api/_payments/${p.s("id")}") { bearerAuth(key) }.bodyAsText())
        assertEquals("paid", paid.s("status"))
        assertEquals("pi_1", paid.s("reference"))
        assertEquals("https://pay.stripe.test/receipts/ch_1", paid.s("receipt"))
        assertEquals("paid", json(client.get("/api/collections/orders/records/${order.s("id")}") { bearerAuth(key) }.bodyAsText()).s("payment_status"))
        val page = client.get("/payments/$token").bodyAsText()
        assertTrue("Ödendi" in page && "149,90 TRY" in page, page)
        val receipt = createClient { followRedirects = false }.get("/payments/$token/receipt")
        assertEquals("https://pay.stripe.test/receipts/ch_1", receipt.headers["Location"])

        // Tam iade: ödeme niyetinden eşleşir; ödendi → iade.
        val refund = """{"id":"evt_2","type":"charge.refunded","data":{"object":{"id":"ch_1","payment_intent":"pi_1","refunded":true,"receipt_url":"https://pay.stripe.test/receipts/ch_1"}}}"""
        client.post("/payments/hook/stripe") { header("Stripe-Signature", stripeSig(refund)); setBody(refund) }
        assertEquals("refunded", json(client.get("/api/collections/orders/records/${order.s("id")}") { bearerAuth(key) }.bodyAsText()).s("payment_status"))
        // Sırasız gelen eski olay ödenmişi geri almaz.
        val list = json(client.get("/api/_payments?status=refunded") { bearerAuth(key) }.bodyAsText())
        assertEquals(1, list.getValue("items").jsonArray.size)
        assertEquals(404, client.get("/payments/yok-boyle-bir-token").status.value)
    }

    @Test
    fun `iyzico alici bilgisi imza donus ve makbuz pdf`() = testApplication {
        application { droidServerModule(config.copy(automations = emptyList()), db, paymentGateways = gateways()) }
        val missing = client.post("/api/_payments") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"amount":"50","description":"Ders","provider":"iyzico"}""") }
        assertEquals(400, missing.status.value)
        assertTrue("buyer.identityNumber" in missing.bodyAsText() && "email" in missing.bodyAsText(), missing.bodyAsText())
        val buyer = """{"name":"Ali","surname":"Veli","identityNumber":"11111111111","gsmNumber":"+905350000000","address":"Nidakule","city":"İstanbul"}"""
        val p = json(client.post("/api/_payments") {
            bearerAuth(key); contentType(ContentType.Application.Json)
            setBody("""{"amount":"50","description":"Ders","provider":"iyzico","email":"ali@example.com","buyer":$buyer}""")
        }.bodyAsText())
        assertEquals("iyz-token", p.s("external"))
        val init = seen.last()
        val sent = json(init.body)
        assertEquals("50.00", sent.s("price"))
        assertEquals("50.00", sent.s("paidPrice"))
        assertEquals("https://ornek.example.com/payments/${p.s("token")}", sent.s("callbackUrl"))
        assertEquals("VIRTUAL", sent.getValue("basketItems").jsonArray[0].jsonObject.s("itemType"))
        // IYZWSv2: base64("apiKey:..&randomKey:..&signature:HMAC(secret, rnd + yol + gövde)").
        val auth = Base64.getDecoder().decode(init.headers.getValue("authorization").removePrefix("IYZWSv2 ")).decodeToString()
        val rnd = auth.substringAfter("&randomKey:").substringBefore("&")
        assertEquals("apiKey:sandbox-api&randomKey:$rnd&signature:" + hmacHex("sandbox-gizli", rnd + "/payment/iyzipos/checkoutform/initialize/auth/ecom" + init.body), auth)

        // Ödeme formundan dönüş (POST token): sonuç iyzico'ya sorulur.
        val back = createClient { followRedirects = false }.submitForm("/payments/${p.s("token")}", parameters { append("token", "iyz-token") })
        assertEquals("/payments/${p.s("token")}", back.headers["Location"])
        assertEquals("paid", json(client.get("/api/_payments/${p.s("id")}") { bearerAuth(key) }.bodyAsText()).s("status"))
        // Bildirim: imza başlığı varsa doğrulanır; geçersizse reddedilir.
        val hook = """{"iyziEventType":"CHECKOUT_FORM_AUTH","iyziPaymentId":"9001","token":"iyz-token","paymentConversationId":"${p.s("token")}","status":"SUCCESS"}"""
        assertEquals(400, client.post("/payments/hook/iyzico") { header("X-IYZ-SIGNATURE-V3", "bozuk"); setBody(hook) }.status.value)
        val sig = hmacHex("sandbox-gizli", "sandbox-gizli" + "CHECKOUT_FORM_AUTH" + "9001" + "iyz-token" + p.s("token") + "SUCCESS")
        assertEquals("1", json(client.post("/payments/hook/iyzico") { header("X-IYZ-SIGNATURE-V3", sig); setBody(hook) }.bodyAsText()).s("received"))

        val pdf = client.get("/payments/${p.s("token")}/receipt")
        assertEquals("application/pdf", pdf.headers["Content-Type"])
        val bytes = pdf.bodyAsBytes()
        assertTrue(bytes.decodeToString(0, 8).startsWith("%PDF-1.4"))
        // Türkçe harfler ISO-8859-9 kodlarıyla (Ş = \336) ve glif eşlemesiyle.
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue("\\326rnek \\336irket" in text && "/Scedilla" in text && "50,00 TRY" in text, text.take(1500))
    }

    @Test
    fun `paddle abonelik rol ve kota`() = testApplication {
        application { droidServerModule(config.copy(automations = emptyList()), db, paymentGateways = gateways()) }
        val signup = json(client.post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"ayse@example.com","password":"parola-1234"}""") }.bodyAsText())
        val user = signup.getValue("token").jsonPrimitive.content
        val uid = signup.getValue("user").jsonObject.s("id")
        assertEquals("pro", json(client.get("/api/payments/plans").bodyAsText()).getValue("items").jsonArray[0].jsonObject.s("name"))
        assertTrue("/payments/subscribe" in client.get("/api/_openapi.json") { bearerAuth(user) }.bodyAsText())
        val sub = json(client.post("/api/payments/subscribe") { bearerAuth(user); contentType(ContentType.Application.Json); setBody("""{"plan":"pro"}""") }.bodyAsText())
        assertEquals("https://ornek.example.com/payments/paddle?_ptxn=txn_1", sub.s("url"))
        val sent = json(seen.last().body)
        assertEquals("pri_pro", sent.getValue("items").jsonArray[0].jsonObject.s("price_id"))
        assertEquals(uid, sent.getValue("custom_data").jsonObject.s("ds_user"))
        assertEquals("Bearer pdl_sdbx_apikey_x", seen.last().headers["authorization"])

        fun event(id: String, status: String) = """{"event_id":"$id","event_type":"subscription.${if (status == "active") "activated" else status}","occurred_at":"2026-10-23T10:00:00Z","data":{"id":"sub_1","status":"$status","custom_data":{"ds_user":"$uid"},"items":[{"price":{"id":"pri_pro"}}],"current_billing_period":{"ends_at":"2026-11-23T10:00:00Z"},"management_urls":{"cancel":"https://paddle.test/cancel"}}}"""
        val active = event("evt_a", "active")
        assertEquals(400, client.post("/payments/hook/paddle") { header("Paddle-Signature", "ts=1;h1=00"); setBody(active) }.status.value)
        client.post("/payments/hook/paddle") { header("Paddle-Signature", paddleSig(active)); setBody(active) }
        assertEquals("pro", json(client.get("/api/auth/me") { bearerAuth(user) }.bodyAsText()).s("role"))
        val mine = json(client.get("/api/payments/subscriptions") { bearerAuth(user) }.bodyAsText()).getValue("items").jsonArray
        assertEquals("active", mine[0].jsonObject.s("status"))
        assertEquals("https://paddle.test/cancel", mine[0].jsonObject.s("manage_url"))
        assertFalse("previous_role" in mine[0].jsonObject)
        // Plan kotası (günlük 1000 istek) uygulanır: kullanım özetinde görünür.
        assertEquals("1000", json(client.get("/api/auth/me/usage") { bearerAuth(user) }.bodyAsText()).getValue("requests").jsonObject.s("limit"))

        val canceled = event("evt_c", "canceled")
        client.post("/payments/hook/paddle") { header("Paddle-Signature", paddleSig(canceled)); setBody(canceled) }
        assertEquals("user", json(client.get("/api/auth/me") { bearerAuth(user) }.bodyAsText()).s("role"))

        // Tek seferlik Paddle ödemesi, dönüş ve süreli fatura adresi.
        val p = json(client.post("/api/_payments") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"amount":"10","currency":"USD","description":"Şablon","provider":"paddle"}""") }.bodyAsText())
        val tx = json(seen.last().body)
        assertEquals("1000", tx.getValue("items").jsonArray[0].jsonObject.getValue("price").jsonObject.getValue("unit_price").jsonObject.s("amount"))
        assertEquals("https://ornek.example.com/payments/paddle", tx.getValue("checkout").jsonObject.s("url"))
        assertTrue("Paddle.Environment.set(\"sandbox\")" in client.get("/payments/paddle").bodyAsText())
        val done = createClient { followRedirects = false }.get("/payments/paddle/done?txn=txn_1")
        assertEquals("/payments/${p.s("token")}", done.headers["Location"])
        assertTrue("Ödendi" in client.get("/payments/${p.s("token")}").bodyAsText(), "bildirim gecikse de durum Paddle'a sorulur")
        assertEquals("https://paddle.test/invoice.pdf", createClient { followRedirects = false }.get("/payments/${p.s("token")}/receipt").headers["Location"])
    }

    @Test
    fun `otomasyon siparise baglanti uretir`() = testApplication {
        application { droidServerModule(config, db, paymentGateways = gateways()) }
        val order = json(client.post("/api/collections/orders/records") {
            bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"item":"Kupa","total":75.5,"email":"ali@example.com"}""")
        }.bodyAsText())
        var after = JsonObject(emptyMap())
        repeat(50) {
            after = json(client.get("/api/collections/orders/records/${order.s("id")}") { bearerAuth(key) }.bodyAsText())
            if (after["payment_url"] != null && after["payment_url"].toString() != "null") return@repeat
            Thread.sleep(50)
        }
        assertTrue(after.s("payment_url").startsWith("https://checkout.stripe.test/"), after.toString())
        assertEquals("pending", after.s("payment_status"))
        val sent = form(seen.last().body)
        assertEquals("7550", sent["line_items[0][price_data][unit_amount]"])
        assertEquals("Sipariş: Kupa", sent["line_items[0][price_data][product_data][name]"])
        assertEquals("ali@example.com", sent["customer_email"])
    }

    @Test
    fun `odemeli siparis ve randevu sablonlari`() = testApplication {
        val base = config.copy(collections = emptyList(), automations = emptyList())
        val applied = AppTemplate.APPOINTMENTS.apply(AppTemplate.SHOP.apply(base, site = false), site = false)
        val errors = ConfigValidator.validateDetailed(applied)
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(listOf("siparis-odeme", "randevu-odeme"), applied.automations.map { it.name })
        val plain = AppTemplate.SHOP.apply(base.copy(payments = PaymentsConfig()), site = false)
        assertTrue(plain.automations.isEmpty() && plain.collections.none { c -> c.fields.any { it.name == "payment_url" } }, "ödemeler kapalıysa eklenmez")

        val tdb = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(tdb).migrate(applied.schemaCollections())
        application { droidServerModule(applied, tdb, paymentGateways = gateways(applied)) }
        val user = json(client.post("/api/auth/signup") { contentType(ContentType.Application.Json); setBody("""{"email":"mus@example.com","password":"parola-1234"}""") }.bodyAsText()).s("token")
        val order = """{"items":[{"product":1,"qty":2}],"total":99.5,"address":"Adres","phone":"0555"}"""
        // Müşteri ödeme alanlarını yazamaz (sahte "ödendi" ya da başka bir ödeme adresi).
        val forged = client.post("/api/collections/orders/records") { bearerAuth(user); contentType(ContentType.Application.Json); setBody(order.dropLast(1) + ""","payment_url":"https://kotu.example.com"}""") }
        assertEquals(403, forged.status.value, forged.bodyAsText())
        val o = json(client.post("/api/collections/orders/records") { bearerAuth(user); contentType(ContentType.Application.Json); setBody(order) }.bodyAsText())
        Thread.sleep(200)
        assertEquals(0, seen.size, "onaylanmadan bağlantı yok")
        val approve = client.request("/api/collections/orders/records/${o.s("id")}") {
            method = io.ktor.http.HttpMethod.Patch; bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"status":"onaylandı"}""")
        }
        assertEquals(200, approve.status.value, approve.bodyAsText())
        var after = JsonObject(emptyMap())
        repeat(50) {
            after = json(client.get("/api/collections/orders/records/${o.s("id")}") { bearerAuth(user) }.bodyAsText())
            if (after["payment_url"]?.toString()?.startsWith("\"https") == true) return@repeat
            Thread.sleep(50)
        }
        assertTrue(after.s("payment_url").startsWith("https://checkout.stripe.test/"), after.toString())
        assertEquals("9950", form(seen.single().body)["line_items[0][price_data][unit_amount]"])
        // Bir sonraki güncelleme yeni bağlantı üretmez (koşul: adres boş).
        client.request("/api/collections/orders/records/${o.s("id")}") { method = io.ktor.http.HttpMethod.Patch; bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"note":"hazır"}""") }
        Thread.sleep(200)
        assertEquals(1, seen.size)

        // Randevu: tutar ilişkili hizmetin fiyatından ({service.price}), istemcinin gönderdiğinden değil.
        val svc = json(client.post("/api/collections/services/records") { bearerAuth(key); contentType(ContentType.Application.Json); setBody("""{"name":"Saç kesimi","duration_min":30,"price":300}""") }.bodyAsText())
        val ap = client.post("/api/collections/appointments/records") {
            contentType(ContentType.Application.Json)
            setBody("""{"service":${svc.s("id")},"starts_at":"2026-11-01T10:00:00Z","name":"Ali","phone":"0555","email":"ali@example.com","status":"bekliyor"}""")
        }
        assertEquals(201, ap.status.value, ap.bodyAsText())
        repeat(50) { if (seen.size < 2) Thread.sleep(50) }
        val sent = form(seen.last().body)
        assertEquals("30000", sent["line_items[0][price_data][unit_amount]"])
        assertEquals("Randevu: Saç kesimi", sent["line_items[0][price_data][product_data][name]"])
        tdb.close()
    }

    @Test
    fun `dogrulama erisim ve anahtarlar`() {
        assertTrue(ConfigValidator.validateDetailed(config).none { it.path.startsWith("payments") || it.path.startsWith("automations") }, ConfigValidator.validateDetailed(config).toString())
        fun paths(c: ServerConfig) = ConfigValidator.validateDetailed(c).map { it.path }
        assertTrue("payments" in paths(config.copy(access = AccessConfig())), "yalnızca yerel ağ")
        assertTrue("payments" in paths(config.copy(access = AccessConfig(AccessMode.CLOUDFLARE))), "hızlı tünel")
        val bad = config.copy(
            payments = config.payments.copy(
                publicUrl = "http://ornek.example.com",
                providers = listOf(stripe.copy(webhookSecret = ""), iyzico.copy(apiKey = ""), paddle.copy(clientToken = "")),
                plans = listOf(PaymentPlan("p", "P", PaymentProvider.IYZICO, "price_1", "yok")),
            ),
            automations = listOf(AutomationConfig("a", AutomationTrigger(collection = "orders", events = listOf("update")), actions = listOf(AutomationAction(AutomationActionType.PAYMENT, amount = "", field = "total")))),
        )
        val errors = paths(bad)
        for (p in listOf(
            "payments.publicUrl", "payments.providers[0].webhookSecret", "payments.providers[1].apiKey", "payments.providers[2].clientToken",
            "payments.plans[0].provider", "payments.plans[0].role", "automations[0].actions[0]", "automations[0].actions[0].amount", "automations[0].actions[0].field",
        )) assertTrue(p in errors, "$p yok: $errors")
    }

    @Test
    fun `sdk ve openapi abonelik uclari`() {
        val ts = SdkGenerator.generate(config, SdkGenerator.Lang.TS, "x")
        assertTrue("readonly payments = new Payments(this, { plans: \"/payments/plans\"" in ts, ts.take(300))
        assertTrue("val payments = Payments(this" in SdkGenerator.generate(config, SdkGenerator.Lang.KOTLIN, "x"))
        assertFalse("payments = Payments(" in SdkGenerator.generate(config.copy(payments = config.payments.copy(plans = emptyList())), SdkGenerator.Lang.DART, "x"))
    }

    @Test
    fun `makbuz pdf yapisi`() {
        val pdf = ReceiptPdf.render("Ödeme makbuzu", "Ğİ Şirket\nİstanbul", listOf("Açıklama" to "(özel) \\ karakterler €"), "Toplam" to "1,00 EUR", "Not")
        val text = pdf.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4") && text.trimEnd().endsWith("%%EOF"))
        assertTrue("\\(\\366zel\\) \\\\ karakterler \\200" in text, text)
        // xref kayıtları nesnelerin gerçek konumunu gösterir.
        val xref = text.substringAfter("xref\n0 7\n").lines().drop(1).take(6).map { it.take(10).toInt() }
        xref.forEachIndexed { i, at -> assertTrue(text.startsWith("${i + 1} 0 obj", at), "nesne ${i + 1}") }
    }
}
