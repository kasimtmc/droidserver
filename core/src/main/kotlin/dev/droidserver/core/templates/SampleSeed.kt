package dev.droidserver.core.templates

import dev.droidserver.core.api.RecordService
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.security.FieldEncryption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Örnek kayıtlar (şablonlar ve paketler): `"@ref:koleksiyon:sıra"` o koleksiyondaki n. örneğin kimliği olur. */
object SampleSeed {
    /**
     * Kayıtları ekler (yalnızca boş koleksiyonlara; tekrar çalıştırmak çoğaltmaz). Eklenen kayıt sayısı. Kayıt olayı
     * (script, webhook) üretmez.
     */
    fun seed(db: SqlDriver, config: ServerConfig, samples: List<Pair<String, List<JsonObject>>>): Int {
        val ids = HashMap<String, List<Long>>()
        var count = 0
        db.transaction {
            for ((name, records) in samples) {
                val c = config.collections.firstOrNull { it.name == name } ?: continue
                val existing = (db.query("SELECT COUNT(*) AS n FROM ${quoteIdent(name)}").first()["n"] as Number).toLong()
                if (existing > 0) continue
                val svc = RecordService(db, c, collections = config.schemaCollections(), publicUserFields = config.auth.users.publicFields, cipher = FieldEncryption.cipher(config))
                ids[name] = records.map { r ->
                    val created = svc.create(JsonObject(r.mapValues { (_, v) -> resolve(v, ids) }))
                    count++
                    (created["id"] as JsonPrimitive).longOrNull ?: 0
                }
            }
        }
        return count
    }

    private fun resolve(v: JsonElement, ids: Map<String, List<Long>>): JsonElement {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return if (v is JsonArray) JsonArray(v.map { resolve(it, ids) }) else v
        if (!s.startsWith("@ref:")) return v
        val (c, i) = s.removePrefix("@ref:").split(':')
        return JsonPrimitive(ids[c]?.getOrNull(i.toInt()) ?: error(tr("Örnek veri: {0} yok", s)))
    }
}
