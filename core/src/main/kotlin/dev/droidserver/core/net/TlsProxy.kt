package dev.droidserver.core.net

import dev.droidserver.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Doğrudan erişimin HTTPS ucu. HTTP sunucusu (Ktor CIO) TLS desteklemediği için TLS burada sonlanır ve istek
 * yerel HTTP sunucusuna aktarılır. Her bağlantıda yalnızca ilk isteğin başlıkları düzenlenir:
 *  - istemcinin gönderdiği `X-Forwarded-For`, `X-Real-IP`, `Forwarded`, `CF-Connecting-IP` silinir (taklit edilemesin),
 *    gerçek adres `X-Forwarded-For` olarak eklenir (sunucu yalnızca yerel bağlantıdan gelen bu başlığa güvenir);
 *  - `Connection: close` konur: her bağlantı tek istek taşır, böylece sonraki isteklerin başlıklarını çözmek gerekmez.
 *
 * Aynı anda en çok [maxConnections] bağlantı; başlıklar [headerTimeoutMs] içinde gelmezse bağlantı kapanır
 * (yavaş istemciyle kaynak tüketme saldırısına karşı).
 */
class TlsProxy(
    private val ssl: SSLContext,
    private val bind: InetSocketAddress,
    private val targetPort: Int,
    private val log: (String) -> Unit = {},
    private val maxConnections: Int = 64,
    private val headerTimeoutMs: Int = 15_000,
    private val idleTimeoutMs: Int = 120_000,
) : AutoCloseable {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tls-proxy").apply { isDaemon = true } }
    private val slots = Semaphore(maxConnections)
    private val open = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private var server: SSLServerSocket? = null

    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: -1

    fun start(): TlsProxy {
        val s = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
        s.reuseAddress = true
        s.bind(bind, 128)
        // Eski protokolleri kapat; Conscrypt ve JDK'nın güncel varsayılanları zaten TLS 1.2+.
        s.enabledProtocols = s.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        server = s
        running = true
        pool.execute { acceptLoop(s) }
        log(tr("HTTPS ucu {0} üzerinde (→ 127.0.0.1:{1})", "${bind.address.hostAddress}:${s.localPort}", targetPort))
        return this
    }

    override fun close() {
        running = false
        runCatching { server?.close() }
        // Süren bağlantılar da kesilir: erişim kapatıldığında açık kalan oturum olmasın.
        open.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: IOException) {
                if (running) log(tr("HTTPS ucu bağlantı kabul edemedi: {0}", e.message))
                continue
            }
            if (!running || !slots.tryAcquire()) {
                runCatching { client.close() } // dolu: yeni bağlantıları reddet
                continue
            }
            open += client
            pool.execute {
                try {
                    handle(client as SSLSocket)
                } catch (_: IOException) {
                    // istemci veya sunucu kapattı; TLS el sıkışma hataları dahil
                } finally {
                    runCatching { client.close() }
                    open -= client
                    slots.release()
                }
            }
        }
    }

    private fun handle(client: SSLSocket) {
        client.soTimeout = headerTimeoutMs
        client.startHandshake()
        val clientIn = client.inputStream
        val head = readHead(clientIn) ?: return reject(client.outputStream, 431, "Request Header Fields Too Large")
        val ip = (client.remoteSocketAddress as? InetSocketAddress)?.address?.let(::plainAddress) ?: "unknown"

        Socket().use { backend ->
            open += backend
            try {
                backend.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 5_000)
                backend.soTimeout = idleTimeoutMs
                client.soTimeout = idleTimeoutMs
                val out = backend.getOutputStream()
                out.write(rewrite(head.headers, ip))
                out.write(head.rest)
                out.flush()
                // Gövdenin kalanı istemciden sunucuya, yanıt sunucudan istemciye.
                val upstream = pool.submit { pump(clientIn, out) { runCatching { backend.shutdownOutput() } } }
                pump(backend.getInputStream(), client.outputStream) {}
                upstream.cancel(true)
            } finally {
                open -= backend
            }
        }
    }

    private class Head(val headers: String, val rest: ByteArray)

    /** İstek satırı ve başlıklar (`\r\n\r\n`'e kadar, en çok 16 KB) ile aynı okumada gelen gövde parçası. */
    private fun readHead(input: InputStream): Head? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (buf.size() < MAX_HEADER) {
            val n = input.read(chunk)
            if (n < 0) return null
            buf.write(chunk, 0, n)
            val bytes = buf.toByteArray()
            val end = indexOf(bytes, CRLFCRLF)
            if (end >= 0) {
                return Head(String(bytes, 0, end, Charsets.ISO_8859_1), bytes.copyOfRange(end + 4, bytes.size))
            }
        }
        return null
    }

    private fun rewrite(head: String, ip: String): ByteArray {
        val lines = head.split("\r\n")
        val kept = lines.drop(1).filterNot { line ->
            val name = line.substringBefore(':').trim().lowercase()
            name in DROPPED
        }
        val out = buildString {
            append(lines.first()).append("\r\n")
            kept.forEach { append(it).append("\r\n") }
            append("X-Forwarded-For: ").append(ip).append("\r\n")
            append("X-Forwarded-Proto: https\r\n")
            append("Connection: close\r\n\r\n")
        }
        return out.toByteArray(Charsets.ISO_8859_1)
    }

    private fun reject(out: OutputStream, status: Int, reason: String) {
        runCatching {
            out.write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun pump(from: InputStream, to: OutputStream, onEnd: () -> Unit) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: SocketException) {
        } catch (_: IOException) {
        } finally {
            onEnd()
        }
    }

    companion object {
        private const val MAX_HEADER = 16 * 1024
        private val CRLFCRLF = "\r\n\r\n".toByteArray()
        private val DROPPED = setOf(
            "x-forwarded-for", "x-forwarded-proto", "x-real-ip", "forwarded", "cf-connecting-ip",
            "connection", "keep-alive", "proxy-connection",
        )

        private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
            outer@ for (i in 0..data.size - pattern.size) {
                for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
                return i
            }
            return -1
        }

        /** IPv4'e eşlenmiş IPv6 adresini (`::ffff:1.2.3.4`) düz IPv4 olarak yazar. */
        fun plainAddress(a: InetAddress): String = a.hostAddress.removePrefix("::ffff:").substringBefore('%')
    }
}
