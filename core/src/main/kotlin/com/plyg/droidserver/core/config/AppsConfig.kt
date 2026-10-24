@file:OptIn(ExperimentalSerializationApi::class)

package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kullanıcının kendi uygulaması (Node.js, Python, Java veya ikili): kodu zip olarak yüklenir ya da Git'ten çekilir,
 * telefonda `127.0.0.1:[port]`'ta çalışır ve sunucunun [path] yolu altında yayınlanır (HTTPS, tünel, güvenlik duvarı
 * ve hız sınırı ondan da geçer). Uygulama portu `PORT` ortam değişkeninden okumalıdır.
 *
 * Deploy: yeni sürüm (yüklenen zip veya Git) hazırlanır, [build] komutları bir kez çalışır, süreç başlatılır ve sağlık
 * denetimi ([health]) beklenir. Geçmezse önceki sürüme dönülür; çalışan sürüm değişmez.
 */
@Serializable
data class AppConfig(
    /** Küçük harf, rakam ve `-`; süreç kimliği `app:<ad>`, klasörü `apps/<ad>`. */
    val name: String,
    val enabled: Boolean = true,
    /** Çalışma ortamı bileşeni (Bileşenler'den kurulur), örn. `nodejs`, `python`, `openjdk`; yoksa komut koddaki bir ikilidir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val runtime: String? = null,
    /** Başlatma komutu, örn. `["node", "server.js"]`. İlk öğe çalışma ortamının `bin` klasöründe aranır, yoksa koda göredir. */
    val command: List<String>,
    /** Her yeni sürümde bir kez, başlatmadan önce çalışan komutlar, örn. `[["npm", "ci", "--omit=dev"]]`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val build: List<List<String>> = emptyList(),
    /** Uygulamanın dinlediği yerel port (1024-65535); `PORT` olarak verilir. */
    val port: Int,
    /** Sunucudaki yayın yolu; boşsa `/apps/<ad>`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val path: String? = null,
    /** true: yol öneki uygulamaya iletilmez (`/apps/api/users` → `/users`); öneki `BASE_PATH` ortam değişkeninden okunur. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stripPath: Boolean = true,
    /** Kimler erişebilir; `auth`/`admin`de kimlik sunucuda doğrulanır ve uygulamaya `X-DroidServer-User` başlığıyla iletilir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val access: AppAccess = AppAccess.PUBLIC,
    /** Sağlık denetimi yolu (uygulamanın kendi yolu, önek olmadan): 2xx/3xx yanıtı beklenir. null: yalnızca port açık mı. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val health: String? = "/",
    /** Başlatmadan sonra sağlık denetiminin en çok bekleneceği süre (sn). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val startTimeoutSec: Int = 60,
    /** Uygulamaya özel ortam değişkenleri (`secret:<ad>` referansı olabilir); ortak değişkenlerin üstüne yazar. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val env: Map<String, String> = emptyMap(),
    /** true: ortak ortam değişkenleri ([ServerConfig.env]) de verilir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sharedEnv: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val restart: RestartPolicy = RestartPolicy.ON_FAILURE,
    /** Saklanan sürüm sayısı (geri dönmek için); en eskiler silinir. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val keepReleases: Int = 3,
    /** İstek gövdesi sınırı (MB); dosya yüklemeleri dahil. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxBodyMb: Int = 10,
    /** WebSocket bağlantıları da uygulamaya iletilsin. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val websockets: Boolean = false,
    /** Kod Git deposundan çekilir (yükleme yerine veya yanında). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val git: GitSource? = null,
) {
    /** Yayın yolu (sonunda `/` olmadan). */
    val publicPath: String get() = path?.let { p -> p.trimEnd('/').ifEmpty { "/" } } ?: "/apps/$name"

    /** ProcessSupervisor'daki kimliği. */
    val processId: String get() = PROCESS_PREFIX + name

    companion object {
        const val PROCESS_PREFIX = "app:"
        val NAME = Regex("^[a-z][a-z0-9-]{0,31}$")
    }
}

@Serializable
enum class AppAccess(val title: String) {
    @SerialName("public") PUBLIC(tr("Herkes")),
    @SerialName("auth") AUTH(tr("Giriş yapmış kullanıcılar")),
    @SerialName("admin") ADMIN(tr("Yalnızca yönetici")),
}

/**
 * Git deposundan kod: sağlayıcının arşiv (zip) ucu kullanılır, telefonda `git` gerekmez. Gizli depo için [token]
 * (`secret:` referansı; GitHub'da yalnızca okuma izinli ince ayarlı token yeter). [webhookSecret] verilirse
 * `POST /api/_apps/<ad>/hook` push olaylarını kabul eder ve dal eşleşince yeni sürümü çekip deploy eder.
 */
@Serializable
data class GitSource(
    val provider: GitProvider = GitProvider.GITHUB,
    /** `sahip/depo` (GitLab'da alt gruplar olabilir); [GitProvider.URL]'de zip'in https adresi. */
    val repo: String,
    /** GitLab (varsayılan gitlab.com) ve Gitea/Forgejo için sunucu adı, örn. `git.example.com`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val host: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val branch: String = "main",
    /** Uygulama deponun bir alt klasöründeyse, örn. `backend`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val subdir: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val token: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val webhookSecret: String? = null,
)

@Serializable
enum class GitProvider(val title: String) {
    @SerialName("github") GITHUB("GitHub"),
    @SerialName("gitlab") GITLAB("GitLab"),
    @SerialName("gitea") GITEA("Gitea / Forgejo"),
    @SerialName("url") URL(tr("Zip adresi")),
}

/** Komut satırı metni ↔ argüman listesi (uygulama ekranındaki tek satırlık komut alanı için; kabuk çalıştırılmaz). */
object CommandLine {
    /** Boşluklarla ayırır; tek veya çift tırnak içindeki boşluklar korunur, `\` sonraki karakteri olduğu gibi alır. */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; inToken = true }
                c == '\\' && quote != '\'' && i + 1 < text.length -> { cur.append(text[++i]); inToken = true }
                quote == null && c.isWhitespace() -> if (inToken) { out += cur.toString(); cur.clear(); inToken = false }
                else -> { cur.append(c); inToken = true }
            }
            i++
        }
        if (inToken) out += cur.toString()
        return out
    }

    fun join(args: List<String>): String = args.joinToString(" ") { a ->
        if (a.isNotEmpty() && a.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }) a
        else "'" + a.replace("'", "'\\''") + "'"
    }
}
