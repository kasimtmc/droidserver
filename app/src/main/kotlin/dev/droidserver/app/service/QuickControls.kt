package dev.droidserver.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.droidserver.app.DroidServerApp
import dev.droidserver.app.R
import dev.droidserver.app.data.AndroidDeviceStats
import dev.droidserver.app.data.NetUtil
import dev.droidserver.app.graph
import dev.droidserver.app.ui.MainActivity
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.server.EngineState
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
        private const val ACTION_TOGGLE = "dev.droidserver.WIDGET_TOGGLE"
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
