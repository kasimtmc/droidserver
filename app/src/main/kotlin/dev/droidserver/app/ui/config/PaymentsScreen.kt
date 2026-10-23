package dev.droidserver.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.Pill
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.common.SoftCard
import dev.droidserver.app.ui.common.TipCard
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.app.ui.common.saveConfigChange
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.AccessMode
import dev.droidserver.core.config.CloudflareTunnel
import dev.droidserver.core.config.PaymentPlan
import dev.droidserver.core.config.PaymentProvider
import dev.droidserver.core.config.PaymentProviderConfig
import dev.droidserver.core.config.PaymentsConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.payments.Money
import dev.droidserver.core.payments.PaddleGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/** Sağlayıcının düzenlenen hali; boş bırakılan anahtar alanı kayıtlı anahtarı korur. */
private class ProviderDraft(c: PaymentProviderConfig?) {
    var enabled by mutableStateOf(c?.enabled ?: false)
    var secretKey by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var webhookSecret by mutableStateOf("")
    var clientToken by mutableStateOf(c?.clientToken.orEmpty())
    var sandbox by mutableStateOf(c?.sandbox ?: true)
}

/**
 * Ödemeler: sağlayıcılar (Stripe, iyzico, Paddle) ve anahtarları, sağlayıcı panelinde girilecek bildirim adresleri,
 * internet erişimi denetimi, abonelik planları ve son ödemeler.
 */
