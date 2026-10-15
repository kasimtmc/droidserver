package dev.droidserver.core.auth

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.SystemCollections.TEAMS
import dev.droidserver.core.config.SystemCollections.TEAM_MEMBER
import dev.droidserver.core.config.SystemCollections.TEAM_MEMBERS
import dev.droidserver.core.config.SystemCollections.TEAM_OWNER
import dev.droidserver.core.config.SystemCollections.USERS
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Locale

/**
 * Takımlar ve üyelikleri. Kullanıcı yalnızca üyesi olduğu takımları görür; üye eklemek, çıkarmak, rol vermek, adı
 * değiştirmek ve silmek takım sahibinin (`owner`) işidir. Her üye kendisi ayrılabilir; takımın son sahibi ayrılamaz.
 * Yönetici (API anahtarı, yönetici kullanıcı) tüm takımları yönetir. Takım silinince takım alanı ona bağlı kayıtlar
 * alanın `onDelete` davranışına göre silinir, boşalır veya silme reddedilir.
 */
class TeamService(private val db: SqlDriver, config: ServerConfig, private val clock: () -> Instant = Instant::now) {
    private val settings = config.auth.users.teams
    private val teamRecords = RecordService(db, SystemCollections.teams, clock, config.schemaCollections())
    private val memberRecords = RecordService(db, SystemCollections.teamMembers, clock, config.schemaCollections())
    private val teams = quoteIdent(TEAMS)
    private val members = quoteIdent(TEAM_MEMBERS)
    private val users = quoteIdent(USERS)

    /** Kullanıcının takımları (yöneticide hepsi): kimlik, ad, kullanıcının rolü, üye sayısı. */
    fun list(caller: Caller): JsonObject {
        requireUserOrAdmin(caller)
        val rows = when (caller) {
            is Caller.User -> db.query(
                "SELECT t.\"id\", t.\"name\", m.\"role\" FROM $members m JOIN $teams t ON t.\"id\" = m.\"team\" WHERE m.\"user\" = ? ORDER BY t.\"name\", t.\"id\"",
                listOf(caller.id),
            )
            else -> db.query("SELECT \"id\", \"name\" FROM $teams ORDER BY \"name\", \"id\"")
        }
        return buildJsonObject {
            put("items", JsonArray(rows.map { r -> summary(id(r["id"]), r["name"].toString(), r["role"]?.toString()) }))
        }
    }

    fun create(caller: Caller, name: String?): JsonObject {
        requireUserOrAdmin(caller)
        val clean = name(name)
        val user = (caller as? Caller.User)?.id
        if (!caller.isAdmin && !settings.allowCreate) throw ApiException(403, tr("Takımları yalnızca yönetici kurabilir"))
        return db.transaction {
            if (user != null) requireTeamRoom(user)
            val id = id(teamRecords.create(buildJsonObject {
                put("name", JsonPrimitive(clean))
                user?.let { put("created_by", JsonPrimitive(it)) }
            })["id"])
            if (user != null) addRow(id, user, TEAM_OWNER)
            get(caller, id)
        }
    }

    /** Takım ve üyeleri; üye olmayana yokmuş gibi 404. */
    fun get(caller: Caller, id: Long): JsonObject {
        val role = roleOf(caller, id)
        val name = db.query("SELECT \"name\" FROM $teams WHERE \"id\" = ?", listOf(id)).firstOrNull()?.get("name")?.toString()
            ?: throw notFound()
        val list = db.query(
            "SELECT m.\"user\", m.\"role\", u.\"email\" FROM $members m JOIN $users u ON u.\"id\" = m.\"user\" WHERE m.\"team\" = ? ORDER BY m.\"id\"",
            listOf(id),
        )
        return JsonObject(
            summary(id, name, role, list.size) + ("members" to JsonArray(list.map { r ->
                buildJsonObject {
                    put("user", JsonPrimitive(id(r["user"])))
                    put("email", JsonPrimitive(r["email"]?.toString()))
                    put("role", JsonPrimitive(r["role"].toString()))
                }
            })),
        )
    }

    fun rename(caller: Caller, id: Long, name: String?): JsonObject {
        requireOwner(caller, id)
        teamRecords.update(id, buildJsonObject { put("name", JsonPrimitive(name(name))) })
        return get(caller, id)
    }

    fun delete(caller: Caller, id: Long) {
        requireOwner(caller, id)
        teamRecords.delete(id)
    }

    /** Üye ekler: [email] veya [user] kimliği; [role] `owner` ya da `member` (varsayılan). */
    fun addMember(caller: Caller, id: Long, email: String?, user: Long?, role: String?): JsonObject {
        requireOwner(caller, id)
        val r = role(role)
        val target = user ?: email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { e ->
            db.query("SELECT \"id\" FROM $users WHERE LOWER(\"email\") = ?", listOf(e)).firstOrNull()?.let { id(it["id"]) }
                ?: throw ApiException(404, tr("Bu e-postayla kayıtlı kullanıcı yok"))
        } ?: throw ApiException(400, tr("email veya user gerekli"))
        return db.transaction {
            if (db.query("SELECT 1 AS x FROM $users WHERE \"id\" = ?", listOf(target)).isEmpty()) throw ApiException(404, tr("Kullanıcı bulunamadı"))
            if (membership(id, target) != null) throw ApiException(409, tr("Kullanıcı zaten bu takımda"))
            val count = count("SELECT COUNT(*) AS n FROM $members WHERE \"team\" = ?", id)
            if (count >= settings.maxMembers) throw ApiException(409, tr("Takım en çok {0} üyeli olabilir", settings.maxMembers))
            requireTeamRoom(target)
            addRow(id, target, r)
            get(caller, id)
        }
    }

