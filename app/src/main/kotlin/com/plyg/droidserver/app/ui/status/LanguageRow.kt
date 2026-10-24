package com.plyg.droidserver.app.ui.status

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.service.ServerService
import com.plyg.droidserver.app.ui.RestartActivity
import com.plyg.droidserver.core.i18n.I18n
import com.plyg.droidserver.core.i18n.Lang
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.EngineState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Arayüz dili (uygulama, web paneli, API hata iletileri, e-postalar). Dil değişince uygulama yeniden açılır; sunucu
 * çalışıyorsa durdurulup açılışta yeniden başlatılır.
 */
@Composable
fun LanguageRow(g: AppGraph) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(g.settings.language) }
    var busy by remember { mutableStateOf(false) }

    fun select(code: String?) = scope.launch {
        if (busy) return@launch
        choice = code
        g.settings.language = code
        if (g.settings.lang == I18n.lang) return@launch
        busy = true
        if (g.engine.state.value !is EngineState.Stopped) {
            ServerService.stop(ctx)
            withTimeoutOrNull(20_000) { g.engine.state.first { it is EngineState.Stopped } }
            g.settings.startServerOnLaunch = true
        }
        (ctx as? Activity)?.let { RestartActivity.restart(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Dil / Language")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = choice == null, enabled = !busy, onClick = { select(null) }, label = { Text(tr("Telefonun dili")) })
            Lang.entries.forEach { lang ->
                FilterChip(selected = choice == lang.code, enabled = !busy, onClick = { select(lang.code) }, label = { Text(lang.title) })
            }
        }
        Text(
            if (busy) tr("Uygulama yeniden açılıyor…")
            else tr("Uygulama, web paneli, API hata iletileri ve e-postalar bu dilde olur. Değişince uygulama yeniden açılır; çalışan sunucu kısa süre durup yeniden başlar."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