@Composable
fun PaymentsScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit, onAccess: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by wizard.active.collectAsState()
    val cfg = active ?: ServerConfig()
    val initial = cfg.payments
    val drafts = remember(initial) { PaymentProvider.entries.associateWith { p -> ProviderDraft(initial.providers.firstOrNull { it.provider == p }) } }
    var publicUrl by remember(initial) { mutableStateOf(initial.publicUrl.ifBlank { wizard.suggestedPublicUrl().orEmpty() }) }
    var merchant by remember(initial) { mutableStateOf(initial.merchant) }
    var statusField by remember(initial) { mutableStateOf(initial.statusField) }
    val plans = remember(initial) { mutableStateListOf<PaymentPlan>().apply { addAll(initial.plans) } }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var check by remember { mutableStateOf<Boolean?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf<List<JsonObject>?>(null) }
    var refunding by remember { mutableStateOf<JsonObject?>(null) }
    var refundError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) { recent = withContext(Dispatchers.IO) { runCatching { g.engine.recentPayments() }.getOrNull() } }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }

    fun save() = scope.launch {
        errors = saveConfigChange(ctx, g, wizard, deploy = true, scope = listOf("payments", "automations")) { c ->
            val old = c.payments.providers.associateBy { it.provider }
            c.copy(
                payments = PaymentsConfig(
                    providers = PaymentProvider.entries.mapNotNull { p ->
                        val d = drafts.getValue(p)
                        val o = old[p]
                        if (o == null && d.secretKey.isBlank()) return@mapNotNull null
                        PaymentProviderConfig(
                            p, d.secretKey.ifBlank { o?.secretKey.orEmpty() },
                            apiKey = d.apiKey.ifBlank { o?.apiKey.orEmpty() },
                            webhookSecret = d.webhookSecret.ifBlank { o?.webhookSecret.orEmpty() },
                            clientToken = d.clientToken.trim(), sandbox = d.sandbox, enabled = d.enabled,
                        )
                    },
                    publicUrl = publicUrl.trim().trimEnd('/'), plans = plans.toList(),
                    statusField = statusField.trim().ifEmpty { "payment_status" }, merchant = merchant.trim(),
                ),
            )
        }
        if (errors.isEmpty()) drafts.values.forEach { it.secretKey = ""; it.apiKey = ""; it.webhookSecret = "" }
    }

    refunding?.let { p ->
        val amount = Money.format((p["amount"] as? JsonPrimitive)?.longOrNull ?: 0, (p["currency"] as? JsonPrimitive)?.contentOrNull ?: "TRY")
        AlertDialog(
            onDismissRequest = { refunding = null },
            title = { Text(tr("İade edilsin mi?")) },
            text = { Text(tr("{0} ({1}) alıcıya tam iade edilir. Bu geri alınamaz.", (p["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), amount)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = (p["id"] as JsonPrimitive).content.toLong()
                    refunding = null
                    scope.launch {
                        refundError = withContext(Dispatchers.IO) { runCatching { g.engine.payments.refund(id) }.exceptionOrNull()?.message }
                        tick++
                    }
                }) { Text(tr("İade et")) }
            },
            dismissButton = { TextButton(onClick = { refunding = null }) { Text(tr("Vazgeç")) } },
        )
    }

    ScreenScaffold(tr("Ödemeler"), subtitle = initial.providers.filter { it.enabled }.joinToString { it.provider.title }.ifEmpty { tr("Kapalı") }, onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TipCard(
                g, "payments-intro",
                tr("Müşteri sağlayıcının kendi ödeme sayfasında öder; kart bilgisi telefona hiç gelmez. Sonuç sağlayıcının imzalı bildirimiyle gelir, ödeme kaydına ve bağlı kaydın ödeme durumu alanına yazılır, otomasyonları tetikler."),
            )

            SectionCard(tr("İnternet erişimi")) {
                val access = cfg.access
                when {
                    !access.mode.public -> WarningText(tr("Sağlayıcı bildirimleri telefona internetten ulaşmalı. Erişim'de doğrudan erişim veya Cloudflare Tunnel seçin."))
                    access.mode == AccessMode.CLOUDFLARE && access.cloudflare.tunnel == CloudflareTunnel.QUICK ->
                        WarningText(tr("Hızlı tünelin adresi her başlatmada değişir; kendi alan adınızla tünel kurun."))
                    else -> Text(tr("Erişim: {0}", access.mode.title), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onAccess) { Text(tr("Erişim adımına git")) }
                OutlinedTextField(
                    publicUrl, { publicUrl = it.trim(); check = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Sunucunun internet adresi")) }, placeholder = { Text("https://ornek.duckdns.org") },
                )
                wizard.suggestedPublicUrl()?.takeIf { it != publicUrl }?.let { url ->
                    TextButton(onClick = { publicUrl = url }) { Text(tr("Erişim ayarından kullan: {0}", url)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = publicUrl.startsWith("https://"), onClick = {
                        scope.launch { check = withContext(Dispatchers.IO) { healthy(publicUrl.trimEnd('/') + cfg.server.basePath.trimEnd('/') + "/health") } }
                    }) { Text(tr("Denetle")) }
                    when (check) {
                        true -> Text(tr("Adres yanıt veriyor"), color = MaterialTheme.colorScheme.primary)
                        false -> Text(tr("Adrese ulaşılamadı"), color = MaterialTheme.colorScheme.error)
                        null -> {}
                    }
                }
            }

            PaymentProvider.entries.forEach { p ->
                val d = drafts.getValue(p)
                val saved = initial.providers.firstOrNull { it.provider == p }
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(d.enabled, { d.enabled = it })
                    }
                    Text(
                        when (p) {
                            PaymentProvider.STRIPE -> tr("Uluslararası kartlar, Apple Pay ve Google Pay; abonelik. Test için sk_test_ anahtarı.")
                            PaymentProvider.IYZICO -> tr("Türkiye: yerli kartlar ve taksit. Alıcının adı, kimlik numarası, telefonu ve adresi gerekir.")
                            PaymentProvider.PADDLE -> tr("Vergi ve faturayı Paddle üstlenir (satıcı kaydı); abonelik. Ödeme sayfası Paddle.js ile bu sunucuda açılır.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (d.enabled) {
                        fun keep(v: String, old: String?) = if (v.isEmpty() && !old.isNullOrEmpty()) tr("Kayıtlı; değiştirmek için yenisini yazın.") else tr("Şifreli depoda saklanır.")
                        Secret(if (p == PaymentProvider.PADDLE) tr("API anahtarı") else tr("Gizli anahtar"), d.secretKey, keep(d.secretKey, saved?.secretKey)) { d.secretKey = it }
                        if (p == PaymentProvider.IYZICO) Secret(tr("API anahtarı"), d.apiKey, keep(d.apiKey, saved?.apiKey)) { d.apiKey = it }
                        if (p != PaymentProvider.IYZICO) Secret(tr("Bildirim imza sırrı"), d.webhookSecret, keep(d.webhookSecret, saved?.webhookSecret)) { d.webhookSecret = it }
                        if (p == PaymentProvider.PADDLE) OutlinedTextField(
                            d.clientToken, { d.clientToken = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("İstemci token'ı")) },
                        )
                        if (p != PaymentProvider.STRIPE) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Test ortamı (sandbox)"), Modifier.weight(1f)); Switch(d.sandbox, { d.sandbox = it })
                        }
                        val base = publicUrl.trimEnd('/')
                        if (base.isNotEmpty()) {
                            CopyLine(tr("Bildirim (webhook) adresi"), "$base${PaymentsConfig.PATH}/hook/${p.wire}") { copyToClipboard(ctx, p.title, it) }
                            if (p == PaymentProvider.PADDLE) CopyLine(tr("Varsayılan ödeme bağlantısı"), base + PaddleGateway.PAGE) { copyToClipboard(ctx, p.title, it) }
                        }
                        Text(
                            when (p) {
                                PaymentProvider.STRIPE -> tr("Stripe → Geliştiriciler → Webhook'lar: adresi ekleyin; olaylar checkout.session.*, charge.refunded ve (abonelikte) customer.subscription.*. İmza sırrı whsec_ ile başlar.")
                                PaymentProvider.IYZICO -> tr("Ödemenin sonucu alıcı döndüğünde iyzico'ya sorulur. Üye işyeri panelinde bildirim adresini de ekleyebilirsiniz. Panelden yapılan iadeler altı saatte bir sorulur.")
                                PaymentProvider.PADDLE -> tr("Paddle → Developer tools → Notifications: adresi ekleyin (transaction.*, adjustment.*, subscription.*). Checkout settings'te varsayılan ödeme bağlantısını yukarıdaki adres yapın.")
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard(tr("Abonelik planları")) {
                Text(tr("Ödeyen kullanıcı abonelik sürdükçe planın rolünü alır; abonelik bitince önceki rolüne döner. Stripe veya Paddle'da oluşturduğunuz fiyatın kimliğini yazın."), style = MaterialTheme.typography.bodySmall)
                plans.forEachIndexed { i, plan ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.title, style = MaterialTheme.typography.titleSmall)
                            Text("${plan.provider.title} · ${plan.price} → ${plan.role}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = { plans.removeAt(i) }) { Text(tr("Kaldır")) }
                    }
                }
                PlanForm(cfg.auth.users.roles.map { it.name }) { plans += it }
            }

            SectionCard(tr("Makbuz ve kayıt")) {
                OutlinedTextField(merchant, { merchant = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Makbuzdaki satıcı bilgisi")) }, placeholder = { Text(cfg.name) })
                OutlinedTextField(
                    statusField, { statusField = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Ödeme durumu alanı")) },
                    supportingText = { Text(tr("Bağlı kayıtta bu ad varsa durum (pending, paid, failed, canceled, refunded) oraya yazılır.")) },
                )
            }

            errors.forEach { ErrorText(it) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Kaydet ve uygula")) }

            recent?.let { list ->
                SectionCard(tr("Son ödemeler")) {
                    refundError?.let { ErrorText(it) }
                    if (list.isEmpty()) Text(tr("Henüz ödeme yok."), style = MaterialTheme.typography.bodySmall)
                    list.forEach { p ->
                        PaymentRow(p) {
                            refunding = p
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Secret(label: String, value: String, hint: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, { onChange(it.trim()) }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
    label = { Text(label) }, supportingText = { Text(hint) },
)

@Composable
private fun CopyLine(label: String, text: String, onCopy: (String) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    TextButton(onClick = { onCopy(text) }) { Text(tr("Kopyala")) }
}

@Composable
private fun PlanForm(roles: List<String>, onAdd: (PaymentPlan) -> Unit) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(PaymentProvider.STRIPE) }
    var price by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(roles.firstOrNull().orEmpty()) }
    if (roles.isEmpty()) {
        Text(tr("Önce Kullanıcılar adımında bir rol ekleyin (ör. pro)."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    OutlinedTextField(name, { name = it.trim().lowercase() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Kısa ad")) }, placeholder = { Text("pro") })
    OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Başlık")) }, placeholder = { Text("Pro (aylık)") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentProvider.entries.filter { it.subscriptions }.forEach { p -> FilterChip(provider == p, { provider = p }, label = { Text(p.title) }) }
    }
    OutlinedTextField(
        price, { price = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Fiyat kimliği")) },
        placeholder = { Text(if (provider == PaymentProvider.PADDLE) "pri_…" else "price_…") },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { roles.forEach { r -> FilterChip(role == r, { role = r }, label = { Text(r) }) } }
    OutlinedButton(enabled = name.isNotEmpty() && title.isNotBlank() && price.isNotEmpty() && role.isNotEmpty(), onClick = {
        onAdd(PaymentPlan(name, title.trim(), provider, price, role)); name = ""; title = ""; price = ""
    }) { Text(tr("Plan ekle")) }
}

@Composable
private fun PaymentRow(p: JsonObject, onRefund: () -> Unit) {
    fun s(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull
    val amount = (p["amount"] as? JsonPrimitive)?.longOrNull ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s("description").orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                listOfNotNull(Money.format(amount, s("currency") ?: "TRY"), PaymentProvider.entries.firstOrNull { it.wire == s("provider") }?.title, s("collection")?.let { c -> "$c #${s("record")}" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Pill(statusLabel(s("status")))
        if (s("status") == "paid") TextButton(onClick = onRefund) { Text(tr("İade et")) }
    }
}

private fun statusLabel(s: String?) = when (s) {
    "paid" -> tr("Ödendi")
    "refunded" -> tr("İade edildi")
    "canceled" -> tr("İptal edildi")
    "failed" -> tr("Başarısız")
    else -> tr("Bekliyor")
}

private fun healthy(url: String): Boolean = runCatching {
    (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 6_000
        readTimeout = 6_000
        try { responseCode in 200..299 } finally { disconnect() }
    }
}.getOrDefault(false)
