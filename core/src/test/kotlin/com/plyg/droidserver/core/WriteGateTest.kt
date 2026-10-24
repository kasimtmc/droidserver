package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.api.WritesPausedException
import com.plyg.droidserver.core.config.CollectionTemplate
import com.plyg.droidserver.core.config.HttpConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.server.EngineState
import com.plyg.droidserver.core.server.ServerEngine
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Veri taşıma sırasında salt okunur sunucu. */
class WriteGateTest {

    @Test
    fun `pause suren yazmanin bitmesini bekler, sonrakileri reddeder`() {
        val gate = WriteGate()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var finished = false
        val writer = thread { gate.write { started.countDown(); release.await(); finished = true } }
        started.await()

        val pauser = thread { gate.pause() }
        Thread.sleep(100)
        assertTrue(pauser.isAlive, "süren yazma bitmeden pause dönmemeli")
        assertFailsWith<WritesPausedException> { gate.write { } }

        release.countDown()
        pauser.join(2000)
        writer.join(2000)
        assertTrue(finished && !pauser.isAlive)
        gate.resume()
        assertEquals(1, gate.write { 1 })
    }

    @Test
    fun `salt okunur sunucu okumaya devam eder, yazmalara 503 doner`() {
        val todos = CollectionTemplate.TODOS.create()
        val testDb = TestDb.create(Dialect.SQLITE)
        val engine = ServerEngine({ testDb.open() }) {}
        val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port/api/collections/todos/records"
        fun request(method: String, body: String? = null): Pair<Int, HttpURLConnection> {
            val c = URI(base).toURL().openConnection() as HttpURLConnection
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return c.responseCode to c
        }
        try {
            engine.deploy(ServerConfig(server = HttpConfig(host = "127.0.0.1", port = port), collections = listOf(todos)))
            assertEquals(201, request("POST", """{"title":"a"}""").first)

            engine.pauseWrites()
            assertEquals(true, (engine.state.value as EngineState.Running).writesPaused)
            val (status, conn) = request("POST", """{"title":"b"}""")
            assertEquals(503, status)
            assertEquals("30", conn.getHeaderField("Retry-After"))
            assertTrue(conn.errorStream.bufferedReader().readText().contains("taşınıyor"))
            assertEquals(200, request("GET").first)

            engine.resumeWrites()
            assertEquals(false, (engine.state.value as EngineState.Running).writesPaused)
            assertEquals(201, request("POST", """{"title":"c"}""").first)
        } finally {
            engine.stop()
        }
    }
}
