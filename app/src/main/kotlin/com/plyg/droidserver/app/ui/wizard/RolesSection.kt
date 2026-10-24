package com.plyg.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr

/** Özel roller (editör, moderatör…): kullanıcıya atanır, koleksiyon kurallarında işlem başına izin verilir. */
@Composable
internal fun RolesSection(vm: WizardViewModel, draft: ServerConfig) {
    val roles = draft.auth.users.roles
    Text(tr("Roller"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Kullanıcı ve yönetici dışında kendi rollerinizi tanımlayın (örn. editör tüm yazıları düzenler, moderatör yorumları " +
            "siler). Rolü Sunucu → Kullanıcılar'da atarsınız; ne yapabileceğini Koleksiyonlar → kurallarda seçersiniz. Rol yönetici " +
            "yetkisi vermez."),
        style = MaterialTheme.typography.bodySmall,
    )
    roles.forEachIndexed { i, r ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = r.name,
                onValueChange = { v -> vm.updateRole(i, r.copy(name = v.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(32))) },
                label = { Text(tr("Ad (API'de)")) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = vm.errorAt("auth.users.roles[$i].name") != null,
            )
            OutlinedTextField(
                value = r.title.orEmpty(),
                onValueChange = { v -> vm.updateRole(i, r.copy(title = v.take(40).ifEmpty { null })) },
                label = { Text(tr("Görünen ad")) }, singleLine = true, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.removeRole(i) }) { Icon(Icons.Default.Delete, contentDescription = tr("Rolü kaldır")) }
        }
        ErrorText(vm.errorAt("auth.users.roles[$i].name"))
    }
    ErrorText(vm.errorAt("auth.users.roles"))
    OutlinedButton(onClick = vm::addRole) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(tr("Rol ekle"))
    }
}

/** Takımlar: kullanıcılar takım kurar ve üye ekler; koleksiyonda "Takım üyeleri" kuralı kaydın takımına bakar. */
@Composable
internal fun TeamsSection(vm: WizardViewModel, draft: ServerConfig) {
    val t = draft.auth.users.teams
    Text(tr("Takımlar"), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr("Kullanıcılar takımlara katılır (aile, ekip, şube…). Koleksiyonda \"Takım üyeleri\" kuralı seçilince her kullanıcı " +
                "yalnızca kendi takımlarının kayıtlarını görür ve düzenler."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Switch(checked = t.enabled, onCheckedChange = { on -> vm.updateTeams { it.copy(enabled = on) } })
    }
    ErrorText(vm.errorAt("auth.users.teams.enabled"))
    if (!t.enabled) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Kullanıcılar takım kurabilir (kuran sahibi olur; kapalıysa yalnızca yönetici kurar)"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = t.allowCreate, onCheckedChange = { on -> vm.updateTeams { it.copy(allowCreate = on) } })
    }
    NumberField(tr("Takım başına en çok üye"), t.maxMembers, vm.errorAt("auth.users.teams.maxMembers"), tr("2-10000")) { n -> vm.updateTeams { it.copy(maxMembers = n) } }
    NumberField(tr("Kullanıcı başına en çok takım"), t.maxTeamsPerUser, vm.errorAt("auth.users.teams.maxTeamsPerUser"), tr("1-1000")) { n -> vm.updateTeams { it.copy(maxTeamsPerUser = n) } }
    Text(
        tr("Takımları uygulamada Sunucu → Takımlar'dan, istemcide /teams uçlarından yönetirsiniz (üye ekleme e-postayla)."),
        style = MaterialTheme.typography.bodySmall,
    )
}
