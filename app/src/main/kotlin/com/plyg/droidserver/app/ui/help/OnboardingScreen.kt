package com.plyg.droidserver.app.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.templates.AppTemplate
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: String, val text: String)

private val pages = listOf(
    Page(
        Icons.Default.Dns, tr("Telefonunuz bir sunucu"),
        tr("DroidServer telefonu uygulamalarınız ve siteleriniz için bir arka uca çevirir: veritabanı, REST ve GraphQL API, kullanıcı hesapları, dosyalar ve web paneli. Hepsi telefonda çalışır; bulut hesabı gerekmez."),
    ),
    Page(
        Icons.Default.Storage, tr("Veriniz sizde"),
        tr("Koleksiyonlar tablolarınızdır; her biri için API kendiliğinden oluşur. Kimin neyi okuyup yazacağını kurallarla siz belirlersiniz. Yedekler telefonda kalır, isterseniz şifreli olarak buluta da gider."),
    ),
    Page(
        Icons.Default.Public, tr("Ev ağından internete"),
        tr("Sunucu önce yalnızca ev ağınızdan erişilir. İnternetten erişim için Cloudflare tüneli, Tailscale veya alan adınızla doğrudan erişimi sihirbazdan seçersiniz. Sorunlar Telegram'a, e-postaya veya bildirim olarak gelir."),
    ),
)

/**
 * İlk açılış rehberi: üç tanıtım sayfası ve başlangıç seçimi (hazır bir örnek proje veya boş sihirbaz). [onDone]'a seçilen
 * şablon (boş başlangıçta null) verilir; atlamak da boş başlangıçtır.
 */
@Composable
fun OnboardingScreen(onDone: (AppTemplate?) -> Unit, initialPage: Int = 0) {
    val pager = rememberPagerState(initialPage) { pages.size + 1 }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                if (pager.currentPage < pages.size) TextButton(onClick = { onDone(null) }) { Text(tr("Atla")) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                if (i < pages.size) IntroPage(pages[i]) else StartPage(onDone)
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(pages.size + 1) { i ->
                        val color = if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        Box(Modifier.size(8.dp).background(color, CircleShape))
                    }
                }
                if (pager.currentPage < pages.size) Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text(tr("İleri")) }
            }
        }
    }
}

@Composable
private fun IntroPage(p: Page) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(p.icon, contentDescription = null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(24.dp))
        Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(12.dp))
        Text(p.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StartPage(onDone: (AppTemplate?) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Nasıl başlamak istersiniz?"), style = MaterialTheme.typography.headlineSmall)
        Text(
            tr("Hazır bir örnek proje koleksiyonları, kuralları, örnek kayıtları ve basit bir siteyi kurar; sihirbazda istediğinizi değiştirebilirsiniz."),
            style = MaterialTheme.typography.bodyMedium,
        )
        AppTemplate.entries.forEach { t ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { onDone(t) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(onClick = { onDone(null) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Boş başla (sihirbaz)")) }
        Text(tr("Bu bilgilere sonra Durum ekranındaki yardım simgesinden de ulaşabilirsiniz."), style = MaterialTheme.typography.bodySmall)
    }
}
