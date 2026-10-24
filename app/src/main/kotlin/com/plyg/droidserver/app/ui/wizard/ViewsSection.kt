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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.core.api.ViewService
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ReportConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.ViewConfig
import com.plyg.droidserver.core.i18n.tr

/**
 * Salt okunur görünümler ve raporlar: koleksiyonun seçilen sütunları (ilişki üzerinden bir adım dahil), sabit koşul ve
 * sıralama ya da gruplu özet; kim görebilir koleksiyon kurallarından ayrı seçilir.
 */
@Composable
internal fun ViewsSection(vm: WizardViewModel, draft: ServerConfig) {
    Text(tr("Görünümler ve raporlar"), style = MaterialTheme.typography.titleMedium)
    Text(
        tr("Koleksiyonu değiştirmeden salt okunur bir liste veya özet yayınlayın: örn. herkese açık \"ödenmiş siparişler (müşteri " +
            "adıyla)\" ya da yalnızca muhasebeciye \"duruma göre ciro\". Adres: {0}/views/<ad>, CSV için ?format=csv.", draft.server.basePath.trimEnd('/')),
        style = MaterialTheme.typography.bodySmall,
    )
    draft.views.forEachIndexed { i, v -> ViewCard(vm, draft, i, v) }
    OutlinedButton(onClick = vm::addView) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(tr("Görünüm ekle"))
    }
}

@Composable
private fun ViewCard(vm: WizardViewModel, draft: ServerConfig, index: Int, v: ViewConfig) {
    val path = "views[$index]"
    fun set(change: (ViewConfig) -> ViewConfig) = vm.updateView(index, change)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = v.name, onValueChange = { s -> set { it.copy(name = s.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(40)) } },
                    label = { Text(tr("Ad (adreste)")) }, singleLine = true, modifier = Modifier.weight(1f),
                    isError = vm.errorAt("$path.name") != null,
                )
                OutlinedTextField(
                    value = v.title.orEmpty(), onValueChange = { s -> set { it.copy(title = s.take(60).ifEmpty { null }) } },
                    label = { Text(tr("Başlık")) }, singleLine = true, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.removeView(index) }) { Icon(Icons.Default.Delete, contentDescription = tr("Görünümü kaldır")) }
            }
            ErrorText(vm.errorAt("$path.name"))
            Chips(tr("Koleksiyon:"), draft.collections.map { it.name to it.name }, setOf(v.collection)) { name ->
                set { it.copy(collection = name, columns = emptyList(), filter = null, sort = null, report = null) }
            }
            ErrorText(vm.errorAt("$path.collection"))
            val c = draft.collections.firstOrNull { it.name == v.collection } ?: return@Column

            // Sütunlar: temel alanlar ve ilişki alanlarının hedef alanları (bir adım).
            val options = buildList {
                add("id"); c.fields.filter { it.type != FieldType.RELATION }.forEach { add(it.name) }; add("created_at")
                c.fields.filter { it.type == FieldType.RELATION }.forEach { rel ->
                    add(rel.name)
                    val targetFields = if (rel.collection == SystemCollections.USERS) draft.auth.users.publicFields
                    else draft.collections.firstOrNull { it.name == rel.collection }?.fields?.filter { it.type != FieldType.RELATION }?.map { it.name }.orEmpty()
                    targetFields.forEach { add("${rel.name}.$it") }
                }
            }
            val selected = v.columns.ifEmpty { ViewService.defaultColumns(draft, v) }
            if (v.report == null) {
                Chips(tr("Sütunlar:"), options.map { it to it }, selected.toSet()) { col ->
                    set { it.copy(columns = if (col in selected) selected - col else options.filter { o -> o in selected || o == col }) }
                }
                v.columns.indices.mapNotNull { vm.errorAt("$path.columns[$it]") }.firstOrNull()?.let { ErrorText(it) }
            }
            OutlinedTextField(
                value = v.filter.orEmpty(), onValueChange = { s -> set { it.copy(filter = s.trim().ifEmpty { null }) } },
                label = { Text(tr("Koşul (isteğe bağlı)")) }, placeholder = { Text("status=paid") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = vm.errorAt("$path.filter") != null,
                supportingText = { Text(vm.errorAt("$path.filter") ?: tr("Liste filtresi sözdizimi; @me istekteki kullanıcıdır (giriş gerektiren görünümde).")) },
            )
            if (v.report == null) {
                OutlinedTextField(
                    value = v.sort.orEmpty(), onValueChange = { s -> set { it.copy(sort = s.trim().ifEmpty { null }) } },
                    label = { Text(tr("Sıralama (isteğe bağlı)")) }, placeholder = { Text("-created_at") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = vm.errorAt("$path.sort") != null,
                )
                ErrorText(vm.errorAt("$path.sort"))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Rapor (özet)"))
                    Text(tr("Kayıtlar yerine sayı ve toplamlar; isteğe bağlı gruplama."), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = v.report != null, onCheckedChange = { on -> set { it.copy(report = if (on) ReportConfig() else null) } })
            }
            v.report?.let { r ->
                val groupable = listOf("" to tr("Gruplama yok")) + c.fields.filter {
                    it.type in setOf(FieldType.TEXT, FieldType.SELECT, FieldType.BOOLEAN, FieldType.RELATION, FieldType.INTEGER) && !it.encrypted
                }.map { it.name to it.name } + (c.fields.filter { it.type == FieldType.DATETIME }.map { it.name } + "created_at").flatMap { d ->
                    listOf("$d:day" to tr("{0} (gün)", d), "$d:month" to tr("{0} (ay)", d), "$d:year" to tr("{0} (yıl)", d))
                }
                Chips(tr("Grupla:"), groupable, setOf(r.groupBy.orEmpty())) { g -> set { it.copy(report = r.copy(groupBy = g.ifEmpty { null })) } }
                val numbers = c.fields.filter { it.type == FieldType.INTEGER || it.type == FieldType.REAL }.map { it.name }
                if (numbers.isNotEmpty()) {
                    Chips(tr("Topla:"), numbers.map { it to it }, r.sum.toSet()) { n -> set { it.copy(report = r.copy(sum = r.sum.toggle(n))) } }
                    Chips(tr("Ortalama:"), numbers.map { it to it }, r.avg.toSet()) { n -> set { it.copy(report = r.copy(avg = r.avg.toggle(n))) } }
                }
                ErrorText(vm.errorAt("$path.report"))
            }

            val accessChoices = listOf(Rule.PUBLIC.name to Rule.PUBLIC.title, Rule.AUTH.name to Rule.AUTH.title, Rule.ADMIN.name to Rule.ADMIN.title)
            Chips(tr("Kim görür:"), accessChoices, setOf(v.access.name)) { a -> set { it.copy(access = Rule.valueOf(a), roles = if (a == Rule.ADMIN.name) it.roles else emptyList()) } }
            ErrorText(vm.errorAt("$path.access"))
            val roles = draft.auth.users.roles
            if (v.access == Rule.ADMIN && roles.isNotEmpty()) {
                Chips(tr("Ayrıca:"), roles.map { it.name to it.label }, v.roles.toSet()) { n -> set { it.copy(roles = it.roles.toggle(n)) } }
                ErrorText(vm.errorAt("$path.roles"))
            }
            Text(
                draft.server.basePath.trimEnd('/') + "/views/" + v.name,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Chips(label: String, options: List<Pair<String, String>>, selected: Set<String>, onClick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        options.forEach { (key, title) -> FilterChip(selected = key in selected, onClick = { onClick(key) }, label = { Text(title) }) }
    }
}

private fun List<String>.toggle(name: String): List<String> = if (name in this) this - name else this + name
