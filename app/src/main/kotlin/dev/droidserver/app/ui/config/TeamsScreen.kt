package dev.droidserver.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.auth.Caller
import dev.droidserver.core.auth.TeamService
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Çalışan sunucunun takımları (yönetici olarak): kur, üye ekle/çıkar, rol, sil. */
@Composable
fun TeamsScreen(g: AppGraph, onBack: () -> Unit) {
    val engineState by g.engine.state.collectAsState()
    val running = engineState is EngineState.Running
    val scope = rememberCoroutineScope()
    var teams by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }

    fun act(block: (TeamService) -> Unit) = scope.launch {
        error = withContext(Dispatchers.IO) {
            val svc = g.engine.teams() ?: return@withContext tr("Sunucu çalışmıyor veya takımlar kapalı")
            runCatching { block(svc) }.exceptionOrNull()?.message
        }
        reload++
    }

    LaunchedEffect(running, reload) {
        withContext(Dispatchers.IO) {
            val svc = g.engine.teams() ?: return@withContext
            teams = svc.list(Caller.Admin)["items"]!!.jsonArray.map { it.jsonObject }
            open = open?.let { o -> runCatching { svc.get(Caller.Admin, o["id"]!!.jsonPrimitive.long) }.getOrNull() }
        }
    }

    ScreenScaffold(tr("Takımlar"), subtitle = tr("{0} takım", teams.size), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!running) WarningText(tr("Takımları yönetmek için sunucuyu başlatın."))
            Text(
                tr("Kullanıcılar da kendi takımlarını /teams uçlarıyla yönetir (takımı kuran sahibi olur). Burada yönetici olarak tüm takımları görürsünüz."),
                style = MaterialTheme.typography.bodySmall,
            )
            ErrorText(error)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text(tr("Yeni takım adı")) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = running && newName.isNotBlank(), onClick = { val n = newName; newName = ""; act { it.create(Caller.Admin, n) } }) { Text(tr("Kur")) }
            }
            teams.forEach { t ->
                val id = t["id"]!!.jsonPrimitive.long
                Card(Modifier.fillMaxWidth().clickable { open = if (open?.get("id")?.jsonPrimitive?.long == id) null else t; reload++ }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t["name"]!!.jsonPrimitive.content, style = MaterialTheme.typography.titleSmall)
                                Text(tr("{0} üye · kimlik {1}", t["memberCount"]!!.jsonPrimitive.content, id), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { deleting = t }) { Icon(Icons.Default.Delete, contentDescription = tr("Sil")) }
                        }
                        val detail = open?.takeIf { it["id"]?.jsonPrimitive?.long == id }
                        detail?.get("members")?.jsonArray?.forEach { m ->
                            val o = m.jsonObject
                            val user = o["user"]!!.jsonPrimitive.long
                            val role = o["role"]!!.jsonPrimitive.content
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${o["email"]?.jsonPrimitive?.content ?: "#$user"} · " + if (role == SystemCollections.TEAM_OWNER) tr("sahip") else tr("üye"),
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    act { it.setRole(Caller.Admin, id, user, if (role == SystemCollections.TEAM_OWNER) SystemCollections.TEAM_MEMBER else SystemCollections.TEAM_OWNER) }
                                }) { Text(if (role == SystemCollections.TEAM_OWNER) tr("Üye yap") else tr("Sahip yap")) }
                                TextButton(onClick = { act { it.removeMember(Caller.Admin, id, user) } }) { Text(tr("Çıkar")) }
                            }
                        }
                        if (detail != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(email, { email = it.trim() }, label = { Text(tr("Üye e-postası")) }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedButton(enabled = email.isNotBlank(), onClick = { val e = email; email = ""; act { it.addMember(Caller.Admin, id, e, null, null) } }) { Text(tr("Ekle")) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("'{0}' silinsin mi?", t["name"]!!.jsonPrimitive.content)) },
            text = { Text(tr("Üyelikler silinir; takıma bağlı kayıtlar takım alanının silme davranışına göre silinir veya boşalır.")) },
            confirmButton = { Button(onClick = { deleting = null; act { it.delete(Caller.Admin, t["id"]!!.jsonPrimitive.long) } }) { Text(tr("Sil")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Vazgeç")) } },
        )
    }
}
