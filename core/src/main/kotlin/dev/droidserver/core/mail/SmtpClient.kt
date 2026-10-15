package dev.droidserver.core.mail

import dev.droidserver.core.config.EmailConfig
import dev.droidserver.core.config.SmtpSecurity
import dev.droidserver.core.i18n.tr
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SmtpException(message: String) : IOException(message)

/**
 * Bağımlılıksız küçük SMTP istemcisi (RFC 5321): SSL (465) veya STARTTLS (587) ile şifreli bağlantı, sunucu
 * sertifikası ve adı doğrulanır; AUTH PLAIN / LOGIN; UTF-8 konu ve gövde (base64), isteğe bağlı HTML alternatifi.
 * Şifresiz bağlantıda (`none`) parola gönderilmez.
 */
class SmtpClient(
    private val config: EmailConfig,
    private val password: String?,
    private val timeoutMs: Int = 20_000,
    private val ssl: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    /** Testler için; verilmezse sertifikanın sunucu adına ait olduğu TLS el sıkışmasında denetlenir. */
    private val hostnameVerifier: HostnameVerifier? = null,
) {
    /** [to] adresine gönderir; adres veya konuda satır sonu olamaz (başlık enjeksiyonu). */
    fun send(to: String, subject: String, text: String, html: String? = null) {
        require(EMAIL.matches(to)) { tr("Geçersiz alıcı adresi: {0}", to) }
        require('\r' !in subject && '\n' !in subject) { tr("Konu satır sonu içeremez") }
        val raw = Socket()
        raw.connect(InetSocketAddress(config.host, config.port), timeoutMs)
        raw.soTimeout = timeoutMs
        var socket: Socket = if (config.security == SmtpSecurity.SSL) secure(raw) else raw
        try {
            var io = Conversation(socket)
            io.expect(220)
            var caps = io.ehlo()
            if (config.security == SmtpSecurity.STARTTLS) {
                if (caps.none { it.startsWith("STARTTLS") }) throw SmtpException(tr("Sunucu STARTTLS desteklemiyor; SSL (465) deneyin"))
                io.command("STARTTLS", 220)
                socket = secure(socket)
                io = Conversation(socket)
                caps = io.ehlo()
            }
            val user = config.username?.takeIf { it.isNotBlank() }
            if (user != null) {
                if (config.security == SmtpSecurity.NONE) throw SmtpException(tr("Şifresiz bağlantıda parola gönderilmez; STARTTLS veya SSL seçin"))
                val auth = caps.firstOrNull { it.startsWith("AUTH") }.orEmpty()
                val pass = password.orEmpty()
                if ("PLAIN" in auth || "LOGIN" !in auth) {
                    io.command("AUTH PLAIN " + b64("\u0000$user\u0000$pass".toByteArray()), 235, secret = true)
                } else {
                    io.command("AUTH LOGIN", 334)
                    io.command(b64(user.toByteArray()), 334, secret = true)
                    io.command(b64(pass.toByteArray()), 235, secret = true)
                }
            }
            io.command("MAIL FROM:<${config.from}>", 250)
            io.command("RCPT TO:<$to>", 250, 251)
            io.command("DATA", 354)
            io.write(message(to, subject, text, html))
            io.command(".", 250)
            runCatching { io.command("QUIT", 221) }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun secure(plain: Socket): Socket {
        val s = ssl.createSocket(plain, config.host, config.port, true) as SSLSocket
        s.useClientMode = true
        if (hostnameVerifier == null) s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.startHandshake()
        if (hostnameVerifier != null && !hostnameVerifier.verify(config.host, s.session)) {
            s.close()
            throw SmtpException(tr("Sunucunun sertifikası {0} adına ait değil", config.host))
        }
        return s
    }

    private fun message(to: String, subject: String, text: String, html: String?): String = buildString {
        val crlf = "\r\n"
        val domain = config.from.substringAfter('@')
        val from = config.fromName?.takeIf { it.isNotBlank() }?.let { "${encodeWord(it)} <${config.from}>" } ?: config.from
        append("From: $from$crlf")
        append("To: $to$crlf")
        append("Subject: ${encodeWord(subject)}$crlf")
        append("Date: ${ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US))}$crlf")
        append("Message-ID: <${UUID.randomUUID()}@$domain>$crlf")
        append("MIME-Version: 1.0$crlf")
        if (html == null) {
            append(part("text/plain", text))
        } else {
            val boundary = "ds-" + UUID.randomUUID()
            append("Content-Type: multipart/alternative; boundary=\"$boundary\"$crlf$crlf")
            append("--$boundary$crlf").append(part("text/plain", text))
            append("--$boundary$crlf").append(part("text/html", html))
            append("--$boundary--$crlf")
        }
    }

    private fun part(type: String, body: String): String {
        val encoded = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
        return "Content-Type: $type; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$encoded\r\n"
    }

    /** SMTP konuşması: yanıt kodlarını denetler; hata mesajına sunucunun yanıtı eklenir (parolalar hariç). */
    private class Conversation(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val out: OutputStream = socket.getOutputStream()

        fun write(data: String) {
            // Satır başındaki nokta ikilenir (RFC 5321 4.5.2).
            val stuffed = data.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            out.write(stuffed.toByteArray(Charsets.UTF_8))
            if (!stuffed.endsWith("\r\n")) out.write("\r\n".toByteArray())
            out.flush()
        }

        /** Çok satırlı yanıtın kodu ve satırları. */
        fun read(): Pair<Int, List<String>> {
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: throw SmtpException(tr("Sunucu bağlantıyı kapattı"))
                lines += line
                if (line.length < 4 || line[3] != '-') break
            }
            val code = lines.last().take(3).toIntOrNull() ?: throw SmtpException(tr("Anlaşılmayan yanıt: {0}", lines.last()))
            return code to lines.map { it.drop(4) }
        }

        fun expect(vararg codes: Int): List<String> {
            val (code, lines) = read()
            if (code !in codes) throw SmtpException(explain(code, lines))
            return lines
        }

        fun command(line: String, vararg codes: Int, secret: Boolean = false): List<String> {
            out.write("$line\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val (code, lines) = read()
            if (code !in codes) {
                val what = if (secret) tr("kimlik doğrulama") else line.substringBefore(':').substringBefore(' ')
                throw SmtpException("$what: ${explain(code, lines)}")
            }
            return lines
        }

        fun ehlo(): List<String> = command("EHLO droidserver", 250).map { it.uppercase(Locale.ROOT) }

        private fun explain(code: Int, lines: List<String>): String {
            val reply = "$code ${lines.joinToString(" ").take(300)}"
            return when (code) {
                535, 534 -> tr("kullanıcı adı veya parola kabul edilmedi ({0}). Gmail/Outlook'ta hesap parolası yerine uygulama şifresi gerekir", reply)
                else -> tr("sunucu reddetti ({0})", reply)
            }
        }
    }

    companion object {
        val EMAIL = Regex("^[^@\\s<>\"]+@[^@\\s<>\"]+\\.[^@\\s<>\"]+$")

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        /** RFC 2047: ASCII dışı karakter varsa `=?UTF-8?B?...?=`. */
        fun encodeWord(s: String): String =
            if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + b64(s.toByteArray(Charsets.UTF_8)) + "?="
    }
}
