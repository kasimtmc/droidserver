package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.copyToClipboard
import dev.droidserver.core.config.OAuthProvider
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr

/** Google / GitHub ile giriş: kendi OAuth uygulamanızın istemci kimliği ve sırrı, sunucu adresi, izinli dönüş adresleri. */
@Composable
internal fun OAuthSection(vm: WizardViewModel, draft: ServerConfig) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val oauth = draft.auth.users.oauth
    Text(tr("Sosyal giriş"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Kullanıcılar Google veya GitHub hesabıyla giriş yapar; doğrulanmış e-postaları hesapla eşleşir (yoksa ve kayıt açıksa " +
            "hesap açılır). Her sağlayıcıda kendi OAuth uygulamanızı oluşturup aşağıdaki dönüş adresini kaydedin. Sunucu " +
            "internetten HTTPS ile erişilebilir olmalı (Erişim adımı)."),
        style = MaterialTheme.typography.bodySmall,
    )
    OAuthProvider.entries.forEach { p ->
        val cfg = oauth.providers.firstOrNull { it.provider == p }
        val index = oauth.providers.indexOfFirst { it.provider == p }
        SwitchRow(tr("{0} ile giriş", p.title), if (cfg == null) tr("Kapalı") else tr("Açık"), cfg != null) { vm.setOAuthProvider(p, it) }
        if (cfg == null) return@forEach
        val path = "auth.users.oauth.providers[$index]"
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val callback = oauth.publicUrl.trimEnd('/') + draft.server.basePath.trimEnd('/') + "/auth/oauth/${p.name.lowercase()}/callback"
                Text(tr("Dönüş adresi (sağlayıcıya kaydedin):"), style = MaterialTheme.typography.labelLarge)
                Text(callback, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { copyToClipboard(ctx, tr("dönüş adresi"), callback) }) { Text(tr("Kopyala")) }
                    TextButton(onClick = { runCatching { uri.openUri(p.consoleUrl) } }) { Text(tr("{0} konsolunu aç", p.title)) }
                }
                Text(
                    when (p) {
                        OAuthProvider.GOOGLE -> tr("Google Cloud → API'ler ve hizmetler → Kimlik bilgileri → OAuth istemci kimliği (Web uygulaması); " +
                            "\"Yetkilendirilmiş yönlendirme URI'leri\"ne dönüş adresini ekleyin.")
                        OAuthProvider.GITHUB -> tr("GitHub → Settings → Developer settings → OAuth Apps → New OAuth App; " +
                            "\"Authorization callback URL\"e dönüş adresini yazın.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = cfg.clientId, onValueChange = { vm.setOAuthClientId(p, it) },
                    label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = vm.errorAt("$path.clientId") != null,
                )
                var secret by rememberSaveable(p) { mutableStateOf("") }
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = { Text(if (cfg.clientSecret.isNotEmpty()) tr("Client secret (kayıtlı; değiştirmek için yazın)") else tr("Client secret")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                    isError = vm.errorAt("$path.clientSecret") != null,
                    supportingText = { Text(tr("Şifreli depoda saklanır, konfigürasyon dosyasına yazılmaz.")) },
                )
                if (secret.isNotBlank()) TextButton(onClick = { vm.setOAuthSecret(p, secret); secret = "" }) { Text(tr("Sırrı kaydet")) }
                ErrorText(vm.errorAt("$path.clientId") ?: vm.errorAt("$path.clientSecret") ?: vm.errorAt(path))
            }
        }
    }
    if (oauth.providers.isEmpty()) return
    OutlinedTextField(
        value = oauth.publicUrl, onValueChange = { v -> vm.updateOAuth { it.copy(publicUrl = v.trim()) } },
        label = { Text(tr("Sunucunun adresi")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("https://evim.duckdns.org:8443") },
        isError = vm.errorAt("auth.users.oauth.publicUrl") != null,
        supportingText = { Text(vm.errorAt("auth.users.oauth.publicUrl") ?: tr("Kullanıcının tarayıcısının sunucuya ulaştığı adres.")) },
    )
    vm.suggestedPublicUrl()?.takeIf { it != oauth.publicUrl }?.let { url ->
        TextButton(onClick = { vm.updateOAuth { it.copy(publicUrl = url) } }) { Text(tr("Erişim ayarından kullan: {0}", url)) }
    }
    var redirects by rememberSaveable { mutableStateOf(oauth.redirectUrls.joinToString("\n")) }
    OutlinedTextField(
        value = redirects,
        onValueChange = { v ->
            redirects = v
            vm.updateOAuth { it.copy(redirectUrls = v.lines().map(String::trim).filter(String::isNotEmpty)) }
        },
        label = { Text(tr("İzinli dönüş adresleri (satır başına bir)")) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        placeholder = { Text("https://uygulamam.com/giris\nuygulamam://giris") },
        isError = oauth.redirectUrls.indices.any { vm.errorAt("auth.users.oauth.redirectUrls[$it]") != null },
        supportingText = {
            Text(
                oauth.redirectUrls.indices.firstNotNullOfOrNull { vm.errorAt("auth.users.oauth.redirectUrls[$it]") }
                    ?: tr("Giriş bitince kullanıcı bu adrese ?code= ile döner; istemci kodu POST /auth/oauth/exchange ile token'a çevirir."),
            )
        },
    )
    val base = draft.server.basePath.trimEnd('/') + "/auth/oauth"
    listOf(tr("GET  {0}/providers", base), tr("GET  {0}/{google|github}?redirect=<adres>", base), tr("POST {0}/exchange {code}", base))
        .forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
}
