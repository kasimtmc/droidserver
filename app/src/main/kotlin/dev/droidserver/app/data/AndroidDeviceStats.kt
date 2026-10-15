package dev.droidserver.app.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.storage.StorageManager
import dev.droidserver.core.metrics.DeviceSnapshot
import dev.droidserver.core.metrics.DeviceStats

/** Pil (yapışkan ACTION_BATTERY_CHANGED yayını; alıcı kaydı gerekmez), boş depolama ve bellek. */
class AndroidDeviceStats(private val context: Context) : DeviceStats {
    override fun snapshot(): DeviceSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return DeviceSnapshot(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            batteryTempC = if (temp != Int.MIN_VALUE) temp / 10.0 else null,
            charging = if (plugged >= 0) plugged != 0 else null,
            freeStorageBytes = freeBytes(),
            memoryUsedBytes = memory.totalMem - memory.availMem,
            memoryTotalBytes = memory.totalMem,
        )
    }

    /** Silinebilir önbellekler de sayılır (sistem gerekince boşaltır). */
    private fun freeBytes(): Long {
        val sm = context.getSystemService(StorageManager::class.java)
        return runCatching { sm.getAllocatableBytes(sm.getUuidForPath(context.filesDir)) }.getOrDefault(context.filesDir.usableSpace)
    }
}
