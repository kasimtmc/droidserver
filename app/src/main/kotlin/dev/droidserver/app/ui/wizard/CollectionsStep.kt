package dev.droidserver.app.ui.wizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.RadioRow
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.ChangeWarnings
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.CollectionTemplate
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.templates.AppTemplate
import dev.droidserver.core.config.ConfigSummary
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldDefaults
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.FileLimits
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.RuleCondition
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.TypeChangeAction
import dev.droidserver.core.config.TypeConversions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant

internal val FieldType.label: String
    get() = when (this) {
        FieldType.TEXT -> tr("Metin")
        FieldType.INTEGER -> tr("Tam sayı")
        FieldType.REAL -> tr("Ondalıklı sayı")
        FieldType.BOOLEAN -> tr("Evet/Hayır")
        FieldType.DATETIME -> tr("Tarih-saat")
        FieldType.JSON -> "JSON"
        FieldType.RELATION -> tr("İlişki (başka bir kayda bağlantı)")
        FieldType.FILE -> tr("Dosya (görsel, PDF, ses…)")
        FieldType.SELECT -> tr("Seçim (listeden bir değer)")
        FieldType.MULTISELECT -> tr("Çoklu seçim (etiketler)")
    }

@Composable
internal fun CollectionsStep(
    vm: WizardViewModel,
    draft: ServerConfig,
    siteExists: Boolean = false,
    onTemplate: (AppTemplate, samples: Boolean, site: Boolean) -> String? = { t, _, site -> vm.applyTemplate(t, site) },
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickingApp by rememberSaveable { mutableStateOf(false) }
    StepColumn {
        StepIntro(tr("Her koleksiyon bir tablo ve otomatik oluşturulan okuma/yazma uçları demektir."))
        vm.removedCollections().forEach {
            WarningText(tr("'{0}' koleksiyonu kaldırıldı: tablo ve verisi veritabanında kalır ama API'de görünmez.", it))
        }
        if (draft.collections.isEmpty()) {
            Text(tr("Henüz koleksiyon yok. Hazır bir şablonla başlayabilirsiniz."), style = MaterialTheme.typography.bodySmall)
        }
        draft.collections.forEachIndexed { i, c ->
            val errorCount = vm.errorsFor(WizardStep.COLLECTIONS).count { it.path.startsWith("collections[$i]") }
            val warningCount = vm.warningsFor(i).size
            OutlinedCard(onClick = { vm.openCollection(i) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                        Text(tr("{0} alan", c.fields.size), style = MaterialTheme.typography.bodySmall)
                        Text(ConfigSummary.rulesSummary(c.rules), style = MaterialTheme.typography.bodySmall)
                        if (errorCount > 0) ErrorText(tr("{0} sorun", errorCount))
                        if (warningCount > 0) WarningText(tr("{0} uyarı", warningCount))
                    }
                    IconButton(onClick = { vm.removeCollection(i) }) { Icon(Icons.Default.Delete, contentDescription = tr("Koleksiyonu kaldır")) }
                }
            }
        }
        Button(onClick = { picking = true }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("Koleksiyon ekle"))
        }
        OutlinedButton(onClick = { pickingApp = true }) {
            Icon(Icons.Default.Apps, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("Hazır uygulama (blog, mağaza, randevu, görev)"))
        }
        if (draft.collections.isNotEmpty()) {
            HorizontalDivider()
            ViewsSection(vm, draft)
        }
    }

    if (pickingApp) {
        AppTemplateDialog(siteExists = siteExists, onDismiss = { pickingApp = false }) { t, samples, site ->
            onTemplate(t, samples, site).also { if (it == null) pickingApp = false }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(tr("Şablon seçin")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CollectionTemplate.entries.forEach { t ->
                        Column(
                            Modifier.fillMaxWidth().clickable { picking = false; vm.addCollection(t) }.padding(vertical = 8.dp),
                        ) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

@Composable
internal fun CollectionEditor(vm: WizardViewModel, index: Int, draft: ServerConfig) {
    val active by vm.active.collectAsState()
    val c = draft.collections[index]
    val warnings = vm.warningsFor(index)
    val path = "collections[$index]"
    StepColumn {
        val nameError = vm.errorAt("$path.name")
        OutlinedTextField(
            value = c.name, onValueChange = { v -> vm.renameCollection(index, v) },
            label = { Text(tr("Koleksiyon adı (tablo adı)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = nameError != null,
            supportingText = { Text(nameError ?: tr("Küçük harf, rakam ve _ ; harfle başlamalı. Adres: /collections/{0}/records", c.name)) },
        )
        if (active?.collections?.any { it.name == c.name } == true) {
            Text(
                tr("Bu koleksiyon çalışan sunucuda var. Adını değiştirmek yeni, boş bir tablo açar; eski veriler eski tabloda kalır."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RulesEditor(vm, index, c, draft)

        Text(tr("Alanlar"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Sıralamak için tutamaktan sürükleyin. id, created_at ve updated_at otomatik eklenir."),
            style = MaterialTheme.typography.bodySmall,
        )
        ErrorText(vm.errorAt("$path.fields"))
        val fieldNames = c.fields.map { it.name }.toSet()
        warnings.filter { it.field !in fieldNames }.forEach { WarningText("'${it.field}': ${it.message}") }

        ReorderableColumn(items = c.fields, onMove = { from, to -> vm.moveField(index, from, to) }) { fi, field, handle ->
            FieldCard(vm, index, fi, field, handle, warnings.filter { it.field == field.name }, draft)
        }

        OutlinedButton(onClick = { vm.addField(index) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("Alan ekle"))
        }
        if (c.search.isNotEmpty()) {
            SwitchRow(
                tr("Tam metin dizini"),
                tr("Arama kelime başıyla ve dizinden yapılır (binlerce kayıtta da hızlı), sonuçlar alakaya göre sıralanır. " +
                    "Kapalıyken arama, metnin herhangi bir yerindeki parçayı da bulur ama her aramada tüm kayıtlara bakar."),
                c.fullText,
            ) { on -> vm.updateCollection(index) { it.copy(fullText = on) } }
            ErrorText(vm.errorAt("$path.fullText"))
        }
        ArchiveSection(vm, index, c)
        val expert by vm.expert.collectAsState()
        if (expert) {
            ExpertHeader(tr("Sistem alanları"), tr("Her kayda otomatik eklenen alanlar. Yeni ad verebilir veya API'den kaldırabilirsiniz."))
            SystemFieldsEditor(
                fields = listOf("id" to tr("Kayıt kimliği (tek kayıt, güncelleme ve silme adreslerinde)"), "created_at" to tr("Oluşturulma zamanı"), "updated_at" to tr("Son değişiklik zamanı")),
                values = c.systemFields, errorPrefix = "$path.systemFields", vm = vm,
                warning = { key, cfg -> tr("Kimlik yanıtlarda görünmez; istemciler kaydı güncellemek/silmek için kimliği bilemez.").takeIf { key == "id" && cfg.removed } },
            ) { key, value -> vm.setCollectionSystemField(index, key, value) }
            CollectionEndpointsEditor(vm, index, c, draft)
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { vm.removeCollection(index) }) {
                Text(tr("Koleksiyonu kaldır"), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.openCollection(null) }) { Text(tr("Tamam")) }
        }
    }
}

/** Kim neyi yapabilir: hazır kümeler, işlem başına kural ve kaydın sahibini tutan alan. */
@Composable
private fun RulesEditor(vm: WizardViewModel, index: Int, c: CollectionConfig, draft: ServerConfig) {
    val path = "collections[$index]"
    val usersOn = draft.auth.users.enabled
    val teamsOn = usersOn && draft.auth.users.teams.enabled
    Text(tr("Kim neyi yapabilir?"), style = MaterialTheme.typography.titleMedium)
    if (draft.auth.mode == AuthMode.NONE) {
        WarningText(tr("Kimlik doğrulama kapalı: herkes yönetici sayılır ve kurallar uygulanmaz (Güvenlik adımı)."))
    }
    RulePreset.entries.forEach { p ->
        RadioRow(
            selected = c.rules.copy(conditions = emptyMap(), roles = emptyMap()) == p.rules,
            title = p.title,
            description = p.description + if (p.needsUsers && !usersOn) tr(" (Kullanıcı hesaplarını açar.)") else "",
        ) { vm.applyRulePreset(index, p) }
    }
    Text(tr("Ayrıntılı"), style = MaterialTheme.typography.labelLarge)
    Rules.Op.entries.forEach { op ->
        RulePicker(op.title, c.rules[op], usersOn, teamsOn) { vm.setRule(index, op, it) }
        ErrorText(vm.errorAt("$path.rules.${op.key}"))
        if (c.rules[op] in setOf(Rule.PUBLIC, Rule.AUTH, Rule.OWNER)) ConditionEditor(vm, index, c, op)
        val roles = draft.auth.users.roles
        if (usersOn && roles.isNotEmpty() && c.rules[op] != Rule.NOBODY && c.rules[op] != Rule.PUBLIC) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Ayrıca:"), style = MaterialTheme.typography.bodySmall)
                roles.forEach { r ->
                    val on = r.name in c.rules.roles(op)
                    FilterChip(selected = on, onClick = { vm.setRoleAllowed(index, op, r.name, !on) }, label = { Text(r.label) })
                }
            }
            ErrorText(vm.errorAt("$path.rules.roles.${op.key}"))
        }
    }
    Text(
        tr("Yönetici (API anahtarı, yönetici token'ı veya rolü admin olan kullanıcı) \"Kapalı\" dışında her şeyi yapabilir. " +
            "\"Ayrıca\" ile seçilen roller o işlemi tüm kayıtlarda koşulsuz yapar. " +
            "Giriş yapmamış biri izin olmayan işlemde 401, giriş yapmış ama yetkisi olmayan 403 alır."),
        style = MaterialTheme.typography.bodySmall,
    )

    val candidates = c.fields.filter { it.type == FieldType.RELATION && it.collection == SystemCollections.USERS }.map { it.name }
    if (usersOn || c.ownerField != null) {
        Text(tr("Kaydın sahibi"), style = MaterialTheme.typography.labelLarge)
        Text(
            tr("Giriş yapan kullanıcının eklediği kayıtta bu alan otomatik olarak o kullanıcı olur; kullanıcı başkasını yazamaz, " +
                "sonradan değiştiremez. \"{0}\" kuralı bu alana bakar.", Rule.OWNER.title),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(tr("Sahip alanı: {0}", c.ownerField ?: tr("yok")))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(tr("Yok")) }, onClick = { open = false; vm.setOwnerField(index, null) })
                    candidates.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { open = false; vm.setOwnerField(index, name) })
                    }
                }
            }
            if (candidates.isEmpty() && usersOn) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.addOwnerField(index) }) { Text(tr("owner alanı ekle")) }
            }
        }
        ErrorText(vm.errorAt("$path.ownerField"))
    }

    val teamCandidates = c.fields.filter { it.type == FieldType.RELATION && it.collection == SystemCollections.TEAMS }.map { it.name }
    if (teamsOn || c.teamField != null) {
        Text(tr("Kaydın takımı"), style = MaterialTheme.typography.labelLarge)
        Text(
            tr("\"{0}\" kuralı bu alana bakar: kullanıcı yalnızca üyesi olduğu takımların kayıtlarını görür ve yalnızca kendi " +
                "takımına kayıt ekler (tek takımı varsa alan kendiliğinden dolar).", Rule.TEAM.title),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(tr("Takım alanı: {0}", c.teamField ?: tr("yok")))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(tr("Yok")) }, onClick = { open = false; vm.setTeamField(index, null) })
                    teamCandidates.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { open = false; vm.setTeamField(index, name) })
                    }
                }
            }
            if (teamCandidates.isEmpty() && teamsOn) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.addTeamField(index) }) { Text(tr("team alanı ekle")) }
            }
        }
        ErrorText(vm.errorAt("$path.teamField"))
    }
}

