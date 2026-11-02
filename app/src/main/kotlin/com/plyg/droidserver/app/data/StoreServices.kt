package com.plyg.droidserver.app.data

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Play sürümünde Play'in uygulama içi güncellemesi, değerlendirmesi ve tam sürüm satın alması. GitHub sürümünde yok
 * ([storeServices] null); orada uygulamanın kendi güncelleyicisi (AppUpdater) çalışır ve satın alma yoktur.
 */
interface StoreServices {
    /** Durum ekranındaki güncelleme kartının durumu. */
    val update: StateFlow<StoreUpdate>

    /** Play'e yeni sürüm var mı diye sorar (Play'den kurulmamışsa sessizce hiçbir şey olmaz). */
    fun checkForUpdate()

    /** Esnek güncellemeyi başlatır: Play onay penceresi, sonra arka planda indirme. */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>)

    /** İnen güncellemeyi kurar; uygulama yeniden başlar (sunucu kısa süre durur). */
    fun completeUpdate()

    /** Play'in değerlendirme penceresini ister; gösterilip gösterilmeyeceğine Play karar verir (kota). */
    fun requestReview(activity: Activity)

    /** Uygulamanın Play sayfası (Ayarlar'daki "Play'de değerlendir"). */
    fun storePage(): Intent

    /** Tam sürüm: tek seferlik satın alma ya da ilk açılıştan itibaren [TrialPolicy.DAYS] günlük deneme. */
    val license: StateFlow<License>

    /** Satın alımları Play'den yeniden sorar (açılışta, ekrana dönüşte ve "Satın alımı geri yükle"). */
    fun refreshLicense()

    /** Play'in satın alma penceresini açar; sonuç [license]'a yansır. */
    fun purchase(activity: Activity)
}

sealed interface StoreUpdate {
    data object None : StoreUpdate
    data class Available(val versionCode: Int) : StoreUpdate
    data class Downloading(val percent: Int) : StoreUpdate
    data object Downloaded : StoreUpdate
}
