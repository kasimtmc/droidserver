package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.api.forwardedClient
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FirewallConfig
import dev.droidserver.core.config.ReplicaSource
import dev.droidserver.core.config.ReplicationConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.replication.REPLICA_HEADER
import dev.droidserver.core.replication.forwardedRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Faz 18.2: kopyanın yazma iletimi ve ana sunucunun iletilen istemci adresine güvenmesi. */
class WriteForwardingTest {
    private val key = "yonetici-anahtari-1234567890"
    private val notes = CollectionConfig(
        "notes", listOf(FieldConfig("title", FieldType.TEXT, required = true)),
        rules = Rules(Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC, Rule.PUBLIC),
    )

    @Test
    fun `iletilen uclar ve ulasilamayan ana sunucu`() = testApplication {
        val dead = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/api"
        val config = ServerConfig(
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
            collections = listOf(notes),
            replication = ReplicationConfig(replicaOf = ReplicaSource(dead, key)),
        )
        val routes = forwardedRoutes(config).toSet()
        assertTrue("POST" to "/collections/notes/records" in routes && "PATCH" to "/collections/notes/records/{id}" in routes)
        assertTrue("GET" to "/collections/notes/records" !in routes, "okumalar kopyada")
        assertTrue("POST" to "/batch" in routes)

        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        val r = client.post("/api/collections/notes/records") { contentType(ContentType.Application.Json); setBody("""{"title":"x"}""") }
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue("iletilemedi" in r.bodyAsText(), r.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/collections/notes/records").status, "okuma yerel")
        db.close()
    }

    @Test
    fun `ana sunucu yalnizca kopyanin anahtariyla iletilen adrese guvenir`() = testApplication {
        val config = ServerConfig(
            auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
            collections = listOf(notes),
            firewall = FirewallConfig(deny = listOf("203.0.113.9")),
            replication = ReplicationConfig(source = true),
        )
        val db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        suspend fun get(replica: String?) = client.get("/api/collections/notes/records") {
            replica?.let { header(REPLICA_HEADER, it) }
            header(HttpHeaders.XForwardedFor, "203.0.113.9")
        }.status
        assertEquals(HttpStatusCode.Forbidden, get(key), "kopyanın ilettiği istemci engelli")
        db.close()
    }

    @Test
    fun `iletilen adres yalnizca dogru anahtarla ve ip ise`() {
        val trust = { t: String -> t == key }
        assertEquals("203.0.113.9", forwardedClient(key, "10.0.0.1, 203.0.113.9", trust))
        assertEquals(null, forwardedClient("yanlis", "203.0.113.9", trust))
        assertEquals(null, forwardedClient(null, "203.0.113.9", trust))
        assertEquals(null, forwardedClient(key, "203.0.113.9", null), "kopyalanabilir olmayan sunucu güvenmez")
        assertEquals(null, forwardedClient(key, "yerel", trust), "yerel işareti iletilemez")
    }
}
