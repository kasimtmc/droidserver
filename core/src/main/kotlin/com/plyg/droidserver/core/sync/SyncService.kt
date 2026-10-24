package com.plyg.droidserver.core.sync

import com.plyg.droidserver.core.api.Access
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.ApiNames
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.auth.Caller
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.SyncConflict
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/** Eşitlemeden gelen yazmanın kayıt servisi karşılığı: kurallar, script kancaları ve olaylar (canlı, webhook) dahil. */
interface SyncWriter {
    fun create(c: CollectionConfig, caller: Caller, body: JsonObject): JsonObject
    fun update(c: CollectionConfig, caller: Caller, id: Long, body: JsonObject): JsonObject
    fun delete(c: CollectionConfig, caller: Caller, id: Long)
}

/**
 * Çevrimdışı istemci eşitlemesi.
 *
 * **Çekme** (`GET /sync/{c}?since=&epoch=`): imleçten sonraki değişiklikler, kayıt başına güncel hal ve sürüm. Liste
 * kuralı ve alan gizliliği uygulanır; istemcinin artık göremediği (silinen veya kuralın dışına çıkan) kayıt `delete`
 * olarak gelir. `reset`: iz yeniden kuruldu (`epoch` değişti) ya da imleç silinmiş izlerden eski; istemci yerel kopyayı
 * silip baştan eşitlenir.
 *
 * **Gönderme** (`POST /sync/{c}`): bekleyen değişiklikler birer birer (biri hatalıysa diğerleri yine uygulanır).
 * Güncelleme ve silme, istemcinin son gördüğü sürümle (`base`) gelir. O sürümden sonra sunucuda da değişen alan
 * çakışmadır: `lww` iken alan başına düzenleme zamanı yeni olan kazanır (istemci saati gönderdiği `now` ile sunucu
 * saatine göre düzeltilir), `manual` iken istemcinin o alanı uygulanmaz ve çakışma yanıtta döner.
 */
