package dev.droidserver.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ErrorText
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.WarningText
import dev.droidserver.app.ui.common.copySecret
import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.Page
import dev.droidserver.core.auth.UserService
import dev.droidserver.core.config.ConfigDefaults
import dev.droidserver.core.config.FieldDefaults
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.UsersConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Çalışan sunucunun kullanıcı hesapları: arama, hesap açma, parola sıfırlama (e-posta gönderimi yok; yeni parolayı
 * kullanıcıya siz iletirsiniz), rol ve doğrulandı bilgisi, silme. Sunucunun veritabanı bağlantısını kullanır.
 */
@Composable
fun UsersScreen(g: AppGraph, onBack: () -> Unit) {
    val engineState by g.engine.state.collectAsState()
    val running = engineState is EngineState.Running
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf<Page?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<JsonObject?>(null) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    var roleFor by remember { mutableStateOf<JsonObject?>(null) }
    val users = remember(engineState) { runCatching { g.configRepo.loadActiveOrNull()?.auth?.users }.getOrNull() }

    LaunchedEffect(query, reload, running) {
        if (!running) {
            page = null
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { runCatching { g.engine.users()?.list(1, 200, query) } }
        page = result.getOrNull()
        result.exceptionOrNull()?.let { error = describe(it) }
    }

    /** Sunucunun kullanıcı servisiyle bir işlem; hata ekranda gösterilir, liste yenilenir. */
    fun act(done: String? = null, block: (UserService) -> Unit) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { block(g.engine.users() ?: throw IllegalStateException(tr("Sunucu çalışmıyor veya kullanıcı hesapları kapalı"))) }
                    .exceptionOrNull()
            }
            error = failure?.let(::describe)
            notice = done.takeIf { failure == null }
            reload++
        }
    }

    ScreenScaffold(
        tr("Kullanıcılar"),
        onBack = onBack,
        actions = {
            IconButton(onClick = { adding = true }, enabled = running) { Icon(Icons.Default.PersonAdd, contentDescription = tr("Kullanıcı ekle")) }
        },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (!running) WarningText(tr("Sunucu çalışmıyor; kullanıcıları görmek için Durum ekranından başlatın."))
                if (users?.enabled != true) WarningText(tr("Kullanıcı hesapları kapalı (Sihirbaz → Kullanıcılar)."))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(tr("E-postada ara")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                page?.let { Text(tr("{0} kullanıcı", it.totalItems) + if (it.totalItems > it.items.size) tr(" (ilk {0})", it.items.size) else "", style = MaterialTheme.typography.bodySmall) }
                ErrorText(error)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            items(page?.items.orEmpty(), key = { it.getValue("id").jsonPrimitive.content }) { u ->
                val id = u.getValue("id").jsonPrimitive.content.toLong()
                val email = u.text("email")
                val role = u.text("role")
                val admin = role == SystemCollections.ROLE_ADMIN
                val verified = u.text("verified") == "true"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(email, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            listOfNotNull(
                                "#$id",
                                roleLabel(role, users),
                                tr("doğrulandı").takeIf { verified },
                                tr("iki adımlı").takeIf { u.text("twoFactor") == "true" },
                                tr("kayıt: {0}", u.text("created_at").take(10)),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        users?.extraFields?.mapNotNull { f -> u[f.name]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { "${f.name}: ${FieldDefaults.display(it)}" } }
                            ?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                        val usage by produceState<JsonObject?>(null, id, reload) {
                            value = if (admin) null else withContext(Dispatchers.IO) { runCatching { g.engine.usage(id) }.getOrNull() }
                        }
                        usage?.let { Text(usageLine(it), style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { resetting = u }) { Text(tr("Parola")) }
                            TextButton(onClick = { roleFor = u }) { Text(tr("Rol")) }
                            TextButton(onClick = {
                                act { it.adminUpdate(id, JsonObject(mapOf("verified" to JsonPrimitive(!verified)))) }
                            }) { Text(if (verified) tr("Doğrulanmadı") else tr("Doğrulandı")) }
                            if (u.text("twoFactor") == "true") {
                                TextButton(onClick = {
                                    act(tr("{0}: iki adımlı doğrulama sıfırlandı", email)) { it.adminUpdate(id, JsonObject(mapOf("twoFactor" to JsonPrimitive(false)))) }
                                }) { Text(tr("2FA sıfırla")) }
                            }
                            TextButton(onClick = { deleting = u }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    resetting?.let { u ->
        var password by remember(u) { mutableStateOf(generatePassword()) }
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text(tr("Parolayı sıfırla")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("{0} için yeni parola. Kullanıcının tüm oturumları kapanır; yeni parolayı kendisine siz iletin.", u.text("email")))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        TextButton(onClick = { password = generatePassword() }) { Text(tr("Yeniden üret")) }
                        TextButton(onClick = { copySecret(ctx, "parola", password) }) { Text(tr("Kopyala")) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = u.getValue("id").jsonPrimitive.content.toLong()
                    val p = password
                    resetting = null
                    act(tr("{0}: parola değişti", u.text("email"))) { it.adminUpdate(id, JsonObject(mapOf("password" to JsonPrimitive(p)))) }
                }) { Text(tr("Sıfırla")) }
            },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text(tr("Vazgeç")) } },
        )
    }

    roleFor?.let { u ->
        val current = u.text("role")
        AlertDialog(
            onDismissRequest = { roleFor = null },
            title = { Text(tr("{0} rolü", u.text("email"))) },
            text = {
                Column {
                    roleChoices(users).forEach { (name, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val id = u.getValue("id").jsonPrimitive.content.toLong()
                            roleFor = null
                            if (name != current) {
                                act(tr("{0}: {1} yapıldı", u.text("email"), label)) { it.adminUpdate(id, JsonObject(mapOf("role" to JsonPrimitive(name)))) }
                            }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = name == current, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(
                        tr("Yönetici tüm kuralları aşar. Diğer rollerin neyi yapabileceği Sihirbaz → Koleksiyonlar → kurallarda seçilir."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { roleFor = null }) { Text(tr("Kapat")) } },
        )
    }

    deleting?.let { u ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Kullanıcı silinsin mi?")) },
            text = {
                Text(
                    tr("{0} ve oturumları silinir. Ona bağlı kayıtlara, ilişki alanlarındaki \"silinince\" seçimine " +
                        "göre davranılır (örn. kayıtları da silinir veya sahip alanı boşalır). Geri alınamaz.", u.text("email")),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = u.getValue("id").jsonPrimitive.content.toLong()
                    deleting = null
                    act(tr("{0} silindi", u.text("email"))) { it.delete(id) }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Vazgeç")) } },
        )
    }

    if (adding) {
        AddUserDialog(users ?: UsersConfig(), ctx = ctx, onDismiss = { adding = false }) { body, email ->
            adding = false
            act(tr("{0} eklendi", email)) { it.create(body) }
        }
    }
}

@Composable
private fun AddUserDialog(users: UsersConfig, ctx: android.content.Context, onDismiss: () -> Unit, onCreate: (JsonObject, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(generatePassword()) }
    var role by remember { mutableStateOf(SystemCollections.ROLE_USER) }
    val extras = remember { mutableStateMapOf<String, String>() }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Kullanıcı ekle")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(email, { email = it }, label = { Text(tr("E-posta")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text(tr("Parola")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Row {
                    TextButton(onClick = { password = generatePassword() }) { Text(tr("Yeniden üret")) }
                    TextButton(onClick = { copySecret(ctx, "parola", password) }) { Text(tr("Kopyala")) }
                }
                users.extraFields.forEach { f ->
                    OutlinedTextField(
                        extras[f.name].orEmpty(), { extras[f.name] = it },
                        label = { Text(f.name + if (f.required) " *" else "") },
                        singleLine = f.type != FieldType.JSON, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(tr("Rol"), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roleChoices(users).forEach { (name, label) ->
                        FilterChip(selected = role == name, onClick = { role = name }, label = { Text(label) })
                    }
                }
                if (role == SystemCollections.ROLE_ADMIN) Text(tr("Yönetici tüm kuralları aşar."), style = MaterialTheme.typography.bodySmall)
                ErrorText(problem)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val fields = linkedMapOf<String, JsonElement>(
                        "email" to JsonPrimitive(email.trim()),
                        "password" to JsonPrimitive(password),
                        "role" to JsonPrimitive(role),
                    )
                    users.extraFields.forEach { f ->
                        FieldDefaults.parse(f.type, extras[f.name].orEmpty())?.let { fields[f.name] = it }
                    }
                    onCreate(JsonObject(fields), email.trim())
                } catch (e: IllegalArgumentException) {
                    problem = e.message
                }
            }) { Text(tr("Ekle")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } },
    )
}

/** Paylaşması kolay, 16 karakterlik (harf ve rakam) parola. */
/** Kota kullanımı: "notes 2/5 · dosya 0,7/1 MB · bugün 12 istek"; sınırsızda yalnızca kullanım. */
private fun usageLine(u: JsonObject): String {
    fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
    fun of(used: Long?, limit: Long?) = "${used ?: 0}" + (limit?.let { "/$it" } ?: "")
    val parts = mutableListOf<String>()
    (u["records"] as? JsonObject)?.forEach { (name, v) -> (v as? JsonObject)?.let { parts += "$name ${of(it.num("used"), it.num("limit"))}" } }
    (u["storage"] as? JsonObject)?.let { s ->
        val mb = { b: Long -> "%.1f".format(b / 1048576.0) }
        val used = s.num("usedBytes") ?: 0
        if (used > 0 || s.num("limitBytes") != null) parts += tr("dosya {0}", mb(used)) + (s.num("limitBytes")?.let { "/${mb(it)}" } ?: "") + " MB"
    }
    (u["requests"] as? JsonObject)?.let { r -> parts += tr("bugün {0} istek", of(r.num("today"), r.num("limit"))) }
    return tr("Kullanım: ") + parts.joinToString(" · ")
}

private fun generatePassword() = ConfigDefaults.generateDbPassword().take(16)

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

private fun describe(t: Throwable): String = when (t) {
    is ApiException -> (t.message ?: tr("Hata")) + t.details.entries.joinToString("") { "\n• ${it.key}: ${it.value}" }
    else -> t.message ?: t.toString()
}

/** Kullanıcı, yönetici ve sihirbazda tanımlanan özel roller: (ad, görünen ad). */
private fun roleChoices(users: UsersConfig?): List<Pair<String, String>> =
    listOf(SystemCollections.ROLE_USER to tr("Kullanıcı"), SystemCollections.ROLE_ADMIN to tr("Yönetici")) +
        users?.roles.orEmpty().map { it.name to it.label }

private fun roleLabel(role: String, users: UsersConfig?): String = when (role) {
    SystemCollections.ROLE_ADMIN -> tr("yönetici")
    SystemCollections.ROLE_USER, "" -> tr("kullanıcı")
    else -> users?.roles?.firstOrNull { it.name == role }?.label ?: role
}
