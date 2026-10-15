package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.api.ApiPaths
import dev.droidserver.core.api.CollectionEndpoint
import dev.droidserver.core.api.Endpoint
import dev.droidserver.core.api.EndpointGroup
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.EndpointConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemFieldConfig
import dev.droidserver.core.i18n.tr

/** Uzman modu bölümlerinin başlığı: ne olduğunu ve neden dikkat gerektirdiğini söyler. */
@Composable
internal fun ExpertHeader(title: String, description: String) {
    Text(tr("{0} · uzman", title), style = MaterialTheme.typography.titleMedium)
    Text(description, style = MaterialTheme.typography.bodySmall)
}

/** Bir uç: açık/kapalı ve yol (boş: varsayılan). */
@Composable
private fun EndpointRow(
    method: String,
    title: String,
    defaultPath: String,
    basePath: String,
    value: EndpointConfig?,
    error: String?,
    onChange: (EndpointConfig?) -> Unit,
) {
    val cfg = value ?: EndpointConfig()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$method ${basePath.trimEnd('/')}${cfg.path ?: defaultPath}",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = cfg.enabled, onCheckedChange = { on -> onChange(cfg.copy(enabled = on)) })
        }
        if (cfg.enabled) {
            var text by rememberSaveable(defaultPath) { mutableStateOf(cfg.path.orEmpty()) }
            LaunchedEffect(cfg.path) { if ((cfg.path ?: "") != text.trim()) text = cfg.path.orEmpty() }
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v; onChange(cfg.copy(path = v.trim().ifEmpty { null })) },
                label = { Text(tr("Yol")) }, placeholder = { Text(defaultPath) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = error != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        ErrorText(error)
    }
}

/** Yerleşik uçlar (sunucu, yönetici, hesap, sosyal giriş, web push): kapatma ve yol değiştirme. */
@Composable
internal fun EndpointsEditor(vm: WizardViewModel, draft: ServerConfig, groups: List<EndpointGroup>) {
    val passwordGone = draft.auth.users.systemFields.let { it["email"]?.removed == true || it["password"]?.removed == true }
    groups.forEach { group ->
        Text(group.title, style = MaterialTheme.typography.labelLarge)
        Endpoint.entries.filter { it.group == group }.forEach { e ->
            if (passwordGone && e in ApiPaths.PASSWORD_ENDPOINTS) {
                Text(tr("{0} {1}: e-posta/parola alanı kaldırıldığı için kapalı", e.method, e.path), style = MaterialTheme.typography.bodySmall)
                return@forEach
            }
            EndpointRow(
                e.method, e.title, e.path, draft.server.basePath, draft.api.endpoints[e.key],
                vm.errorAt("api.endpoints.${e.key}.path") ?: vm.errorAt("api.endpoints.${e.key}"),
            ) { vm.setEndpoint(e.key, it) }
        }
    }
    Text(
        tr("Yolda yalnızca harf, rakam, - _ . ~ ve {parametre} olabilir; varsayılandaki parametreler ({id} gibi) korunmalı. " +
            "Kapatılan uç 404 döner ve OpenAPI belgesinde, örneklerde ve panelde görünmez."),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Koleksiyonun uçları: taban yol kısayolu, her uç için açık/kapalı ve yol. */
@Composable
internal fun CollectionEndpointsEditor(vm: WizardViewModel, index: Int, c: CollectionConfig, draft: ServerConfig) {
    ExpertHeader(
        tr("Uçlar"),
        tr("Kayıt uçlarının adresleri. Örneğin taban yolu /{0} yaparsanız liste /{1}, tek kayıt /{2}/{id} olur. " +
            "Kapatılan uç tümüyle kaldırılır (kuralın \"Kapalı\" seçeneğinden farkı: 403 yerine 404).", c.name, c.name, c.name),
    )
    var base by rememberSaveable(index) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = base, onValueChange = { v -> base = v; vm.setCollectionBasePath(index, v) },
            label = { Text(tr("Taban yol (hepsine uygula)")) }, placeholder = { Text("/collections/${c.name}") },
            singleLine = true, modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CollectionEndpoint.entries.forEach { e ->
                EndpointRow(
                    e.method, e.title, e.defaultPath(c.name), draft.server.basePath, c.endpoints[e.key],
                    vm.errorAt("collections[$index].endpoints.${e.key}.path") ?: vm.errorAt("collections[$index].endpoints.${e.key}"),
                ) { vm.setCollectionEndpoint(index, e.key, it) }
            }
        }
    }
}

