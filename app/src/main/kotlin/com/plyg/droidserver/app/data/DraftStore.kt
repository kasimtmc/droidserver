package com.plyg.droidserver.app.data

import com.plyg.droidserver.core.config.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Sihirbaz taslağının kalıcı saklandığı yer. Testlerde bellek içi sahtesi kullanılır. */
interface DraftStore {
    fun load(): ServerConfig?
    fun save(config: ServerConfig)
    fun clear()
}

/**
 * Taslağı files/config/draft.json'a yazar. Yazmalar arka planda ve sırayla yapılır; hızlı yazarken
 * yalnızca en son hal diske gider (CONFLATED), böylece her tuş vuruşu dosya yazmaz.
 */
class FileDraftStore(
    private val repo: ConfigRepository,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
) : DraftStore {
    private class Write(val config: ServerConfig?)

    private val writes = Channel<Write>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (w in writes) {
                runCatching { if (w.config == null) repo.clearDraft() else repo.saveDraft(w.config) }
                    .onFailure { log("Taslak kaydedilemedi: ${it.message}") }
            }
        }
    }

    override fun load(): ServerConfig? = repo.loadDraft()

    override fun save(config: ServerConfig) {
        writes.trySend(Write(config))
    }

    override fun clear() {
        writes.trySend(Write(null))
    }
}
