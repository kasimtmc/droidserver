package com.plyg.droidserver.core.net

import com.plyg.droidserver.core.i18n.tr
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * [url]'e giderken alınan ağ hatasını (ad çözümü, bağlantı, zaman aşımı, TLS) arayüz dilinde anlaşılır bir iletiye
 * çevirir; Android'in "Unable to resolve host …" gibi İngilizce metni yerine. Diğer hatalar olduğu gibi döner.
 */
fun readableNetError(e: Throwable, url: String): Throwable {
    val host = runCatching { URI(url).host }.getOrNull() ?: url
    val message = when (e) {
        is UnknownHostException -> tr("{0} adresi bulunamadı; internet bağlantısını denetleyip yeniden deneyin", host)
        is SocketTimeoutException -> tr("{0} zamanında yanıt vermedi; bağlantı yavaş olabilir, yeniden deneyin", host)
        is ConnectException, is NoRouteToHostException -> tr("{0} adresine bağlanılamadı; internet bağlantısını denetleyip yeniden deneyin", host)
        is SSLException -> tr("{0} ile güvenli bağlantı kurulamadı; telefonun tarih ve saatini denetleyin", host)
        else -> return e
    }
    return IOException(message, e)
}
