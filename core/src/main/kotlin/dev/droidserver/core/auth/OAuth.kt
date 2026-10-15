package dev.droidserver.core.auth

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.config.OAuthConfig
import dev.droidserver.core.config.OAuthProvider
import dev.droidserver.core.config.OAuthProviderConfig
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Sağlayıcının uç adresleri (testlerde sahte sağlayıcıya yönlendirilir). */
data class OAuthEndpoints(val authorize: String, val token: String, val userinfo: String, val scope: String) {
    companion object {
        val DEFAULT = mapOf(
            OAuthProvider.GOOGLE to OAuthEndpoints(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo",
                "openid email",
            ),
            OAuthProvider.GITHUB to OAuthEndpoints(
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com/user/emails",
                "user:email",
            ),
        )
    }
}

/** Akış hatası; [redirect] varsa istemciye `?error=` ile dönülür. */
class OAuthException(message: String, val redirect: String?) : RuntimeException(message)

/**
 * Sosyal giriş: OAuth2 yetkilendirme kodu akışı, PKCE (S256) ve `state`. Akış başlarken `state` tarayıcıya HttpOnly
 * çerezle de verilir; dönüşte ikisi eşleşmeli (başkasının başlattığı akışla oturum açtırma engellenir). Sağlayıcıdan
 * yalnızca doğrulanmış e-posta kabul edilir. İstemciye token yerine 60 sn geçerli, tek kullanımlık kod verilir
 * (adreste token taşınmaz); istemci kodu `POST /auth/oauth/exchange` ile token'a çevirir. Bekleyen akışlar bellektedir.
 */
