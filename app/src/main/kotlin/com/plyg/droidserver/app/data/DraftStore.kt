package com.plyg.droidserver.app.data

import com.plyg.droidserver.core.config.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Sihirbaz taslağının kalıcı saklandığı yer. Testlerde bellek içi sahtesi kullanılır. */
interface DraftStore {
    fun load(): ServerConfig?
    /** Taslağın başladığı etkin konfigürasyon (eski taslaklarda yok). */
    fun base(): ServerConfig?
    fun save(config: ServerConfig, base: ServerConfig?)
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
    private class Write(val config: ServerConfig?, val base: ServerConfig? = null)

    private val writes = Channel<Write>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (w in writes) {
                runCatching { if (w.config == null) repo.clearDraft() else repo.saveDraft(w.config, w.base) }
                    .onFailure { log("Taslak kaydedilemedi: ${it.message}") }
            }
        }
    }

    override fun load(): ServerConfig? = repo.loadDraft()

    override fun base(): ServerConfig? = repo.loadDraftBase()

    override fun save(config: ServerConfig, base: ServerConfig?) {
        writes.trySend(Write(config, base))
    }

    override fun clear() {
        writes.trySend(Write(null))
    }
}
