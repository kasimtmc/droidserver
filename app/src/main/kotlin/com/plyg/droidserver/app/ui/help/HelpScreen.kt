package com.plyg.droidserver.app.ui.help

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plyg.droidserver.app.ui.common.ScreenScaffold
import com.plyg.droidserver.core.help.HelpDocs
import com.plyg.droidserver.core.i18n.I18n
import com.plyg.droidserver.core.i18n.Lang
import com.plyg.droidserver.core.i18n.tr

/** README'den derlemede uygulamaya giren yardım (`assets/help/`), etkin dile göre; bir kez okunur. */
object HelpContent {
    @Volatile
    private var cache: Pair<Lang, List<HelpDocs.Section>>? = null

    fun sections(ctx: Context): List<HelpDocs.Section> {
        val lang = I18n.lang
        cache?.takeIf { it.first == lang }?.let { return it.second }
        val name = if (lang == Lang.TR) "help/README.md" else "help/README.en.md"
        val text = runCatching { ctx.assets.open(name).use { it.readBytes().decodeToString() } }.getOrDefault("")
        return HelpDocs.sections(text, tr("Genel bakış")).also { cache = lang to it }
    }
}

/** Yardım: README bölümleri, aranabilir; çevrimdışı. */
@Composable
fun HelpScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val sections = remember { HelpContent.sections(ctx) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) { HelpDocs.search(sections, query) }
    ScreenScaffold(tr("Yardım"), subtitle = tr("{0} bölüm", sections.size), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, label = { Text(tr("Yardımda ara")) },
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )
            if (sections.isEmpty()) Text(tr("Yardım bu sürümde yok."), Modifier.padding(16.dp))
            LazyColumn {
                items(shown) { s ->
                    val top = s.level == 2
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(sections.indexOf(s)) }
                            .padding(start = if (top) 16.dp else 32.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Text(s.title, style = if (top) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge, fontWeight = if (top) FontWeight.SemiBold else null)
                        // Aramada üst bölüm adı da gösterilir.
                        if (query.isNotBlank() && s.parent != null) Text(s.parent!!, style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun HelpSectionScreen(index: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember(index) { HelpContent.sections(ctx).getOrNull(index) }
    ScreenScaffold(s?.title ?: tr("Yardım"), subtitle = s?.parent, onBack = onBack) { modifier ->
        if (s == null) return@ScreenScaffold Text(tr("Bölüm bulunamadı."), modifier.padding(16.dp))
        MarkdownText(s.body, modifier.verticalScroll(rememberScrollState()).padding(16.dp))
    }
}
