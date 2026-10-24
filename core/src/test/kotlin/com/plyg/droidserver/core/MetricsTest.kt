package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.droidServerModule
import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.metrics.DeviceSnapshot
import com.plyg.droidserver.core.metrics.Metrics
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {
    private val key = "yonetici-anahtari-1234567890"

    @Test
    fun `uc adlari soyutlanir`() {
        assertEquals("/api/collections/todos/records/:id", Metrics.routeOf("/api/collections/todos/records/42"))
        assertEquals("/api/files/photos/:id/:name", Metrics.routeOf("/api/files/photos/7/foto_abc.png?thumb=100x100"))
        assertEquals("/api/health", Metrics.routeOf("/api/health"))
    }

    @Test
    fun `istek ve cihaz metrikleri, json ve prometheus`() = testApplication {
        val db = TestDb.create(Dialect.SQLITE).open()
        val todos = CollectionConfig("todos", listOf(FieldConfig("title", FieldType.TEXT)))
        SchemaMigrator(db).migrate(listOf(todos))
        var now = 1_000_000L
        val metrics = Metrics { now }
        val config = ServerConfig(auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)), collections = listOf(todos))
        application { droidServerModule(config, db, metrics = metrics) }

        repeat(3) { client.get("/api/health") }
        client.get("/api/collections/todos/records/1") { bearerAuth(key) } // 404
        client.get("/api/collections/todos/records/2") { bearerAuth(key) } // 404
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/_metrics").status)
        metrics.sampleDevice(DeviceSnapshot(batteryPercent = 80, batteryTempC = 36.5, charging = true))
        now += 60_000
        client.get("/api/health")

        val m = Json.parseToJsonElement(client.get("/api/_metrics") { bearerAuth(key) }.bodyAsText()).jsonObject
        val req = m.getValue("requests").jsonObject
        assertEquals(7, req.getValue("total").jsonPrimitive.content.toInt(), "401 dahil")
        val byStatus = req.getValue("byStatus").jsonObject
        assertEquals("4", byStatus.getValue("2xx").jsonPrimitive.content)
        assertEquals("3", byStatus.getValue("4xx").jsonPrimitive.content)
        assertEquals("1", req.getValue("active").jsonPrimitive.content, "bu istek sürüyor")
        val routes = m.getValue("routes").jsonArray.associate { it.jsonObject.getValue("route").jsonPrimitive.content to it.jsonObject }
        assertEquals("2", routes.getValue("GET /api/collections/todos/records/:id").getValue("count").jsonPrimitive.content)
        assertEquals("4", routes.getValue("GET /api/health").getValue("count").jsonPrimitive.content)
        val minutes = m.getValue("minutes").jsonArray
        assertEquals(2, minutes.size)
        assertEquals("36.5", minutes[0].jsonObject.getValue("batteryTempC").jsonPrimitive.content)
        assertEquals("80", m.getValue("device").jsonObject.getValue("batteryPercent").jsonPrimitive.content)

        val prom = client.get("/api/_metrics?format=prometheus") { bearerAuth(key) }.bodyAsText()
        assertTrue("droidserver_requests_total{status=\"2xx\"} 4" in prom, "başarılı _metrics okumaları sayılmaz: $prom")
        assertTrue("droidserver_requests_active 1\n" in prom, "önceki okuma süren istek olarak kalmaz: $prom")
        assertTrue("droidserver_battery_temperature_celsius 36.50" in prom, prom)
        assertTrue(Regex("droidserver_route_duration_ms\\{route=\"GET /api/health\",quantile=\"0.95\"\\} \\d+").containsMatchIn(prom), prom)
        db.close()
    }

    @Test
    fun `dakikalik seri son 60 dakika`() {
        var now = 0L
        val m = Metrics { now }
        repeat(90) {
            m.finished("GET", "/api/health", 200, 5)
            now += 60_000
        }
        val s = m.series()
        assertEquals(60, s.size)
        assertEquals(30L * 60_000, s.first().startMs)
        assertEquals(5.0, s.last().avgMs)
    }
}
