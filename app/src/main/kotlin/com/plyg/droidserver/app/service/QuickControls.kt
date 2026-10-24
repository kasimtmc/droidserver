package com.plyg.droidserver.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.plyg.droidserver.app.DroidServerApp
import com.plyg.droidserver.app.R
import com.plyg.droidserver.app.data.AndroidDeviceStats
import com.plyg.droidserver.app.data.NetUtil
import com.plyg.droidserver.app.graph
import com.plyg.droidserver.app.ui.MainActivity
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.mqtt.runScene
import com.plyg.droidserver.core.server.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Uygulamayı açmadan sunucu yönetimi: bildirim panelindeki hızlı ayar kutucuğu ve ana ekran widget'ı. İkisi de
 * etkin profilin sunucusunu açıp kapatır; konfigürasyon yoksa uygulamayı açar (önce sihirbaz).
 */
object QuickControls {
    /** Sunucu çalışıyorsa durdurur, değilse başlatır. Konfigürasyon yoksa false (çağıran uygulamayı açar). */
    fun toggle(ctx: Context): Boolean {
        val g = ctx.graph
        if (g.engine.state.value is EngineState.Running) {
            ServerService.stop(ctx)
            return true
        }
        if (!g.configRepo.hasConfig()) return false
        ServerService.start(ctx)
        return true
    }

    /** Kutucuk ve widget'lar yeni durumu göstersin. */
    fun refresh(ctx: Context) {
        StatusWidget.refresh(ctx)
        SceneWidget.refresh(ctx)
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, ServerTileService::class.java)) }
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Kısa durum metni: "Çalışıyor · 192.168.1.20:8080", "Kapalı" veya hata. */
    internal fun summary(state: EngineState): Pair<String, String?> = when (state) {
        is EngineState.Running -> tr("Çalışıyor") to "${NetUtil.localIpv4().firstOrNull() ?: "127.0.0.1"}:${state.port}"
        is EngineState.Failed -> tr("Başlatılamadı") to state.reason.take(80)
        EngineState.Stopped -> tr("Kapalı") to null
    }
}

/** Hızlı ayar kutucuğu: dokununca sunucuyu açar/kapatır; telefon kilitliyse önce kilidin açılması istenir. */
class ServerTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch { graph.engine.state.collect { update(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val action = {
            if (!QuickControls.toggle(this)) openApp()
        }
        if (isLocked) unlockAndRun(action) else action()
    }

    private fun openApp() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(QuickControls.openApp(this))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun update(state: EngineState) {
        val tile = qsTile ?: return
        val (status, detail) = QuickControls.summary(state)
        tile.state = if (state is EngineState.Running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (state is EngineState.Running) detail else status
        tile.contentDescription = listOfNotNull(getString(R.string.app_name), status, detail).joinToString(", ")
        tile.updateTile()
    }
}

/** Ana ekran widget'ı: durum, adres, son bir saatteki istek ve pil; düğmeyle aç/kapat, karta dokununca uygulama. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            if (!QuickControls.toggle(context)) context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TOGGLE = "com.plyg.droidserver.WIDGET_TOGGLE"
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isNotEmpty()) runCatching { manager.updateAppWidget(ids, views(ctx)) }
        }

        internal fun views(ctx: Context): RemoteViews {
            val g = ctx.graph
            val state = g.engine.state.value
            val running = state is EngineState.Running
            val (status, detail) = QuickControls.summary(state)
            val requests = g.engine.metrics.series().sumOf { it.requests }
            val battery = runCatching { AndroidDeviceStats(ctx).snapshot().batteryPercent }.getOrNull()
            val stats = listOfNotNull(
                tr("Son 1 saat: {0} istek", requests).takeIf { running },
                battery?.let { tr("Pil %{0}", it) },
            ).joinToString(" · ")
            val toggle = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, StatusWidget::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                // Birden çok sunucu profili varsa hangisi olduğu görünsün.
                val multi = runCatching { (ctx.applicationContext as DroidServerApp).profiles.list().size > 1 }.getOrDefault(false)
                setTextViewText(R.id.widget_title, if (multi) g.profile.name else ctx.getString(R.string.app_name))
                setTextViewText(R.id.widget_status, status)
                setTextViewText(R.id.widget_detail, detail ?: if (running) "" else tr("Başlatmak için dokunun"))
                setTextViewText(R.id.widget_stats, stats)
                setTextViewText(R.id.widget_toggle, if (running) tr("Durdur") else tr("Başlat"))
                setOnClickPendingIntent(R.id.widget_toggle, toggle)
                setOnClickPendingIntent(R.id.widget_root, QuickControls.openApp(ctx))
                setInt(R.id.widget_dot, "setBackgroundResource", if (running) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
            }
        }
    }
}

/** Ana ekran widget'ı: MQTT sahneleri; ilk dördü düğme olur, dokununca sahne arka planda yayınlanır. */
class SceneWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SCENE) {
            val name = intent.getStringExtra(EXTRA_NAME) ?: return
            val app = context.applicationContext
            val pending = goAsync()
            // Soket yazımı ana iş parçacığında yapılamaz.
            Thread({
                try {
                    val g = app.graph
                    val scene = runCatching { g.configRepo.loadActiveOrNull()?.mqtt?.scenes }.getOrNull()?.firstOrNull { it.name == name }
                    val broker = g.engine.mqtt.broker
                    val message = when {
                        scene == null -> tr("Sahne yok: {0}", name)
                        broker == null -> tr("MQTT çalışmıyor; sunucuyu başlatın")
                        else -> tr("{0}: {1} cihaza gitti", name, broker.runScene(scene))
                    }
                    Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
                } finally {
                    pending.finish()
                }
            }, "ds-scene").start()
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_SCENE = "com.plyg.droidserver.WIDGET_SCENE"
        private const val EXTRA_NAME = "name"
        private val BUTTONS = intArrayOf(R.id.scene_1, R.id.scene_2, R.id.scene_3, R.id.scene_4)

        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, SceneWidget::class.java))
            if (ids.isNotEmpty()) runCatching { manager.updateAppWidget(ids, views(ctx)) }
        }

        internal fun views(ctx: Context): RemoteViews {
            val scenes = runCatching { ctx.graph.configRepo.loadActiveOrNull()?.mqtt?.scenes }.getOrNull().orEmpty().take(BUTTONS.size)
            return RemoteViews(ctx.packageName, R.layout.widget_scenes).apply {
                setTextViewText(R.id.scenes_title, tr("Sahneler"))
                setTextViewText(R.id.scenes_empty, tr("Uygulamada MQTT → Sahneler'den ekleyin."))
                setViewVisibility(R.id.scenes_empty, if (scenes.isEmpty()) View.VISIBLE else View.GONE)
                BUTTONS.forEachIndexed { i, id ->
                    val scene = scenes.getOrNull(i)
                    setViewVisibility(id, if (scene == null) View.INVISIBLE else View.VISIBLE)
                    if (scene != null) {
                        setTextViewText(id, scene.name)
                        val run = Intent(ctx, SceneWidget::class.java).setAction(ACTION_SCENE).putExtra(EXTRA_NAME, scene.name)
                        setOnClickPendingIntent(id, PendingIntent.getBroadcast(ctx, 100 + i, run, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
                setOnClickPendingIntent(R.id.scenes_root, QuickControls.openApp(ctx))
            }
        }
    }
}
