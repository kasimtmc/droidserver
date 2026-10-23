package dev.droidserver.core.api

import dev.droidserver.core.config.PaymentProvider
import dev.droidserver.core.config.PaymentsConfig
import dev.droidserver.core.i18n.I18n
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.payments.Money
import dev.droidserver.core.payments.PaddleGateway
import dev.droidserver.core.payments.PaymentService
import dev.droidserver.core.payments.PaymentStatus
import dev.droidserver.core.payments.ReceiptPdf
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Ödemelerin herkese açık uçları (`/payments`, API yolunun dışında):
 *  - `POST /payments/hook/<sağlayıcı>`: sağlayıcı bildirimi (imzalı).
 *  - `GET /payments/<token>`: alıcının döndüğü durum sayfası (bekliyorsa sağlayıcıya sorulur); `POST` aynı adrese
 *    iyzico'nun ödeme formu dönüşü.
 *  - `GET /payments/<token>/receipt`: makbuz (sağlayıcının faturası varsa ona yönlenir, yoksa PDF).
 *  - `GET /payments/subscribed`: abonelik ödemesinden dönüş.
 *  - `GET /payments/paddle`: Paddle.js ile ödeme sayfası (Paddle açıksa).
 * Token tahmin edilemez (144 bit); sayfalar arama motorlarına kapalı.
 */
internal fun Route.paymentPages(serverName: String, config: PaymentsConfig, service: PaymentService, writes: WriteGate) {
    val path = PaymentsConfig.PATH
    config.provider(PaymentProvider.PADDLE)?.let { paddle ->
        // Paddle'da varsayılan ödeme bağlantısı: Paddle.js adresteki işlemi açar (betikli tek sayfa).
        get(PaddleGateway.PAGE) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.respondText(PaddleGateway.page(paddle), ContentType.Text.Html)
        }
        get("${PaddleGateway.PAGE}/done") {
            val token = call.request.queryParameters["txn"]?.let { io { service.tokenOf(it) } }
            call.respondRedirect(if (token != null) "$path/$token" else "$path/subscribed")
        }
    }
    post("$path/hook/{provider}") {
        val provider = PaymentProvider.entries.firstOrNull { it.wire == call.parameters["provider"] }
            ?: return@post call.respondText(tr("Bilinmeyen sağlayıcı"), status = HttpStatusCode.NotFound)
        val body = call.receiveText()
        val handled = io { writes.write { service.hook(provider, { call.request.headers[it] }, body) } }
        call.respond(JsonObject(mapOf("received" to JsonPrimitive(handled))))
    }
    get("$path/subscribed") {
        call.page(serverName, tr("Abonelik"), "<p>" + esc(tr("Ödemeniz alındıysa aboneliğiniz birkaç saniye içinde hesabınıza yansır. Bu sayfayı kapatabilirsiniz.")) + "</p>")
    }
    get("$path/{token}") {
        val row = io { writes.write { service.refresh(call.parameters["token"].orEmpty()) } } ?: return@get call.missing(serverName)
        call.page(serverName, tr("Ödeme"), status(row))
    }
    post("$path/{token}") {
        val token = call.parameters["token"].orEmpty()
        val form = call.receiveParameters().entries().associate { it.key to it.value.firstOrNull().orEmpty() }
        io { writes.write { service.refresh(token, form) } } ?: return@post call.missing(serverName)
        call.respondRedirect("$path/$token")
    }
    get("$path/{token}/receipt") {
        val row = io { service.byToken(call.parameters["token"].orEmpty()) } ?: return@get call.missing(serverName)
        val status = str(row, "status")
        if (status != PaymentStatus.PAID && status != PaymentStatus.REFUNDED) return@get call.missing(serverName)
        io { service.providerReceipt(row) }?.let { return@get call.respondRedirect(it) }
        call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        call.response.headers.append(HttpHeaders.ContentDisposition, "inline; filename=\"makbuz-${row["id"]}.pdf\"")
        call.respondBytes(receipt(row, config.merchant.ifBlank { serverName }), ContentType.Application.Pdf)
    }
}

