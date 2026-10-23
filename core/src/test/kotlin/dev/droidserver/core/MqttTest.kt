package dev.droidserver.core

import dev.droidserver.core.alerts.Alert
import dev.droidserver.core.alerts.AlertChannel
import dev.droidserver.core.alerts.AlertKind
import dev.droidserver.core.alerts.HealthMonitor
import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.config.AlertsConfig
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.HomeAssistantConfig
import dev.droidserver.core.config.HttpConfig
import dev.droidserver.core.config.MqttConfig
import dev.droidserver.core.config.MqttDevice
import dev.droidserver.core.config.MqttMessage
import dev.droidserver.core.config.MqttRoute
import dev.droidserver.core.config.MqttScene
import dev.droidserver.core.config.SensorConfig
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.mqtt.HomeAssistant
import dev.droidserver.core.mqtt.MqttBroker
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 16.12: MQTT sunucusu, mesajların koleksiyona yazılması ve kayıt olaylarının yayını. */
class MqttTest {
    private val key = "yonetici-anahtari-1234567890"
    private val readings = CollectionConfig(
        "readings",
        listOf(
            FieldConfig("device", FieldType.TEXT),
            FieldConfig("temp", FieldType.REAL),
            FieldConfig("humidity", FieldType.INTEGER),
        ),
    )
    private val httpPort = ServerSocket(0).use { it.localPort }
    private val mqttPort = ServerSocket(0).use { it.localPort }
    private val config = ServerConfig(
        server = HttpConfig(port = httpPort, host = "127.0.0.1"),
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(readings),
        mqtt = MqttConfig(
            enabled = true, port = mqttPort, username = "device", password = "cihaz-parolasi",
            routes = listOf(MqttRoute("sensors/+/temp", "readings", valueField = "temp", topicField = "device"), MqttRoute("sensors/+/all", "readings", topicField = "device")),
            events = listOf("readings"),
        ),
    )
    private lateinit var db: SqlDriver
    private lateinit var server: EmbeddedServer<*, *>

    @BeforeTest
    fun start() {
        db = TestDb.create(Dialect.SQLITE).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
        server = embeddedServer(CIO, port = httpPort, host = "127.0.0.1") { droidServerModule(config, db) }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        server.stop(100, 1000)
        db.close()
    }

    /** Test için en küçük MQTT istemcisi. */
    private class Client(port: Int, id: String, user: String, pass: String) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        val input = DataInputStream(socket.getInputStream())
        val connack: Int

