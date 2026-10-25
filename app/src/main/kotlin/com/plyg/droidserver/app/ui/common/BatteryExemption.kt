package com.plyg.droidserver.app.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import com.plyg.droidserver.core.i18n.tr

/**
 * Pil iyileştirmesi muafiyeti. GitHub sürümünde doğrudan soran pencere (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); Play
 * sürümünde bu izin yok (Play yalnızca belirli uygulama türlerine verir), Android'in pil iyileştirmesi listesi açılır ve
 * kullanıcı DroidServer'ı orada muaf tutar.
 */
@SuppressLint("BatteryLife")
fun openBatteryExemption(ctx: Context) {
    if (ctx.checkSelfPermission(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == PackageManager.PERMISSION_GRANTED) {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri()))
    } else {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        Toast.makeText(ctx, tr("Listede DroidServer'ı bulun ve pil iyileştirmesini kapatın (\"Optimize etme\" / \"Kısıtlama yok\")"), Toast.LENGTH_LONG).show()
    }
}
