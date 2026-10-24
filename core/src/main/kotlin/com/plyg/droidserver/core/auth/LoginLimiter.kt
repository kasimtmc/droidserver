package com.plyg.droidserver.core.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Giriş denemelerine sınır (bellekte): aynı istemciden aynı e-postaya [perAccount] hatalı denemeden sonra, ya da
 * aynı istemciden toplam [perClient] hatalı denemeden sonra [windowMinutes] dakika boyunca giriş reddedilir (429).
 * Pencere ilk hatalı denemeyle başlar; başarılı giriş o e-postanın sayacını sıfırlar.
 */
class LoginLimiter(
    private val perAccount: Int = 5,
    private val perClient: Int = 20,
    private val windowMinutes: Int = 15,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()
    private val windowMs get() = windowMinutes * 60_000L

    /** Giriş denenebiliyorsa null; değilse kaç saniye sonra denenebileceği. */
    fun blockedFor(client: String, email: String): Long? {
        val now = clock()
        return listOf(accountKey(client, email) to perAccount, clientKey(client) to perClient).maxOfOrNull { (key, limit) ->
            val w = windows[key] ?: return@maxOfOrNull 0L
            synchronized(w) {
                if (now - w.start >= windowMs || w.count < limit) 0L else (w.start + windowMs - now + 999) / 1000
            }
        }?.takeIf { it > 0 }
    }

    fun failed(client: String, email: String) {
        val now = clock()
        if (windows.size > MAX_TRACKED) windows.entries.removeIf { now - it.value.start >= windowMs }
        for (key in listOf(accountKey(client, email), clientKey(client))) {
            val w = windows.computeIfAbsent(key) { Window(now, 0) }
            synchronized(w) {
                if (now - w.start >= windowMs) { w.start = now; w.count = 0 }
                w.count++
            }
        }
    }

    fun succeeded(client: String, email: String) {
        windows.remove(accountKey(client, email))
    }

    private fun accountKey(client: String, email: String) = "$client|$email"
    private fun clientKey(client: String) = "$client|"

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