/** Sistem alanı: API'deki adı ve kaldırılması (veritabanı sütunu değişmez). */
@Composable
internal fun SystemFieldsEditor(
    fields: List<Pair<String, String>>,
    values: Map<String, SystemFieldConfig>,
    errorPrefix: String,
    vm: WizardViewModel,
    warning: (String, SystemFieldConfig) -> String? = { _, _ -> null },
    onChange: (String, SystemFieldConfig?) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEach { (key, description) ->
                val cfg = values[key] ?: SystemFieldConfig()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(checked = cfg.removed, onCheckedChange = { on -> onChange(key, cfg.copy(removed = on)) })
                    Text(tr("Kaldır"), style = MaterialTheme.typography.bodySmall)
                }
                if (!cfg.removed) {
                    var text by rememberSaveable(errorPrefix, key) { mutableStateOf(cfg.name.orEmpty()) }
                    OutlinedTextField(
                        value = text, onValueChange = { v -> text = v; onChange(key, cfg.copy(name = v.trim().ifEmpty { null })) },
                        label = { Text(tr("API'deki adı")) }, placeholder = { Text(key) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), isError = vm.errorAt("$errorPrefix.$key.name") != null,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                ErrorText(vm.errorAt("$errorPrefix.$key.name") ?: vm.errorAt("$errorPrefix.$key"))
                warning(key, cfg)?.let { WarningText(it) }
            }
            Text(
                tr("Yeni ad yanıtlarda, filtre/sıralama/alan seçiminde, gövdelerde, canlı abonelikte, webhook'larda ve dışa/içe " +
                    "aktarmada geçerlidir; özgün ad isteklerde de kabul edilir. Kaldırılan alan yanıtlarda görünmez. Veritabanındaki " +
                    "sütun değişmez: veri taşınmaz, geri almak tek dokunuştur. Script'ler özgün adları görür."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Sayı alanı (yerel metin; geçerli sayı yazıldıkça kaydedilir). */
@Composable
internal fun NumberField(label: String, value: Int, error: String?, hint: String, onValue: (Int) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.filter(Char::isDigit).take(7); text.toIntOrNull()?.let(onValue) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error != null, supportingText = { Text(error ?: hint) },
    )
}

/** İstek sınırları (genel). */
@Composable
internal fun LimitsEditor(vm: WizardViewModel, draft: ServerConfig) {
    val l = draft.server.limits
    ExpertHeader(tr("İstek sınırları"), tr("Yerel ağ dışındaki istemcilere uygulanır; telefonun kendisi (uygulama) etkilenmez."))
    NumberField(tr("İstek gövdesi sınırı (KB)"), l.maxBodyKb, vm.errorAt("server.limits.maxBodyKb"), tr("Aşılırsa 413; dosya yüklemeleri ayrıca alan sınırına tabidir.")) { n -> vm.updateLimits { it.copy(maxBodyKb = n) } }
    NumberField(tr("İstemci başına dakikada istek"), l.requestsPerMinute, vm.errorAt("server.limits.requestsPerMinute"), tr("Aşılırsa 429; 0: sınırsız.")) { n -> vm.updateLimits { it.copy(requestsPerMinute = n) } }
    NumberField(tr("Hatalı kimlik denemesi sınırı"), l.authFailures, vm.errorAt("server.limits.authFailures"), tr("Bu kadar 401'den sonra istemci engellenir; 0: kapalı.")) { n -> vm.updateLimits { it.copy(authFailures = n) } }
    NumberField(tr("Engel süresi (dakika)"), l.banMinutes, vm.errorAt("server.limits.banMinutes"), tr("1 dakika - 7 gün.")) { n -> vm.updateLimits { it.copy(banMinutes = n) } }
}

/** Kullanıcı hesaplarının otomatik eklenen alanları, uçları ve oturum ayarları. */
@Composable
internal fun UsersExpertSection(vm: WizardViewModel, draft: ServerConfig) {
    val users = draft.auth.users
    ExpertHeader(
        tr("Sistem alanları"),
        tr("Hesap tablosuna (_users) otomatik eklenen alanlar. Yeni ad verebilir veya API'den kaldırabilirsiniz."),
    )
    SystemFieldsEditor(
        fields = listOf(
            "email" to tr("Giriş kimliği, benzersiz"),
            "password" to tr("Kayıt/giriş gövdesindeki parola (özeti saklanır, hiç gösterilmez)"),
            "verified" to tr("E-posta doğrulandı mı"),
            "role" to tr("user veya admin"),
            "id" to tr("Kullanıcı kimliği"),
            "created_at" to tr("Kayıt zamanı"),
            "updated_at" to tr("Son değişiklik"),
        ),
        values = users.systemFields, errorPrefix = "auth.users.systemFields", vm = vm,
        warning = { key, cfg ->
            when {
                !cfg.removed -> null
                key == "email" || key == "password" ->
                    tr("Parolayla kayıt, giriş ve sıfırlama uçları kapanır; kullanıcılar yalnızca sosyal girişle (Google/GitHub) " +
                        "girebilir") + if (!users.oauth.enabled) tr(". Sosyal giriş kapalı: kimse giriş yapamaz!") else "."
                key == "role" -> tr("Rol yanıtlarda görünmez ve API'den değiştirilemez (yöneticiler uygulamadan atanır).")
                key == "id" -> tr("Kullanıcı kimliği yanıtlarda görünmez; istemciler sahibine göre filtreleyemez.")
                else -> null
            }
        },
    ) { key, value -> vm.setUserSystemField(key, value) }

    ExpertHeader(tr("Oturum ve giriş"), tr("Token süreleri ve hatalı giriş sınırı."))
    NumberField(tr("Erişim token'ı (dakika)"), users.accessTokenMinutes, vm.errorAt("auth.users.accessTokenMinutes"), tr("Kısa tutun; istemci yenileme token'ıyla yenisini alır.")) { n -> vm.updateUsersSettings { it.copy(accessTokenMinutes = n) } }
    NumberField(tr("Yenileme token'ı (gün)"), users.refreshTokenDays, vm.errorAt("auth.users.refreshTokenDays"), tr("Bu süre kullanılmazsa yeniden giriş gerekir.")) { n -> vm.updateUsersSettings { it.copy(refreshTokenDays = n) } }
    NumberField(tr("Hesap başına hatalı giriş"), users.loginAttempts, vm.errorAt("auth.users.loginAttempts"), tr("Sonra aynı istemciden o hesaba giriş engellenir.")) { n -> vm.updateUsersSettings { it.copy(loginAttempts = n) } }
    NumberField(tr("Giriş engeli (dakika)"), users.loginBlockMinutes, vm.errorAt("auth.users.loginBlockMinutes"), tr("Engel süresi.")) { n -> vm.updateUsersSettings { it.copy(loginBlockMinutes = n) } }

    ExpertHeader(tr("Hesap uçları"), tr("Her uç kapatılabilir veya yolu değiştirilebilir (örn. /auth/login → /hesap/giris)."))
    EndpointsEditor(vm, draft, listOf(EndpointGroup.AUTH, EndpointGroup.OAUTH, EndpointGroup.TEAMS))
}
