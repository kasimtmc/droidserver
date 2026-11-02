package com.plyg.droidserver.app.data

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.plyg.droidserver.core.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play sürümünün tam sürümü: Play Billing ile tek seferlik [PRODUCT_ID] ve ilk açılıştan itibaren deneme. Deneme
 * başlangıcı uygulama verisinde ve Block Store'da (Google hesabına bağlı; uygulama kaldırılıp kurulunca da kalır)
 * saklanır, erken olanı geçer. Satın alındığı görülünce telefonda da işaretlenir: Play'e ulaşılamazken kilitlenmez,
 * Play satın alımı listelemezse (iade) kaldırılır.
 */
internal class PlayLicense(private val context: Context) {
    private val prefs = context.getSharedPreferences("license", Context.MODE_PRIVATE)
    private val blockstore = Blockstore.getClient(context)
    private val state = MutableStateFlow<License>(License.Checking)
    val license: StateFlow<License> = state

    private var product: ProductDetails? = null
    private var pending = false
    private var error: String? = null

    private val billing = BillingClient.newBuilder(context)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        // Yeni kurulumda telefonda kayıt yoksa Block Store'daki eski başlangıç beklenir (yeniden kurarak deneme yenilenmesin).
        if (prefs.contains(KEY_TRIAL_START)) publish()
        blockstore.retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(BLOCKSTORE_KEY)).build())
            .addOnSuccessListener { r -> mergeTrialStart(r.blockstoreDataMap[BLOCKSTORE_KEY]?.bytes?.decodeToString()?.toLongOrNull()) }
            .addOnFailureListener { mergeTrialStart(null) }
        // Block Store yanıt vermezse deneme yine de başlar; yanıt sonradan gelirse erken olan yine geçer.
        Handler(Looper.getMainLooper()).postDelayed({ if (!prefs.contains(KEY_TRIAL_START)) mergeTrialStart(null) }, BLOCKSTORE_WAIT_MS)
        connect()
    }

    @Synchronized
    private fun mergeTrialStart(cloud: Long?) {
        val local = prefs.getLong(KEY_TRIAL_START, 0L).takeIf { it > 0 }
        val start = TrialPolicy.start(local, cloud, System.currentTimeMillis())
        if (start != local) prefs.edit { putLong(KEY_TRIAL_START, start) }
        if (start != cloud) {
            blockstore.storeBytes(StoreBytesData.Builder().setKey(BLOCKSTORE_KEY).setBytes(start.toString().encodeToByteArray()).setShouldBackupToCloud(true).build())
        }
        publish()
    }

    /** Durumu kayıtlardan yeniden hesaplar; deneme başlangıcı henüz bilinmiyorsa [License.Checking] kalır. */
    @Synchronized
    private fun publish() {
        val start = prefs.getLong(KEY_TRIAL_START, 0L).takeIf { it > 0 } ?: return
        state.value = TrialPolicy.license(
            start, System.currentTimeMillis(), owned = prefs.getBoolean(KEY_OWNED, false),
            price = product?.oneTimePurchaseOfferDetails?.formattedPrice, pending = pending, error = error,
        )
    }

    private fun connect() {
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    queryPurchases()
                } else {
                    error = billingError(result)
                    publish()
                }
            }

            // Kendiliğinden yeniden bağlanır (enableAutoServiceReconnection).
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun refresh() {
        publish()
        if (billing.isReady) queryPurchases() else connect()
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()),
        ).build()
        billing.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                product = details.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                publish()
            }
        }
    }

    private fun queryPurchases() {
        billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                error = null
                handle(purchases, complete = true)
            } else {
                error = billingError(result)
                publish()
            }
        }
    }

    fun purchase(activity: Activity) {
        val details = product
        if (details == null) {
            error = tr("Satın alma bilgisi Play'den alınamadı. İnternet bağlantınızı denetleyip yeniden deneyin.")
            refresh()
            return
        }
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            .apply { details.oneTimePurchaseOfferDetails?.offerToken?.let { setOfferToken(it) } }
            .build()
        val result = billing.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            error = billingError(result)
            publish()
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> { error = null; handle(purchases.orEmpty(), complete = false) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            else -> { error = billingError(result); publish() }
        }
    }

    /** [complete]: liste Play'deki bütün satın alımlar (sorgu); değilse yalnızca yeni gelenler (satın alma penceresi). */
    private fun handle(purchases: List<Purchase>, complete: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val owned = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (owned || complete) prefs.edit { putBoolean(KEY_OWNED, owned) }
        // Onaylanmayan satın alım Play'de üç gün sonra iade edilir.
        ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { p ->
            billing.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
        }
        publish()
    }

    private fun billingError(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            tr("Bu telefonda Google Play ile satın alma kullanılamıyor (uygulama Play'den kurulmamış olabilir).")
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.NETWORK_ERROR ->
            tr("Google Play'e ulaşılamadı. İnternet bağlantınızı denetleyip yeniden deneyin.")
        else -> tr("Google Play hatası ({0}): {1}", result.responseCode, result.debugMessage)
    }

    companion object {
        const val PRODUCT_ID = "droidserver_full"
        private const val KEY_TRIAL_START = "trialStart"
        private const val KEY_OWNED = "owned"
        private const val BLOCKSTORE_KEY = "com.plyg.droidserver.trialStart"
        private const val BLOCKSTORE_WAIT_MS = 5_000L
    }
}