        init {
            fun s(v: String) = v.toByteArray().let { byteArrayOf((it.size shr 8).toByte(), it.size.toByte()) + it }
            send(0x10, s("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 60) + s(id) + s(user) + s(pass))
            val (type, body) = read()
            assertEquals(2, type)
            connack = body[1].toInt()
        }

        fun send(header: Int, body: ByteArray) = socket.getOutputStream().write(MqttBroker.packet(header, body))

        /** Son okunan paketin bayrakları (PUBLISH'te RETAIN = 1). */
        var flags = 0

        fun read(): Pair<Int, ByteArray> {
            val first = input.readUnsignedByte()
            flags = first and 0x0F
            var len = 0
            var mul = 1
            while (true) {
                val d = input.readUnsignedByte()
                len += (d and 0x7F) * mul
                if (d and 0x80 == 0) break
                mul *= 128
            }
            return (first shr 4) to ByteArray(len).also(input::readFully)
        }

        fun publish(topic: String, payload: String, qos: Int = 0, pid: Int = 1) {
            val out = ByteArrayOutputStream()
            topic.toByteArray().let { out.write(it.size shr 8); out.write(it.size); out.write(it) }
            if (qos > 0) { out.write(pid shr 8); out.write(pid) }
            out.write(payload.toByteArray())
            send(0x30 or (qos shl 1), out.toByteArray())
        }

        fun subscribe(filter: String, pid: Int = 7) {
            val f = filter.toByteArray()
            send(0x82, byteArrayOf(0, pid.toByte(), (f.size shr 8).toByte(), f.size.toByte()) + f + byteArrayOf(0))
            val (type, body) = read()
            assertEquals(9, type)
            assertEquals(0, body[2].toInt(), "izin verilen QoS")
        }

        /** Gelen yayın: konu ve içerik. */
        fun nextPublish(): Pair<String, String> {
            val (type, body) = read()
            assertEquals(3, type)
            val len = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            return String(body, 2, len) to String(body, 2 + len, body.size - 2 - len)
        }

        override fun close() = socket.close()
    }

    private fun records() = Json.parseToJsonElement(http("GET", "/collections/readings/records?sort=id").second).jsonObject.getValue("items").jsonArray.map { it.jsonObject }

    private fun http(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$httpPort/api$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("zaman aşımı: $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun `yalnizca yerel ag`() {
        val port = ServerSocket(0).use { it.localPort }
        val logs = mutableListOf<String>()
        val broker = MqttBroker(port, "device", "cihaz-parolasi", "127.0.0.1", fromInternet = { true }, log = { logs += it })
        broker.start()
        try {
            assertFailsWith<java.io.IOException>("internetten gelen bağlantı CONNACK almadan kapanır") { Client(port, "dis", "device", "cihaz-parolasi") }
            assertTrue(logs.any { "internetten" in it }, logs.toString())
        } finally {
            broker.stop()
        }
    }

    /** 17.8: sahne, Home Assistant keşfi, sensör panosu ve eşik uyarısı. */
    @Test
    fun `ev otomasyonu`() {
        val http2 = ServerSocket(0).use { it.localPort }
        val mqtt2 = ServerSocket(0).use { it.localPort }
        val home = config.copy(
            server = HttpConfig(port = http2, host = "127.0.0.1"),
            mqtt = config.mqtt.copy(
                port = mqtt2, events = emptyList(),
                scenes = listOf(MqttScene("Akşam", listOf(MqttMessage("ev/salon/lamba", "on"), MqttMessage("ev/salon/perde", "kapali", retain = true)))),
                homeAssistant = HomeAssistantConfig(enabled = true),
            ),
            sensors = listOf(SensorConfig("readings", "temp", name = "Salon sıcaklığı", unit = "°C", deviceClass = "temperature", min = 10.0, max = 30.0)),
        )
        assertTrue(ConfigValidator.validateDetailed(home).isEmpty(), ConfigValidator.validateDetailed(home).toString())
        val alerts = Collections.synchronizedList(mutableListOf<Alert>())
        val health = HealthMonitor().also { it.configure(AlertsConfig(), mapOf("test" to AlertChannel { a -> alerts += a }), "Ev") }
        val server2 = embeddedServer(CIO, port = http2, host = "127.0.0.1") { droidServerModule(home, db, health = health) }.start(wait = false)
        fun call(method: String, path: String): Pair<Int, String> {
            val c = URI("http://127.0.0.1:$http2/api$path").toURL().openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $key")
            val code = c.responseCode
            return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty())
        }
        try {
            Client(mqtt2, "ha", "device", "cihaz-parolasi").use { ha ->
                // Keşif mesajı tutulur: sonradan abone olan Home Assistant da alır.
                ha.subscribe("homeassistant/#")
                val (topic, payload) = ha.nextPublish()
                assertTrue(Regex("homeassistant/sensor/droidserver_[a-z0-9_]+_readings_temp/config").matches(topic), topic)
                val discovery = Json.parseToJsonElement(payload).jsonObject
                assertEquals("droidserver/readings/state", discovery.getValue("state_topic").jsonPrimitive.content)
                assertEquals("{{ value_json.temp }}", discovery.getValue("value_template").jsonPrimitive.content)
                assertEquals("°C", discovery.getValue("unit_of_measurement").jsonPrimitive.content)

                // Cihaz değeri → kayıt → Home Assistant durumu; eşik dışı değer sağlık uyarısı.
                ha.subscribe("droidserver/readings/state")
                Client(mqtt2, "sensor", "device", "cihaz-parolasi").use { it.publish("sensors/salon/temp", "35.5") }
                val (stateTopic, state) = ha.nextPublish()
                assertEquals("droidserver/readings/state", stateTopic)
                assertEquals(35.5, Json.parseToJsonElement(state).jsonObject.getValue("temp").jsonPrimitive.content.toDouble())
                waitFor("eşik uyarısı") { alerts.any { it.kind == AlertKind.SENSOR } }
                assertTrue("üst eşiğin" in alerts.first { it.kind == AlertKind.SENSOR }.message)

                // Sahne: mesajlar sırayla gider.
                ha.subscribe("ev/#")
                val (code, body) = call("POST", "/_mqtt/scenes/Ak%C5%9Fam")
                assertEquals(200, code, body)
                assertEquals("ev/salon/lamba" to "on", ha.nextPublish())
                assertEquals("ev/salon/perde" to "kapali", ha.nextPublish())
                assertEquals(404, call("POST", "/_mqtt/scenes/yok").first)
            }
            // Sensör panosu.
            val (code, body) = call("GET", "/_sensors?hours=1")
            assertEquals(200, code, body)
            val series = Json.parseToJsonElement(body).jsonArray.single().jsonObject
            assertEquals(35.5, series.getValue("last").jsonObject.getValue("value").jsonPrimitive.content.toDouble())
            assertTrue(series.getValue("points").jsonArray.isNotEmpty())
        } finally {
            server2.stop(100, 1000)
        }
    }

    @Test
    fun `home assistant kesfi listeden cikan sensoru siler`() {
        val port = ServerSocket(0).use { it.localPort }
        val broker = MqttBroker(port, "device", "cihaz-parolasi", "127.0.0.1")
        broker.start()
        try {
            val withSensor = config.copy(mqtt = config.mqtt.copy(homeAssistant = HomeAssistantConfig(enabled = true)), sensors = listOf(SensorConfig("readings", "temp"), SensorConfig("readings", "humidity")))
            HomeAssistant.announce(broker, withSensor)
            assertEquals(2, broker.retainedTopics().size)
            broker.publish("homeassistant/sensor/baska_bir_sey/config", "{}".toByteArray(), retain = true)
            HomeAssistant.announce(broker, withSensor.copy(sensors = listOf(SensorConfig("readings", "temp"))))
            val left = broker.retainedTopics()
            assertEquals(2, left.size, "çıkan sensör silinir, başkasının keşfine dokunulmaz: $left")
            assertTrue(left.any { it.endsWith("_readings_temp/config") } && "homeassistant/sensor/baska_bir_sey/config" in left)
        } finally {
            broker.stop()
        }
    }

    @Test
    fun `cihaz basina kimlik`() {
        val port = ServerSocket(0).use { it.localPort }
        // Ortak parola boş: yalnızca listedeki cihazlar.
        val broker = MqttBroker(port, "device", "", "127.0.0.1", devices = mapOf("salon" to "salon-parolasi-1", "mutfak" to "mutfak-parolasi-2"))
        broker.start()
        try {
            Client(port, "x", "device", "").use { assertEquals(4, it.connack, "ortak giriş kapalı") }
            Client(port, "y", "salon", "mutfak-parolasi-2").use { assertEquals(4, it.connack, "başka cihazın parolası geçmez") }
            Client(port, "esp-salon", "salon", "salon-parolasi-1").use { c ->
                assertEquals(0, c.connack)
                c.subscribe("ev/#")
                assertEquals("salon", broker.clients().single().user)
            }
        } finally {
            broker.stop()
        }
        fun errors(m: MqttConfig) = ConfigValidator.validateDetailed(config.copy(mqtt = m)).filter { it.path.startsWith("mqtt") }.map { it.path }
        val base = config.mqtt.copy(password = "")
        assertTrue(errors(base).contains("mqtt.password"), "cihaz yoksa ortak parola gerekir")
        assertTrue(errors(base.copy(devices = listOf(MqttDevice("salon", "salon-parolasi-1")))).isEmpty())
        assertTrue(errors(base.copy(devices = listOf(MqttDevice("salon", "salon-parolasi-1"), MqttDevice("salon", "baska-parola-9")))).contains("mqtt.devices[1].name"))
        assertTrue(errors(base.copy(devices = listOf(MqttDevice("salon", "kisa")))).contains("mqtt.devices[0].password"))
    }

    @Test
    fun `tutulan mesajlar`() {
        val port = ServerSocket(0).use { it.localPort }
        val file = java.io.File.createTempFile("ds-mqtt", ".json").apply { delete() }
        var broker = MqttBroker(port, "device", "cihaz-parolasi", "127.0.0.1", retainedFile = file)
        broker.start()
        try {
            Client(port, "lamba", "device", "cihaz-parolasi").use { a ->
                a.send(0x31, "ev/salon/lamba".toByteArray().let { byteArrayOf(0, it.size.toByte()) + it } + "on".toByteArray())
                a.publish("ev/salon/sicaklik", "23") // tutulmaz
            }
            waitFor("tutuldu") { broker.retainedCount() == 1 }
            Client(port, "telefon", "device", "cihaz-parolasi").use { b ->
                b.subscribe("ev/#")
                assertEquals("ev/salon/lamba" to "on", b.nextPublish())
                assertEquals(1, b.flags, "yeni aboneliğe RETAIN bayrağıyla")
                broker.publish("ev/salon/lamba", "off".toByteArray(), retain = true)
                assertEquals("ev/salon/lamba" to "off", b.nextPublish())
                assertEquals(0, b.flags, "var olan aboneye RETAIN bayraksız")
            }
            broker.stop()
            assertTrue(file.isFile, "durunca dosyaya yazılır")

            broker = MqttBroker(port, "device", "cihaz-parolasi", "127.0.0.1", retainedFile = file)
            broker.start()
            assertEquals(1, broker.retainedCount(), "yeniden başlayınca geri gelir")
            Client(port, "telefon", "device", "cihaz-parolasi").use { b ->
                b.subscribe("ev/salon/+")
                assertEquals("ev/salon/lamba" to "off", b.nextPublish())
            }
            broker.publish("ev/salon/lamba", ByteArray(0), retain = true)
            assertEquals(0, broker.retainedCount(), "boş içerik tutulanı siler")
        } finally {
            broker.stop()
            file.delete()
        }
    }

    @Test
    fun `konu suzgecleri ve dogrulama`() {
        assertTrue(MqttBroker.matches("sensors/+/temp", "sensors/salon/temp"))
        assertFalse(MqttBroker.matches("sensors/+/temp", "sensors/salon/nem"))
        assertTrue(MqttBroker.matches("ev/#", "ev/salon/lamba"))
        assertTrue(MqttBroker.matches("ev/#", "ev"))
        assertFalse(MqttBroker.matches("#", "\$SYS/x"))
        assertEquals(listOf("salon"), MqttBroker.captures("sensors/+/temp", "sensors/salon/temp"))
        assertFalse(MqttBroker.validFilter("a/#/b"))
        assertFalse(MqttBroker.validFilter("a/b+"))

        assertEquals(emptyList(), ConfigValidator.validate(config))
        fun errors(m: MqttConfig) = ConfigValidator.validate(config.copy(mqtt = m)).joinToString("\n")
        assertTrue("en az 8" in errors(config.mqtt.copy(password = "kisa")))
        assertTrue("portuyla aynı" in errors(config.mqtt.copy(port = httpPort)))
        assertTrue("Geçersiz konu" in errors(config.mqtt.copy(routes = listOf(MqttRoute("a/#/b", "readings")))))
        assertTrue("'yok' koleksiyonu yok" in errors(config.mqtt.copy(routes = listOf(MqttRoute("a", "yok")))))
        assertTrue("alanı yok" in errors(config.mqtt.copy(routes = listOf(MqttRoute("a", "readings", valueField = "x")))))
    }

    @Test
    fun `cihaz mesajlari kayda yazilir, olaylar yayinlanir`() {
        assertEquals(4, Client(mqttPort, "kotu", "device", "yanlis").use { it.connack })

        Client(mqttPort, "izleyici", "device", "cihaz-parolasi").use { watcher ->
            assertEquals(0, watcher.connack)
            watcher.subscribe("droidserver/readings/+")
            watcher.subscribe("ev/#", pid = 8)

            Client(mqttPort, "esp32-salon", "device", "cihaz-parolasi").use { esp ->
                // Düz değer → değer alanı; konudaki + bölümü → cihaz alanı. QoS 1: PUBACK.
                esp.publish("sensors/salon/temp", "23.5", qos = 1, pid = 5)
                val (ack, body) = esp.read()
                assertEquals(4, ack)
                assertEquals(5, body[1].toInt())
                // JSON nesnesi: bilinen alanlar yazılır, bilinmeyen atlanır. QoS 2: PUBREC → PUBREL → PUBCOMP.
                esp.publish("sensors/mutfak/all", """{"temp":19.0,"humidity":55,"pil":80}""", qos = 2, pid = 6)
                assertEquals(5, esp.read().first)
                esp.publish("sensors/mutfak/all", """{"temp":19.0,"humidity":55}""", qos = 2, pid = 6) // yineleme
                assertEquals(5, esp.read().first)
                esp.send(0x62, byteArrayOf(0, 6))
                assertEquals(7, esp.read().first)
                // Yönlendirmesi olmayan konu kaydedilmez ama abonelere gider.
                esp.publish("ev/salon/sicaklik", "21")
            }

            waitFor("iki kayıt") { records().size == 2 }
            val rows = records()
            assertEquals(listOf("salon", "mutfak"), rows.map { it.getValue("device").jsonPrimitive.content })
            assertEquals("23.5", rows[0].getValue("temp").jsonPrimitive.content)
            assertEquals("55", rows[1].getValue("humidity").jsonPrimitive.content)

            // Kayıt olayları ve cihazlar arası mesaj abonelere gider.
            val got = List(3) { watcher.nextPublish() }
            assertEquals(listOf("droidserver/readings/create", "droidserver/readings/create", "ev/salon/sicaklik"), got.map { it.first })
            assertTrue("\"device\":\"salon\"" in got[0].second, got[0].second)
            assertEquals("21", got[2].second)

            // Uygulamadan / API'den cihaza komut.
            val (code, sent) = http("POST", "/_mqtt/publish", """{"topic":"ev/salon/lamba","payload":"on"}""")
            assertEquals(200, code, sent)
            assertEquals("ev/salon/lamba" to "on", watcher.nextPublish())
        }

        val status = Json.parseToJsonElement(http("GET", "/_mqtt").second).jsonObject
        assertEquals("true", status.getValue("listening").jsonPrimitive.content)
        assertTrue(status.getValue("recent").jsonArray.any { "readings #" in it.jsonObject.getValue("result").jsonPrimitive.content }, status.toString())
        assertEquals(400, http("POST", "/_mqtt/publish", """{"topic":"ev/#"}""").first)
    }
}
