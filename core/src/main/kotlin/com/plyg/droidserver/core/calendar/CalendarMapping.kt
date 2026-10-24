package com.plyg.droidserver.core.calendar

import com.plyg.droidserver.core.config.CalendarConfig
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ContactsConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.GeoPoint
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Kayıt ↔ etkinlik (VEVENT) ve kayıt ↔ kartvizit (VCARD). Kayıtlar RecordService biçimindedir (sistem alanları özgün
 * adlarıyla). Tüm gün etkinliklerinin tarihi [zone]'da (telefonun saat dilimi) günün başıdır.
 */
object CalendarMapping {
    const val PRODID = "-//DroidServer//DroidServer//TR"

    private fun JsonObject.text(name: String?): String? = name?.let { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }

    private fun JsonObject.instant(name: String?): Instant? = text(name)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** VEVENT satırları; başlangıcı olmayan kayıt etkinlik değildir (null). */
    fun event(c: CollectionConfig, cal: CalendarConfig, record: JsonObject, uid: String, zone: ZoneId): List<String>? {
        val start = record.instant(cal.start) ?: return null
        val allDay = cal.allDay?.let { (record[it] as? JsonPrimitive)?.booleanOrNull } == true
        val end = record.instant(cal.end)?.takeIf { it > start } ?: if (allDay) start.atZone(zone).plusDays(1).toInstant() else start.plus(Duration.ofHours(1))
        val updated = record.instant("updated_at") ?: start
        return buildList {
            add("BEGIN:VEVENT")
            add("UID:" + uid)
            add("DTSTAMP:" + ICal.utc(updated))
            record.instant("created_at")?.let { add("CREATED:" + ICal.utc(it)) }
            add("LAST-MODIFIED:" + ICal.utc(updated))
            add("SUMMARY:" + ICal.escape(record.text(cal.title).orEmpty()))
            if (allDay) {
                add("DTSTART;VALUE=DATE:" + ICal.date(start.atZone(zone).toLocalDate()))
                add("DTEND;VALUE=DATE:" + ICal.date(end.atZone(zone).toLocalDate().let { if (it <= start.atZone(zone).toLocalDate()) it.plusDays(1) else it }))
            } else {
                add("DTSTART:" + ICal.utc(start))
                add("DTEND:" + ICal.utc(end))
            }
            record.text(cal.description)?.let { add("DESCRIPTION:" + ICal.escape(it)) }
            cal.location?.let { name ->
                if (c.fields.firstOrNull { it.name == name }?.type == FieldType.GEO) {
                    (record[name] as? JsonObject)?.let { runCatching { GeoPoint.of(it) }.getOrNull() }?.let { add("GEO:${it.lat};${it.lng}") }
                } else {
                    record.text(name)?.let { add("LOCATION:" + ICal.escape(it)) }
                }
            }
            record.text(cal.recurrence)?.let { add("RRULE:" + it.removePrefix("RRULE:")) }
            add("END:VEVENT")
        }
    }

    fun calendar(name: String, events: List<List<String>>): String = ICal.lines(
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:$PRODID", "CALSCALE:GREGORIAN", "X-WR-CALNAME:" + ICal.escape(name)) +
            events.flatten() + "END:VCALENDAR",
    )

