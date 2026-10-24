package com.plyg.droidserver.app.ui.config

import androidx.compose.material.icons.filled.Javascript
import com.plyg.droidserver.app.ui.common.EmptyState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plyg.droidserver.app.AppGraph
import com.plyg.droidserver.app.service.ServerService
import com.plyg.droidserver.app.ui.common.ErrorText
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.app.ui.common.WarningText
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.script.ScriptRuntime
import com.plyg.droidserver.core.script.ScriptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `files/scripts` altındaki .js dosyaları: liste, yeni dosya (örnekle), düzenleme, sözdizimi denetimi, silme ve
 * "Kaydet ve deploy et". Script'ler deploy'da yüklenir; sözdizimi hatası deploy'u reddeder, çalışan sunucu sürer.
 */
@Composable
fun ScriptsScreen(g: AppGraph, onBack: () -> Unit) {
    val dir = g.engine.scriptsDir ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val enabled = remember { runCatching { g.configRepo.loadActiveOrNull()?.scripts?.enabled }.getOrNull() == true }

    LaunchedEffect(refresh) {
        files = withContext(Dispatchers.IO) { dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }.orEmpty().sortedBy { it.name } }
    }

    val open = editing?.let { File(dir, it) }
    if (open != null) {
        ScriptEditor(open, onBack = { editing = null; refresh++ }, onDeploy = { ServerService.deploy(ctx) })
        return
    }

    ScreenScaffold(
        tr("Script'ler"),
        onBack = onBack,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = tr("Yeni script")) } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (!enabled) WarningText(tr("Script'ler kapalı: Sihirbaz → Güvenlik → Script'ler'den açıp deploy edin. Dosyaları şimdiden yazabilirsiniz."))
                Text(
                    tr("Her .js dosyası deploy'da ad sırasıyla yüklenir. Kullanılabilenler: routerAdd(yöntem, yol, (req, ctx) => …, erişim), " +
                        "onBeforeCreate/onAfterCreate/onBeforeUpdate/onAfterUpdate/onBeforeDelete/onAfterDelete(koleksiyon, (kayıt, ctx) => …), " +
                        "cronAdd(\"0 3 * * *\", (ctx) => …), jobAdd(ad, (veri, ctx, iş) => …, {retries, timeoutSec, concurrency}), " +
                        "fail(durum, mesaj), reply(durum, gövde); ctx.db, ctx.http, ctx.jobs.enqueue(ad, veri, {delaySec, key}), ctx.log, ctx.env, ctx.auth."),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (files.isEmpty()) {
                    EmptyState(
                        Icons.Default.Javascript, tr("Henüz script yok"),
                        tr("JavaScript ile kendi uçlarınızı, kayıt olaylarına bağlı mantığı ve zamanlanmış görevleri yazın. Örnekle başlamak en kolayı."),
                        action = tr("Örnek script oluştur"),
                        onAction = {
                            scope.launch {
                                withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, "main.js").writeText(SAMPLE) }
                                editing = "main.js"
                            }
                        },
                    )
                }
            }
            items(files, key = { it.name }) { f ->
                OutlinedCard(onClick = { editing = f.name }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                        Text(tr("{0} bayt", f.length()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        val valid = Regex("^[a-z0-9_-]{1,40}$").matches(name.removeSuffix(".js"))
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(tr("Yeni script")) },
            text = {
                OutlinedTextField(
                    name, { name = it.lowercase().trim() }, label = { Text(tr("Dosya adı")) }, singleLine = true,
                    supportingText = { Text(tr("Küçük harf, rakam, - ve _ ; .js eklenir")) },
                )
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val file = name.removeSuffix(".js") + ".js"
                    creating = false
                    scope.launch {
                        withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, file).takeIf { !it.exists() }?.writeText("// $file\n") }
                        editing = file
                    }
                }) { Text(tr("Oluştur")) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

@Composable
private fun ScriptEditor(file: File, onBack: () -> Unit, onDeploy: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(file.name) { mutableStateOf<String?>(null) }
    var saved by rememberSaveable(file.name) { mutableStateOf<String?>(null) }
    var problems by remember { mutableStateOf<List<String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(file) {
        if (text == null) {
            val content = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
            text = content
            saved = content
        }
    }
    val current = text ?: return
    val dirty = current != saved

    suspend fun save(): Boolean {
        val errors = withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(current)
            ScriptRuntime.check(ScriptSource(file.name, current))
        }
        saved = current
        problems = errors
        return errors.isEmpty()
    }

    ScreenScaffold(file.name + if (dirty) " •" else "", onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = dirty, onClick = { scope.launch { save(); notice = tr("Kaydedildi") } }) { Text(tr("Kaydet")) }
                OutlinedButton(onClick = {
                    scope.launch {
                        if (save()) {
                            notice = tr("Kaydedildi; deploy başladı (sonuç Loglar'da)")
                            onDeploy()
                        }
                    }
                }) { Text(tr("Kaydet ve deploy et")) }
                TextButton(onClick = { confirmDelete = true }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            }
            problems?.let { p -> if (p.isEmpty()) notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) } else p.forEach { ErrorText(it) } }
            OutlinedTextField(
                value = current,
                onValueChange = { text = it; problems = null; notice = null },
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp).horizontalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("{0} silinsin mi?", file.name)) },
            text = { Text(tr("Dosya silinir; sonraki deploy'da bu script'in uçları ve olayları kalkar.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { file.delete() }
                        onBack()
                    }
                }) { Text(tr("Sil"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Vazgeç")) } },
        )
    }
}

private val SAMPLE = """
// Örnek script. Kaydedip deploy edince çalışır; kullanmadıklarınızı silin.

// Özel uç: GET /api/merhaba/Ayşe → {"mesaj":"Merhaba Ayşe"}
routerAdd("GET", "/merhaba/:ad", (req, ctx) => {
  return { mesaj: "Merhaba " + req.params.ad }
})

// Kayıt eklenmeden önce: başlığın boşluklarını temizle, boşsa reddet.
// onBeforeCreate("todos", (record, ctx) => {
//   record.title = (record.title || "").trim()
//   if (!record.title) fail(400, "Başlık boş olamaz")
// })

// Kayıt eklendikten sonra: başka bir servise bildir (adres Güvenlik > Script'ler'de izinli olmalı).
// onAfterCreate("orders", (record, ctx) => {
//   ctx.http.post("https://hooks.example.com/siparis", { id: record.id })
// })

// Her gece 03:00'te: 30 günden eski tamamlanmış işleri sil.
// cronAdd("0 3 * * *", (ctx) => {
//   const eski = new Date(Date.now() - 30 * 86400000).toISOString()
//   const sayfa = ctx.db.list("todos", { filter: { "done": "true", "created_at[lt]": eski }, perPage: 500 })
//   sayfa.items.forEach((t) => ctx.db.delete("todos", t.id))
//   ctx.log("silinen:", sayfa.items.length)
// })

// Arka plan işi: yavaş işleri (e-posta, dış servis) isteği bekletmeden kuyrukta yapın. Hata verirse beklemeyle
// yeniden denenir; fail(4xx, ...) kalıcı hatadır. İşler en az bir kez çalışır: aynı işi iki kez yapmak zarar vermemeli.
// jobAdd("hosgeldin", (veri, ctx, is) => {
//   ctx.mail.send(veri.email, "Hoş geldiniz", "Kaydınız için teşekkürler!")
// }, { retries: 5 })
// onAfterCreate("todos", (kayit, ctx) => ctx.jobs.enqueue("hosgeldin", { email: "ben@example.com" }, { delaySec: 60 }))
""".trimStart()
