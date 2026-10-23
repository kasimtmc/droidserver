package dev.droidserver.core.auth

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.PasskeysConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Passkey kaydı, listesi ve girişi. [table] kullanıcıların (`_passkeys`, sahip sütunu [ownerColumn] = `user`) veya web
 * panelinin (`_panel_passkeys`, sahip yok) tablosudur. Tarayıcıya `navigator.credentials.create/get` için seçenekler
 * verilir; yanıt [WebAuthn] ile doğrulanır. Challenge'lar bellekte durur: 5 dakika geçerli, tek kullanımlık.
 */
class PasskeyService(
    private val db: SqlDriver,
    config: PasskeysConfig,
    private val rpName: String,
    private val table: String,
    private val ownerColumn: String?,
    private val clock: () -> Instant = Instant::now,
) {
    private val t = quoteIdent(table)
    private val rpId = config.rpId.trim().lowercase()
    private val dialect = db.dialect

    private class Pending(val create: Boolean, val owner: Long?, val expires: Instant)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** `navigator.credentials.create({ publicKey })` seçenekleri; var olan anahtarlar yeniden eklenmesin diye dışlanır. */
    fun registrationOptions(owner: Long?, userName: String, displayName: String): JsonObject = buildJsonObject {
        put("challenge", JsonPrimitive(newChallenge(create = true, owner)))
        put("rp", buildJsonObject { put("id", JsonPrimitive(rpId)); put("name", JsonPrimitive(rpName)) })
        put("user", buildJsonObject {
            put("id", JsonPrimitive(WebAuthn.b64url((owner ?: 0L).toString().toByteArray())))
            put("name", JsonPrimitive(userName))
            put("displayName", JsonPrimitive(displayName))
        })
        put("pubKeyCredParams", JsonArray(WebAuthn.pubKeyCredParams()))
        put("timeout", JsonPrimitive(TIMEOUT.toMillis()))
        put("attestation", JsonPrimitive("none"))
        put("authenticatorSelection", buildJsonObject {
            put("residentKey", JsonPrimitive("preferred"))
            put("userVerification", JsonPrimitive("preferred"))
        })
        put("excludeCredentials", descriptors(owner))
    }

    /** `{ name?, credential: { id, response: { clientDataJSON, attestationObject } } }` → eklenen anahtar. */
    fun register(owner: Long?, body: JsonObject): JsonObject {
        val response = response(body)
        val clientData = WebAuthn.fromB64url(response.text("clientDataJSON"))
        val challenge = take(clientData, create = true, owner)
        val reg = WebAuthn.verifyRegistration(clientData, WebAuthn.fromB64url(response.text("attestationObject")), challenge, rpId)
        val credentialId = WebAuthn.b64url(reg.credentialId)
        if (db.query("SELECT 1 AS x FROM $t WHERE \"credential_id\" = ?", listOf(credentialId)).isNotEmpty()) {
            throw ApiException(409, tr("Bu passkey zaten kayıtlı"))
        }
        val count = count(owner)
        if (count >= MAX_PER_OWNER) throw ApiException(409, tr("En çok {0} passkey eklenebilir; önce birini silin", MAX_PER_OWNER))
        val name = (body["name"] as? JsonPrimitive)?.content?.trim()?.take(60)?.ifEmpty { null } ?: tr("Passkey {0}", count + 1)
        val now = dialect.encode(FieldType.DATETIME, clock())
        val cols = listOfNotNull(ownerColumn, "credential_id", "public_key", "algorithm", "sign_count", "name", "created_at", "updated_at")
        val values = listOfNotNull(
            ownerColumn?.let { dialect.encode(FieldType.RELATION, owner) },
            credentialId, WebAuthn.b64url(reg.publicKey), reg.algorithm, reg.signCount, name, now, now,
        )
        val id = db.insert(dialect.insertReturningId(table, cols), values)
        return item(rows(owner, id).single())
    }

    fun list(owner: Long?): JsonObject = buildJsonObject { put("items", JsonArray(rows(owner).map(::item))) }

    fun delete(owner: Long?, id: Long) {
        val (where, args) = ownerFilter(owner)
        if (db.execute("DELETE FROM $t WHERE \"id\" = ?$where", listOf<Any?>(id) + args) == 0) throw ApiException(404, tr("Passkey bulunamadı"))
    }

    /**
     * `navigator.credentials.get({ publicKey })` seçenekleri. [owner] verilirse yalnızca onun anahtarları istenir;
     * null'da tarayıcı cihazdaki passkey'leri kendisi önerir (kullanıcı adı yazmadan giriş).
     */
    fun loginOptions(owner: Long?): JsonObject = buildJsonObject {
        put("challenge", JsonPrimitive(newChallenge(create = false, owner = null)))
        put("rpId", JsonPrimitive(rpId))
        put("timeout", JsonPrimitive(TIMEOUT.toMillis()))
        put("userVerification", JsonPrimitive("preferred"))
        put("allowCredentials", if (owner != null) descriptors(owner) else JsonArray(emptyList()))
    }

    /**
     * `{ credential: { id, response: { clientDataJSON, authenticatorData, signature } } }` doğrulanır; anahtarın sahibi
     * (panelde 0). Sayaç ve son kullanım güncellenir.
     */
    fun login(body: JsonObject): Long {
        val credential = body["credential"] as? JsonObject ?: throw invalid()
        val response = response(body)
        val credentialId = (credential["id"] as? JsonPrimitive)?.content ?: throw invalid()
        val row = db.query("SELECT * FROM $t WHERE \"credential_id\" = ?", listOf(WebAuthn.b64url(WebAuthn.fromB64url(credentialId)))).firstOrNull()
            ?: throw ApiException(401, tr("Bu passkey bu sunucuda kayıtlı değil"))
        val clientData = WebAuthn.fromB64url(response.text("clientDataJSON"))
        val challenge = take(clientData, create = false, owner = null)
        val stored = (dialect.decode(FieldType.INTEGER, row["sign_count"]) as Long?) ?: 0L
        val result = WebAuthn.verifyAssertion(
            clientData, WebAuthn.fromB64url(response.text("authenticatorData")), WebAuthn.fromB64url(response.text("signature")),
            challenge, rpId, WebAuthn.fromB64url(row["public_key"].toString()), dialect.decode(FieldType.INTEGER, row["algorithm"]) as Long, stored,
        )
        val now = dialect.encode(FieldType.DATETIME, clock())
        db.execute("UPDATE $t SET \"sign_count\" = ?, \"last_used_at\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", listOf(result.signCount, now, now, row["id"]))
        return ownerColumn?.let { dialect.decode(FieldType.RELATION, row[it]) as Long } ?: 0L
    }

    /** Sahibin anahtarı var mı (girişte seçeneği göstermek için). */
    fun any(owner: Long?): Boolean = count(owner) > 0

    private fun newChallenge(create: Boolean, owner: Long?): String {
        val now = clock()
        pending.entries.removeIf { it.value.expires.isBefore(now) }
        // Kimliksiz giriş seçenekleri bellekte birikmesin.
        if (pending.size >= MAX_PENDING) throw ApiException(429, tr("Çok fazla passkey isteği; biraz sonra yeniden deneyin"))
        return WebAuthn.challenge().also { pending[it] = Pending(create, owner, now.plus(TIMEOUT)) }
    }

    /** İstemcinin imzaladığı challenge'ı bulur ve tüketir; süresi dolmuş, başka amaçlı veya başkasınınsa 401. */
    private fun take(clientData: ByteArray, create: Boolean, owner: Long?): String {
        val challenge = WebAuthn.challengeOf(clientData) ?: throw invalid()
        val p = pending.remove(challenge)
        if (p == null || p.create != create || p.owner != owner || p.expires.isBefore(clock())) {
            throw ApiException(401, tr("Passkey isteğinin süresi doldu veya geçersiz; yeniden deneyin"))
        }
        return challenge
    }

    private fun descriptors(owner: Long?) = JsonArray(rows(owner).map { r ->
        buildJsonObject { put("type", JsonPrimitive("public-key")); put("id", JsonPrimitive(r["credential_id"].toString())) }
    })

    private fun ownerFilter(owner: Long?): Pair<String, List<Any?>> =
        if (ownerColumn == null) "" to emptyList() else " AND ${quoteIdent(ownerColumn)} = ?" to listOf(dialect.encode(FieldType.RELATION, owner))

    private fun rows(owner: Long?, id: Long? = null): List<Map<String, Any?>> {
        val (where, args) = ownerFilter(owner)
        val byId = if (id != null) " AND \"id\" = ?" else ""
        return db.query("SELECT * FROM $t WHERE 1 = 1$where$byId ORDER BY \"id\"", args + listOfNotNull(id))
    }

    private fun count(owner: Long?): Int = rows(owner).size

    private fun item(r: Map<String, Any?>) = buildJsonObject {
        put("id", JsonPrimitive(dialect.decode(FieldType.INTEGER, r["id"]) as Long))
        put("name", JsonPrimitive(r["name"]?.toString()))
        put("createdAt", JsonPrimitive(dialect.decode(FieldType.DATETIME, r["created_at"])?.toString()))
        put("lastUsedAt", dialect.decode(FieldType.DATETIME, r["last_used_at"])?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun response(body: JsonObject): JsonObject =
        ((body["credential"] as? JsonObject)?.get("response") as? JsonObject) ?: throw invalid()

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content ?: throw invalid()

    private fun invalid() = ApiException(400, tr("Passkey yanıtı geçersiz"))

    companion object {
        val TIMEOUT: Duration = Duration.ofMinutes(5)
        const val MAX_PER_OWNER = 10
        private const val MAX_PENDING = 1_000
    }
}
