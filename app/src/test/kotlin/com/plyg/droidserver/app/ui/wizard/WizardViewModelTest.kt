package com.plyg.droidserver.app.ui.wizard

import com.plyg.droidserver.app.data.DraftStore
import com.plyg.droidserver.core.api.ApiExamples
import com.plyg.droidserver.core.api.ApiProbe
import com.plyg.droidserver.core.config.AccessMode
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.CloudflareTunnel
import com.plyg.droidserver.core.config.DirectNetwork
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionTemplate
import com.plyg.droidserver.core.config.ConfigDefaults
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.CorsMode
import com.plyg.droidserver.core.config.DbEngine
import com.plyg.droidserver.core.config.FieldRules
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.PostgresDefaults
import com.plyg.droidserver.core.config.SecretSink
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.TypeChange
import com.plyg.droidserver.core.config.TypeChangeAction
import com.plyg.droidserver.core.config.accessToken
import com.plyg.droidserver.core.db.JdbcSqlDriver
import com.plyg.droidserver.core.server.DeployOrchestrator
import com.plyg.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saf Kotlin ViewModel: Android veya Robolectric olmadan JVM'de koşar. */
class WizardViewModelTest {

    private class MemoryDrafts : DraftStore {
        var saved: ServerConfig? = null
        override fun load() = saved
        override fun save(config: ServerConfig) { saved = config }
        override fun clear() { saved = null }
    }

    /** api_key_1, jwt_2 ... adlarıyla bellekte saklar. */
    private class MemorySecrets : SecretSink {
        val values = linkedMapOf<String, String>()
        override fun store(kind: String, value: String) = "${kind}_${values.size + 1}".also { values[it] = value }
    }

    private val drafts = MemoryDrafts()
    private val secrets = MemorySecrets()

