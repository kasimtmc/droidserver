package com.plyg.droidserver.app.ui.wizard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.core.config.ChannelConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr

/** WebSocket kanalları: sohbet, oyun, canlı imleç gibi iki yönlü mesajlaşma için. */
@Composable
internal fun ChannelsSection(vm: WizardViewModel, draft: ServerConfig) {
    val channels = draft.realtime.channels
    fun set(change: (List<ChannelConfig>) -> List<ChannelConfig>) = vm.updateRealtime { it.copy(channels = change(it.channels)) }
    Text(tr("WebSocket kanalları"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr("İki yönlü mesajlaşma (sohbet, oyun, canlı imleç): istemciler {0}/realtime/ws adresine bağlanıp kanala katılır, " +
            "ileti gönderir; kanaldakilerin listesi de gelir. Ad sonunda * olan kalıp birçok kanalı kapsar (room:* → room:42).",
            draft.server.basePath.trimEnd('/')),
        style = MaterialTheme.typography.bodySmall,
    )
    channels.forEachIndexed { i, ch ->
        val path = "realtime.channels[$i]"
        fun update(change: (ChannelConfig) -> ChannelConfig) = set { list -> list.mapIndexed { j, c -> if (j == i) change(c) else c } }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ch.name, onValueChange = { v -> update { it.copy(name = v.filter { c -> c.isLetterOrDigit() || c in "_:.-*" }.take(64)) } },
                        label = { Text(tr("Kanal adı veya kalıbı")) }, singleLine = true, modifier = Modifier.weight(1f),
                        isError = vm.errorAt("$path.name") != null,
                    )
                    IconButton(onClick = { set { it.filterIndexed { j, _ -> j != i } } }) { Icon(Icons.Default.Delete, contentDescription = tr("Kanalı kaldır")) }
                }
                ErrorText(vm.errorAt("$path.name"))
                RuleChips(tr("Katılır:"), ch.join) { r -> update { it.copy(join = r) } }
                RuleChips(tr("Gönderir:"), ch.publish) { r -> update { it.copy(publish = r) } }
                val roles = draft.auth.users.roles
                if (roles.isNotEmpty() && (ch.join == Rule.ADMIN || ch.publish == Rule.ADMIN)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Yöneticiyle birlikte:"), style = MaterialTheme.typography.bodySmall)
                        roles.forEach { r ->
                            val on = r.name in ch.roles
                            FilterChip(selected = on, onClick = { update { it.copy(roles = if (on) it.roles - r.name else it.roles + r.name) } }, label = { Text(r.label) })
                        }
                    }
                    ErrorText(vm.errorAt("$path.roles"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ch.presence, onCheckedChange = { v -> update { it.copy(presence = v) } })
                    Text(tr("Kimlerin bağlı olduğunu göster"))
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = ch.history.toString(),
                        onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { n -> update { it.copy(history = n) } } },
                        label = { Text(tr("Son ileti")) }, singleLine = true, modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = vm.errorAt("$path.history") != null,
                    )
                }
                ErrorText(vm.errorAt("$path.history"))
            }
        }
    }
    OutlinedButton(onClick = { set { it + ChannelConfig(if (it.any { c -> c.name == "chat" }) "room:*" else "chat") } }) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(tr("Kanal ekle"))
    }
}

@Composable
private fun RuleChips(label: String, value: Rule, onPick: (Rule) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        listOf(Rule.PUBLIC, Rule.AUTH, Rule.ADMIN).forEach { r -> FilterChip(selected = value == r, onClick = { onPick(r) }, label = { Text(r.title) }) }
    }
}
