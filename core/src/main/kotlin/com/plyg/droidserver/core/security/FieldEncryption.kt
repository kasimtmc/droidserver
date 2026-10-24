package com.plyg.droidserver.core.security

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigJson
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deploy'da şifreleme açılan alanların mevcut değerlerini şifreler, kapatılanlarınkini açar (geçmiş ve çöp kutusundaki
 * kopyaları dahil). Yeni yazılan değerleri RecordService şifreler. Canlı kopyada çalışmaz: veri ana sunucudan gelir.
 */
object FieldEncryption {
    /** Çözülmüş konfigürasyondaki anahtar; yoksa veya geçersizse null. */
    fun cipher(config: ServerConfig): FieldCipher? =
        config.database.fieldKey?.takeIf { it.isNotBlank() && !ConfigSecrets.isRef(it) }?.let { runCatching { FieldCipher.of(it) }.getOrNull() }

    /**
     * Konfigürasyon metninde şifreli alan varsa ama anahtar yoksa yeni anahtar ekler (web panelinden şema düzenleme);
     * kaydedilirken anahtar sır deposuna taşınır. Değişiklik gerekmiyorsa metin olduğu gibi döner.
     */
    fun ensureKey(text: String): String {
        val json = runCatching { ConfigJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return text
        val config = runCatching { ServerConfig.fromJson(text) }.getOrNull() ?: return text
        val encrypted = config.schemaCollections().any { c -> c.fields.any { it.encrypted } }
        if (!encrypted || !config.database.fieldKey.isNullOrBlank()) return text
        val db = json["database"] as? JsonObject ?: JsonObject(emptyMap())
        val out = JsonObject(json + ("database" to JsonObject(db + ("fieldKey" to JsonPrimitive(FieldCipher.generateKey())))))
        return ConfigJson.encodeToString(JsonObject.serializer(), out)
    }

    /**
     * [previous]: çalışan konfigürasyon (bilinmiyorsa null: o zaman şifresiz alanlarda kalmış şifreli değer de aranır).
     * Değişen değer sayısını döndürür.
     */
    fun sync(db: SqlDriver, config: ServerConfig, previous: ServerConfig?): Int {
        if (config.replication.replicaOf != null) return 0
        val cipher = cipher(config) ?: return 0
        val tables = db.dialect.listTables(db)
        var changed = 0
        for (c in config.schemaCollections()) {
            if (c.name !in tables || c.name.startsWith("_") && c.name != SystemCollections.USERS) continue
            val before = previous?.schemaCollections()?.firstOrNull { it.name == c.name }
            for (f in c.fields) {
                if (f.type != FieldType.TEXT && f.type != FieldType.JSON) continue
                val was = before?.fields?.firstOrNull { it.name == f.name }?.encrypted
                val encrypt = when {
                    f.encrypted && was != true -> true
                    !f.encrypted && was != false -> false
                    else -> continue
                }
                changed += convertColumn(db, c, f, cipher, encrypt)
                changed += convertArchive(db, c, f, cipher, encrypt, tables)
            }
        }
        return changed
    }

    private fun convertColumn(db: SqlDriver, c: CollectionConfig, f: FieldConfig, cipher: FieldCipher, encrypt: Boolean): Int {
        val col = quoteIdent(f.name)
        val rows = db.query("SELECT \"id\", $col AS v FROM ${quoteIdent(c.name)} WHERE $col IS NOT NULL", emptyList())
        var n = 0
        for (r in rows) {
            val raw = db.dialect.decode(f.type, r["v"])?.toString() ?: continue
            val next = convert(f, raw, cipher, encrypt) ?: continue
            db.execute("UPDATE ${quoteIdent(c.name)} SET $col = ? WHERE \"id\" = ?", listOf(db.dialect.encode(f.type, next), r["id"]))
            n++
        }
        return n
    }

    /** Geçmiş ve çöp kutusundaki anlık görüntülerde aynı alan. */
    private fun convertArchive(db: SqlDriver, c: CollectionConfig, f: FieldConfig, cipher: FieldCipher, encrypt: Boolean, tables: Set<String>): Int {
        var n = 0
        for (table in listOf(SystemCollections.HISTORY, SystemCollections.TRASH)) {
            if (table !in tables) continue
            val rows = db.query("SELECT \"id\", \"data\" FROM ${quoteIdent(table)} WHERE \"collection\" = ? AND \"data\" IS NOT NULL", listOf(c.name))
            for (r in rows) {
                val data = db.dialect.decode(FieldType.JSON, r["data"])?.toString()
                    ?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: continue
                val value = data[f.name] ?: continue
                val raw = (value as? JsonPrimitive)?.takeIf { it.isString && f.type == FieldType.TEXT }?.content ?: value.toString()
                val next = convert(f, raw, cipher, encrypt) ?: continue
                val element: JsonElement = if (f.type == FieldType.TEXT) JsonPrimitive(next) else Json.parseToJsonElement(next)
                db.execute(
                    "UPDATE ${quoteIdent(table)} SET \"data\" = ? WHERE \"id\" = ?",
                    listOf(db.dialect.encode(FieldType.JSON, JsonObject(data + (f.name to element)).toString()), r["id"]),
                )
                n++
            }
        }
        return n
    }

    /**
     * Sütundaki değerin yeni hali; değişmeyecekse null. Metin alanında değer doğrudan, JSON alanında JSON metni:
     * şifreli JSON değeri `"enc:v1:…"` biçiminde bir JSON dizgisidir.
     */
    private fun convert(f: FieldConfig, raw: String, cipher: FieldCipher, encrypt: Boolean): String? {
        val sealed = if (f.type == FieldType.JSON) {
            runCatching { Json.parseToJsonElement(raw) }.getOrNull()?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        } else raw
        val isSealed = sealed != null && FieldCipher.isEncrypted(sealed)
        return when {
            encrypt && !isSealed -> cipher.encrypt(raw).let { if (f.type == FieldType.JSON) JsonPrimitive(it).toString() else it }
            !encrypt && isSealed -> try {
                cipher.decrypt(sealed!!)
            } catch (_: FieldCipherException) {
                null // başka anahtarla şifrelenmiş: olduğu gibi kalır
            }
            else -> null
        }
    }
}