/**
 * Kurala koşul: yalnızca filtreye uyan kayıtlar (örn. `status=published`). Sahip istisnası: kaydın sahibi koşula
 * bakılmaksızın erişir (taslaklar). Yönetici koşula bağlı değildir.
 */
@Composable
private fun ConditionEditor(vm: WizardViewModel, index: Int, c: CollectionConfig, op: Rules.Op) {
    val condition = c.rules.condition(op)
    val key = "collections[$index].rules.conditions.${op.key}"
    var editing by rememberSaveable(index, op) { mutableStateOf(condition != null) }
    if (!editing && condition == null) {
        TextButton(onClick = { editing = true }) { Text(tr("+ {0} için koşul", op.title)) }
        return
    }
    var text by rememberSaveable(index, op) { mutableStateOf(condition?.filter.orEmpty()) }
    Column(Modifier.padding(start = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    vm.setRuleCondition(index, op, v.trim().takeIf { it.isNotEmpty() }?.let { RuleCondition(it, condition?.owner == true) })
                },
                label = { Text(tr("{0} koşulu", op.title)) },
                placeholder = { Text(if (op == Rules.Op.CREATE) "status=draft" else "status=published") },
                singleLine = true,
                isError = vm.errorAt(key) != null,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            IconButton(onClick = { text = ""; editing = false; vm.setRuleCondition(index, op, null) }) {
                Icon(Icons.Default.Delete, contentDescription = tr("Koşulu kaldır"))
            }
        }
        ErrorText(vm.errorAt(key))
        if (op != Rules.Op.CREATE && c.ownerField != null && condition != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = condition.owner, onCheckedChange = { v -> vm.setRuleCondition(index, op, condition.copy(owner = v)) })
                Text(tr("Kaydın sahibi koşula bakılmaksızın erişir"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            if (op == Rules.Op.CREATE) tr("Yeni kayıt bu koşula uymalı (uymazsa 403).")
            else tr("Yalnızca koşula uyan kayıtlar; diğerleri yokmuş gibi (listede görünmez, tek kayıtta 404). " +
                "Liste filtresi sözdizimi, & ile VE: status[in]=published,archived&archived=false. " +
                "Kullanıcıya bağlı alanda @me istekteki kullanıcıdır: assignee=@me."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RulePicker(title: String, rule: Rule, usersOn: Boolean, teamsOn: Boolean, onPick: (Rule) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(rule.title)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Rule.entries.forEach { r ->
                    val needsUsers = r == Rule.AUTH || r == Rule.OWNER || r == Rule.TEAM
                    val off = when {
                        needsUsers && !usersOn -> tr(" (kullanıcı hesapları kapalı)")
                        r == Rule.TEAM && !teamsOn -> tr(" (takımlar kapalı)")
                        else -> ""
                    }
                    DropdownMenuItem(
                        text = { Text(r.title + off) },
                        enabled = off.isEmpty(),
                        onClick = { open = false; onPick(r) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// dragHandle kök modifier değil, yalnızca tutamak ikonuna uygulanır.
@Suppress("ModifierParameter")
@Composable
private fun FieldCard(
    vm: WizardViewModel,
    ci: Int,
    fi: Int,
    field: FieldConfig,
    dragHandle: Modifier,
    warnings: List<ChangeWarnings.FieldWarning>,
    draft: ServerConfig,
) {
    val path = "collections[$ci].fields[$fi]"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragHandle, contentDescription = tr("Sürükleyerek taşı"), modifier = dragHandle.padding(end = 8.dp))
                val nameError = vm.errorAt("$path.name")
                OutlinedTextField(
                    value = field.name, onValueChange = { v -> vm.updateField(ci, fi) { it.copy(name = v) } },
                    label = { Text(tr("Alan adı")) }, singleLine = true, modifier = Modifier.weight(1f),
                    isError = nameError != null,
                )
                IconButton(onClick = { vm.removeField(ci, fi) }) { Icon(Icons.Default.Delete, contentDescription = tr("Alanı sil")) }
            }
            ErrorText(vm.errorAt("$path.name"))
            TypePicker(field.type) { vm.setFieldType(ci, fi, it) }
            val oldType = vm.activeFieldType(ci, fi)
            val typeChanged = oldType != null && oldType != field.type
            if (field.type == FieldType.RELATION) RelationEditor(vm, ci, fi, field, draft)
            if (field.type == FieldType.FILE) FileEditor(vm, ci, fi, field, draft)
            if (oldType != null && typeChanged) TypeChangeChoice(vm, ci, fi, oldType, field)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = field.required, onCheckedChange = { v -> vm.updateField(ci, fi) { it.copy(required = v) } })
                Text(tr("Zorunlu"))
                if (field.type != FieldType.FILE && !field.encrypted) {
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = field.unique, onCheckedChange = { v -> vm.updateField(ci, fi) { it.copy(unique = v) } })
                    Text(tr("Benzersiz"))
                }
            }
            val c = draft.collections[ci]
            val indexable = !field.unique && !field.encrypted && field.type !in setOf(FieldType.FILE, FieldType.JSON, FieldType.RELATION)
            val searchable = field.type == FieldType.TEXT && !field.encrypted
            if (indexable || searchable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (indexable) {
                        Checkbox(checked = field.index, onCheckedChange = { v -> vm.updateField(ci, fi) { it.copy(index = v) } })
                        Text(tr("İndeks"))
                        Spacer(Modifier.width(12.dp))
                    }
                    if (searchable) {
                        Checkbox(checked = field.name in c.search, onCheckedChange = { v -> vm.setSearchable(ci, field.name, v) })
                        Text(tr("Aranabilir (?q=)"))
                    }
                }
                if (field.index || field.name in c.search) {
                    Text(
                        listOfNotNull(
                            tr("İndeks: bu alana göre filtreleme ve sıralama çok kayıtta hızlanır.").takeIf { field.index },
                            tr("Aranabilir: ?q=kelime büyük/küçük harf ve Türkçe karakterden bağımsız arar (İ/ı, ş, ğ, ü, ö, ç).").takeIf { field.name in c.search },
                        ).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FieldRulesEditor("$ci/$fi", field, path, vm::errorAt, c.fields.filter { it.name != field.name && it.type != FieldType.FILE && it.type != FieldType.JSON }.map { it.name }) { change ->
                vm.updateField(ci, fi, change)
            }
            if (field.computed == null) DefaultValueEditor("$ci/$fi", field) { vm.setFieldDefault(ci, fi, it) }
            ErrorText(vm.errorAt("$path.default"))
            if (field.name != c.ownerField) VisibilityPicker(field, ownerAvailable = c.ownerField != null) { v -> vm.updateField(ci, fi) { it.copy(visibility = v) } }
            ErrorText(vm.errorAt("$path.visibility"))
            if ((field.type == FieldType.TEXT || field.type == FieldType.JSON) && field.computed == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = field.encrypted, onCheckedChange = { v -> vm.setFieldEncrypted(ci, fi, v) })
                    Text(tr("Şifreli sakla"))
                }
                if (field.encrypted) {
                    Text(
                        tr("Değer veritabanında, yedeklerde ve canlı kopyada şifreli durur; API'de yetkili olana açık döner. Bu alana göre " +
                            "filtrelenemez, sıralanamaz, aranamaz. Anahtar: Veritabanı adımı → Alan şifreleme."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ErrorText(vm.errorAt("$path.encrypted"))
            }
            // Tip değişikliği için seçim kartı gösteriliyor; aynı uyarı tekrarlanmaz.
            warnings.filterNot { typeChanged && it.typeChange }.forEach { WarningText(it.message) }
        }
    }
}

/** Alanı kim görür: kaydı görebilen herkes, sahibi ve yönetici, yalnızca yönetici. */
@Composable
private fun VisibilityPicker(field: FieldConfig, ownerAvailable: Boolean, onPick: (FieldVisibility?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Kim görür"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(field.visibility?.title ?: tr("Kaydı görebilen herkes"))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(tr("Kaydı görebilen herkes")) }, onClick = { open = false; onPick(null) })
                FieldVisibility.entries.forEach { v ->
                    val needsOwner = v == FieldVisibility.OWNER && !ownerAvailable
                    DropdownMenuItem(
                        text = { Text(v.title + if (needsOwner) tr(" (önce sahip alanını seçin)") else "") },
                        enabled = !needsOwner,
                        onClick = { open = false; onPick(v) },
                    )
                }
            }
        }
    }
    if (field.visibility != null) {
        Text(
            tr("Gizli alan diğerlerine yanıtta dönmez; yönetici dışındakiler bu alana göre filtreleyemez, sıralayamaz.") +
                if (field.visibility == FieldVisibility.ADMIN) tr(" Yalnızca yönetici yazabilir.") else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** İlişki alanı: hedef koleksiyon ve hedef kayıt silinince bu kayıtlara ne olacağı. */
@Composable
private fun RelationEditor(vm: WizardViewModel, ci: Int, fi: Int, field: FieldConfig, draft: ServerConfig) {
    val path = "collections[$ci].fields[$fi]"
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(tr("Bağlandığı koleksiyon: {0}", field.collection ?: tr("seçin")))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            draft.collections.forEach { c ->
                val self = c.name == draft.collections.getOrNull(ci)?.name
                DropdownMenuItem(
                    text = { Text(if (self) tr("{0} (kendisi: ağaç yapısı)", c.name) else c.name) },
                    onClick = { open = false; vm.setRelationTarget(ci, fi, c.name) },
                )
            }
            if (draft.auth.users.enabled || field.collection == SystemCollections.USERS) {
                DropdownMenuItem(
                    text = { Text(tr("{0} (kullanıcılar)", SystemCollections.USERS)) },
                    onClick = { open = false; vm.setRelationTarget(ci, fi, SystemCollections.USERS) },
                )
            }
            if ((draft.auth.users.enabled && draft.auth.users.teams.enabled) || field.collection == SystemCollections.TEAMS) {
                DropdownMenuItem(
                    text = { Text(tr("{0} (takımlar)", SystemCollections.TEAMS)) },
                    onClick = { open = false; vm.setRelationTarget(ci, fi, SystemCollections.TEAMS) },
                )
            }
        }
    }
    ErrorText(vm.errorAt("$path.collection"))
    Text(
        tr("Kayıtta hedef kaydın kimliği (id) tutulur; ?expand={0} ile hedef kayıt yanıta eklenir.", field.name),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(tr("Bağlandığı kayıt silinince:"), style = MaterialTheme.typography.labelLarge)
    val effective = field.effectiveOnDelete
    OnDelete.entries.forEach { o ->
        val disabled = o == OnDelete.SET_NULL && field.required
        RadioRow(
            selected = effective == o,
            title = o.title + if (field.onDelete == null && effective == o) tr(" (varsayılan)") else "",
            description = when (o) {
                OnDelete.CASCADE -> tr("Bu koleksiyondaki bağlı kayıtlar da silinir.")
                OnDelete.SET_NULL -> if (disabled) tr("Zorunlu alanda kullanılamaz.") else tr("Kayıtlar kalır, bu alan boşalır.")
                OnDelete.RESTRICT -> tr("Bağlı kayıt varken silme reddedilir (önce bunları silin veya değiştirin).")
            },
            enabled = !disabled,
        ) { vm.setOnDelete(ci, fi, o) }
    }
    ErrorText(vm.errorAt("$path.onDelete"))
}

/** Dosya alanı: boyut sınırı, kabul edilen içerik tipleri, çoklu dosya ve küçük resim boyutları. */
@Composable
private fun FileEditor(vm: WizardViewModel, ci: Int, fi: Int, field: FieldConfig, draft: ServerConfig) {
    val path = "collections[$ci].fields[$fi]"
    var sizeText by rememberSaveable(ci, fi) { mutableStateOf((field.maxSizeMb ?: FileLimits.DEFAULT_MAX_MB).toString()) }
    OutlinedTextField(
        value = sizeText,
        onValueChange = { v ->
            sizeText = v.filter(Char::isDigit).take(4)
            sizeText.toIntOrNull()?.let { mb -> vm.updateField(ci, fi) { it.copy(maxSizeMb = mb.takeIf { it != FileLimits.DEFAULT_MAX_MB }) } }
        },
        label = { Text(tr("En büyük dosya (MB)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = vm.errorAt("$path.maxSizeMb") != null,
        supportingText = { Text(vm.errorAt("$path.maxSizeMb") ?: tr("Aşan yükleme, dosyanın tamamı gelmeden reddedilir (413).")) },
    )
    Text(tr("Kabul edilen dosyalar"), style = MaterialTheme.typography.labelLarge)
    val preset = FILE_PRESETS.firstOrNull { it.second == field.mimeTypes }
    var custom by rememberSaveable(ci, fi) { mutableStateOf(preset == null) }
    FILE_PRESETS.forEach { (title, types) ->
        RadioRow(selected = !custom && field.mimeTypes == types, title = title) {
            custom = false
            vm.updateField(ci, fi) { it.copy(mimeTypes = types) }
        }
    }
    RadioRow(selected = custom, title = tr("Özel"), description = tr("Virgülle: image/png, application/pdf, audio/* …")) { custom = true }
    if (custom) {
        var typesText by rememberSaveable(ci, fi) { mutableStateOf(field.mimeTypes.orEmpty().joinToString(", ")) }
        OutlinedTextField(
            value = typesText,
            onValueChange = { v ->
                typesText = v
                val list = v.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                vm.updateField(ci, fi) { it.copy(mimeTypes = list.ifEmpty { null }) }
            },
            label = { Text(tr("İçerik tipleri")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
    field.mimeTypes?.indices?.forEach { i -> ErrorText(vm.errorAt("$path.mimeTypes[$i]")) }
    Text(
        tr("Tip dosyanın içeriğinden tespit edilir; uzantısı içeriğiyle uyuşmayan dosya (örn. .png adlı bir sayfa) reddedilir."),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = field.isMultiple, onCheckedChange = { v -> vm.updateField(ci, fi) { it.copy(multiple = v.takeIf { it }) } })
        Text(tr("Birden çok dosya (en çok {0})", FileLimits.MAX_FILES))
    }
    var thumbsText by rememberSaveable(ci, fi) { mutableStateOf(field.thumbs.orEmpty().joinToString(", ")) }
    OutlinedTextField(
        value = thumbsText,
        onValueChange = { v ->
            thumbsText = v
            val list = v.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            vm.updateField(ci, fi) { it.copy(thumbs = list.ifEmpty { null }) }
        },
        label = { Text(tr("Ek küçük resim boyutları")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(tr("Örn. 300x300, 800x0 (0: oranı koru). {0} her zaman var; ?thumb={1}", FileLimits.DEFAULT_THUMB, FileLimits.DEFAULT_THUMB)) },
    )
    field.thumbs?.indices?.forEach { i -> ErrorText(vm.errorAt("$path.thumbs[$i]")) }
    val c = draft.collections.getOrNull(ci)
    Text(
        tr("Yükleme: POST/PATCH multipart/form-data, bu alan adıyla dosya parçası. İndirme: {0}/files/" +
            "{1}/<id>/<dosya-adı> — kaydın görüntüleme kuralı geçerlidir. Kayıt silinince dosyaları da silinir.", draft.server.basePath, c?.name),
        style = MaterialTheme.typography.bodySmall,
    )
}

private val FILE_PRESETS: List<Pair<String, List<String>?>> = listOf(
    tr("Her tür") to null,
    tr("Görseller") to listOf("image/*"),
    tr("Görseller ve PDF") to listOf("image/*", "application/pdf"),
    "PDF" to listOf("application/pdf"),
    tr("Ses") to listOf("audio/*"),
    "Video" to listOf("video/*"),
)

/** Kayıtlı verisi olan alanın tipi değişti: mevcut değerler dönüştürülsün mü, boşaltılsın mı? */
@Composable
private fun TypeChangeChoice(vm: WizardViewModel, ci: Int, fi: Int, oldType: FieldType, field: FieldConfig) {
    val chosen = field.typeChange?.takeIf { it.from == oldType }?.action
    val canConvert = TypeConversions.canConvert(oldType, field.type)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                tr("Tip değişti: {0} → {1}. Kayıtlı değerlere ne olsun?", oldType.label, field.type.label),
                style = MaterialTheme.typography.labelLarge,
            )
            RadioRow(chosen == TypeChangeAction.CONVERT, tr("Dönüştür"), TypeConversions.describe(oldType, field.type), enabled = canConvert) {
                vm.setTypeChange(ci, fi, TypeChangeAction.CONVERT)
            }
            RadioRow(chosen == TypeChangeAction.CLEAR, tr("Boşalt"), tr("Bu alandaki kayıtlı değerler silinir; kayıtların diğer alanları korunur.")) {
                vm.setTypeChange(ci, fi, TypeChangeAction.CLEAR)
            }
            Text(
                tr("Kaç değerin çevrileceği Özet'teki şema planında görünür. Değerleri olduğu gibi korumak için tipi geri alıp " +
                    "yeni adla bir alan da ekleyebilirsiniz."),
                style = MaterialTheme.typography.bodySmall,
            )
            ErrorText(vm.errorAt("collections[$ci].fields[$fi].typeChange"))
        }
    }
}

@Composable
internal fun TypePicker(type: FieldType, types: List<FieldType> = FieldType.entries, onPick: (FieldType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(tr("Tip: {0}", type.label))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            types.forEach { t ->
                DropdownMenuItem(text = { Text(t.label) }, onClick = { open = false; onPick(t) })
            }
        }
    }
}

/** Tipe uygun varsayılan değer girişi: boolean için anahtar, tarih için tarih seçici, JSON için çok satırlı alan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultValueEditor(key: String, field: FieldConfig, setDefault: (JsonElement?) -> Unit) {
    when (field.type) {
        FieldType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = field.default != null,
                onCheckedChange = { on -> setDefault(if (on) JsonPrimitive(false) else null) },
            )
            Text(tr("Varsayılan değer"))
            if (field.default != null) {
                Spacer(Modifier.width(12.dp))
                val value = (field.default as? JsonPrimitive)?.booleanOrNull ?: false
                Switch(checked = value, onCheckedChange = { setDefault(JsonPrimitive(it)) })
                Spacer(Modifier.width(6.dp))
                Text(if (value) tr("Evet") else tr("Hayır"))
            }
        }

        FieldType.DATETIME -> {
            var picking by remember { mutableStateOf(false) }
            val current = FieldDefaults.display(field.default)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Varsayılan: {0}", current.ifEmpty { tr("yok") }), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { picking = true }) { Text(tr("Tarih seç")) }
                if (field.default != null) TextButton(onClick = { setDefault(null) }) { Text(tr("Temizle")) }
            }
            if (picking) {
                val initial = runCatching { Instant.parse(current).toEpochMilli() }.getOrNull()
                val state = rememberDatePickerState(initialSelectedDateMillis = initial)
                DatePickerDialog(
                    onDismissRequest = { picking = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { setDefault(JsonPrimitive(Instant.ofEpochMilli(it).toString())) }
                            picking = false
                        }) { Text(tr("Seç")) }
                    },
                    dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Vazgeç")) } },
                ) { DatePicker(state = state) }
            }
        }

        FieldType.RELATION, FieldType.FILE -> {} // ilişki ve dosya alanının varsayılanı olmaz

        else -> {
            // Yazılan metin yerel tutulur (yazarken biçim bozulmasın); taslağa yalnızca geçerli değer yazılır.
            // Taslak başka yerden değişirse (JSON, sıralama) metin yeniden doldurulur.
            var text by rememberSaveable(key, field.type) { mutableStateOf(FieldDefaults.display(field.default)) }
            var produced by remember(key, field.type) { mutableStateOf(field.default) }
            var localError by remember(key, field.type) { mutableStateOf<String?>(null) }
            LaunchedEffect(field.default) {
                if (field.default != produced) {
                    text = FieldDefaults.display(field.default)
                    produced = field.default
                    localError = null
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    try {
                        val parsed = FieldDefaults.parse(field.type, v)
                        produced = parsed
                        localError = null
                        setDefault(parsed)
                    } catch (e: IllegalArgumentException) {
                        localError = e.message
                    }
                },
                label = { Text(tr("Varsayılan değer (boş = yok)")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = field.type != FieldType.JSON,
                minLines = if (field.type == FieldType.JSON) 3 else 1,
                textStyle = if (field.type == FieldType.JSON) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        FieldType.INTEGER -> KeyboardType.Number
                        FieldType.REAL -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    },
                ),
                isError = localError != null,
                supportingText = localError?.let { { Text(it) } },
            )
        }
    }
}

/**
 * Tutamaktan sürükleyerek sıralanabilen sütun (alan listeleri kısa; lazy gerekmez). Sürüklenen öğe
 * komşusunun yarısını geçince yer değiştirir. Erişilebilirlik için "Yukarı/Aşağı taşı" eylemleri de var.
 */
@Composable
private fun <T> ReorderableColumn(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    itemContent: @Composable (index: Int, item: T, dragHandle: Modifier) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val spacing = with(LocalDensity.current) { 8.dp.toPx() }
    val currentSize by rememberUpdatedState(items.size)
    val currentOnMove by rememberUpdatedState(onMove)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            val handle = Modifier
                .size(32.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = index; offset = 0f },
                        onDragEnd = { dragging = null; offset = 0f },
                        onDragCancel = { dragging = null; offset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            val i = dragging ?: return@detectDragGestures
                            offset += amount.y
                            val down = i + 1
                            val up = i - 1
                            if (offset > 0 && down < currentSize && offset > ((heights[down] ?: 0) + spacing) / 2) {
                                currentOnMove(i, down)
                                dragging = down
                                offset -= (heights[down] ?: 0) + spacing
                            } else if (offset < 0 && up >= 0 && -offset > ((heights[up] ?: 0) + spacing) / 2) {
                                currentOnMove(i, up)
                                dragging = up
                                offset += (heights[up] ?: 0) + spacing
                            }
                        },
                    )
                }
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(tr("Yukarı taşı")) { onMove(index, index - 1); true }.takeIf { index > 0 },
                        CustomAccessibilityAction(tr("Aşağı taşı")) { onMove(index, index + 1); true }.takeIf { index < items.lastIndex },
                    )
                }
            val isDragging = dragging == index
            Box(
                Modifier
                    .onSizeChanged { heights[index] = it.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) offset else 0f },
            ) {
                itemContent(index, item, handle)
            }
        }
    }
}
