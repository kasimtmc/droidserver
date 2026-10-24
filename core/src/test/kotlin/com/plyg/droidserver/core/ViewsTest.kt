package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FieldVisibility
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.ReportConfig
import com.plyg.droidserver.core.config.RoleConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.UsersConfig
import com.plyg.droidserver.core.config.ViewConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Görünümler ve raporlar: sütun seçimi, ilişki sütunu, sabit koşul, rapor, erişim ve CSV; iki lehçede. */
@RunWith(Parameterized::class)
class ViewsTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val customers = CollectionConfig("customers", listOf(FieldConfig("name", FieldType.TEXT), FieldConfig("phone", FieldType.TEXT)))
    private val orders = CollectionConfig(
        "orders",
        listOf(
            FieldConfig("total", FieldType.REAL),
            FieldConfig("status", FieldType.TEXT),
            FieldConfig("note", FieldType.TEXT, visibility = FieldVisibility.ADMIN),
            FieldConfig("customer", FieldType.RELATION, collection = "customers", onDelete = OnDelete.SET_NULL),
            FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.SET_NULL),
        ),
        rules = Rules(), // koleksiyonun kendisi yalnızca yöneticiye açık; görünümler ayrıca açar
    )
    private val views = listOf(
        ViewConfig("paid", "orders", title = "Ödenenler", columns = listOf("id", "total", "customer.name"), filter = "status=paid", sort = "-total", access = Rule.PUBLIC),
        ViewConfig("revenue", "orders", report = ReportConfig(groupBy = "status", sum = listOf("total")), access = Rule.ADMIN, roles = listOf("accountant")),
        ViewConfig("mine", "orders", columns = listOf("id", "total", "status"), filter = "owner=@me", access = Rule.AUTH),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key), users = UsersConfig(enabled = true, roles = listOf(RoleConfig("accountant")))),
        collections = listOf(customers, orders),
        views = views,
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.send(method: String, path: String, token: String?, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, build)
            "PATCH" -> patch(path, build)
            else -> post(path, build)
        }
    }
    private suspend fun HttpClient.signup(email: String): Pair<Long, String> {
        val r = json(send("POST", "/api/auth/signup", null, """{"email":"$email","password":"parola-123"}""").bodyAsText())
        return r.getValue("user").jsonObject.getValue("id").jsonPrimitive.content.toLong() to r.getValue("token").jsonPrimitive.content
    }

    @Test
    fun `gorunum, rapor, erisim ve csv`() = testApplication {
        application { droidServerModule(config, db) }
        val (aliceId, alice) = client.signup("alice@example.com")
        val (accId, _) = client.signup("acc@example.com")
        client.send("PATCH", "/api/auth/users/$accId", key, """{"role":"accountant"}""")
        val acc = json(client.send("POST", "/api/auth/login", null, """{"email":"acc@example.com","password":"parola-123"}""").bodyAsText()).getValue("token").jsonPrimitive.content
        client.send("POST", "/api/collections/customers/records", key, """{"name":"Ayşe","phone":"555"}""")
        for ((total, status, owner) in listOf(Triple(120.0, "paid", aliceId), Triple(80.5, "paid", accId), Triple(40.0, "open", aliceId))) {
            client.send("POST", "/api/collections/orders/records", key, """{"total":$total,"status":"$status","note":"gizli","customer":1,"owner":$owner}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/collections/orders/records", null).status, "koleksiyon kapalı")

        // Herkese açık görünüm: yalnızca seçilen sütunlar, ilişki sütunu, sabit koşul ve sıralama.
        val paid = json(client.send("GET", "/api/views/paid", null).bodyAsText())
        val items = paid.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(setOf("id", "total", "customer.name")), items.map { it.keys }.distinct())
        assertEquals(listOf("120.0", "80.5"), items.map { it.getValue("total").jsonPrimitive.content })
        assertEquals("Ayşe", items.first().getValue("customer.name").jsonPrimitive.content)
        assertEquals(1, json(client.send("GET", "/api/views/paid?total[gt]=100", null).bodyAsText()).getValue("items").jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, client.send("GET", "/api/views/paid?note=gizli", null).status, "görünümde olmayan sütunla filtre yok")
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/api/views/yok", null).status)

        // CSV: BOM, başlık, satırlar.
        val csv = client.send("GET", "/api/views/paid?format=csv", null)
        assertTrue("paid-" in csv.headers["Content-Disposition"].orEmpty())
        val lines = csv.bodyAsText().removePrefix("﻿").trim().lines()
        assertEquals(listOf("id,total,customer.name", "1,120.0,Ayşe", "2,80.5,Ayşe"), lines)

        // Rapor: yönetici ve muhasebeci; diğerleri değil.
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/views/revenue", null).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/views/revenue", alice).status)
        val report = json(client.send("GET", "/api/views/revenue", acc).bodyAsText()).getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf("open" to "40.0", "paid" to "200.5"), report.map { it.getValue("key").jsonPrimitive.content to it.getValue("sum_total").jsonPrimitive.content })
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/views/revenue", key).status)
        assertEquals("key,count,sum_total", client.send("GET", "/api/views/revenue?format=csv", key).bodyAsText().removePrefix("﻿").lines().first())

        // @me: kullanıcının kendi siparişleri.
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/views/mine", null).status)
        assertEquals(2, json(client.send("GET", "/api/views/mine", alice).bodyAsText()).getValue("items").jsonArray.size)
        assertEquals(1, json(client.send("GET", "/api/views/mine", acc).bodyAsText()).getValue("items").jsonArray.size)

        // Liste: çağıranın görebildikleri.
        fun names(token: String?) = kotlinx.coroutines.runBlocking {
            json(client.send("GET", "/api/views", token).bodyAsText()).getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        }
        assertEquals(listOf("paid"), names(null))
        assertEquals(listOf("paid", "mine"), names(alice))
        assertEquals(listOf("paid", "revenue", "mine"), names(acc))
        val doc = json(client.send("GET", "/api/_openapi.json", null).bodyAsText()).getValue("paths").jsonObject
        assertTrue("/views/paid" in doc && "/views/revenue" !in doc)
    }

    @Test
    fun `gorunum dogrulamasi`() {
        val errors = { v: ViewConfig -> ConfigValidator.validateDetailed(config.copy(views = listOf(v))).map { it.path }.toSet() }
        assertEquals(emptySet(), ConfigValidator.validateDetailed(config).map { it.path }.toSet())
        assertEquals(setOf("views[0].columns[0]", "views[0].columns[1]", "views[0].columns[2]", "views[0].columns[3]"),
            errors(ViewConfig("v", "orders", columns = listOf("yok", "note", "owner.email", "customer.yok"), access = Rule.PUBLIC)))
        assertEquals(emptySet(), errors(ViewConfig("v", "orders", columns = listOf("note", "owner.id"))), "yöneticiye özel görünümde gizli alan olur")
        assertEquals(setOf("views[0].filter"), errors(ViewConfig("v", "orders", filter = "owner=@me", access = Rule.PUBLIC)))
        assertEquals(setOf("views[0].roles"), errors(ViewConfig("v", "orders", roles = listOf("yazar"))))
        assertEquals(setOf("views[0].report"), errors(ViewConfig("v", "orders", report = ReportConfig(sum = listOf("status")))))
        assertEquals(setOf("views[0].collection"), errors(ViewConfig("v", "yok")))
        assertEquals(setOf("views[0].access"), errors(ViewConfig("v", "orders", access = Rule.OWNER)))
        assertEquals(setOf("views[1].name"), ConfigValidator.validateDetailed(config.copy(views = listOf(views[0], views[0]))).map { it.path }.toSet() - "views[0].name")
    }
}