/** Basit makbuz: satıcı, numara, tarih, açıklama, ödeme yöntemi, referans ve tutar. */
internal fun receipt(row: JsonObject, merchant: String): ByteArray {
    val amount = (row["amount"] as? JsonPrimitive)?.longOrNull ?: 0
    val currency = str(row, "currency") ?: "TRY"
    val paid = str(row, "paid_at")?.let { runCatching { date(Instant.parse(it)) }.getOrNull() }
    val provider = PaymentProvider.entries.firstOrNull { it.wire == str(row, "provider") }?.title ?: str(row, "provider").orEmpty()
    val rows = listOfNotNull(
        tr("Makbuz no") to "#${row["id"]}",
        paid?.let { tr("Ödeme tarihi") to it },
        tr("Açıklama") to str(row, "description").orEmpty(),
        tr("Ödeme yöntemi") to provider,
        str(row, "reference")?.let { tr("Referans") to it },
        str(row, "email")?.let { tr("Alıcı") to it },
        (tr("Durum") to tr("İade edildi")).takeIf { str(row, "status") == PaymentStatus.REFUNDED },
    )
    return ReceiptPdf.render(
        tr("Ödeme makbuzu"), merchant, rows, tr("Toplam") to Money.format(amount, currency),
        tr("Bu belge ödemenin alındığını gösterir; fatura yerine geçmez."),
    )
}

private fun status(row: JsonObject): String {
    val amount = (row["amount"] as? JsonPrimitive)?.longOrNull ?: 0
    val currency = str(row, "currency") ?: "TRY"
    val token = str(row, "token").orEmpty()
    val (label, note) = when (str(row, "status")) {
        PaymentStatus.PAID -> tr("Ödendi") to tr("Ödemeniz alındı, teşekkürler.")
        PaymentStatus.REFUNDED -> tr("İade edildi") to tr("Ödeme iade edildi.")
        PaymentStatus.CANCELED -> tr("İptal edildi") to tr("Bu ödeme iptal edildi.")
        PaymentStatus.FAILED -> tr("Başarısız") to tr("Ödeme tamamlanamadı; yeniden deneyebilirsiniz.")
        else -> tr("Bekliyor") to tr("Ödeme henüz tamamlanmadı. Ödediyseniz birkaç saniye sonra sayfayı yenileyin.")
    }
    return buildString {
        append("<p class=\"d\">").append(esc(str(row, "description"))).append("</p>")
        append("<p class=\"a\">").append(esc(Money.format(amount, currency))).append("</p>")
        append("<p><span class=\"s\">").append(esc(label)).append("</span></p><p>").append(esc(note)).append("</p>")
        val status = str(row, "status")
        if ((status == PaymentStatus.PENDING || status == PaymentStatus.FAILED) && str(row, "url") != null) {
            append("<p><a class=\"b\" href=\"").append(esc(str(row, "url"))).append("\">").append(esc(tr("Ödemeye geç"))).append("</a></p>")
        }
        if (status == PaymentStatus.PAID || status == PaymentStatus.REFUNDED) {
            append("<p><a class=\"b\" href=\"").append(PaymentsConfig.PATH).append('/').append(esc(token)).append("/receipt\">").append(esc(tr("Makbuz"))).append("</a></p>")
        }
    }
}

private fun date(i: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(I18n.lang.code)).withZone(ZoneId.systemDefault()).format(i)

private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private suspend fun RoutingCall.missing(server: String) =
    page(server, tr("Ödeme"), "<p>" + esc(tr("Ödeme bulunamadı.")) + "</p>", HttpStatusCode.NotFound)

private suspend fun RoutingCall.page(server: String, title: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
    respondText(
        """<!doctype html><html lang="${I18n.lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        |<meta name="robots" content="noindex"><title>${esc(title)} · ${esc(server)}</title><style>
        |:root{--bg:#f4faf8;--fg:#161d1c;--muted:#5b6563;--card:#fff;--line:#d5e0dd;--accent:#006a65;--on:#fff}
        |@media(prefers-color-scheme:dark){:root{--bg:#0f1514;--fg:#dde4e2;--muted:#9aa6a3;--card:#171d1c;--line:#2c3634;--accent:#82d5ce;--on:#003734}}
        |*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--fg);background:var(--bg)}
        |main{max-width:480px;margin:8vh auto;padding:24px;background:var(--card);border:1px solid var(--line);border-radius:20px;text-align:center}
        |h1{font-size:22px;margin:0 0 12px}.d{color:var(--muted)}.a{font-size:34px;font-weight:700;margin:8px 0}
        |.s{display:inline-block;padding:2px 12px;border-radius:999px;border:1px solid var(--line);font-weight:600}
        |.b{display:inline-block;margin-top:8px;padding:10px 22px;border-radius:999px;background:var(--accent);color:var(--on);text-decoration:none;font-weight:600}
        |footer{text-align:center;color:var(--muted);font-size:12px;margin:14px 0}
        |</style></head><body><main><h1>${esc(title)}</h1>$body</main><footer>${esc(server)}</footer></body></html>""".trimMargin(),
        ContentType.Text.Html, status,
    )
}