    fun setRole(caller: Caller, id: Long, user: Long, role: String?): JsonObject {
        requireOwner(caller, id)
        val r = role(role)
        db.transaction {
            val current = membership(id, user) ?: throw ApiException(404, tr("Kullanıcı bu takımda değil"))
            if (current == TEAM_OWNER && r != TEAM_OWNER) requireAnotherOwner(id, user)
            db.execute("UPDATE $members SET \"role\" = ?, \"updated_at\" = ? WHERE \"team\" = ? AND \"user\" = ?", listOf(r, now(), id, user))
        }
        return get(caller, id)
    }

    /** Üyeyi çıkarır (sahip) veya kullanıcı kendisi ayrılır. */
    fun removeMember(caller: Caller, id: Long, user: Long) {
        val self = (caller as? Caller.User)?.id == user
        if (self) roleOf(caller, id) else requireOwner(caller, id)
        db.transaction {
            val current = membership(id, user) ?: throw ApiException(404, tr("Kullanıcı bu takımda değil"))
            if (current == TEAM_OWNER) requireAnotherOwner(id, user)
            db.execute("DELETE FROM $members WHERE \"team\" = ? AND \"user\" = ?", listOf(id, user))
        }
    }

    private fun summary(id: Long, name: String, role: String?, size: Int? = null) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        role?.let { put("role", JsonPrimitive(it)) }
        put("memberCount", JsonPrimitive(size ?: count("SELECT COUNT(*) AS n FROM $members WHERE \"team\" = ?", id)))
    }

    /** Giriş yapmış kullanıcı (iki adımlı doğrulaması tamam) veya yönetici; sınırlı API anahtarı takımları yönetemez. */
    private fun requireUserOrAdmin(caller: Caller) {
        when {
            caller.isAdmin -> {}
            caller is Caller.Anonymous -> throw ApiException(401, tr("Bu işlem için giriş yapın (Authorization: Bearer <token>)"))
            caller is Caller.User && caller.restricted -> throw ApiException(403, tr("İki adımlı doğrulama zorunlu; önce kurun (/auth/2fa/setup ve /auth/2fa/enable)"))
            caller !is Caller.User -> throw ApiException(403, tr("Takımlar kullanıcılar ve yönetici içindir"))
        }
    }

    /** Kullanıcının bu takımdaki rolü (yöneticide null); üye değilse 404. */
    private fun roleOf(caller: Caller, id: Long): String? {
        requireUserOrAdmin(caller)
        if (caller.isAdmin) return null
        val user = (caller as? Caller.User)?.id ?: throw ApiException(401, tr("Bu işlem için giriş yapın (Authorization: Bearer <token>)"))
        return membership(id, user) ?: throw notFound()
    }

    private fun requireOwner(caller: Caller, id: Long) {
        if (caller.isAdmin) {
            if (db.query("SELECT 1 AS x FROM $teams WHERE \"id\" = ?", listOf(id)).isEmpty()) throw notFound()
            return
        }
        if (roleOf(caller, id) != TEAM_OWNER) throw ApiException(403, tr("Bu işlem takım sahibinindir"))
    }

    private fun requireAnotherOwner(id: Long, user: Long) {
        val others = count("SELECT COUNT(*) AS n FROM $members WHERE \"team\" = ? AND \"role\" = '$TEAM_OWNER' AND \"user\" <> ?", id, user)
        if (others == 0) throw ApiException(409, tr("Takımın son sahibi; önce başka bir üyeyi sahip yapın veya takımı silin"))
    }

    private fun requireTeamRoom(user: Long) {
        if (count("SELECT COUNT(*) AS n FROM $members WHERE \"user\" = ?", user) >= settings.maxTeamsPerUser) {
            throw ApiException(409, tr("Bir kullanıcı en çok {0} takımda olabilir", settings.maxTeamsPerUser))
        }
    }

    private fun membership(id: Long, user: Long): String? =
        db.query("SELECT \"role\" FROM $members WHERE \"team\" = ? AND \"user\" = ?", listOf(id, user)).firstOrNull()?.get("role")?.toString()

    private fun addRow(team: Long, user: Long, role: String) {
        memberRecords.create(buildJsonObject {
            put("team", JsonPrimitive(team))
            put("user", JsonPrimitive(user))
            put("role", JsonPrimitive(role))
        })
    }

    private fun count(sql: String, vararg args: Any): Int = (db.query(sql, args.toList()).first()["n"] as Number).toInt()

    private fun now() = db.dialect.encode(dev.droidserver.core.config.FieldType.DATETIME, clock())

    private fun notFound() = ApiException(404, tr("Takım bulunamadı"))

    private fun name(name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n.length > 100) throw ApiException(400, tr("Takım adı 1-100 karakter olmalı"), mapOf("name" to tr("Takım adı 1-100 karakter olmalı")))
        return n
    }

    private fun role(role: String?): String = when (role?.trim()) {
        null, "", TEAM_MEMBER -> TEAM_MEMBER
        TEAM_OWNER -> TEAM_OWNER
        else -> throw ApiException(400, tr("Takım rolü '{0}' veya '{1}' olmalı", TEAM_OWNER, TEAM_MEMBER))
    }

    private fun id(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is JsonPrimitive -> v.longOrNull ?: v.content.toLong()
        else -> v.toString().toLong()
    }
}
