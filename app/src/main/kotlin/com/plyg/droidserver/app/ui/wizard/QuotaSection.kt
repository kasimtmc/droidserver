package com.plyg.droidserver.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.core.config.QuotaConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr

/** Kullanıcı başına kotalar: boş bırakılan sınırsız. Yönetici rolündeki kullanıcılar ve API anahtarları muaf. */
@Composable
internal fun QuotaSection(vm: WizardViewModel, draft: ServerConfig) {
    val q = draft.auth.users.quotas
    fun set(change: (QuotaConfig) -> QuotaConfig) = vm.updateUsersSettings { it.copy(quotas = change(it.quotas)) }
    Text(tr("Kotalar"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Her kullanıcı için üst sınırlar; boş bırakılan sınırsızdır. Yöneticiler ve API anahtarları muaftır. Aşılınca istek " +
            "açıklayıcı bir hatayla reddedilir; kullanıcı kendi kullanımını /auth/me/usage ile görür."),
        style = MaterialTheme.typography.bodySmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LimitField("quota/records", tr("Koleksiyon başına kayıt"), q.records, Modifier.weight(1f)) { v -> set { it.copy(records = v) } }
            LimitField("quota/storage", tr("Depolama (MB)"), q.storageMb, Modifier.weight(1f)) { v -> set { it.copy(storageMb = v) } }
        }
        LimitField("quota/requests", tr("Günlük istek"), q.dailyRequests, Modifier.fillMaxWidth()) { v -> set { it.copy(dailyRequests = v) } }
        ErrorText(vm.errorAt("auth.users.quotas") ?: vm.errorAt("auth.users.quotas.records") ?: vm.errorAt("auth.users.quotas.storageMb")
            ?: vm.errorAt("auth.users.quotas.dailyRequests"))
        val owned = draft.collections.filter { it.ownerField != null }
        if (owned.isEmpty()) {
            Text(tr("Kayıt kotası, sahip alanı seçilmiş koleksiyonlarda uygulanır (Koleksiyonlar → kurallar)."), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(tr("Koleksiyona özel kayıt sınırı (boş = yukarıdaki):"), style = MaterialTheme.typography.bodySmall)
            owned.forEach { c ->
                LimitField("quota/c/${c.name}", c.name, q.collections[c.name], Modifier.fillMaxWidth()) { v ->
                    set { it.copy(collections = if (v == null) it.collections - c.name else it.collections + (c.name to v)) }
                }
                ErrorText(vm.errorAt("auth.users.quotas.collections.${c.name}"))
            }
        }
    }
}

@Composable
private fun LimitField(key: String, label: String, value: Int?, modifier: Modifier, onValue: (Int?) -> Unit) {
    var text by rememberSaveable(key) { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value?.toString().orEmpty() }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(9)
            onValue(text.toIntOrNull())
        },
        label = { Text(label) },
        placeholder = { Text(tr("sınırsız")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
