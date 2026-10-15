package dev.droidserver.core.api

import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.i18n.tr

/**
 * Koleksiyon kurallarının uygulanması. Yönetici [Rule.NOBODY] dışında her şeyi yapabilir; `owner` kuralında
 * kullanıcı yalnızca kendi kayıtlarını görür (başkasının kaydı 404). Kurala koşul eklenmişse yalnızca koşula uyan
 * kayıtlar (bkz. [RuleConditions]). Kuralda izin verilen özel roldeki kullanıcı işlemi koşulsuz yapar ([Rules.roles]). Reddedilen anonim istek 401 (giriş yapın), reddedilen kullanıcı 403 alır.
 */
object Access {
    fun scope(collection: CollectionConfig, op: Rules.Op, caller: Caller): Scope = baseScope(collection, op, caller).copy(actor = actor(caller))

    /** Geçmişte ve çöp kutusunda görünen "kim". */
    fun actor(caller: Caller): String = when (caller) {
        Caller.Anonymous -> "anonymous"
        Caller.Admin -> "admin"
        is Caller.User -> "user:${caller.id}"
        is Caller.Key -> "key:${caller.name}"
    }

    private fun baseScope(collection: CollectionConfig, op: Rules.Op, caller: Caller): Scope {
        val rule = collection.rules[op]
        if (rule == Rule.NOBODY) throw ApiException(403, tr("'{0}' koleksiyonunda {1} kapalı", collection.name, op.title.lowercase()))
        val user = (caller as? Caller.User)?.id
        if ((caller as? Caller.User)?.restricted == true) {
            throw ApiException(403, tr("İki adımlı doğrulama zorunlu; önce kurun (/auth/2fa/setup ve /auth/2fa/enable)"))
        }
        if (caller.isAdmin) return Scope(admin = true, user = user)
        if (caller is Caller.Key) {
            val write = op != Rules.Op.LIST && op != Rules.Op.VIEW
            if (!caller.allows(collection.name, write)) {
                throw ApiException(403, tr("'{0}' anahtarı '{1}' koleksiyonunda {2} için yetkili değil", caller.name, collection.name, op.title.lowercase()))
            }
            return Scope(admin = true)
        }
        val signedIn = caller is Caller.User
        // Özel rol: kural ne olursa olsun bu işlemde koşulsuz ve sahip sınırı olmadan (yönetici alanları yine gizli).
        if (caller is Caller.User && caller.role in collection.rules.roles(op)) return Scope(admin = false, user = user)
        val base = when (rule) {
            Rule.PUBLIC -> Scope(admin = false, user = user)
            Rule.AUTH -> if (signedIn) Scope(admin = false, user = user) else deny(caller)
            Rule.OWNER -> if (signedIn) Scope(admin = false, user = user, ownedBy = user.takeIf { op != Rules.Op.CREATE }) else deny(caller)
            Rule.TEAM -> if (caller is Caller.User) teamScope(collection, caller) else deny(caller)
            Rule.ADMIN, Rule.NOBODY -> deny(caller)
        }
        val condition = collection.rules.condition(op) ?: return base
        return base.copy(
            condition = base.condition + RuleConditions.parse(collection, condition.filter, user),
            ownerBypass = condition.owner && op != Rules.Op.CREATE && collection.ownerField != null && user != null,
            conditionText = condition.filter,
        )
    }

    /**
     * `team` kuralı: kaydın takım alanı kullanıcının takımlarından biri (takımı yoksa hiçbir kayıt). Takımlar tablosunun
     * kendisinde kaydın kimliği takımdır.
     */
    private fun teamScope(collection: CollectionConfig, caller: Caller.User): Scope {
        val field = collection.teamField ?: "id".takeIf { collection.name == SystemCollections.TEAMS }
            ?: throw ApiException(500, tr("'{0}' koleksiyonunun takım alanı yok", collection.name))
        val type = if (field == "id") FieldType.INTEGER else FieldType.RELATION
        val ids: List<Any> = caller.teams.sorted().ifEmpty { listOf(0L) }
        return Scope(admin = false, user = caller.id, condition = listOf(FilterParser.Condition(field, type, FilterParser.Op.IN, ids)), teams = caller.teams)
    }

    private fun deny(caller: Caller): Nothing =
        if (caller is Caller.Anonymous) throw ApiException(401, tr("Bu işlem için giriş yapın (Authorization: Bearer <token>)"))
        else throw ApiException(403, tr("Bu işlem için yetkiniz yok"))
}