class SyncService(
    private val db: SqlDriver,
    private val services: Map<String, RecordService>,
    private val names: (String) -> ApiNames,
    private val out: (CollectionConfig, JsonObject) -> JsonObject,
    private val writer: SyncWriter,
    private val writes: WriteGate = WriteGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun pull(c: CollectionConfig, caller: Caller, since: Long, limit: Int, epoch: String?): JsonObject {
        val scope = Access.scope(c, Rules.Op.LIST, caller)
        val current = SyncLog.epoch(db)
        val reset = (epoch != null && epoch != current) || (since > 0 && since < SyncLog.purgedBefore(db, c.name))
        val from = if (reset) 0 else since
        val n = limit.coerceIn(1, MAX_PULL)
        val rows = SyncLog.changes(db, c.name, from, n + 1)
        val entries = rows.take(n)
        val svc = services.getValue(c.name)
        // Görünürlük kuralla: listede çıkmayan kayıt bu istemci için silinmiş sayılır.
        val visible = entries.filter { !it.deleted }.map { it.record }.chunked(100).flatMap { ids ->
            svc.list(1, ids.size, null, listOf("id[in]" to ids.joinToString(",")), scope = scope).items
        }.associateBy { (it["id"] as JsonPrimitive).longOrNull }
        val idKey = names(c.name).api("id") ?: "id"
        val changes = entries.map { e ->
            val record = visible[e.record]
            buildJsonObject {
                put("id", JsonPrimitive(e.record))
                put("version", JsonPrimitive(e.version))
                if (record == null) {
                    put("action", JsonPrimitive("delete"))
                } else {
                    put("action", JsonPrimitive("upsert"))
                    put("record", out(c, svc.present(record, scope)))
                }
            }
        }
        return buildJsonObject {
            put("epoch", JsonPrimitive(current))
            put("reset", JsonPrimitive(reset))
            put("next", JsonPrimitive(entries.lastOrNull()?.seq ?: from))
            put("more", JsonPrimitive(rows.size > n))
            put("idField", JsonPrimitive(idKey))
            put("changes", JsonArray(changes))
        }
    }

    fun push(c: CollectionConfig, caller: Caller, body: JsonObject): JsonObject {
        val list = body["changes"] as? JsonArray ?: throw ApiException(400, tr("changes dizisi gerekli"))
        if (list.size > MAX_PUSH) throw ApiException(400, tr("En çok {0} değişiklik gönderilebilir", MAX_PUSH))
        // İstemci saatinin sunucuya göre farkı: düzenleme zamanları bununla düzeltilir.
        val offset = (body["now"] as? JsonPrimitive)?.longOrNull?.let { clock() - it } ?: 0L
        val mode = c.sync?.conflict ?: SyncConflict.LWW
        val results = list.map { el ->
            val ch = el as? JsonObject ?: return@map error(null, null, ApiException(400, tr("Değişiklik nesne olmalı")))
            val tempId = ch["tempId"]
            val id = (ch["id"] as? JsonPrimitive)?.longOrNull
            try {
                when ((ch["op"] as? JsonPrimitive)?.content) {
                    "create" -> create(c, caller, ch, tempId)
                    "update" -> update(c, caller, ch, id ?: throw ApiException(400, tr("id gerekli")), offset, mode)
                    "delete" -> delete(c, caller, ch, id ?: throw ApiException(400, tr("id gerekli")), offset, mode)
                    else -> throw ApiException(400, tr("op create, update veya delete olmalı"))
                }
            } catch (e: ApiException) {
                error(tempId, id, e)
            }
        }
        return buildJsonObject { put("results", JsonArray(results)) }
    }

    private fun fields(ch: JsonObject, c: CollectionConfig): JsonObject = names(c.name).inBody(ch["fields"] as? JsonObject ?: JsonObject(emptyMap()))

    private fun create(c: CollectionConfig, caller: Caller, ch: JsonObject, tempId: JsonElement?): JsonObject {
        val created = writer.create(c, caller, fields(ch, c))
        val id = (created["id"] as JsonPrimitive).longOrNull!!
        return result("ok", tempId, id, SyncLog.entry(db, c.name, id)?.version, out(c, created))
    }

    private fun update(c: CollectionConfig, caller: Caller, ch: JsonObject, id: Long, offset: Long, mode: SyncConflict): JsonObject {
        val entry = SyncLog.entry(db, c.name, id)
        if (entry == null || entry.deleted) return result("gone", null, id, entry?.version, null)
        val base = (ch["base"] as? JsonPrimitive)?.longOrNull ?: 0
        val times = (ch["times"] as? JsonObject).orEmpty().entries
            .associate { (k, v) -> names(c.name).canonical(k) to ((v as? JsonPrimitive)?.longOrNull ?: 0) + offset }
        val incoming = fields(ch, c)
        val server = SyncLog.fieldVersions(db, c.name, id)
        val svc = services.getValue(c.name)
        val now = clock()
        val conflicts = linkedMapOf<String, JsonElement>()
        val applied = incoming.filter { (f, value) ->
            val (version, time) = server[f] ?: return@filter true
            if (version <= base) return@filter true
            // Sunucuda da değişmiş: lww'de düzenleme zamanı yeni olan kazanır, manual'de istemcininki uygulanmaz.
            val clientWins = mode == SyncConflict.LWW && (times[f] ?: now) > time
            if (!clientWins) conflicts[f] = value
            clientWins
        }
        val record = if (applied.isEmpty()) {
            svc.present(svc.get(id, scope = Access.scope(c, Rules.Op.VIEW, caller)), Access.scope(c, Rules.Op.VIEW, caller))
        } else {
            writer.update(c, caller, id, JsonObject(applied)).also {
                writes.write { SyncLog.setTimes(db, c.name, id, applied.keys.associateWith { f -> times[f] ?: now }) }
            }
        }
        val status = when {
            conflicts.isEmpty() -> "ok"
            mode == SyncConflict.MANUAL -> "conflict"
            else -> "merged" // lww: bazı alanlarda sunucu kazandı
        }
        return result(status, null, id, SyncLog.entry(db, c.name, id)?.version, out(c, record)).let { r ->
            if (conflicts.isEmpty()) r else JsonObject(r + ("conflicts" to names(c.name).let { n -> JsonObject(conflicts.mapKeys { n.api(it.key) ?: it.key }) }))
        }
    }

    private fun delete(c: CollectionConfig, caller: Caller, ch: JsonObject, id: Long, offset: Long, mode: SyncConflict): JsonObject {
        val entry = SyncLog.entry(db, c.name, id)
        if (entry == null || entry.deleted) return result("ok", null, id, entry?.version, null)
        val base = (ch["base"] as? JsonPrimitive)?.longOrNull ?: 0
        val time = (ch["time"] as? JsonPrimitive)?.longOrNull?.plus(offset) ?: clock()
        // Silmeden sonra sunucuda değişmişse: lww'de daha yeni değişiklik kazanır, manual'de silme uygulanmaz.
        if (entry.version > base && (mode == SyncConflict.MANUAL || entry.changedAt > time)) {
            val scope = Access.scope(c, Rules.Op.VIEW, caller)
            val svc = services.getValue(c.name)
            return result("conflict", null, id, entry.version, out(c, svc.present(svc.get(id, scope = scope), scope)))
        }
        writer.delete(c, caller, id)
        return result("ok", null, id, SyncLog.entry(db, c.name, id)?.version, null)
    }

    private fun result(status: String, tempId: JsonElement?, id: Long?, version: Long?, record: JsonObject?) = buildJsonObject {
        put("status", JsonPrimitive(status))
        tempId?.let { put("tempId", it) }
        id?.let { put("id", JsonPrimitive(it)) }
        version?.let { put("version", JsonPrimitive(it)) }
        record?.let { put("record", it) }
    }

    private fun error(tempId: JsonElement?, id: Long?, e: ApiException) = buildJsonObject {
        put("status", JsonPrimitive("error"))
        tempId?.let { put("tempId", it) }
        id?.let { put("id", JsonPrimitive(it)) }
        put("code", JsonPrimitive(e.status))
        put("message", JsonPrimitive(e.message ?: ""))
        if (e.details.isNotEmpty()) put("details", JsonObject(e.details.mapValues { JsonPrimitive(it.value) }))
    }

    private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())

    companion object {
        const val MAX_PULL = 500
        const val MAX_PUSH = 100
    }
}
