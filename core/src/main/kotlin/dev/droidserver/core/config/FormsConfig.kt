@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Herkese açık form: `/forms/<name>` adresinde bir koleksiyona kayıt ekleyen HTML sayfası (iletişim, başvuru, anket).
 * Gönderim koleksiyonun ekleme kuralından bağımsızdır: yalnızca formdaki alanlar yazılabilir. Spam'e karşı gizli tuzak
 * alanı, en kısa doldurma süresi ve adres başına sınır uygulanır. Gelen kayıt diğer kayıtlar gibi olay üretir
 * (otomasyon, webhook, canlı abonelik).
 */
@Serializable
data class FormConfig(
    /** Adres parçası: küçük harf, rakam ve `-`. */
    val name: String,
    val collection: String,
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: String? = null,
    /** Formdaki alanlar ve sırası; boşsa koleksiyonun yazılabilir tüm alanları. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fields: List<FormField> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val submitLabel: String? = null,
    /** Gönderim sonrası gösterilen metin. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val successMessage: String? = null,
    /** Gönderim sonrası yönlendirilecek adres (`https://…` veya `/…`); verilirse teşekkür sayfası gösterilmez. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val redirect: String? = null,
    /** Aynı adresten 10 dakikada en çok kaç gönderim. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxPerTenMinutes: Int = 5,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
) {
    val path: String get() = "$PATH/$name"

    companion object {
        const val PATH = "/forms"
        val NAME = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    }
}

@Serializable
data class FormField(
    val name: String,
    /** Görünen ad; yoksa alan adı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val label: String? = null,
    /** Alanın altında küçük açıklama. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val help: String? = null,
    /** Metin alanı çok satırlı kutu olarak gösterilir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val multiline: Boolean = false,
)
