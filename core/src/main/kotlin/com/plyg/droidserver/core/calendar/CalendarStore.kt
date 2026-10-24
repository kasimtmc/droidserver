package com.plyg.droidserver.core.calendar

import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.Scope
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Takvim/kişi istemcilerinin verdiği kaynak adı ve UID'si (`_ds_dav`): telefonun takvimi etkinliği kendi seçtiği adla
 * (`a1b2….ics`) yazar ve o adla arar. Sunucuda oluşan kaydın adı `<kimlik>.ics`, UID'si `<kimlik>-<koleksiyon>@droidserver`.
 */
class CalendarStore(private val db: SqlDriver) {
    data class Entry(val record: Long, val href: String, val uid: String)

    fun install() {
        val id = db.dialect.columnType(FieldType.INTEGER)
        db.execute(
            "CREATE TABLE IF NOT EXISTS $TABLE (\"collection\" TEXT NOT NULL, \"record\" $id NOT NULL, \"href\" TEXT NOT NULL, " +
                "\"uid\" TEXT NOT NULL, PRIMARY KEY (\"collection\", \"record\"))",
        )
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS ${quoteIdent("_ds_dav_href")} ON $TABLE (\"collection\", \"href\")")
    }

    fun entries(collection: String): Map<Long, Entry> =
        db.query("SELECT \"record\", \"href\", \"uid\" FROM $TABLE WHERE \"collection\" = ?", listOf(collection)).associate { r ->
            val id = (r["record"] as Number).toLong()
            id to Entry(id, r["href"] as String, r["uid"] as String)
        }

    fun put(collection: String, record: Long, href: String, uid: String) = db.transaction {
        db.execute("DELETE FROM $TABLE WHERE \"collection\" = ? AND (\"record\" = ? OR \"href\" = ?)", listOf(collection, record, href))
        db.execute("INSERT INTO $TABLE (\"collection\", \"record\", \"href\", \"uid\") VALUES (?, ?, ?, ?)", listOf(collection, record, href, uid))
    }

    fun remove(collection: String, record: Long) {
        db.execute("DELETE FROM $TABLE WHERE \"collection\" = ? AND \"record\" = ?", listOf(collection, record))
    }

    companion object {
        private val TABLE = quoteIdent("_ds_dav")

        /** Takvim ve kişi listelerinde en çok bu kadar kayıt. */
        const val MAX_RECORDS = 10_000

        fun uid(collection: String, id: Long) = "$id-$collection@droidserver"

        /** [scope] ile görünen kayıtlar, kimlik sırasıyla (en çok [MAX_RECORDS]). */
        fun records(svc: RecordService, scope: Scope): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            var page = 1
            while (out.size < MAX_RECORDS) {
                val p = svc.list(page, 500, "id", emptyList(), scope = scope)
                out += p.items
                if (p.items.size < 500) break
                page++
            }
            return out
        }

        fun id(record: JsonObject): Long = record.getValue("id").jsonPrimitive.long
    }
}