    /** VCALENDAR metnindeki ilk VEVENT'in eşlenen alanları (gövde) ve UID'si. Eşlenen ama olmayan alan null gönderilir. */
    fun fromEvent(c: CollectionConfig, cal: CalendarConfig, text: String, zone: ZoneId): Pair<JsonObject, String?> {
        val root = runCatching { ICal.parse(text) }.getOrElse { throw IllegalArgumentException(tr("Geçerli bir iCalendar değil")) }
        val e = (if (root.name == "VEVENT") root else root.child("VEVENT")) ?: throw IllegalArgumentException(tr("Takvim verisinde etkinlik (VEVENT) yok"))
        val (start, allDay) = e.first("DTSTART")?.let { ICal.time(it, zone) } ?: throw IllegalArgumentException(tr("Etkinliğin başlangıcı (DTSTART) yok"))
        val end = e.first("DTEND")?.let { ICal.time(it, zone)?.first }
            ?: e.first("DURATION")?.let { ICal.duration(it.value) }?.let { start.plus(it) }
            ?: if (allDay) start.atZone(zone).plusDays(1).toInstant() else start
        val body = LinkedHashMap<String, JsonElement>()
        body[cal.title] = JsonPrimitive(e.first("SUMMARY")?.value?.let(ICal::unescape).orEmpty())
        body[cal.start] = JsonPrimitive(start.toString())
        cal.end?.let { body[it] = JsonPrimitive(end.toString()) }
        cal.description?.let { body[it] = e.first("DESCRIPTION")?.value?.let(ICal::unescape)?.let(::JsonPrimitive) ?: JsonNull }
        cal.location?.let { name ->
            body[name] = if (c.fields.firstOrNull { it.name == name }?.type == FieldType.GEO) {
                e.first("GEO")?.value?.split(';', ',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }
                    ?.let { runCatching { GeoPoint(it[0], it[1]).json() }.getOrNull() } ?: JsonNull
            } else {
                e.first("LOCATION")?.value?.let(ICal::unescape)?.let(::JsonPrimitive) ?: JsonNull
            }
        }
        cal.allDay?.let { body[it] = JsonPrimitive(allDay) }
        cal.recurrence?.let { body[it] = e.first("RRULE")?.value?.let(::JsonPrimitive) ?: JsonNull }
        return JsonObject(body) to e.first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** vCard 3.0 satırları. */
    fun card(con: ContactsConfig, record: JsonObject, uid: String): String {
        val name = record.text(con.name).orEmpty()
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val family = if (words.size > 1) words.last() else ""
        val given = if (words.size > 1) words.dropLast(1).joinToString(" ") else words.firstOrNull().orEmpty()
        return ICal.lines(buildList {
            add("BEGIN:VCARD")
            add("VERSION:3.0")
            add("UID:" + uid)
            add("FN:" + ICal.escape(name))
            add("N:" + ICal.escape(family) + ";" + ICal.escape(given) + ";;;")
            record.text(con.email)?.let { add("EMAIL;TYPE=INTERNET:" + ICal.escape(it)) }
            record.text(con.phone)?.let { add("TEL;TYPE=CELL:" + ICal.escape(it)) }
            record.text(con.organization)?.let { add("ORG:" + ICal.escape(it)) }
            record.text(con.note)?.let { add("NOTE:" + ICal.escape(it)) }
            record.instant("updated_at")?.let { add("REV:" + ICal.utc(it)) }
            add("END:VCARD")
        })
    }

    /** vCard'ın eşlenen alanları ve UID'si. Ad yoksa N'den kurulur. */
    fun fromCard(con: ContactsConfig, text: String): Pair<JsonObject, String?> {
        val v = runCatching { ICal.parse(text) }.getOrElse { throw IllegalArgumentException(tr("Geçerli bir vCard değil")) }
        if (v.name != "VCARD") throw IllegalArgumentException(tr("Geçerli bir vCard değil"))
        fun value(name: String) = v.first(name)?.value?.let(ICal::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        val name = value("FN") ?: v.first("N")?.value?.split(';')?.map(ICal::unescape)?.let { n ->
            listOfNotNull(n.getOrNull(3), n.getOrNull(1), n.getOrNull(2), n.getOrNull(0), n.getOrNull(4)).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }?.takeIf { it.isNotEmpty() } ?: value("ORG") ?: value("EMAIL") ?: ""
        val body = LinkedHashMap<String, JsonElement>()
        body[con.name] = JsonPrimitive(name)
        fun put(field: String?, raw: String?) { field?.let { body[it] = raw?.let(::JsonPrimitive) ?: JsonNull } }
        put(con.email, value("EMAIL"))
        put(con.phone, value("TEL")?.removePrefix("tel:"))
        put(con.organization, v.first("ORG")?.value?.split(';')?.firstOrNull()?.let(ICal::unescape)?.trim()?.takeIf { it.isNotEmpty() })
        put(con.note, value("NOTE"))
        return JsonObject(body) to value("UID")
    }
}