    /** Uygulamadaki varsayılan: yer tutucu yerine yeni üretilmiş düz metin anahtar. */
    private fun fresh() = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(ConfigDefaults.generateApiKey())))

    private fun vm(initial: ServerConfig = fresh(), active: ServerConfig? = null) =
        WizardViewModel(initial, active, drafts, secrets, secrets.values::get, ::fresh)

    @Test
    fun `gecersiz adimda ileri gidilmez, hata alana ve adima eslenir`() {
        val vm = vm()
        vm.setPort(80)
        assertEquals(listOf("server.port"), vm.errorsFor(WizardStep.GENERAL).map { it.path })
        assertNotNull(vm.errorAt("server.port"))
        assertFalse(vm.next())
        assertFalse(vm.goTo(WizardStep.SUMMARY), "ileri adımlara geçersiz adım atlanarak gidilmez")
        assertEquals(WizardStep.GENERAL, vm.stepFor(vm.errors.value.single()))

        vm.setPort(8080)
        assertTrue(vm.next())
        assertEquals(WizardStep.DATABASE, vm.step.value)
        assertTrue(vm.back())
        assertFalse(vm.back(), "ilk adımda geri = sihirbazdan çık")
    }

    @Test
    fun `sablonla koleksiyon eklenir, alanlar duzenlenir ve siralanir`() {
        val vm = vm()
        assertEquals(0, vm.addCollection(CollectionTemplate.TODOS))
        assertEquals(0, vm.editingCollection.value, "eklenen koleksiyonun düzenleyicisi açılır")
        vm.addCollection(CollectionTemplate.TODOS)
        assertEquals(listOf("todos", "todos_2"), vm.draft.value.collections.map { it.name })
        assertTrue(vm.canProceed(WizardStep.COLLECTIONS))

        // Boş şablon alan eklenene kadar adımı geçersiz kılar.
        val empty = vm.addCollection(CollectionTemplate.EMPTY)
        assertEquals(listOf("collections[2].fields"), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path })
        vm.addField(empty)
        vm.addField(empty)
        assertEquals(listOf("alan", "alan_2"), vm.draft.value.collections[empty].fields.map { it.name })
        assertTrue(vm.canProceed(WizardStep.COLLECTIONS))

        // Tip değişince uymayan varsayılan silinir; geçersiz ad alan yoluna eşlenir.
        vm.setFieldDefault(empty, 0, JsonPrimitive("merhaba"))
        vm.setFieldType(empty, 0, FieldType.INTEGER)
        assertNull(vm.draft.value.collections[empty].fields[0].default)
        vm.updateField(empty, 1) { it.copy(name = "Kötü Ad") }
        assertNotNull(vm.errorAt("collections[2].fields[1].name"))
        vm.updateField(empty, 1) { it.copy(name = "ad") }

        vm.moveField(0, 0, 3)
        assertEquals(listOf("done", "due", "tags", "title"), vm.draft.value.collections[0].fields.map { it.name })

        assertTrue(vm.back(), "düzenleyici açıkken geri onu kapatır")
        assertNull(vm.editingCollection.value)
        vm.removeCollection(1)
        assertEquals(listOf("todos", "items"), vm.draft.value.collections.map { it.name })
    }

    @Test
    fun `sirlar uretilir ve depoda saklanir, taslakta yalnizca referans durur`() {
        val vm = vm()
        vm.setAuthMode(AuthMode.JWT)
        val ref = vm.draft.value.auth.jwtSecret!!
        assertTrue(ConfigSecrets.isRef(ref), ref)
        val value = vm.secretValue(ref)!!
        assertEquals(48, value.length)
        assertEquals(value, secrets.values[ref.removePrefix(ConfigSecrets.PREFIX)])
        assertFalse(drafts.saved!!.toJson().contains(value), "taslakta düz metin sır olmamalı")
        vm.regenerateJwtSecret()
        assertNotEquals(ref, vm.draft.value.auth.jwtSecret)

        vm.setAuthMode(AuthMode.API_KEY)
        vm.addApiKey()
        assertEquals(2, vm.draft.value.auth.apiKeys.size)
        vm.removeApiKey(0)
        assertTrue(ConfigSecrets.isRef(vm.draft.value.auth.apiKeys.single()))
        assertTrue(vm.canProceed(WizardStep.SECURITY))

        vm.setCors(CorsMode.LIST)
        assertNotNull(vm.errorAt("server.cors.allowedHosts"))
        vm.setCors(CorsMode.LIST, listOf(" example.com", "", "app.example.com "))
        assertEquals(listOf("example.com", "app.example.com"), vm.draft.value.server.cors.allowedHosts)
        assertTrue(vm.canProceed(WizardStep.SECURITY))
    }

    @Test
    fun `kayip sir engellenir, herkesce bilinen ornek anahtar yalnizca uyari`() {
        val legacy = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(ConfigDefaults.PLACEHOLDER_API_KEY, "secret:silinmis")))
        val vm = vm(initial = legacy, active = legacy)
        assertEquals(listOf("auth.apiKeys[1]"), vm.errorsFor(WizardStep.SECURITY).map { it.path })
        assertTrue(PLACEHOLDER_WARNING in vm.securityWarnings(), "kullanıcı örnek anahtarı bilerek tutabilir")
        vm.regenerateApiKey(1)
        assertTrue(vm.canProceed(WizardStep.SECURITY))
        vm.regenerateApiKey(0)
        assertTrue(PLACEHOLDER_WARNING !in vm.securityWarnings())
    }

    @Test
    fun `hizli tunel token istemez, kendi alan adiyla tunel token ister ve referansla saklar`() {
        val vm = vm()
        vm.setAccess(AccessMode.CLOUDFLARE)
        assertEquals(CloudflareTunnel.QUICK, vm.draft.value.access.cloudflare.tunnel)
        assertTrue(vm.errorsFor(WizardStep.ACCESS).isEmpty(), "hızlı tünel hesapsız çalışır")

        vm.setCloudflareTunnel(CloudflareTunnel.NAMED)
        assertEquals(listOf(ACCESS_TOKEN_PATH), vm.errorsFor(WizardStep.ACCESS).map { it.path })
        vm.setAccessToken("  gizli-token  ")
        val ref = vm.draft.value.accessToken(AccessMode.CLOUDFLARE)!!
        assertEquals("gizli-token", secrets.values[ref.removePrefix(ConfigSecrets.PREFIX)])
        assertEquals(ref, vm.draft.value.components.single().env["TUNNEL_TOKEN"])
        assertTrue(vm.canProceed(WizardStep.ACCESS))
        vm.setCloudflareHostname(" https://Api.Example.com/ ")
        assertEquals("api.example.com", vm.draft.value.access.cloudflare.hostname)
        vm.clearAccessToken()
        assertEquals(listOf(ACCESS_TOKEN_PATH), vm.errorsFor(WizardStep.ACCESS).map { it.path })

        vm.setAccess(AccessMode.LOCAL)
        assertEquals(AccessMode.LOCAL, AccessMode.of(vm.draft.value))
        assertFalse(vm.draft.value.components.single().enabled, "cloudflared kapanır ama silinmez")
        assertTrue(vm.canProceed(WizardStep.ACCESS))
    }

    @Test
    fun `dogrudan erisim ayarlari ve DuckDNS token'i referansla saklanir`() {
        val vm = vm()
        vm.setAccess(AccessMode.DIRECT)
        assertTrue(vm.canProceed(WizardStep.ACCESS))
        vm.setDirectPort(vm.draft.value.server.port)
        assertEquals(listOf("access.direct.port"), vm.errorsFor(WizardStep.ACCESS).map { it.path })
        vm.setDirectPort(8443)
        vm.setDirectNetwork(DirectNetwork.MOBILE)
        vm.setPortMapping(false)
        vm.setDdns(" Evim.DuckDNS.org ", " tok ")
        val d = vm.draft.value.access.direct
        assertEquals(DirectNetwork.MOBILE, d.network)
        assertFalse(d.portMapping)
        assertEquals("evim", d.ddns!!.domain)
        assertEquals("tok", secrets.values[d.ddns!!.token.removePrefix(ConfigSecrets.PREFIX)])
        assertTrue(vm.canProceed(WizardStep.ACCESS))
        vm.clearDdns()
        assertNull(vm.draft.value.access.direct.ddns)
    }

    @Test
    fun `internete acik modda kimlik dogrulamasiz acmak bilincli onay ister`() {
        val vm = vm()
        vm.setAuthMode(AuthMode.NONE)
        assertTrue(vm.canProceed(WizardStep.SECURITY), "yerel ağda izinli")
        vm.setAccess(AccessMode.CLOUDFLARE)
        assertEquals(listOf("auth.mode"), vm.errorsFor(WizardStep.SECURITY).map { it.path })
        vm.setAllowNoAuth(true)
        assertTrue(vm.canProceed(WizardStep.SECURITY))
        vm.setAccess(AccessMode.TAILSCALE)
        vm.setAllowNoAuth(false)
        assertTrue(vm.canProceed(WizardStep.SECURITY), "Tailscale internete açık değil")
    }

    @Test
    fun `iliski alani hedef onerir, ad degisikligini izler, tip degisince temizlenir`() {
        val vm = vm(initial = fresh().copy(collections = emptyList()))
        val authors = vm.addCollection(CollectionTemplate.EMPTY)
        vm.renameCollection(authors, "authors")
        vm.addField(authors)
        val posts = vm.addCollection(CollectionTemplate.EMPTY)
        vm.renameCollection(posts, "posts")
        vm.addField(posts)

        vm.setFieldType(posts, 0, FieldType.RELATION)
        var f = vm.draft.value.collections[posts].fields[0]
        assertEquals("authors", f.collection, "önce başka bir koleksiyon önerilir")
        assertTrue(vm.errorsFor(WizardStep.COLLECTIONS).isEmpty(), vm.errorsFor(WizardStep.COLLECTIONS).toString())

        vm.renameCollection(authors, "writers")
        assertEquals("writers", vm.draft.value.collections[posts].fields[0].collection, "ilişki yeni adı izler")

        vm.updateField(posts, 0) { it.copy(required = true) }
        vm.setOnDelete(posts, 0, OnDelete.SET_NULL)
        assertEquals(listOf("collections[1].fields[0].onDelete"), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path })
        vm.setOnDelete(posts, 0, OnDelete.CASCADE)
        assertTrue(vm.errorsFor(WizardStep.COLLECTIONS).isEmpty())

        vm.setFieldType(posts, 0, FieldType.INTEGER)
        f = vm.draft.value.collections[posts].fields[0]
        assertNull(f.collection)
        assertNull(f.onDelete)
    }

    @Test
    fun `secim alani ve kurallar, hesaplanan alan, tip degisince uymayan kurallar atilir`() {
        val vm = vm(initial = fresh().copy(collections = emptyList()))
        val c = vm.addCollection(CollectionTemplate.EMPTY)
        vm.renameCollection(c, "products")
        vm.addField(c)
        vm.updateField(c, 0) { it.copy(name = "status") }
        vm.setFieldType(c, 0, FieldType.SELECT)
        assertEquals(listOf("collections[0].fields[0].options"), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path })
        vm.updateField(c, 0) { it.copy(options = listOf("taslak", "yayında")) }
        assertTrue(vm.errorsFor(WizardStep.COLLECTIONS).isEmpty(), vm.errorsFor(WizardStep.COLLECTIONS).toString())

        vm.addField(c)
        vm.updateField(c, 1) { it.copy(name = "qty") }
        vm.setFieldType(c, 1, FieldType.INTEGER)
        vm.updateField(c, 1) { it.copy(min = 0.0, max = 10.0, required = true) }
        vm.addField(c)
        vm.updateField(c, 2) { it.copy(name = "label") }
        vm.updateField(c, 2) { FieldRules.withComputed(it.copy(required = true), "upper(status) + ' ' + qty") }
        val label = vm.draft.value.collections[c].fields[2]
        assertEquals(false, label.required, "hesaplanan alan zorunlu olamaz")
        assertTrue(vm.errorsFor(WizardStep.COLLECTIONS).isEmpty(), vm.errorsFor(WizardStep.COLLECTIONS).toString())
        vm.updateField(c, 2) { it.copy(computed = "yok + 1") }
        assertEquals(listOf("collections[0].fields[2].computed"), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path })

        // Tip değişince yeni tipe uymayan kurallar atılır.
        vm.setFieldType(c, 1, FieldType.TEXT)
        val qty = vm.draft.value.collections[c].fields[1]
        assertNull(qty.min)
        assertNull(qty.max)
        vm.setFieldType(c, 0, FieldType.TEXT)
        assertNull(vm.draft.value.collections[c].fields[0].options)
        vm.setFieldType(c, 2, FieldType.DATETIME)
        assertNull(vm.draft.value.collections[c].fields[2].computed)
    }

    @Test
    fun `ozel roller eklenir, kurallarda izin verilir, ad degisince ve silinince kurallar izler`() {
        val vm = vm(initial = fresh().copy(collections = emptyList()))
        vm.setUsersEnabled(true)
        val c = vm.addCollection(CollectionTemplate.EMPTY)
        vm.renameCollection(c, "posts")
        vm.addField(c)
        vm.updateField(c, 0) { it.copy(name = "title") }
        vm.addRole()
        vm.addRole()
        assertEquals(listOf("editor", "moderator"), vm.draft.value.auth.users.roles.map { it.name })
        vm.setRoleAllowed(c, Rules.Op.UPDATE, "editor", true)
        vm.setRoleAllowed(c, Rules.Op.DELETE, "editor", true)
        vm.setRoleAllowed(c, Rules.Op.DELETE, "moderator", true)
        assertTrue(vm.errorsFor(WizardStep.COLLECTIONS).isEmpty(), vm.errorsFor(WizardStep.COLLECTIONS).toString())

        vm.updateRole(0, vm.draft.value.auth.users.roles[0].copy(name = "yazar", title = "Yazar"))
        val rules = vm.draft.value.collections[c].rules
        assertEquals(listOf("yazar"), rules.roles(Rules.Op.UPDATE))
        assertEquals(listOf("yazar", "moderator"), rules.roles(Rules.Op.DELETE))
        vm.removeRole(0)
        assertEquals(emptyMap(), vm.draft.value.collections[c].rules.roles - "delete")
        assertEquals(listOf("moderator"), vm.draft.value.collections[c].rules.roles(Rules.Op.DELETE))
        vm.setRoleAllowed(c, Rules.Op.DELETE, "moderator", false)
        assertTrue(vm.draft.value.collections[c].rules.roles.isEmpty())

        vm.updateRole(0, vm.draft.value.auth.users.roles[0].copy(name = "admin"))
        assertEquals(listOf("auth.users.roles[0].name"), vm.errorsFor(WizardStep.USERS).map { it.path })
    }

    @Test
    fun `bulut yedegi acilinca sifreleme parolasi uretilir ve sirlar depoda kalir`() {
        val vm = vm()
        vm.setRemoteBackup(true)
        val r = vm.draft.value.backup.remote!!
        assertTrue(r.passphrase.startsWith("secret:"), r.passphrase)
        val pass = vm.secretValue(r.passphrase)!!
        assertTrue(Regex("^([A-Z2-9]{5}-){4}[A-Z2-9]{5}$").matches(pass), pass)
        assertTrue(vm.errorsFor(WizardStep.DATABASE).map { it.path }.containsAll(listOf("backup.remote.url", "backup.remote.username", "backup.remote.password")))
        vm.updateRemoteBackup { it.copy(kind = com.plyg.droidserver.core.config.RemoteKind.S3, url = "https://s3.example.com", username = "AK") }
        vm.setRemotePassword("gizli-anahtar")
        assertEquals(listOf("backup.remote.bucket"), vm.errorsFor(WizardStep.DATABASE).map { it.path }.filter { it.startsWith("backup") })
        vm.updateRemoteBackup { it.copy(bucket = "kova") }
        assertTrue(vm.errorsFor(WizardStep.DATABASE).none { it.path.startsWith("backup") })
        assertEquals("gizli-anahtar", vm.resolvedRemoteBackup()!!.password)
        assertTrue("gizli-anahtar" !in vm.draft.value.toJson() && pass !in vm.draft.value.toJson())
        vm.setRemoteBackup(false)
        assertNull(vm.draft.value.backup.remote)
    }

    @Test
    fun `postgres secilince parola uretilir ve bilesen eklenir, sqlite'a donunce bilesen kapanir`() {
        val active = fresh()
        val vm = vm(initial = active, active = active)
        vm.selectPostgres()
        val db = vm.draft.value.database
        assertEquals(DbEngine.POSTGRES, db.engine)
        assertEquals(PostgresDefaults.URL, db.url)
        val ref = db.password!!
        assertTrue(ConfigSecrets.isRef(ref), ref)
        assertEquals(32, secrets.values[ref.removePrefix(ConfigSecrets.PREFIX)]!!.length)
        val component = vm.draft.value.components.single { it.id == PostgresDefaults.COMPONENT_ID }
        assertEquals(ref, component.env[PostgresDefaults.PASSWORD_ENV], "initdb aynı parolayı almalı")
        assertTrue(component.enabled)

        assertTrue(vm.databaseChanged)
        assertEquals(listOf(MOVE_DATA_PATH), vm.errorsFor(WizardStep.DATABASE).map { it.path }, "taşıma kullanıcıya sorulur")
        assertFalse(vm.shouldMoveData())
        vm.setMoveData(true)
        assertTrue(vm.shouldMoveData())
        assertTrue(vm.canProceed(WizardStep.DATABASE))
        vm.setMoveOptions { it.copy(duringMove = DeployOrchestrator.DuringMove.READ_ONLY, replaceExisting = true) }
        vm.setMoveData(false)
        assertFalse(vm.shouldMoveData())

        vm.setSqliteFile(active.database.file)
        assertEquals(DbEngine.SQLITE, vm.draft.value.database.engine)
        assertFalse(vm.draft.value.components.single().enabled, "Postgres bileşeni kapanır, silinmez")
        assertFalse(vm.databaseChanged)

        vm.selectPostgres()
        assertEquals(ref, vm.draft.value.database.password, "parola yeniden üretilmez")
        assertEquals(1, secrets.values.keys.count { it.startsWith("db_password") })

        vm.setPostgresConnection("postgres://yanlis", "droid")
        assertEquals(listOf("database.url"), vm.errorsFor(WizardStep.DATABASE).map { it.path })

        // Kaydedince bir kezlik seçimler sıfırlanır.
        vm.onSaved(vm.draft.value)
        assertNull(vm.moveData.value)
        assertEquals(DeployOrchestrator.MoveOptions(), vm.moveOptions.value)
    }

    @Test
    fun `postgres veritabani adi ve parolasi kullanicinin secimi`() {
        val active = fresh()
        val vm = vm(initial = active, active = active)
        vm.selectPostgres()
        vm.setPostgresDatabaseName("uygulama")
        assertEquals("jdbc:postgresql://127.0.0.1:5432/uygulama", vm.draft.value.database.url)
        vm.setCreateIfMissing(true)
        vm.setPostgresDatabaseName("Kötü Ad")
        assertEquals(listOf("database.name"), vm.errorsFor(WizardStep.DATABASE).filter { it.path != MOVE_DATA_PATH }.map { it.path })
        vm.setPostgresDatabaseName("uygulama")

        val generated = vm.postgresPasswordValue()!!
        assertNotNull(vm.setPostgresPassword("iki\nsatir"), "satır sonu reddedilir")
        assertEquals(generated, vm.postgresPasswordValue())
        assertNull(vm.setPostgresPassword("kendi parolam"))
        assertEquals("kendi parolam", vm.postgresPasswordValue())
        val ref = vm.draft.value.database.password!!
        assertTrue(ConfigSecrets.isRef(ref))
        assertEquals(ref, vm.draft.value.components.single().env[PostgresDefaults.PASSWORD_ENV])
        assertFalse(vm.postgresPasswordChanged, "çalışan konfigürasyon SQLite: değiştirilecek parola yok")

        // Postgres çalışırken parola değişirse kaydederken uygulanır; yöntem kullanıcının seçimi.
        vm.setMoveData(false)
        vm.onSaved(vm.draft.value)
        assertFalse(vm.postgresPasswordChanged)
        vm.regeneratePostgresPassword()
        assertTrue(vm.postgresPasswordChanged)
        assertEquals(PasswordMethod.CHANGE, vm.passwordMethod.value)
        vm.setPasswordMethod(PasswordMethod.RESET)
        assertEquals(PasswordMethod.RESET, vm.passwordMethod.value)
    }

    @Test
    fun `kayitli alanin tipi degisince degerler icin secim istenir`() {
        val active = fresh().copy(collections = listOf(CollectionTemplate.TODOS.create()))
        val vm = vm(initial = active, active = active)
        val path = "collections[0].fields[2].typeChange"
        vm.setFieldType(0, 2, FieldType.INTEGER) // due: datetime -> integer
        assertEquals(listOf(path), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path })
        assertEquals(FieldType.DATETIME, vm.activeFieldType(0, 2))

        vm.setTypeChange(0, 2, TypeChangeAction.CONVERT)
        assertEquals(listOf(path), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path }, "datetime -> integer dönüştürülemez")
        vm.setTypeChange(0, 2, TypeChangeAction.CLEAR)
        assertTrue(vm.canProceed(WizardStep.COLLECTIONS))
        assertEquals(TypeChange(FieldType.DATETIME, TypeChangeAction.CLEAR), vm.draft.value.collections[0].fields[2].typeChange)

        vm.setFieldType(0, 2, FieldType.TEXT)
        assertEquals(TypeChangeAction.CLEAR, vm.draft.value.collections[0].fields[2].typeChange?.action, "seçim yeni tipe de uyar")
        vm.setTypeChange(0, 2, TypeChangeAction.CONVERT)
        vm.setFieldType(0, 2, FieldType.BOOLEAN)
        assertNull(vm.draft.value.collections[0].fields[2].typeChange, "datetime -> boolean dönüştürülemez: yeniden sorulur")
        vm.setFieldType(0, 2, FieldType.DATETIME)
        assertTrue(vm.canProceed(WizardStep.COLLECTIONS), "tip geri alınınca seçim gerekmez")
        assertNull(vm.draft.value.collections[0].fields[2].typeChange)
    }

    @Test
    fun `taslak kaydedilir, yeniden acilista geri gelir, etkinle ayni olunca silinir`() {
        val active = fresh()
        val first = vm(initial = active, active = active)
        first.setName("evim")
        assertEquals("evim", drafts.saved?.name)

        // Uygulama kapanıp açıldı: yeni ViewModel taslaktan başlar.
        val second = vm(initial = drafts.load() ?: active, active = active)
        assertEquals("evim", second.draft.value.name)
        assertTrue(second.hasUnsavedChanges)

        second.setName(active.name)
        assertNull(drafts.saved, "etkin konfigürasyona dönünce taslak silinir")
        second.setName("x")
        second.discard()
        assertEquals(active, second.draft.value)
        assertNull(drafts.saved)
    }

    @Test
    fun `dosya yukseltilince kaydedilmemis degisiklikler korunur`() {
        val active = fresh()
        val upgraded = active.copy(auth = active.auth.copy(apiKeys = listOf("secret:api_key_9")))
        secrets.values["api_key_9"] = active.auth.apiKeys.single()

        val clean = vm(initial = active, active = active)
        clean.onFileUpgraded(upgraded)
        assertEquals(upgraded, clean.draft.value, "değişiklik yoksa taslak da güncellenir")
        assertFalse(clean.hasUnsavedChanges)

        val editing = vm(initial = active, active = active)
        editing.setName("degisti")
        editing.onFileUpgraded(upgraded)
        assertEquals("degisti", editing.draft.value.name)
        assertEquals(upgraded, editing.active.value)
    }

    @Test
    fun `json duzenleyici ve sihirbaz ayni taslagi paylasir`() {
        val vm = vm()
        val fromJson = ServerConfig.fromJson(
            vm.draft.value.copy(collections = listOf(CollectionTemplate.BLOG.create())).toJson(),
        )
        vm.replaceDraft(fromJson)
        assertEquals(listOf("posts"), vm.draft.value.collections.map { it.name })

        vm.setName("sihirbazdan")
        assertTrue(vm.draft.value.toJson().contains("\"sihirbazdan\""))
    }

    @Test
    fun `veri kaybi uyarilari calisan konfigurasyona gore hesaplanir`() {
        val active = fresh().copy(collections = listOf(CollectionTemplate.TODOS.create()))
        val vm = vm(initial = active, active = active)
        assertTrue(vm.warningsFor(0).isEmpty())
        vm.setFieldType(0, 2, FieldType.TEXT) // due: datetime -> text
        vm.removeField(0, 3) // tags
        assertEquals(setOf("due", "tags"), vm.warningsFor(0).map { it.field }.toSet())

        vm.updateCollection(0) { it.copy(name = "gorevler") }
        assertEquals(listOf("todos"), vm.removedCollections())

        val saved = vm.draft.value
        vm.onSaved(saved)
        assertFalse(vm.hasUnsavedChanges)
        assertTrue(vm.removedCollections().isEmpty())
        assertEquals(WizardStep.GENERAL, vm.step.value)
    }

    @Test
    fun `takim hazir kurali kullanicilari ve takimlari acar, takim alani ekler`() {
        val vm = vm()
        vm.addCollection(CollectionTemplate.TODOS)
        vm.applyRulePreset(0, RulePreset.TEAM)
        val d = vm.draft.value
        assertTrue(d.auth.users.enabled && d.auth.users.teams.enabled)
        val c = d.collections.single()
        assertEquals("team", c.teamField)
        assertEquals(SystemCollections.TEAMS, c.fields.single { it.name == "team" }.collection)
        assertTrue(com.plyg.droidserver.core.config.ConfigValidator.validate(d).none { it.startsWith("collections") || "team" in it }, com.plyg.droidserver.core.config.ConfigValidator.validate(d).joinToString())
        vm.removeField(0, c.fields.indexOfFirst { it.name == "team" })
        assertNull(vm.draft.value.collections.single().teamField)
    }

    @Test
    fun `sihirbaz JSON'a dokunmadan calisan sunucu uretir`() {
        val port = ServerSocket(0).use { it.localPort }
        val vm = vm()

        vm.setName("evim")
        vm.setPort(port)
        assertTrue(vm.next()) // -> veritabanı
        assertTrue(vm.next()) // -> kullanıcılar
        vm.setUsersEnabled(true)
        assertTrue(vm.next()) // -> koleksiyonlar
        vm.addCollection(CollectionTemplate.TODOS)
        vm.applyRulePreset(0, RulePreset.PRIVATE) // herkes yalnızca kendi yapılacaklarını görür
        vm.openCollection(null)
        assertTrue(vm.next()) // -> güvenlik
        vm.regenerateApiKey(0)
        assertTrue(vm.next()) // -> erişim
        assertTrue(vm.next()) // -> özet
        assertEquals(WizardStep.SUMMARY, vm.step.value)
        assertTrue(vm.errors.value.isEmpty(), vm.errors.value.joinToString())

        // Deploy: sırlar depodan çözülür, sunucu gerçekten açılır.
        val draft = vm.draft.value
        val resolved = ConfigSecrets.resolve(draft, secrets.values::get)
        val key = resolved.auth.apiKeys.single()
        val file = Files.createTempFile("ds-wizard", ".db").toFile()
        val engine = ServerEngine({ JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")) }) {}
        try {
            assertFalse(engine.plan(draft).hasBlockers)
            engine.deploy(resolved)

            val list = ApiExamples.forCollection(draft, draft.collections.single()).first()
            val url = "http://127.0.0.1:$port${list.path}"
            assertEquals(200, ApiProbe.get(url, "Bearer $key").status)
            assertEquals(401, ApiProbe.get(url, null).status)

            val conn = URI("http://127.0.0.1:$port/api/collections/todos/records").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("""{"title":"Süt al"}""".toByteArray()) }
            assertEquals(201, conn.responseCode)
            conn.disconnect()

            // Son kullanıcılar kayıt olur; her biri yalnızca kendi kayıtlarını görür.
            fun call(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
                val c = URI("http://127.0.0.1:$port/api$path").toURL().openConnection() as HttpURLConnection
                c.requestMethod = method
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = c.responseCode
                val text = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                c.disconnect()
                return status to text
            }
            fun token(email: String): String {
                val (status, body) = call("POST", "/auth/signup", null, """{"email":"$email","password":"parola-123"}""")
                assertEquals(201, status, body)
                return Json.parseToJsonElement(body).jsonObject.getValue("token").jsonPrimitive.content
            }
            val ali = token("ali@example.com")
            val ayse = token("ayse@example.com")
            assertEquals(201, call("POST", "/collections/todos/records", ali, """{"title":"Ali'nin işi"}""").first)
            val mine = call("GET", "/collections/todos/records", ali).second
            assertTrue("Ali'nin işi" in mine && "Süt al" !in mine, mine)
            assertTrue("Ali'nin işi" !in call("GET", "/collections/todos/records", ayse).second)
            assertEquals(401, call("GET", "/collections/todos/records", null).first)
        } finally {
            engine.stop()
            file.delete()
        }
    }

    @Test
    fun `kullanicilar ve kurallar`() {
        val vm = vm(ServerConfig())
        assertEquals(AuthMode.NONE, vm.draft.value.auth.mode)
        vm.setUsersEnabled(true)
        assertEquals(AuthMode.API_KEY, vm.draft.value.auth.mode, "kullanıcı hesapları yönetici kimliği ister; anahtar üretilir")
        assertNotNull(vm.secretValue(vm.draft.value.auth.apiKeys.single()))
        assertTrue(vm.canProceed(WizardStep.USERS))

        vm.addUserField()
        vm.updateUserField(0) { it.copy(name = "ad") }
        vm.setUserFieldPublic(0, true)
        vm.updateUserField(0) { it.copy(name = "isim") }
        assertEquals(listOf("isim"), vm.draft.value.auth.users.publicFields, "ad değişince herkese görünür listesi izler")
        vm.updateUserField(0) { it.copy(name = "email") }
        assertEquals(listOf("auth.users.extraFields[0].name"), vm.errorsFor(WizardStep.USERS).map { it.path })
        vm.removeUserField(0)
        assertTrue(vm.draft.value.auth.users.publicFields.isEmpty())

        vm.addCollection(CollectionTemplate.BLOG)
        vm.applyRulePreset(0, RulePreset.USERS_POST)
        val posts = vm.draft.value.collections[0]
        assertEquals("owner", posts.ownerField, "sahip alanı otomatik eklenir")
        assertEquals(SystemCollections.USERS, posts.fields.last().collection)
        assertTrue(vm.errors.value.isEmpty(), vm.errors.value.joinToString())
        val owner = posts.fields.lastIndex
        vm.updateField(0, owner) { it.copy(name = "yazar") }
        assertEquals("yazar", vm.draft.value.collections[0].ownerField)
        vm.setRule(0, Rules.Op.UPDATE, Rule.NOBODY)
        assertEquals(Rule.NOBODY, vm.draft.value.collections[0].rules.update)
        vm.removeField(0, owner)
        assertNull(vm.draft.value.collections[0].ownerField)
        assertEquals(listOf("collections[0].ownerField"), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path }, "sahip kuralı alan ister")

        // Hesaplar kapanırsa onlara dayanan kurallar hata verir; güvenlik adımı engellenmez.
        vm.addOwnerField(0)
        vm.setUsersEnabled(false)
        assertTrue(vm.errorsFor(WizardStep.COLLECTIONS).isNotEmpty())
        assertTrue(vm.canProceed(WizardStep.SECURITY))
        vm.setUsersEnabled(true)
        vm.setAuthMode(AuthMode.NONE)
        assertEquals(listOf("auth.mode"), vm.errorsFor(WizardStep.SECURITY).map { it.path })
        assertTrue(vm.canProceed(WizardStep.USERS))
    }

    @Test
    fun `dosya alani ve statik site`() {
        val vm = vm()
        val i = vm.addCollection(CollectionTemplate.GALLERY)
        assertTrue(vm.errors.value.isEmpty(), vm.errors.value.joinToString())
        val image = vm.draft.value.collections[i].fields.indexOfFirst { it.type == FieldType.FILE }
        vm.updateField(i, image) { it.copy(maxSizeMb = 5000) }
        assertEquals(listOf("collections[$i].fields[$image].maxSizeMb"), vm.errorsFor(WizardStep.COLLECTIONS).map { it.path })
        // Tip değişince dosya ayarları atılır (başka tipte geçersiz olurlardı).
        vm.setFieldType(i, image, FieldType.TEXT)
        val asText = vm.draft.value.collections[i].fields[image]
        assertNull(asText.maxSizeMb)
        assertNull(asText.mimeTypes)
        assertNull(asText.thumbs)
        assertTrue(vm.errors.value.isEmpty(), vm.errors.value.joinToString())

        vm.updateStatic { it.copy(enabled = true, path = "/api/site") }
        assertEquals(listOf("static.path"), vm.errorsFor(WizardStep.GENERAL).map { it.path }, "site yolu Genel adımında")
        vm.updateStatic { it.copy(path = "/") }
        assertTrue(vm.canProceed(WizardStep.GENERAL))
        assertTrue(vm.draft.value.toJson().contains("\"static\""))
    }

    @Test
    fun `script ayarlari guvenlik adiminda`() {
        val vm = vm()
        vm.updateScripts { it.copy(enabled = true, allowedHosts = listOf("https://kotu"), timeoutMs = 5) }
        assertEquals(setOf("scripts.allowedHosts[0]", "scripts.timeoutMs"), vm.errorsFor(WizardStep.SECURITY).map { it.path }.toSet())
        vm.updateScripts { it.copy(allowedHosts = listOf("api.example.com"), timeoutMs = 2000) }
        assertTrue(vm.canProceed(WizardStep.SECURITY))
        assertTrue(vm.draft.value.toJson().contains("\"scripts\""))
    }

    @Test
    fun `yedek ve web paneli ayarlari`() {
        val vm = vm()
        vm.updateBackup { it.copy(enabled = true, schedule = "her gece", keep = 0) }
        assertEquals(setOf("backup.schedule", "backup.keep"), vm.errorsFor(WizardStep.DATABASE).map { it.path }.toSet())
        vm.updateBackup { it.copy(schedule = "0 */6 * * *", keep = 7) }
        assertTrue(vm.canProceed(WizardStep.DATABASE))
        vm.updateAdminPanel { it.copy(enabled = true, sessionHours = 0) }
        assertEquals(listOf("adminPanel.sessionHours"), vm.errorsFor(WizardStep.SECURITY).map { it.path })
        vm.updateAdminPanel { it.copy(sessionHours = 12) }
        assertTrue(vm.canProceed(WizardStep.SECURITY))
        val json = vm.draft.value.toJson()
        assertTrue("\"backup\"" in json && "\"adminPanel\"" in json)
    }
    @Test
    fun `hazir uygulama sablonu ve guvenlik duvari, uyari ve is ayarlari`() {
        val vm = vm(ServerConfig())
        assertEquals(AuthMode.NONE, vm.draft.value.auth.mode)
        assertNull(vm.applyTemplate(com.plyg.droidserver.core.templates.AppTemplate.SHOP, site = true))
        val cfg = vm.draft.value
        assertEquals(listOf("products", "orders"), cfg.collections.map { it.name })
        assertTrue(cfg.auth.users.enabled && cfg.static.enabled)
        assertEquals(AuthMode.API_KEY, cfg.auth.mode, "hesaplar için kimlik doğrulama açılır")
        assertEquals(emptyList(), vm.errors.value.map { it.path })
        assertNotNull(vm.applyTemplate(com.plyg.droidserver.core.templates.AppTemplate.SHOP, site = true), "ad çakışması")
        assertEquals(2, vm.draft.value.collections.size)

        vm.updateFirewall { it.copy(deny = listOf("ev")) }
        vm.updateAlerts { it.copy(repeatHours = 0) }
        vm.updateScripts { it.copy(enabled = true, jobs = it.jobs.copy(concurrency = 0)) }
        assertEquals(listOf("firewall.deny[0]", "scripts.jobs.concurrency"), vm.errorsFor(WizardStep.SECURITY).map { it.path }.sorted())
        assertEquals(listOf("alerts.repeatHours"), vm.errorsFor(WizardStep.GENERAL).map { it.path })
        vm.setTelegramToken("123:abc")
        assertTrue("123:abc" !in vm.draft.value.toJson(), "token şifreli depoda")
        assertEquals("123:abc", vm.telegramToken())
    }
}
