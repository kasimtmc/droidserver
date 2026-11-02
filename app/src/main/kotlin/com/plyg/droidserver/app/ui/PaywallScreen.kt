package com.plyg.droidserver.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.data.License
import com.plyg.droidserver.core.i18n.tr

/**
 * Play sürümünde deneme bitip satın alma yokken uygulamanın yerine açılır: uygulamanın sunduklarını anlatır, satın
 * almayı ve geri yüklemeyi sunar. Sunucu arka planda çalışmaya devam eder.
 */
@Composable
internal fun PaywallScreen(license: License.Required, onBuy: () -> Unit, onRestore: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(tr("DroidServer'ın tamamı, tek seferlik ödemeyle"), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("Bir haftalık deneme süreniz bitti. Sunucunuz ve siteleriniz çalışmaya devam ediyor; uygulamayı kullanmaya devam etmek için tam sürümü satın alın."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Feature(Icons.Default.Dns, tr("Telefonunuz tam bir sunucu: veritabanı, REST ve GraphQL API, kullanıcı hesapları ve dosyalar"))
            Feature(Icons.Default.Language, tr("Statik siteler, sayfalar ve panolar; Cloudflare tüneliyle internetten erişim"))
            Feature(Icons.Default.Extension, tr("PostgreSQL, Redis ve kendi Node.js, Python, Java uygulamalarınız"))
            Feature(Icons.Default.SmartToy, tr("Otomasyonlar, formlar, ödemeler, MQTT ile ev otomasyonu ve yapay zekâ"))
            Feature(Icons.Default.Backup, tr("Yedekler, sağlık uyarıları, web paneli ve komut satırı aracı"))
            Feature(Icons.Default.PhoneAndroid, tr("Abonelik yok: bir kez ödersiniz. Verileriniz kendi telefonunuzda kalır."))
            Spacer(Modifier.height(8.dp))
            if (license.pending) {
                Text(tr("Ödemeniz onay bekliyor; onaylanınca uygulama kendiliğinden açılır."), style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) {
                    Text(license.price?.let { tr("Satın al · {0}", it) } ?: tr("Satın al"))
                }
            }
            TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text(tr("Satın alımı geri yükle")) }
            license.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
