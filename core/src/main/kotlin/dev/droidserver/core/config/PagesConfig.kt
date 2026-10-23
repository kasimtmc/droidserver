@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kodsuz sayfa: `/pages/<name>` koleksiyonun kayıtlarını listeler, `/pages/<name>/<id>` birini gösterir. HTML sunucuda
 * üretilir (JavaScript yok, arama motorları okur). Koleksiyonun liste ve görüntüleme kuralları aynen uygulanır;
 * [login] açıksa (ya da kural giriş istiyorsa) ziyaretçi kullanıcı hesabıyla giriş yapar (çerezli oturum).
 */
@Serializable
data class PageConfig(
    /** Adres parçası: küçük harf, rakam ve `-`. */
    val name: String,
    val collection: String,
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: String? = null,
    /** Listede görünen alanlar; boşsa ilk beş okunabilir alan. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fields: List<String> = emptyList(),
    /** Detay sayfasındaki alanlar; boşsa tümü. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detailFields: List<String> = emptyList(),
    /** Sabit süzgeç (liste filtresi sözdizimi), örn. `status=published`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val filter: String? = null,
    /** Varsayılan sıralama, örn. `-created_at`; ziyaretçi listedeki alanlara göre değiştirebilir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sort: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val perPage: Int = 20,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val layout: PageLayout = PageLayout.CARDS,
    /** Detay sayfası (kayda tıklayınca). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detail: Boolean = true,
    /** Yalnızca giriş yapmış kullanıcılar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val login: Boolean = false,
    /** Tema rengi, `#RRGGBB`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val accent: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
) {
    val path: String get() = "$PATH/$name"

    companion object {
        const val PATH = "/pages"
        val NAME = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

@Serializable
enum class PageLayout {
    @SerialName("cards") CARDS,
    @SerialName("table") TABLE,
}

/**
 * Pano: görünümlerden beslenen kutular (sayı, grafik, tablo, harita), panelde ve uygulamada. [shareToken] verilirse
 * `/dashboards/<name>?token=…` herkese salt okunur açılır (kurallar aşılır; adresi bilen görür).
 */
@Serializable
data class DashboardConfig(
    val name: String,
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val widgets: List<WidgetConfig> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val shareToken: String? = null,
) {
    companion object {
        const val PATH = "/dashboards"
    }
}

/**
 * Pano kutusu. [view]: veri kaynağı görünüm. `number`: özetli görünümün [metric] değeri (`count`, `sum_<alan>`…);
 * `chart`: gruplu özetli görünümün grupları; `table`: görünümün ilk [limit] satırı; `map`: görünümdeki [field] konum
 * alanı. [width]: 1-3 sütun.
 */
@Serializable
data class WidgetConfig(
    val type: WidgetType,
    val view: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val metric: String = "count",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val chart: ChartKind = ChartKind.BAR,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val field: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val limit: Int = 10,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val width: Int = 1,
)

@Serializable
enum class WidgetType {
    @SerialName("number") NUMBER,
    @SerialName("chart") CHART,
    @SerialName("table") TABLE,
    @SerialName("map") MAP,
}

@Serializable
enum class ChartKind {
    @SerialName("bar") BAR,
    @SerialName("line") LINE,
}
