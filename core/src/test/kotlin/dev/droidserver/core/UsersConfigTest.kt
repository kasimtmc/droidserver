package dev.droidserver.core

import dev.droidserver.core.auth.LoginLimiter
import dev.droidserver.core.auth.PasswordHasher
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigSummary
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.UsersConfig
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsersConfigTest {
    @Test
    fun `parola ozeti duz metin icermez ve sabit bicimdedir`() {
        val h = PasswordHasher(iterations = 1_000)
        val a = h.hash("gizli-parola")
        val b = h.hash("gizli-parola")
        assertNotEquals(a, b, "her özette yeni tuz")
        val parts = a.split('$')
        assertEquals(listOf("pbkdf2", "1000"), parts.take(2))
        assertEquals(4, parts.size)
        assertFalse("gizli" in a)
        assertTrue(h.verify("gizli-parola", a))
        assertFalse(h.verify("gizli-parolA", a))
        // Özetin bir baytı değişir (son karakterleri değiştirmek, dolgusuz base64'ün kullanılmayan bitlerine denk gelirse
        // aynı baytlara çözülebilir).
        val tampered = Base64.getDecoder().decode(parts[3]).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(h.verify("gizli-parola", parts.take(3).joinToString("$") + "$" + Base64.getEncoder().withoutPadding().encodeToString(tampered)))
        assertFalse(h.verify("gizli-parola", "md5$" + a.substringAfter('$')))
        // Tur sayısı özetle saklandığı için varsayılan değişse de eski özetler doğrulanır.
        assertTrue(PasswordHasher(iterations = 2_000).verify("gizli-parola", a))
        assertFalse(h.verifyDummy("herhangi"))
        assertEquals(310_000, PasswordHasher.DEFAULT_ITERATIONS)
    }

    @Test
    fun `giris sinirlayici hesap ve istemci bazli`() {
        var now = 0L
        val l = LoginLimiter(perAccount = 3, perClient = 5, windowMinutes = 15) { now }
        repeat(3) { assertNull(l.blockedFor("1.1.1.1", "a")); l.failed("1.1.1.1", "a") }
        assertEquals(900L, l.blockedFor("1.1.1.1", "a"))
        assertNull(l.blockedFor("2.2.2.2", "a"), "başka istemci etkilenmez")
        assertNull(l.blockedFor("1.1.1.1", "b"))
        l.failed("1.1.1.1", "b"); l.failed("1.1.1.1", "c")
        assertEquals(900L, l.blockedFor("1.1.1.1", "d"), "istemcinin toplam hatası")
        now += 15 * 60_000L
        assertNull(l.blockedFor("1.1.1.1", "a"), "pencere bitince açılır")
        l.failed("3.3.3.3", "x"); l.succeeded("3.3.3.3", "x")
        assertNull(l.blockedFor("3.3.3.3", "x"))
    }

    @Test
    fun `dogrulama kurallari`() {
        val owned = CollectionConfig(
            "posts",
            listOf(FieldConfig("title", FieldType.TEXT), FieldConfig("author", FieldType.RELATION, collection = SystemCollections.USERS)),
            rules = Rules(list = Rule.PUBLIC, view = Rule.PUBLIC, create = Rule.AUTH, update = Rule.OWNER, delete = Rule.OWNER),
            ownerField = "author",
        )
        val users = UsersConfig(enabled = true, extraFields = listOf(FieldConfig("name", FieldType.TEXT)), publicFields = listOf("name"))
        val good = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf("a".repeat(20)), users = users), collections = listOf(owned))
        assertEquals(emptyList(), ConfigValidator.validateDetailed(good))

        fun paths(c: ServerConfig) = ConfigValidator.validateDetailed(c).map { it.path }.toSet()
        assertEquals(
            setOf("auth.mode"),
            paths(good.copy(auth = good.auth.copy(mode = AuthMode.NONE, apiKeys = emptyList()))),
            "kimlik doğrulama yokken herkes yönetici olurdu",
        )
        val noUsers = good.copy(auth = good.auth.copy(users = UsersConfig()))
        assertEquals(
            setOf("collections[0].fields[1].collection", "collections[0].rules.create", "collections[0].rules.update", "collections[0].rules.delete"),
            paths(noUsers),
        )
        assertEquals(setOf("collections[0].ownerField"), paths(good.copy(collections = listOf(owned.copy(ownerField = null)))))
        assertEquals(setOf("collections[0].ownerField"), paths(good.copy(collections = listOf(owned.copy(ownerField = "title")))))
        val badExtra = users.copy(
            extraFields = listOf(FieldConfig("email", FieldType.TEXT), FieldConfig("team", FieldType.RELATION, collection = "posts")),
            publicFields = listOf("phone"),
        )
        assertEquals(
            setOf("auth.users.extraFields[0].name", "auth.users.extraFields[1].type", "auth.users.publicFields[0]"),
            paths(good.copy(auth = good.auth.copy(users = badExtra))),
        )
        assertEquals(setOf("auth.users.minPasswordLength"), paths(good.copy(auth = good.auth.copy(users = users.copy(minPasswordLength = 3)))))

        // Sistem tabloları şemaya yalnızca kullanıcılar açıkken girer.
        assertEquals(listOf("posts", "_users", "_sessions"), good.schemaCollections().map { it.name })
        assertEquals(listOf("posts"), noUsers.schemaCollections().map { it.name })
        assertEquals(listOf("email", "password_hash", "verified", "role", "name"), SystemCollections.users(users).fields.map { it.name })

        assertEquals("Herkes: listeleme, görüntüleme · Giriş yapmış kullanıcılar: oluşturma · Kaydın sahibi: güncelleme, silme", ConfigSummary.rulesSummary(owned.rules))
        assertEquals("Yalnızca yönetici: tüm işlemler", ConfigSummary.rulesSummary(Rules()))
        val open = good.copy(collections = listOf(owned.copy(rules = owned.rules.copy(delete = Rule.PUBLIC))))
        assertTrue(ConfigSummary.securityWarnings(open).any { "silme herkese açık" in it }, ConfigSummary.securityWarnings(open).toString())
    }
}
