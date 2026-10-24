package com.plyg.droidserver.core.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.api.Page
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.SystemCollections.ROLE_ADMIN
import com.plyg.droidserver.core.config.SystemCollections.ROLE_USER
import com.plyg.droidserver.core.config.SystemCollections.SESSIONS
import com.plyg.droidserver.core.config.SystemCollections.TWO_FACTOR
import com.plyg.droidserver.core.config.SystemCollections.USERS
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import com.plyg.droidserver.core.files.FileStore
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.security.FieldEncryption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** İsteği yapan. */
sealed interface Caller {
    val isAdmin: Boolean

    data object Anonymous : Caller {
        override val isAdmin = false
    }

    /** API anahtarı veya yönetici JWT'si (kimlik doğrulama kapalıysa herkes). */
    data object Admin : Caller {
        override val isAdmin = true
    }

    /**
     * Son kullanıcı; rolü `admin` ise yönetici yetkisi taşır. [restricted]: iki adımlı doğrulama zorunlu ama kurulmamış;
     * yalnızca hesap uçlarını kullanabilir (yönetici yetkisi de kurulana kadar yok).
     */
    data class User(
        val id: Long,
        override val isAdmin: Boolean,
        val restricted: Boolean = false,
        val role: String = ROLE_USER,
        /** Üyesi olduğu takımlar (takımlar açıksa; istek başına okunur). */
        val teams: Set<Long> = emptySet(),
        /** Erişim token'ının oturumu (`_sessions` satırı); eski token'larda null. */
        val session: Long? = null,
    ) : Caller

    /**
     * Kapsamı sınırlı API anahtarı: [collections]'da (boşsa hepsinde) kuralları aşar, [readOnly] ise yalnızca okur.
     * Yönetici uçlarına erişemez. Tam yetkili anahtar [Admin] olarak çözülür.
     */
    data class Key(val name: String, val readOnly: Boolean, val collections: Set<String>) : Caller {
        override val isAdmin = false

        fun allows(collection: String, write: Boolean): Boolean =
            (collections.isEmpty() || collection in collections) && !(write && readOnly)
    }
}

/**
 * Son kullanıcı hesapları: kayıt, giriş, token yenileme, çıkış ve yönetici işlemleri.
 *
 * Erişim token'ı kısa ömürlü bir JWT'dir (HS256). İmza anahtarı veritabanında (`_ds_meta`) rastgele üretilir:
 * konfigürasyonda sır gerektirmez, yönetici JWT sırrından ayrıdır (kullanıcı token'ı yönetici token'ı yerine geçemez).
 * Her istekte kullanıcı veritabanından okunur: silinen kullanıcı hemen erişimini kaybeder, rol değişikliği hemen geçerlidir.
 *
 * Yenileme token'ı rastgele 256 bittir; veritabanında yalnızca SHA-256 özeti saklanır (`_sessions`) ve her kullanımda
 * silinip yenisi verilir (tek kullanımlık).
 */
