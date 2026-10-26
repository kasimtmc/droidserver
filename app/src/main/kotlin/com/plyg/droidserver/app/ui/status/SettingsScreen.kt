package com.plyg.droidserver.app.ui.status

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.BuildConfig
import com.plyg.droidserver.app.ui.common.ListRow
import com.plyg.droidserver.app.ui.common.RowGroup
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.SectionHeader
import com.plyg.droidserver.app.ui.common.SoftCard
import com.plyg.droidserver.app.ui.common.openBatteryExemption
import com.plyg.droidserver.app.ui.theme.ThemeSettings
import com.plyg.droidserver.core.i18n.tr

/** Uygulama ayarları: görünüm, dil, güvenlik, arka planda çalışma, otomasyon uygulamaları ve güncelleme. */
@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(g: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var autoStart by remember { mutableStateOf(g.settings.autoStart) }
    ScreenScaffold(tr("Ayarlar"), onBack = onBack) { modifier ->
        Column(
            modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(tr("Görünüm"))
            RowGroup {
                ListRow(Icons.Default.DarkMode, tr("Tema"), subtitle = tr("Açık, koyu veya telefonunki gibi"), trailing = null)
                val modes = listOf("system" to tr("Telefon"), "light" to tr("Açık"), "dark" to tr("Koyu"))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    modes.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = ThemeSettings.mode == key,
                            onClick = { ThemeSettings.mode = key; g.settings.themeMode = key },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    ListRow(
                        Icons.Default.Palette, tr("Duvar kâğıdı renkleri"),
                        subtitle = tr("Material You: renkler telefonunuzun temasından alınır"),
                        trailing = { Switch(ThemeSettings.dynamic, { ThemeSettings.dynamic = it; g.settings.dynamicColor = it }) },
                    )
                }
            }

            SectionHeader(tr("Dil"))
            SoftCard { LanguageRow(g) }

            SectionHeader(tr("Güvenlik"))
            SoftCard { AppLockRow(g) }

            SectionHeader(tr("Arka planda çalışma"))
            RowGroup {
                ListRow(
                    Icons.Default.PowerSettingsNew, tr("Cihaz açılınca başlat"),
                    subtitle = tr("Telefon yeniden başlayınca sunucu kendiliğinden açılır"),
                    trailing = { Switch(autoStart, { autoStart = it; g.settings.autoStart = it }) },
                )
                val pm = ctx.getSystemService(PowerManager::class.java)
                val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                ListRow(
                    Icons.Default.BatterySaver, tr("Pil optimizasyonu"),
                    subtitle = if (exempt) tr("Muaf: Android sunucuyu arka planda durdurmaz") else tr("Açık: Android sunucuyu durdurabilir; muaf tutmak için dokunun"),
                    onClick = if (exempt) null else ({
                        openBatteryExemption(ctx)
                    }),
                )
            }

            SectionHeader(tr("Tasker ve otomasyon"))
            AutomationSection(g)

            // Play sürümünde güncellemeyi Play yapar (Durum ekranındaki kart).
            if (BuildConfig.SELF_UPDATE) {
                SectionHeader(tr("Güncelleme"))
                UpdateCard(g)
            }
            g.store?.let { store ->
                SectionHeader("Google Play")
                RowGroup {
                    ListRow(
                        Icons.Default.Star, tr("Play'de değerlendir"),
                        subtitle = tr("Görüşünüzü Play'deki uygulama sayfasında yazın"),
                        onClick = {
                            val page = store.storePage()
                            try {
                                ctx.startActivity(page)
                            } catch (_: ActivityNotFoundException) {
                                ctx.startActivity(page.setPackage(null)) // Play Store yoksa tarayıcıda
                            }
                        },
                    )
                }
            }
        }
    }
}