class OAuthService(
    private val config: OAuthConfig,
    private val basePath: String,
    private val endpoints: Map<OAuthProvider, OAuthEndpoints> = OAuthEndpoints.DEFAULT,
    private val clock: () -> Instant = Instant::now,
    private val timeoutMs: Int = 10_000,
    /** Dönüş ucunun yolu (API yoluna göre, `{provider}` ile); uzman modunda değiştirilebilir. */
    private val callbackPath: String? = "/auth/oauth/{provider}/callback",
) {
    private data class Pending(val provider: OAuthProvider, val verifier: String, val redirect: String?, val expires: Instant)
    private data class Issued(val payload: JsonObject, val expires: Instant)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val codes = ConcurrentHashMap<String, Issued>()
    private val random = SecureRandom()

    val providers: List<OAuthProviderConfig> get() = config.providers.filter { it.enabled }

    fun callbackUrl(provider: OAuthProvider): String =
        config.publicUrl.trimEnd('/') + basePath.trimEnd('/') +
            (callbackPath ?: "/auth/oauth/{provider}/callback").replace("{provider}", provider.name.lowercase())

    private fun provider(name: String): OAuthProviderConfig =
        providers.firstOrNull { it.provider.name.equals(name, ignoreCase = true) } ?: throw ApiException(404, tr("'{0}' ile giriş açık değil", name))

    /** İstemcinin dönüş adresi izinli adreslerden biriyle başlamalı (açık yönlendirme engellenir). */
    private fun checkRedirect(redirect: String?): String? {
        val r = redirect?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ('#' in r || config.redirectUrls.none { allowed -> r == allowed || r.startsWith(allowed.trimEnd('/') + "/") || r.startsWith("$allowed?") }) {
            throw ApiException(400, tr("Dönüş adresi izinli değil: {0} (Sihirbaz → Kullanıcılar → Sosyal giriş → İzinli dönüş adresleri)", r))
        }
        return r
    }

    /** Sağlayıcıya yönlendirme adresi ve tarayıcıya verilecek çerez değeri (`state`). */
    fun start(name: String, redirect: String?): Pair<String, String> {
        val p = provider(name)
        val target = checkRedirect(redirect)
        val e = endpoints.getValue(p.provider)
        cleanup()
        if (pending.size >= MAX_PENDING) throw ApiException(429, tr("Çok fazla bekleyen giriş; biraz sonra tekrar deneyin"))
        val state = randomToken()
        val verifier = randomToken()
        pending[state] = Pending(p.provider, verifier, target, clock().plusSeconds(600))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to p.clientId,
            "redirect_uri" to callbackUrl(p.provider),
            "scope" to e.scope,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        if (p.provider == OAuthProvider.GOOGLE) params["prompt"] = "select_account"
        return e.authorize + "?" + form(params) to state
    }

    /**
     * Sağlayıcıdan dönüş: `state` ve çerez denetlenir, kod token'a çevrilir, doğrulanmış e-posta okunur.
     * Dönen çift: e-posta ve istemcinin dönüş adresi.
     */
    fun callback(name: String, code: String?, state: String?, cookie: String?, error: String?): Pair<String, String?> {
        val p = provider(name)
        val flow = state?.let { pending.remove(it) }
        if (flow == null || flow.provider != p.provider || clock().isAfter(flow.expires)) {
            throw OAuthException(tr("Giriş isteğinin süresi dolmuş veya geçersiz; yeniden deneyin"), null)
        }
        if (cookie == null || !MessageDigest.isEqual(cookie.toByteArray(), state.toByteArray())) {
            throw OAuthException(tr("Giriş bu tarayıcıda başlatılmamış; yeniden deneyin"), flow.redirect)
        }
        if (error != null) throw OAuthException(if (error == "access_denied") tr("Giriş iptal edildi") else tr("Sağlayıcı hatası: {0}", error), flow.redirect)
        if (code.isNullOrBlank()) throw OAuthException(tr("Sağlayıcı kod göndermedi"), flow.redirect)
        val e = endpoints.getValue(p.provider)
        val tokenResponse = request(
            e.token, "POST",
            form(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to callbackUrl(p.provider),
                    "client_id" to p.clientId,
                    "client_secret" to p.clientSecret,
                    "code_verifier" to flow.verifier,
                ),
            ),
            null,
        ) as? JsonObject
        val access = (tokenResponse?.get("access_token") as? JsonPrimitive)?.content
            ?: throw OAuthException(tr("Sağlayıcı giriş kodunu kabul etmedi") + ((tokenResponse?.get("error") as? JsonPrimitive)?.content?.let { " ($it)" } ?: ""), flow.redirect)
        val email = when (p.provider) {
            OAuthProvider.GOOGLE -> (request(e.userinfo, "GET", null, access) as? JsonObject)?.let { o ->
                (o["email"] as? JsonPrimitive)?.content?.takeIf { (o["email_verified"] as? JsonPrimitive)?.let { v -> v.booleanOrNull ?: (v.content == "true") } == true }
            }
            OAuthProvider.GITHUB -> (request(e.userinfo, "GET", null, access) as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { list ->
                val verified = list.filter { (it["verified"] as? JsonPrimitive)?.booleanOrNull == true }
                (verified.firstOrNull { (it["primary"] as? JsonPrimitive)?.booleanOrNull == true } ?: verified.firstOrNull())?.let { (it["email"] as? JsonPrimitive)?.content }
            }
        } ?: throw OAuthException(tr("{0} hesabında doğrulanmış e-posta yok", p.provider.title), flow.redirect)
        return email to flow.redirect
    }

    /** Oturum yanıtını tek kullanımlık koda bağlar. */
    fun issueCode(payload: JsonObject): String {
        cleanup()
        val code = randomToken()
        codes[code] = Issued(payload, clock().plusSeconds(60))
        return code
    }

    fun exchange(code: String?): JsonObject {
        val issued = code?.let { codes.remove(it) }
        if (issued == null || clock().isAfter(issued.expires)) throw ApiException(400, tr("Giriş kodu geçersiz, kullanılmış veya süresi dolmuş"))
        return issued.payload
    }

    /** Başarılı veya hatalı sonucu istemcinin dönüş adresine ekler. */
    fun redirectWith(redirect: String, key: String, value: String): String =
        redirect + (if ('?' in redirect) "&" else "?") + key + "=" + URLEncoder.encode(value, "UTF-8")

    private fun cleanup() {
        val now = clock()
        pending.entries.removeIf { now.isAfter(it.value.expires) }
        codes.entries.removeIf { now.isAfter(it.value.expires) }
    }

    private fun request(url: String, method: String, body: String?, bearer: String?): Any? {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "DroidServer")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return runCatching { Json.parseToJsonElement(text) }.getOrNull()
        } catch (e: java.io.IOException) {
            throw OAuthException(tr("Sağlayıcıya ulaşılamadı: {0}", e.message), null)
        } finally {
            c.disconnect()
        }
    }

    private fun randomToken() = b64(ByteArray(32).also(random::nextBytes))

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun form(params: Map<String, String>) =
        params.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    companion object {
        const val COOKIE = "ds_oauth"
        private const val MAX_PENDING = 1000
    }
}
