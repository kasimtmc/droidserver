package com.plyg.droidserver.app.ui.status

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.ui.AppLock
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.core.i18n.tr

/** Uygulama kilidi: açmak ve kapatmak da telefonun doğrulamasını ister. */
@Composable
fun AppLockRow(g: AppGraph) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(g.settings.appLock) }
    var after by remember { mutableIntStateOf(g.settings.appLockAfterSec) }
    val secure = AppLock.deviceSecure(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Uygulama kilidi"))
                Text(tr("Parmak izi, yüz veya ekran kilidi PIN'i ister; açıkken ekran görüntüsü alınamaz. Sırları kopyalamak da onay ister."), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, enabled = secure, onCheckedChange = { v ->
                val activity = ctx as? Activity ?: return@Switch
                AppLock.authenticate(activity, if (v) tr("Uygulama kilidini açmak için doğrulayın") else tr("Uygulama kilidini kapatmak için doğrulayın")) { ok ->
                    if (ok) {
                        on = v
                        g.settings.appLock = v
                        AppLock.applySecureFlag(activity)
                    }
                }
            })
        }
        if (!secure) WarningText(tr("Telefonda ekran kilidi (PIN, desen veya parola) yok; uygulama kilidi için önce onu ayarlayın."))
        if (on) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Arka planda:"), style = MaterialTheme.typography.bodySmall)
                listOf(0 to tr("hemen"), 60 to tr("1 dk sonra"), 300 to tr("5 dk sonra")).forEach { (sec, label) ->
                    FilterChip(selected = after == sec, onClick = { after = sec; g.settings.appLockAfterSec = sec }, label = { Text(label) })
                }
            }
        }
    }
}