class UserService(
    private val db: SqlDriver,
    config: ServerConfig,
    private val hasher: PasswordHasher = PasswordHasher(),
    private val clock: () -> Instant = Instant::now,
    private val limiter: LoginLimiter = LoginLimiter(perAccount = config.auth.users.loginAttempts, windowMinutes = config.auth.users.loginBlockMinutes),
    /** Kullanıcı silinince bağlı kayıtların dosyaları da silinsin diye. */
    files: FileStore? = null,
    /** Yönetici işlemlerinin kaydı (hesap açma, güncelleme, silme). */
    private val audit: AuditLog? = null,
) {
    private val users = config.auth.users
    private val dialect = db.dialect
    private val records = RecordService(db, SystemCollections.users(users), clock, config.schemaCollections(), files = files, cipher = FieldEncryption.cipher(config))
    private val extraNames = users.extraFields.map { it.name }.toSet()
    private val random = SecureRandom()
    private val issuer = config.auth.jwtIssuer
    private val serverName = config.name

    /** Giriş sonucu: token çifti veya ikinci adım (doğrulayıcı kodu) isteği. */
    sealed interface LoginResult {
        fun toJson(): JsonObject
    }

    /** [setupRequired]: iki adımlı doğrulama zorunlu ve kullanıcı henüz kurmamış. */
    data class Tokens(val token: String, val refreshToken: String, val expiresIn: Long, val user: JsonObject, val setupRequired: Boolean = false) : LoginResult {
        override fun toJson(): JsonObject = buildJsonObject {
            put("token", JsonPrimitive(token))
            put("refreshToken", JsonPrimitive(refreshToken))
            put("expiresIn", JsonPrimitive(expiresIn))
            put("user", user)
            if (setupRequired) put("twoFactorSetupRequired", JsonPrimitive(true))
        }
    }

    /** Birinci adım doğru; `POST /auth/2fa/verify {challenge, code}` bekleniyor. */
    data class Challenge(val challenge: String, val expiresIn: Long) : LoginResult {
        override fun toJson(): JsonObject = buildJsonObject {
            put("twoFactorRequired", JsonPrimitive(true))
            put("challenge", JsonPrimitive(challenge))
            put("expiresIn", JsonPrimitive(expiresIn))
        }
    }

    private val algorithm by lazy { Algorithm.HMAC256(signingKey()) }
    private val verifier by lazy { JWT.require(algorithm).withIssuer(issuer).withAudience(AUDIENCE).build() }

    // ---- son kullanıcı ----

    fun signup(body: JsonObject): Tokens = issue(register(body).id())

    /** Hesap açar, oturum vermez (e-posta doğrulaması zorunluysa: giriş doğrulamadan sonra). */
    fun register(body: JsonObject): JsonObject {
        if (!users.allowSignup) throw ApiException(403, tr("Kayıt kapalı; hesabı yönetici açar"))
        return createUser(body, admin = false)
    }

    /** E-posta veya parola yanlışsa 401; çok sayıda hatalı denemede 429. [client]: istemci adresi (sınır için). */
    fun login(client: String, emailRaw: String?, password: String?): LoginResult {
        val email = emailRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        limiter.blockedFor(client, email)?.let { throw ApiException(429, tr("Çok fazla hatalı giriş denemesi; {0} saniye sonra tekrar deneyin", it)) }
        val row = if (email.isEmpty() || password == null) null
        else db.query("SELECT \"id\", \"password_hash\" FROM $table WHERE \"email\" = ?", listOf(email)).firstOrNull()
        val ok = when {
            password == null -> false
            row == null -> hasher.verifyDummy(password)
            else -> hasher.verify(password, row["password_hash"] as String)
        }
        if (!ok || row == null) {
            limiter.failed(client, email)
            throw ApiException(401, tr("E-posta veya parola hatalı"))
        }
        limiter.succeeded(client, email)
        // Parola doğru olduktan sonra denetlenir: hesabın varlığı ve doğrulanma durumu başkasına sızmaz.
        if (users.requireVerified && !isVerified((row["id"] as Number).toLong())) {
            throw ApiException(403, tr("E-posta adresinizi doğrulayın; gelen kutunuzdaki bağlantıya tıklayın (yenisi için /auth/request-verification)"))
        }
        db.execute(
            "DELETE FROM $sessions WHERE ${dialect.comparable(FieldType.DATETIME, "\"expires_at\"")} < ${dialect.comparable(FieldType.DATETIME, "?")}",
            listOf(dialect.encode(FieldType.DATETIME, clock())),
        )
        return complete((row["id"] as Number).toLong())
    }

    /**
     * Sosyal giriş: sağlayıcının doğruladığı e-postayla hesap eşleşir (doğrulanmış yapılır) veya kayıt açıksa
     * rastgele parolalı, doğrulanmış hesap açılır (parola sonradan sıfırlama e-postasıyla belirlenebilir).
     */
    fun oauthLogin(emailRaw: String): LoginResult {
        val email = email(JsonPrimitive(emailRaw))
        val existing = db.query("SELECT \"id\", \"verified\" FROM $table WHERE \"email\" = ?", listOf(email)).firstOrNull()
        val id = if (existing != null) {
            val id = (existing["id"] as Number).toLong()
            if (dialect.decode(FieldType.BOOLEAN, existing["verified"]) != true) update(id, JsonObject(mapOf("verified" to JsonPrimitive(true))), null)
            id
        } else {
            if (!users.allowSignup) throw ApiException(403, tr("Bu e-postayla hesap yok ve kayıt kapalı; hesabı yönetici açar"))
            val password = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val missing = users.extraFields.filter { it.required && it.default == null }
            if (missing.isNotEmpty()) throw ApiException(400, tr("Sosyal girişle hesap açılamıyor: zorunlu alanlar ({0}) var", missing.joinToString { it.name }))
            createUser(buildJsonObject {
                put("email", JsonPrimitive(email))
                put("password", JsonPrimitive(password))
                put("verified", JsonPrimitive(true))
            }, admin = true).id()
        }
        return complete(id)
    }

    /**
     * Yenileme token'ını kullanır ve aynı oturuma yenisini verir (oturum kimliği değişmez); kullanılmış veya süresi dolmuş
     * token 401. Aynı token'la eşzamanlı iki istekten yalnızca biri başarır.
     */
    fun refresh(refreshToken: String?): Tokens {
        val invalid = ApiException(401, tr("Yenileme token'ı geçersiz veya süresi dolmuş; yeniden giriş yapın"))
        val hash = refreshToken?.let(::sha256) ?: throw invalid
        return db.transaction {
            val row = db.query("SELECT \"id\", \"user\", \"expires_at\" FROM $sessions WHERE \"token_hash\" = ?", listOf(hash)).firstOrNull() ?: throw invalid
            val sessionId = (row["id"] as Number).toLong()
            if ((dialect.decode(FieldType.DATETIME, row["expires_at"]) as Instant).isBefore(clock())) {
                db.execute("DELETE FROM $sessions WHERE \"id\" = ?", listOf(sessionId))
                throw invalid
            }
            issue((dialect.decode(FieldType.RELATION, row["user"]) as Number).toLong(), sessionId to hash) ?: throw invalid
        }
    }

    fun logout(refreshToken: String?) {
        if (refreshToken == null) return
        val ids = db.query("SELECT \"id\" FROM $sessions WHERE \"token_hash\" = ?", listOf(sha256(refreshToken))).map { (it["id"] as Number).toLong() }
        db.execute("DELETE FROM $sessions WHERE \"token_hash\" = ?", listOf(sha256(refreshToken)))
        revoke(ids)
    }

    /** Girişte ve yenilemede oturumun cihazı (User-Agent) ve adresi; cihaz ilk girişteki kalır. */
    fun noteSession(refreshToken: String, device: String?, ip: String?) {
        db.execute(
            "UPDATE $sessions SET \"device\" = COALESCE(\"device\", ?), \"ip\" = ? WHERE \"token_hash\" = ?",
            listOf(device?.take(200), ip, sha256(refreshToken)),
        )
    }

    /** Kullanıcının açık oturumları (en yeni önce); [current] isteği yapan oturum. */
    fun sessions(userId: Long, current: Long? = null): JsonObject {
        val rows = db.query(
            "SELECT \"id\", \"device\", \"ip\", \"created_at\", \"last_used_at\" FROM $sessions WHERE \"user\" = ? AND " +
                "${dialect.comparable(FieldType.DATETIME, "\"expires_at\"")} > ${dialect.comparable(FieldType.DATETIME, "?")} ORDER BY \"id\" DESC",
            listOf(dialect.encode(FieldType.RELATION, userId), dialect.encode(FieldType.DATETIME, clock())),
        )
        return buildJsonObject {
            put("items", JsonArray(rows.map { r ->
                val id = (r["id"] as Number).toLong()
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("device", JsonPrimitive(r["device"]?.toString()))
                    put("ip", JsonPrimitive(r["ip"]?.toString()))
                    put("createdAt", JsonPrimitive(dialect.decode(FieldType.DATETIME, r["created_at"])?.toString()))
                    put("lastUsedAt", JsonPrimitive((dialect.decode(FieldType.DATETIME, r["last_used_at"]) ?: dialect.decode(FieldType.DATETIME, r["created_at"]))?.toString()))
                    put("current", JsonPrimitive(id == current))
                }
            }))
        }
    }

    /** Tek oturumu kapatır: yenileme token'ı silinir, erişim token'ı da hemen geçersiz olur. */
    fun closeSession(userId: Long, sessionId: Long) {
        val n = db.execute("DELETE FROM $sessions WHERE \"id\" = ? AND \"user\" = ?", listOf(sessionId, dialect.encode(FieldType.RELATION, userId)))
        if (n == 0) throw ApiException(404, tr("Oturum bulunamadı"))
        revoke(listOf(sessionId))
    }

    /** Kullanıcının oturumlarını kapatır ([except] hariç); kapatılan sayısı. */
    fun closeSessions(userId: Long, except: Long? = null): Int {
        val ids = db.query("SELECT \"id\" FROM $sessions WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, userId)))
            .map { (it["id"] as Number).toLong() }.filter { it != except }
        ids.forEach { db.execute("DELETE FROM $sessions WHERE \"id\" = ?", listOf(it)) }
        revoke(ids)
        return ids.size
    }

    /** Erişim token'ı geçerliyse, oturumu kapatılmamışsa ve kullanıcı hâlâ varsa kullanıcı; değilse null. */
    fun resolve(token: String): Caller.User? {
        val jwt = runCatching { verifier.verify(token) }.getOrNull() ?: return null
        val id = jwt.subject?.toLongOrNull() ?: return null
        val session = jwt.getClaim(SESSION_CLAIM).asLong()
        if (session != null && revoked().containsKey(session)) return null
        val row = db.query("SELECT \"role\" FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull() ?: return null
        val restricted = setupRequired(id)
        val role = row["role"]?.toString() ?: ROLE_USER
        val teams = if (!users.teams.enabled) emptySet() else {
            db.query("SELECT \"team\" FROM ${quoteIdent(SystemCollections.TEAM_MEMBERS)} WHERE \"user\" = ?", listOf(id))
                .mapNotNull { (it["team"] as? Number)?.toLong() }.toSet()
        }
        return Caller.User(id, role == ROLE_ADMIN && !restricted, restricted, role, teams, session)
    }

    fun me(id: Long): JsonObject = clean(records.get(id))

    /**
     * Kullanıcının kendi ek alanlarını ve parolasını değiştirmesi. Parola için `currentPassword` gerekir; parola değişince
     * [session] (değiştiren cihazın oturumu) dışındaki tüm oturumlar kapanır (diğer cihazlarda yeniden giriş).
     */
    fun updateMe(id: Long, bodyRaw: JsonObject, session: Long? = null): JsonObject {
        val body = withPhone(bodyRaw)
        checkKeys(body, extraNames + setOf("password", "currentPassword"))
        val newPassword = body["password"]?.let { password(it) }
        if (newPassword != null) {
            val current = (body["currentPassword"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val stored = db.query("SELECT \"password_hash\" FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull()
                ?: throw ApiException(404, tr("Kullanıcı bulunamadı"))
            if (current == null || !hasher.verify(current, stored["password_hash"] as String)) {
                throw ApiException(400, tr("Doğrulama hatası"), mapOf("currentPassword" to tr("mevcut parola hatalı")))
            }
        }
        return update(id, JsonObject(body.filterKeys { it in extraNames }), newPassword, keepSession = session)
    }

    // ---- e-posta ile doğrulama ve parola sıfırlama ----

    /** Tek amaçlı e-posta belirteçleri; erişim token'ı yerine geçemez (ayrı hedef), erişim token'ı da bunların yerine. */
    enum class Purpose(val claim: String, val ttlSeconds: Long) {
        VERIFY("verify", 24 * 3600L),
        RESET("reset", 3600L),
    }

    /** E-postaya ait hesap (kimlik, doğrulanmış mı); yoksa null. */
    fun findByEmail(emailRaw: String?): Pair<Long, Boolean>? {
        val email = emailRaw?.trim()?.lowercase(Locale.ROOT)?.takeIf { EMAIL.matches(it) } ?: return null
        val row = db.query("SELECT \"id\", \"verified\" FROM $table WHERE \"email\" = ?", listOf(email)).firstOrNull() ?: return null
        return (row["id"] as Number).toLong() to (dialect.decode(FieldType.BOOLEAN, row["verified"]) == true)
    }

    /**
     * Belirteç: kullanıcı, amaç, e-posta ve (sıfırlamada) parola özetinin parmak izi. E-posta değişince doğrulama,
     * parola değişince sıfırlama belirteci geçersizleşir; böylece sıfırlama bağlantısı tek kullanımlıktır.
     */
    fun purposeToken(userId: Long, purpose: Purpose): String {
        val (email, hash) = credentials(userId) ?: throw ApiException(404, tr("Kullanıcı bulunamadı"))
        val now = clock()
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(MAIL_AUDIENCE)
            .withSubject(userId.toString())
            .withClaim("purpose", purpose.claim)
            .withClaim("em", sha256(email).take(16))
            .withClaim("ph", if (purpose == Purpose.RESET) sha256(hash).take(16) else "")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(purpose.ttlSeconds)))
            .sign(algorithm)
    }

    /** Doğrulama bağlantısı: hesabı doğrulanmış yapar ve kullanıcıyı döndürür. */
    fun confirmVerification(token: String?): JsonObject {
        val id = checkPurpose(token, Purpose.VERIFY)
        return update(id, JsonObject(mapOf("verified" to JsonPrimitive(true))), null)
    }

    /** Parola sıfırlama: yeni parola yazılır, tüm oturumlar kapanır; e-postayı alan kişi adresin sahibidir (doğrulanır). */
    fun confirmReset(token: String?, password: String?) {
        val id = checkPurpose(token, Purpose.RESET)
        val newPassword = password(password?.let(::JsonPrimitive) ?: JsonNull)
        update(id, JsonObject(mapOf("verified" to JsonPrimitive(true))), newPassword)
    }

    private val mailVerifier by lazy { JWT.require(algorithm).withIssuer(issuer).withAudience(MAIL_AUDIENCE).build() }

    private fun checkPurpose(token: String?, purpose: Purpose): Long {
        val invalid = ApiException(400, tr("Bağlantının süresi dolmuş veya daha önce kullanılmış; yenisini isteyin"))
        val jwt = token?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { mailVerifier.verify(it) }.getOrNull() } ?: throw invalid
        if (jwt.getClaim("purpose").asString() != purpose.claim) throw invalid
        val id = jwt.subject?.toLongOrNull() ?: throw invalid
        val (email, hash) = credentials(id) ?: throw invalid
        if (jwt.getClaim("em").asString() != sha256(email).take(16)) throw invalid
        if (purpose == Purpose.RESET && jwt.getClaim("ph").asString() != sha256(hash).take(16)) throw invalid
        return id
    }

    private fun credentials(id: Long): Pair<String, String>? =
        db.query("SELECT \"email\", \"password_hash\" FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull()
            ?.let { it["email"] as String to it["password_hash"] as String }

    private fun isVerified(id: Long): Boolean =
        db.query("SELECT \"verified\" FROM $table WHERE \"id\" = ?", listOf(id)).firstOrNull()
            ?.let { dialect.decode(FieldType.BOOLEAN, it["verified"]) == true } == true

    // ---- yönetici ----

    fun list(page: Int, perPage: Int, email: String? = null): Page {
        val params = email?.takeIf { it.isNotBlank() }?.let { listOf("email[like]" to it.trim()) }.orEmpty()
        val p = records.list(page, perPage, "-created_at", params)
        val enrolled = if (twoFactor.enabled) enrolledAmong(p.items.map { it.id() }) else emptySet()
        return p.copy(items = p.items.map { u -> clean(u).let { if (twoFactor.enabled) JsonObject(it + ("twoFactor" to JsonPrimitive(u.id() in enrolled))) else it } })
    }

    /**
     * Yönetici hesap açar: `email`, `password`, isteğe bağlı `role` (user/admin), `verified` ve ek alanlar. [by] ve [ip]
     * denetim kaydı için (kim, nereden).
     */
    fun create(body: JsonObject, by: String = AuditLog.APP, ip: String? = null): JsonObject =
        createUser(body, admin = true).also { audit?.record(by, "user.create", it.str("email"), "rol: " + it.str("role"), ip) }

    /** Yönetici günceller: e-posta, parola (sıfırlama: tüm oturumlar kapanır), rol, doğrulanmış, ek alanlar. */
    fun adminUpdate(id: Long, bodyRaw: JsonObject, by: String = AuditLog.APP, ip: String? = null): JsonObject {
        val body = withPhone(bodyRaw)
        checkKeys(body, extraNames + setOf("email", "password", "role", "verified") + if (twoFactor.enabled) setOf("twoFactor") else emptySet())
        body["twoFactor"]?.let { v ->
            // Yönetici yalnızca kapatabilir (telefonunu kaybeden kullanıcı); kurmak kullanıcının işidir.
            if (verified(v)) throw ApiException(400, tr("Doğrulama hatası"), mapOf("twoFactor" to tr("yalnızca false (sıfırla) gönderilebilir")))
            db.execute("DELETE FROM $tfTable WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, id)))
        }
        val fields = body.filterKeys { it in extraNames }.toMutableMap()
        body["email"]?.let { fields["email"] = JsonPrimitive(email(it)) }
        body["role"]?.let { fields["role"] = JsonPrimitive(role(it)) }
        body["verified"]?.let { fields["verified"] = JsonPrimitive(verified(it)) }
        val out = update(id, JsonObject(fields), body["password"]?.let { password(it) })
        val detail = listOfNotNull(
            (fields["email"] as? JsonPrimitive)?.let { "e-posta: ${it.content}" },
            (fields["role"] as? JsonPrimitive)?.let { "rol: ${it.content}" },
            (fields["verified"] as? JsonPrimitive)?.let { tr("doğrulandı: {0}", it.content) },
            tr("parola sıfırlandı").takeIf { "password" in body },
            tr("iki adımlı doğrulama sıfırlandı").takeIf { "twoFactor" in body },
            (extraNames intersect body.keys).takeIf { it.isNotEmpty() }?.let { "alanlar: ${it.joinToString()}" },
        ).joinToString("; ")
        audit?.record(by, "user.update", out.str("email"), detail, ip)
        return out
    }

    /** Kullanıcıyı siler; oturumları ve sahip alanı ona bağlı kayıtlar `onDelete` davranışına göre. */
    fun delete(id: Long, by: String = AuditLog.APP, ip: String? = null) {
        val email = credentials(id)?.first
        records.delete(id)
        audit?.record(by, "user.delete", email ?: "#$id", null, ip)
    }

    /** Yönetici: kullanıcının tüm oturumlarını kapatır (denetim kaydına). */
    fun closeAllSessions(id: Long, by: String = AuditLog.APP, ip: String? = null) {
        val email = credentials(id)?.first ?: throw ApiException(404, tr("Kullanıcı bulunamadı"))
        val n = closeSessions(id)
        audit?.record(by, "user.sessions.close", email, tr("{0} oturum", n), ip)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    // ---- iki adımlı doğrulama ----

    private val twoFactor = users.twoFactor
    private val tfTable = quoteIdent(TWO_FACTOR)
    private val box by lazy { SecretBox(signingKey() + "two-factor".toByteArray()) }
    private val challengeVerifier by lazy { JWT.require(algorithm).withIssuer(issuer).withAudience(CHALLENGE_AUDIENCE).build() }

    private fun requireTwoFactor() {
        if (!twoFactor.enabled) throw ApiException(404, tr("İki adımlı doğrulama kapalı"))
    }

    private fun tfRow(userId: Long): Map<String, Any?>? = if (!twoFactor.enabled) null
    else db.query("SELECT * FROM $tfTable WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, userId))).firstOrNull()

    private fun confirmed(row: Map<String, Any?>) = dialect.decode(FieldType.BOOLEAN, row["confirmed"]) == true

    private fun recoveryHashes(row: Map<String, Any?>): List<String> =
        (row["recovery"] as String?)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() as? JsonArray }
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    fun hasTwoFactor(userId: Long): Boolean = tfRow(userId)?.let(::confirmed) == true

    private fun setupRequired(userId: Long) = twoFactor.enabled && twoFactor.required && !hasTwoFactor(userId)

    private fun enrolledAmong(ids: List<Long>): Set<Long> = if (ids.isEmpty()) emptySet() else
        db.query("SELECT \"user\", \"confirmed\" FROM $tfTable WHERE \"user\" IN (${ids.joinToString { "?" }})", ids.map { dialect.encode(FieldType.RELATION, it) })
            .filter(::confirmed).map { (it["user"] as Number).toLong() }.toSet()

    /** Birinci adım tamam: iki adımlı doğrulama kuruluysa ikinci adım isteği, değilse token çifti. */
    private fun complete(userId: Long): LoginResult {
        if (!hasTwoFactor(userId)) return issue(userId)
        val now = clock()
        val token = JWT.create().withIssuer(issuer).withAudience(CHALLENGE_AUDIENCE).withSubject(userId.toString())
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(CHALLENGE_SECONDS))).sign(algorithm)
        return Challenge(token, CHALLENGE_SECONDS)
    }

    /** İkinci adım: doğrulayıcı kodu veya yedek kod; doğruysa token çifti. Hatalı denemeler girişle aynı sınıra tabi. */
    fun verifyTwoFactor(client: String, challenge: String?, code: String?): Tokens {
        val id = challenge?.trim()?.let { runCatching { challengeVerifier.verify(it).subject?.toLongOrNull() }.getOrNull() }
            ?: throw ApiException(401, tr("Giriş adımının süresi dolmuş; yeniden giriş yapın"))
        val key = "2fa:$id"
        limiter.blockedFor(client, key)?.let { throw ApiException(429, tr("Çok fazla hatalı kod; {0} saniye sonra tekrar deneyin", it)) }
        if (!checkSecondFactor(id, code.orEmpty())) {
            limiter.failed(client, key)
            throw ApiException(401, tr("Kod hatalı"))
        }
        limiter.succeeded(client, key)
        return issue(id)
    }

    /** Doğrulayıcı kodu (aynı kod ikinci kez geçmez) veya yedek kod (tek kullanımlık). */
    private fun checkSecondFactor(userId: Long, code: String): Boolean = db.transaction {
        val row = tfRow(userId)?.takeIf(::confirmed) ?: return@transaction false
        val now = dialect.encode(FieldType.DATETIME, clock())
        val step = Totp.match(box.open(row["secret"] as String), code, clock(), (row["last_step"] as Number?)?.toLong())
        if (step != null) {
            db.execute("UPDATE $tfTable SET \"last_step\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", listOf(step, now, row["id"]))
            return@transaction true
        }
        val hash = sha256(normalizeRecovery(code))
        val hashes = recoveryHashes(row)
        if (hash !in hashes) return@transaction false
        db.execute(
            "UPDATE $tfTable SET \"recovery\" = ?, \"updated_at\" = ? WHERE \"id\" = ?",
            listOf(JsonArray((hashes - hash).map(::JsonPrimitive)).toString(), now, row["id"]),
        )
        true
    }

    private fun normalizeRecovery(code: String) = code.uppercase(Locale.ROOT).filter { it in RECOVERY_ALPHABET }

    /** 10 yedek kod (`ABCDE-FGHIJ`); yalnızca özetleri saklanır. */
    private fun newRecoveryCodes(): Pair<List<String>, String> {
        val codes = (1..10).map { (1..10).map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("").chunked(5).joinToString("-") }
        return codes to JsonArray(codes.map { JsonPrimitive(sha256(normalizeRecovery(it))) }).toString()
    }

    fun twoFactorStatus(userId: Long): JsonObject {
        requireTwoFactor()
        val row = tfRow(userId)
        return buildJsonObject {
            put("enabled", JsonPrimitive(row?.let(::confirmed) == true))
            put("required", JsonPrimitive(twoFactor.required))
            put("recoveryCodesLeft", JsonPrimitive(row?.takeIf(::confirmed)?.let { recoveryHashes(it).size } ?: 0))
        }
    }

    /** Yeni gizli anahtar (onaylanana kadar geçerli değil): `{secret, uri}`; uri doğrulayıcı uygulamaya ekler. */
    fun twoFactorSetup(userId: Long): JsonObject {
        requireTwoFactor()
        val (email, _) = credentials(userId) ?: throw ApiException(404, tr("Kullanıcı bulunamadı"))
        if (hasTwoFactor(userId)) throw ApiException(409, tr("İki adımlı doğrulama zaten açık; yeniden kurmak için önce kapatın"))
        val secret = Totp.newSecret(random)
        val now = dialect.encode(FieldType.DATETIME, clock())
        db.transaction {
            db.execute("DELETE FROM $tfTable WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, userId)))
            db.insert(
                dialect.insertReturningId(TWO_FACTOR, listOf("user", "secret", "confirmed", "created_at", "updated_at")),
                listOf(dialect.encode(FieldType.RELATION, userId), box.seal(secret), dialect.encode(FieldType.BOOLEAN, false), now, now),
            )
        }
        return buildJsonObject {
            put("secret", JsonPrimitive(secret))
            put("uri", JsonPrimitive(Totp.uri(secret, email, twoFactor.issuer?.takeIf { it.isNotBlank() } ?: serverName)))
        }
    }

    /** Kurulumu doğrulayıcı kodla onaylar; yedek kodlar bir kez gösterilir: `{recoveryCodes}`. */
    fun twoFactorEnable(userId: Long, code: String?): JsonObject {
        requireTwoFactor()
        val row = tfRow(userId) ?: throw ApiException(400, tr("Önce /auth/2fa/setup ile anahtar alın"))
        if (confirmed(row)) throw ApiException(409, tr("İki adımlı doğrulama zaten açık"))
        val step = Totp.match(box.open(row["secret"] as String), code.orEmpty(), clock())
            ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("code" to tr("kod hatalı; doğrulayıcı uygulamadaki güncel kodu girin")))
        val (codes, hashes) = newRecoveryCodes()
        db.execute(
            "UPDATE $tfTable SET \"confirmed\" = ?, \"last_step\" = ?, \"recovery\" = ?, \"updated_at\" = ? WHERE \"id\" = ?",
            listOf(dialect.encode(FieldType.BOOLEAN, true), step, hashes, dialect.encode(FieldType.DATETIME, clock()), row["id"]),
        )
        return buildJsonObject { put("recoveryCodes", JsonArray(codes.map(::JsonPrimitive))) }
    }

    /** Kapatır (doğrulayıcı veya yedek kodla); zorunluysa kapatılamaz. */
    fun twoFactorDisable(userId: Long, code: String?) {
        requireTwoFactor()
        if (twoFactor.required) throw ApiException(403, tr("İki adımlı doğrulama bu sunucuda zorunlu; kapatılamaz"))
        if (!checkSecondFactor(userId, code.orEmpty())) throw ApiException(400, tr("Doğrulama hatası"), mapOf("code" to tr("kod hatalı")))
        db.execute("DELETE FROM $tfTable WHERE \"user\" = ?", listOf(dialect.encode(FieldType.RELATION, userId)))
    }

    /** Yeni yedek kodlar (eskiler geçersizleşir). */
    fun regenerateRecoveryCodes(userId: Long, code: String?): JsonObject {
        requireTwoFactor()
        if (!checkSecondFactor(userId, code.orEmpty())) throw ApiException(400, tr("Doğrulama hatası"), mapOf("code" to tr("kod hatalı")))
        val (codes, hashes) = newRecoveryCodes()
        db.execute(
            "UPDATE $tfTable SET \"recovery\" = ?, \"updated_at\" = ? WHERE \"user\" = ?",
            listOf(hashes, dialect.encode(FieldType.DATETIME, clock()), dialect.encode(FieldType.RELATION, userId)),
        )
        return buildJsonObject { put("recoveryCodes", JsonArray(codes.map(::JsonPrimitive))) }
    }

    // ---- e-postayla kodla giriş ----

    /**
     * Giriş kodu üretir (8 hane, 10 dakika, en çok 5 deneme; kullanıcı başına tek geçerli kod). Hesap yoksa veya özellik
     * kapalıysa null (çağıran yine aynı yanıtı verir; adresin kayıtlı olduğu sızmaz).
     */
    fun newLoginCode(emailRaw: String?): Pair<String, String>? {
        if (!users.emailLogin) return null
        val email = emailRaw?.trim()?.lowercase(Locale.ROOT) ?: return null
        val (id, _) = findByEmail(email) ?: return null
        val code = (1..8).map { random.nextInt(10) }.joinToString("")
        metaPut("login_code:$id", "${sha256(code)}:${clock().plusSeconds(600).epochSecond}:0")
        return email to code
    }

    /** E-postadaki kodla giriş; doğruysa e-posta doğrulanmış sayılır. */
    fun loginWithCode(client: String, emailRaw: String?, code: String?): LoginResult {
        if (!users.emailLogin) throw ApiException(404, tr("E-postayla giriş kapalı"))
        val email = emailRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val key = "code:$email"
        limiter.blockedFor(client, key)?.let { throw ApiException(429, tr("Çok fazla hatalı deneme; {0} saniye sonra tekrar deneyin", it)) }
        val invalid = ApiException(401, tr("Kod hatalı veya süresi dolmuş; yeni kod isteyin"))
        val id = findByEmail(email)?.first
        val stored = id?.let { metaGet("login_code:$it") }?.split(':')
        val ok = stored != null && stored.size == 3 && (stored[1].toLongOrNull() ?: 0) >= clock().epochSecond &&
            (stored[2].toIntOrNull() ?: 99) < 5 && MessageDigest.isEqual(stored[0].toByteArray(), sha256(code?.trim().orEmpty()).toByteArray())
        if (!ok || id == null) {
            limiter.failed(client, key)
            if (id != null && stored != null && stored.size == 3) metaPut("login_code:$id", "${stored[0]}:${stored[1]}:${(stored[2].toIntOrNull() ?: 0) + 1}")
            throw invalid
        }
        limiter.succeeded(client, key)
        metaDelete("login_code:$id")
        if (!isVerified(id)) update(id, JsonObject(mapOf("verified" to JsonPrimitive(true))), null)
        return complete(id)
    }

    // ---- SMS ile kodla giriş ----

    /** SMS girişi açıkken telefon alanı uluslararası biçime getirilir (boşluk, tire atılır); geçersizse 400. */
    private fun withPhone(body: JsonObject): JsonObject {
        if (!users.smsLogin) return body
        val v = body[users.phoneField] as? JsonPrimitive ?: return body
        if (!v.isString || v.content.isBlank()) return body
        val number = SmsGuard.normalize(v.content)
            ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf(users.phoneField to tr("uluslararası biçimde olmalı, örn. +905551234567")))
        return JsonObject(body + (users.phoneField to JsonPrimitive(number)))
    }

    /** Numaraya ait hesabın kimliği; yoksa null. */
    fun findByPhone(number: String): Long? {
        if (!users.smsLogin) return null
        return db.query("SELECT \"id\" FROM $table WHERE ${quoteIdent(users.phoneField)} = ?", listOf(number)).firstOrNull()?.let { (it["id"] as Number).toLong() }
    }

    /** SMS kodu üretir (6 hane, 10 dakika, en çok 5 deneme; hesap başına tek geçerli kod). Hesap yoksa null. */
    fun newSmsCode(number: String): String? {
        val id = findByPhone(number) ?: return null
        val code = (1..6).map { random.nextInt(10) }.joinToString("")
        metaPut("sms_code:$id", "${sha256(code)}:${clock().plusSeconds(600).epochSecond}:0")
        return code
    }

    /**
     * Passkey ile giriş (imza [PasskeyService] ile doğrulandı). Passkey cihaz ve kilit ekranıyla iki etkenli olduğu
     * için iki adımlı doğrulamanın ikinci adımı istenmez.
     */
    fun loginWithPasskey(userId: Long): Tokens = issue(userId)

    /** SMS'le gelen kodla giriş; iki adımlı doğrulama kuruluysa ikinci adım istenir. */
    fun loginWithSms(client: String, phoneRaw: String?, code: String?): LoginResult {
        if (!users.smsLogin) throw ApiException(404, tr("SMS ile giriş kapalı"))
        val number = SmsGuard.normalize(phoneRaw).orEmpty()
        val key = "sms:$number"
        limiter.blockedFor(client, key)?.let { throw ApiException(429, tr("Çok fazla hatalı deneme; {0} saniye sonra tekrar deneyin", it)) }
        val id = number.takeIf { it.isNotEmpty() }?.let(::findByPhone)
        val stored = id?.let { metaGet("sms_code:$it") }?.split(':')
        val ok = stored != null && stored.size == 3 && (stored[1].toLongOrNull() ?: 0) >= clock().epochSecond &&
            (stored[2].toIntOrNull() ?: 99) < 5 && MessageDigest.isEqual(stored[0].toByteArray(), sha256(code?.trim().orEmpty()).toByteArray())
        if (!ok || id == null) {
            limiter.failed(client, key)
            if (id != null && stored != null && stored.size == 3) metaPut("sms_code:$id", "${stored[0]}:${stored[1]}:${(stored[2].toIntOrNull() ?: 0) + 1}")
            throw ApiException(401, tr("Kod hatalı veya süresi dolmuş; yeni kod isteyin"))
        }
        limiter.succeeded(client, key)
        metaDelete("sms_code:$id")
        return complete(id)
    }

    private val meta by lazy { signingKey(); quoteIdent(SchemaMigrator.META_TABLE) }

    private fun metaGet(key: String): String? =
        db.query("SELECT \"value\" FROM $meta WHERE \"key\" = ?", listOf(key)).firstOrNull()?.get("value") as String?

    private fun metaPut(key: String, value: String) = db.transaction {
        db.execute("DELETE FROM $meta WHERE \"key\" = ?", listOf(key))
        db.execute("INSERT INTO $meta (\"key\", \"value\") VALUES (?, ?)", listOf(key, value))
    }

    private fun metaDelete(key: String) = db.execute("DELETE FROM $meta WHERE \"key\" = ?", listOf(key))

    // ---- iç işler ----

    private val table = quoteIdent(USERS)
    private val sessions = quoteIdent(SESSIONS)

    private fun createUser(bodyRaw: JsonObject, admin: Boolean): JsonObject {
        val body = withPhone(bodyRaw)
        checkKeys(body, extraNames + setOf("email", "password") + if (admin) setOf("role", "verified") else emptySet())
        val errors = linkedMapOf<String, String>()
        val email = capture(errors, "email") { email(body["email"] ?: JsonNull) }
        val password = capture(errors, "password") { password(body["password"] ?: JsonNull) }
        val role = if (admin) capture(errors, "role") { body["role"]?.let(::role) ?: ROLE_USER } else ROLE_USER
        val verified = if (admin) capture(errors, "verified") { body["verified"]?.let(::verified) ?: false } else false
        if (errors.isNotEmpty()) throw ApiException(400, tr("Doğrulama hatası"), errors)

        val hash = hasher.hash(password!!) // yavaş: işlem dışında
        return db.transaction {
            emailTaken(email!!, except = null)
            val values = body.filterKeys { it in extraNames } + mapOf(
                "email" to JsonPrimitive(email),
                "password_hash" to JsonPrimitive(hash),
                "role" to JsonPrimitive(role),
                "verified" to JsonPrimitive(verified),
            )
            clean(records.create(JsonObject(values)))
        }
    }

    private fun update(id: Long, fields: JsonObject, newPassword: String?, keepSession: Long? = null): JsonObject {
        val hash = newPassword?.let(hasher::hash)
        return db.transaction {
            (fields["email"] as? JsonPrimitive)?.let { emailTaken(it.content, except = id) }
            val values = hash?.let { JsonObject(fields + ("password_hash" to JsonPrimitive(it))) } ?: fields
            val out = clean(records.update(id, values))
            if (hash != null) closeSessions(id, except = keepSession)
            out
        }
    }

    private fun emailTaken(email: String, except: Long?) {
        val other = db.query("SELECT \"id\" FROM $table WHERE \"email\" = ?", listOf(email)).firstOrNull()
        if (other != null && (other["id"] as Number).toLong() != except) throw ApiException(409, tr("Bu e-posta adresiyle bir hesap var"))
    }

    private fun issue(userId: Long): Tokens = issue(userId, null)!!

    /**
     * Token çifti. [rotate] (oturum kimliği, eski yenileme token'ının özeti) verilirse yeni oturum açılmaz, o oturumun
     * yenileme token'ı değiştirilir; eski token o arada başka istekte kullanıldıysa null.
     */
    private fun issue(userId: Long, rotate: Pair<Long, String>?): Tokens? {
        val raw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val now = clock()
        val nowSql = dialect.encode(FieldType.DATETIME, now)
        val expires = dialect.encode(FieldType.DATETIME, now.plusSeconds(users.refreshTokenDays * 86_400L))
        val sessionId = if (rotate != null) {
            val n = db.execute(
                "UPDATE $sessions SET \"token_hash\" = ?, \"expires_at\" = ?, \"last_used_at\" = ?, \"updated_at\" = ? WHERE \"id\" = ? AND \"token_hash\" = ?",
                listOf(sha256(raw), expires, nowSql, nowSql, rotate.first, rotate.second),
            )
            if (n == 0) return null
            rotate.first
        } else {
            db.insert(
                dialect.insertReturningId(SESSIONS, listOf("token_hash", "user", "expires_at", "last_used_at", "created_at", "updated_at")),
                listOf(sha256(raw), dialect.encode(FieldType.RELATION, userId), expires, nowSql, nowSql, nowSql),
            )
        }
        val ttl = users.accessTokenMinutes * 60L
        val token = JWT.create()
            .withIssuer(issuer)
            .withAudience(AUDIENCE)
            .withSubject(userId.toString())
            .withClaim(SESSION_CLAIM, sessionId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(ttl)))
            .sign(algorithm)
        return Tokens(token, raw, ttl, me(userId), setupRequired = setupRequired(userId))
    }

    /**
     * Kapatılan oturumlar → onların erişim token'larının en geç süresinin dolacağı an (epoch sn). Erişim token'ları
     * durumsuz olduğu için oturum kapatılınca bu listeye girer; `_ds_meta`'da kalıcı, süresi geçenler atılır. Kopyada ve
     * devralmada token'lar oturum tablosuna bakılmadan geçerli kalsın diye "oturum var mı" yerine bu liste tutulur.
     */
    private val revokedMap: ConcurrentHashMap<Long, Long> by lazy {
        ConcurrentHashMap<Long, Long>().also { map ->
            metaGet(REVOKED_KEY)?.split(',')?.forEach { e ->
                val parts = e.split(':')
                if (parts.size == 2) parts[0].toLongOrNull()?.let { id -> parts[1].toLongOrNull()?.let { map[id] = it } }
            }
        }
    }

    private fun revoked(): Map<Long, Long> = revokedMap

    private fun revoke(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = clock().epochSecond
        revokedMap.entries.removeIf { it.value < now }
        ids.forEach { revokedMap[it] = now + users.accessTokenMinutes * 60L }
        metaPut(REVOKED_KEY, revokedMap.entries.joinToString(",") { "${it.key}:${it.value}" })
    }

    /** Toplu e-postadaki "abonelikten çık" bağlantısının belirteci: adrese bağlı, sunucunun anahtarıyla imzalı. */
    fun unsubscribeToken(email: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey() + "unsubscribe".toByteArray(), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(email.trim().lowercase().toByteArray())).take(32)
    }

    /** Adresi toplu e-postalardan çıkarır; belirteç yanlışsa 400. */
    fun unsubscribe(email: String?, token: String?) {
        val address = email?.trim()?.lowercase().orEmpty()
        if (address.isEmpty() || token == null || !MessageDigest.isEqual(unsubscribeToken(address).toByteArray(), token.toByteArray())) {
            throw ApiException(400, tr("Bağlantı geçersiz"))
        }
        val t = quoteIdent(SystemCollections.UNSUBSCRIBED)
        if (db.query("SELECT \"id\" FROM $t WHERE \"email\" = ?", listOf(address)).isEmpty()) {
            val now = db.dialect.encode(FieldType.DATETIME, Instant.now())
            db.execute("INSERT INTO $t (\"email\", \"created_at\", \"updated_at\") VALUES (?, ?, ?)", listOf(address, now, now))
        }
    }

    /** İmza anahtarı `_ds_meta`'da; ilk kullanımda üretilir. */
    private fun signingKey(): ByteArray = db.transaction {
        val meta = quoteIdent(SchemaMigrator.META_TABLE)
        db.execute("""CREATE TABLE IF NOT EXISTS $meta ("key" TEXT PRIMARY KEY, "value" TEXT NOT NULL)""")
        val stored = db.query("SELECT \"value\" FROM $meta WHERE \"key\" = ?", listOf(KEY_NAME)).firstOrNull()?.get("value") as String?
        val key = stored ?: Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes)).also {
            db.execute("INSERT INTO $meta (\"key\", \"value\") VALUES (?, ?)", listOf(KEY_NAME, it))
        }
        Base64.getDecoder().decode(key)
    }

    private fun checkKeys(body: JsonObject, allowed: Set<String>) {
        val unknown = body.keys.filter { it !in allowed }
        if (unknown.isNotEmpty()) throw ApiException(400, tr("Doğrulama hatası"), unknown.associateWith { tr("bilinmeyen alan") })
    }

    private inline fun <T> capture(errors: MutableMap<String, String>, key: String, block: () -> T): T? = try {
        block()
    } catch (e: ApiException) {
        errors[key] = e.details[key] ?: e.message.orEmpty()
        null
    }

    private fun string(key: String, v: JsonElement): String = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf(key to if (v is JsonNull) tr("zorunlu alan") else tr("metin bekleniyor")))

    private fun email(v: JsonElement): String {
        val email = string("email", v).trim().lowercase(Locale.ROOT)
        if (email.length > 254 || !EMAIL.matches(email)) throw ApiException(400, tr("Doğrulama hatası"), mapOf("email" to tr("geçerli bir e-posta adresi girin")))
        return email
    }

    private fun password(v: JsonElement): String {
        val p = string("password", v)
        if (p.length < users.minPasswordLength) {
            throw ApiException(400, tr("Doğrulama hatası"), mapOf("password" to tr("en az {0} karakter olmalı", users.minPasswordLength)))
        }
        if (p.length > 1024) throw ApiException(400, tr("Doğrulama hatası"), mapOf("password" to tr("en çok 1024 karakter olabilir")))
        return p
    }

    private fun role(v: JsonElement): String = string("role", v).also {
        val custom = users.roles.map { r -> r.name }
        if (it != ROLE_USER && it != ROLE_ADMIN && it !in custom) {
            throw ApiException(400, tr("Doğrulama hatası"), mapOf("role" to tr("şunlardan biri olmalı: {0}", (listOf(ROLE_USER, ROLE_ADMIN) + custom).joinToString())))
        }
    }

    private fun verified(v: JsonElement): Boolean = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: throw ApiException(400, tr("Doğrulama hatası"), mapOf("verified" to tr("true/false bekleniyor")))

    private fun clean(user: JsonObject) = JsonObject(user - "password_hash")

    private fun JsonObject.id(): Long = (this["id"] as JsonPrimitive).content.toLong()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Kullanıcı token'larının hedefi; yönetici JWT'lerinden ayrı. */
        const val AUDIENCE = "droidserver-users"
        /** E-posta belirteçlerinin hedefi (doğrulama, sıfırlama). */
        const val MAIL_AUDIENCE = "droidserver-mail"
        /** İkinci adım belirtecinin hedefi. */
        const val CHALLENGE_AUDIENCE = "droidserver-2fa"
        private const val CHALLENGE_SECONDS = 300L
        private const val SESSION_CLAIM = "sid"
        private const val REVOKED_KEY = "revoked_sessions"
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val KEY_NAME = "auth:token_key"
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
