@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ödemeler: sağlayıcının barındırılan ödeme sayfasına bağlantı üretilir (kart verisi sunucudan hiç geçmez), sonuç
 * sağlayıcının imzalı bildirimiyle (webhook) gelir ve `_payments` tablosuna, kayda bağlandıysa kaydın [statusField]
 * alanına yazılır. Bildirimler telefona internetten ulaşmalı: [publicUrl] doğrudan erişimin ya da adlandırılmış
 * tünelin HTTPS adresidir.
 */
@Serializable
data class PaymentsConfig(
    /** Ödeme sağlayıcıları; birden çok açılabilir, bağlantı üretirken biri seçilir (verilmezse ilki). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val providers: List<PaymentProviderConfig> = emptyList(),
    /** Sunucunun internetten erişilen adresi (`https://…`): ödeme sonrası dönüş ve sağlayıcı bildirimleri. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val publicUrl: String = "",
    /** Abonelik planları (Stripe ve Paddle): ödeyen kullanıcıya rol ve kota. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val plans: List<PaymentPlan> = emptyList(),
    /** Bağlı kayıtta ödeme durumunun yazılacağı alan (koleksiyonda varsa): `pending`, `paid`, `failed`, `canceled`, `refunded`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val statusField: String = "payment_status",
    /** Makbuzdaki satıcı adı ve bilgisi (boşsa sunucunun adı). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val merchant: String = "",
) {
    val enabled: Boolean get() = providers.any { it.enabled }

    fun provider(type: PaymentProvider?): PaymentProviderConfig? =
        providers.firstOrNull { it.enabled && (type == null || it.provider == type) }

    companion object {
        /** Sağlayıcı bildirimlerinin geldiği uç: `<publicUrl>/payments/hook/<sağlayıcı>`. */
        const val PATH = "/payments"
        val PLAN_NAME = Regex("^[a-z][a-z0-9_-]{0,39}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}

@Serializable
enum class PaymentProvider(val title: String, val subscriptions: Boolean) {
    /** Uluslararası; kartlar, Apple/Google Pay. Test modu anahtarın `sk_test_` olmasıyla. */
    @SerialName("stripe") STRIPE("Stripe", true),

    /** Türkiye; taksit, yerli kartlar. */
    @SerialName("iyzico") IYZICO("iyzico", false),

    /** Satıcı kaydı (merchant of record): vergi ve fatura Paddle'da. */
    @SerialName("paddle") PADDLE("Paddle", true),
    ;

    val wire: String get() = name.lowercase()
}

@Serializable
data class PaymentProviderConfig(
    val provider: PaymentProvider,
    /** Stripe: gizli anahtar (`sk_…`); iyzico: gizli anahtar (secret key); Paddle: API anahtarı. Şifreli depoda. */
    val secretKey: String,
    /** iyzico: API anahtarı (api key). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apiKey: String = "",
    /** Stripe: webhook imza sırrı (`whsec_…`); Paddle: bildirim sırrı. iyzico gizli anahtarla imzalar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val webhookSecret: String = "",
    /** Paddle: istemci token'ı (gizli değil); ödeme sayfası Paddle.js ile `/payments/paddle` adresinde açılır. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val clientToken: String = "",
    /** Test ortamı (iyzico ve Paddle sandbox). Stripe'ta test modu anahtardan anlaşılır. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sandbox: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
)

/** Abonelik planı: sağlayıcıda oluşturulmuş fiyat; abonelik sürdükçe kullanıcının rolü [role], kotası [quotas]. */
@Serializable
data class PaymentPlan(
    val name: String,
    val title: String,
    val provider: PaymentProvider,
    /** Sağlayıcıdaki fiyat kimliği: Stripe `price_…`, Paddle `pri_…`. */
    val price: String,
    /** Abonelik sürdükçe verilen rol; bitince kullanıcı önceki rolüne döner. */
    val role: String,
    /** Bu roldeki kullanıcıların kotası (genel kotanın yerine); yoksa genel kota. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quotas: QuotaConfig? = null,
)
