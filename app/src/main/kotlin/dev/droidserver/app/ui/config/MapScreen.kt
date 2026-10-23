package dev.droidserver.app.ui.config

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.common.ScreenScaffold
import dev.droidserver.app.ui.common.SectionCard
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.ServerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** Konum alanı olan koleksiyonların kayıtları haritada (OpenStreetMap karoları; en yeni 500 kayıt). */
@Composable
fun MapScreen(g: AppGraph, wizard: WizardViewModel, onBack: () -> Unit) {
    val state by g.engine.state.collectAsState()
    val active by wizard.active.collectAsState()
    val choices = active?.collections.orEmpty().flatMap { c -> c.fields.filter { it.type == FieldType.GEO }.map { c.name to it.name } }
    var choice by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    var points by remember { mutableStateOf<List<ServerEngine.MapPoint>>(emptyList()) }
    var selected by remember { mutableStateOf<ServerEngine.MapPoint?>(null) }
    LaunchedEffect(choice, state) {
        val (c, f) = choice ?: return@LaunchedEffect
        points = withContext(Dispatchers.IO) { runCatching { g.engine.mapPoints(c, f) }.getOrNull().orEmpty() }
        selected = null
    }
    ScreenScaffold(tr("Harita"), onBack = onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choices.isEmpty()) {
                Text(tr("Konum alanı olan koleksiyon yok. Sihirbaz → Koleksiyonlar'da bir alanın tipini \"Konum\" seçin."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (choices.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { ch -> FilterChip(selected = ch == choice, onClick = { choice = ch }, label = { Text("${ch.first}.${ch.second}") }) }
                }
            }
            GeoMap(points, Modifier.fillMaxWidth().height(440.dp), onMarker = { selected = it })
            Text(tr("{0} konumlu kayıt · sürükleyin, iki parmakla yakınlaştırın", points.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            selected?.let { p ->
                SectionCard("#${p.id}" + (p.label?.let { " · $it" } ?: "")) {
                    Text("%.6f, %.6f".format(java.util.Locale.ROOT, p.point.lat, p.point.lng) + (p.point.accuracy?.let { " (±${it.roundToInt()} m)" } ?: ""))
                }
            }
            Text(tr("Harita © OpenStreetMap katkıcıları"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val TILE = 256

private fun worldX(lng: Double, z: Int) = (lng + 180) / 360 * TILE * (1 shl z)
private fun worldY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * TILE * (1 shl z)
}

/**
 * Kütüphanesiz karo haritası: sürükleme, iki parmakla ve düğmelerle yakınlaştırma, işaretler. Karolar önbellek
 * klasöründe tutulur (OpenStreetMap kullanım kuralı gereği tanıtıcı User-Agent ile, aynı anda en çok iki istek).
 */
@Composable
fun GeoMap(points: List<ServerEngine.MapPoint>, modifier: Modifier = Modifier, onMarker: (ServerEngine.MapPoint) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tiles = remember { TileCache.get(ctx) }
    val loaded = remember { mutableStateMapOf<String, ImageBitmap>() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableIntStateOf(2) }
    var cx by remember { mutableDoubleStateOf(0.0) }
    var cy by remember { mutableDoubleStateOf(0.0) }
    var fitted by remember(points) { mutableStateOf(false) }
    val pin = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surface
    val hit = with(LocalDensity.current) { 24.dp.toPx() }

    fun setZoom(z: Int, focus: Offset = Offset(size.width / 2f, size.height / 2f)) {
        val nz = z.coerceIn(1, 19)
        if (nz == zoom) return
        val k = Math.pow(2.0, (nz - zoom).toDouble())
        cx = (cx - size.width / 2 + focus.x) * k - focus.x + size.width / 2
        cy = (cy - size.height / 2 + focus.y) * k - focus.y + size.height / 2
        zoom = nz
    }

    if (!fitted && size.width > 0) {
        fitted = true
        if (points.isEmpty()) {
            zoom = 5; cx = worldX(35.0, zoom); cy = worldY(39.0, zoom)
        } else {
            var z = 17
            while (z > 1) {
                val xs = points.map { worldX(it.point.lng, z) }
                val ys = points.map { worldY(it.point.lat, z) }
                if (xs.max() - xs.min() < size.width - 120 && ys.max() - ys.min() < size.height - 120) break
                z--
            }
            zoom = z
            cx = (points.maxOf { worldX(it.point.lng, z) } + points.minOf { worldX(it.point.lng, z) }) / 2
            cy = (points.maxOf { worldY(it.point.lat, z) } + points.minOf { worldY(it.point.lat, z) }) / 2
        }
    }

    Box(modifier.clipToBounds().onSizeChanged { size = it }) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    var scale = 1f
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        cx -= pan.x; cy -= pan.y
                        scale *= gestureZoom
                        if (scale > 1.6f) { setZoom(zoom + 1, centroid); scale = 1f }
                        if (scale < 0.6f) { setZoom(zoom - 1, centroid); scale = 1f }
                    }
                }
                .pointerInput(points) {
                    detectTapGestures(onDoubleTap = { setZoom(zoom + 1, it) }) { tap ->
                        val left = cx - size.width / 2
                        val top = cy - size.height / 2
                        points.minByOrNull { p ->
                            val dx = worldX(p.point.lng, zoom) - left - tap.x
                            val dy = worldY(p.point.lat, zoom) - top - tap.y
                            dx * dx + dy * dy
                        }?.takeIf { p ->
                            val dx = worldX(p.point.lng, zoom) - left - tap.x
                            val dy = worldY(p.point.lat, zoom) - top - tap.y
                            dx * dx + dy * dy < hit * hit
                        }?.let(onMarker)
                    }
                },
        ) {
            drawRect(Color(0xFFE5E3DF))
            val n = 1 shl zoom
            val left = cx - size.width / 2
            val top = cy - size.height / 2
            var ty = floor(top / TILE).toInt()
            while (ty * TILE < top + size.height) {
                var tx = floor(left / TILE).toInt()
                while (tx * TILE < left + size.width) {
                    if (ty in 0 until n) {
                        val key = "$zoom/${((tx % n) + n) % n}/$ty"
                        val image = loaded[key] ?: tiles.memory(key)
                        if (image != null) {
                            drawImage(image, dstOffset = IntOffset((tx * TILE - left).roundToInt(), (ty * TILE - top).roundToInt()), dstSize = IntSize(TILE, TILE))
                        } else {
                            tiles.request(scope, key) { loaded[key] = it }
                        }
                    }
                    tx++
                }
                ty++
            }
            for (p in points) {
                val o = Offset((worldX(p.point.lng, zoom) - left).toFloat(), (worldY(p.point.lat, zoom) - top).toFloat())
                drawCircle(ring, radius = 9.dp.toPx(), center = o)
                drawCircle(pin, radius = 7.dp.toPx(), center = o)
                drawCircle(Color.Black.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = o, style = Stroke(1.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = { setZoom(zoom + 1) }) { Icon(Icons.Default.Add, contentDescription = tr("Yakınlaş")) }
            FilledTonalIconButton(onClick = { setZoom(zoom - 1) }) { Icon(Icons.Default.Remove, contentDescription = tr("Uzaklaş")) }
        }
    }
}

/** Harita karoları: bellekte son 64, diskte `cache/tiles` (sistem gerekirse siler). */
private class TileCache(private val dir: File) {
    private val memory = LruCache<String, ImageBitmap>(64)
    private val pending = HashSet<String>()
    private val limit = Semaphore(2)

    fun memory(key: String): ImageBitmap? = memory.get(key)

    fun request(scope: CoroutineScope, key: String, done: (ImageBitmap) -> Unit) {
        synchronized(pending) { if (!pending.add(key)) return }
        scope.launch {
            val image = withContext(Dispatchers.IO) { limit.withPermit { runCatching { load(key) }.getOrNull() } }
            synchronized(pending) { pending.remove(key) }
            if (image != null) {
                memory.put(key, image)
                done(image)
            }
        }
    }

    private fun load(key: String): ImageBitmap? {
        val file = File(dir, "$key.png")
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > 30L * 24 * 3600 * 1000) {
            val c = URI("https://tile.openstreetmap.org/$key.png").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "DroidServer (+https://github.com/kasimtmc/droidserver)")
            try {
                if (c.responseCode != 200) return null
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            } finally {
                c.disconnect()
            }
        }
        return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }

    companion object {
        @Volatile
        private var instance: TileCache? = null

        fun get(ctx: Context): TileCache = instance ?: synchronized(this) {
            instance ?: TileCache(File(ctx.cacheDir, "tiles")).also { instance = it }
        }
    }
}
