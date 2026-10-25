package com.plyg.droidserver.app.data

import android.app.Activity
import com.plyg.droidserver.core.components.ComponentManifest
import java.io.File

/**
 * Play sürümünde bileşen programlarını getiren isteğe bağlı Play modülleri (PLAN 19.4). GitHub sürümünde yok
 * ([componentModules] null döner); orada paketler bileşen deposundan indirilir.
 */
interface ComponentModules {
    /** Uygulamayla gelen katalog: modüllerdeki programlarla aynı sürümü anlatır. */
    fun catalog(): ComponentManifest

    /** Modüllerin programlarını kurduğu klasör (uygulamanın yerel kitaplık klasörü; güncellemede değişir). */
    fun libDir(): File

    /** Modülü Play'den indirip kurar (kuruluysa hemen döner); [progress] ekranda gösterilecek durum. */
    suspend fun install(activity: Activity?, id: String, progress: (String) -> Unit)

    /** Modülü kaldırılacak olarak işaretler (Play boş bir anda siler). */
    fun uninstall(id: String)
}
