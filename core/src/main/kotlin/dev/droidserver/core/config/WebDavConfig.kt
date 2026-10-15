@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Ağ sürücüsü (WebDAV): bilgisayarda (Windows, macOS, Linux) veya dosya yöneticisi uygulamasında telefondaki klasörleri
 * sürücü gibi bağlar. Her zaman `drive/` klasörü paylaşılır; site, script'ler ve (salt okunur) yüklenen dosyalar isteğe
 * bağlıdır. Kimlik: [username] + [password] (Digest; HTTPS üzerinden Basic de kabul edilir).
 */
@Serializable
data class WebDavConfig(
    val enabled: Boolean = false,
    /** Adres; örn. `/dav` → `http://telefon:8080/dav`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val path: String = "/dav",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val username: String = "droid",
    /** `secret:` referansı; kaydedince şifreli depoya taşınır. En az 8 karakter. */
    val password: String = "",
    /** Sürücü klasörüne yalnızca okuma. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val readOnly: Boolean = false,
    /** Statik site klasörü (`www/`) de paylaşılsın (yazılabilir). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val site: Boolean = false,
    /** Script'ler klasörü de paylaşılsın (yazılabilir; değişiklikler deploy'da geçerli olur). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scripts: Boolean = false,
    /** Kayıtlara yüklenen dosyalar da paylaşılsın (salt okunur). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val uploads: Boolean = false,
    /** Tek dosyanın en çok boyutu (MB). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxFileMb: Int = 2048,
) {
    companion object {
        val PATH = Regex("^(/[A-Za-z0-9._~-]+)+$")
    }
}
