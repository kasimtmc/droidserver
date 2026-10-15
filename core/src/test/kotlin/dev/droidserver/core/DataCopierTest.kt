package dev.droidserver.core

import dev.droidserver.core.api.RecordService
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.TypeChange
import dev.droidserver.core.config.TypeChangeAction
import dev.droidserver.core.db.DataCopier
import dev.droidserver.core.db.SchemaMigrator
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SQLite kaynağından hedefe (SQLite veya PostgreSQL) veri taşıma. */
@RunWith(Parameterized::class)
class DataCopierTest(private val target: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sqlite-to-{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val events = CollectionConfig(
        "events",
        listOf(
            FieldConfig("name", FieldType.TEXT, required = true),
            FieldConfig("slug", FieldType.TEXT, unique = true),
            FieldConfig("count", FieldType.INTEGER),
            FieldConfig("ratio", FieldType.REAL),
            FieldConfig("active", FieldType.BOOLEAN),
            FieldConfig("at", FieldType.DATETIME),
            FieldConfig("data", FieldType.JSON),
        ),
    )
    private val tags = CollectionConfig("tags", listOf(FieldConfig("label", FieldType.TEXT)))

    // Sabit saat: Postgres zamanı mikro saniyeye yuvarlar; karşılaştırma tam eşitlikle yapılabilsin.
    private val clock = { Instant.parse("2026-10-13T10:00:00.123456Z") }

    private fun body(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is JsonObject -> v
            else -> JsonPrimitive(null as String?)
        })
    }

    @Test
    fun `kayitlar kimlikleri ve tipleriyle tasinir, sayac en buyuk idden devam eder`() {
        val source = TestDb.create(Dialect.SQLITE).open()
        val dest = TestDb.create(target).open()
        try {
            SchemaMigrator(source).migrate(listOf(events, tags))
            val src = RecordService(source, events, clock)
            src.create(body("name" to "a", "slug" to "a", "count" to 3, "ratio" to 0.5, "active" to true,
                "at" to "2026-01-02T03:04:05Z", "data" to buildJsonObject { put("k", JsonPrimitive("v")) }))
            src.create(body("name" to "silinecek"))
            src.create(body("name" to "c", "active" to false))
            src.delete(2) // id boşluğu korunmalı

            val report = DataCopier.copy(source, dest, listOf(events, tags), listOf(events, tags))
            assertEquals(mapOf("events" to 2L, "tags" to 0L), report.rows)

            val dst = RecordService(dest, events, clock)
            assertEquals(src.list(1, 100, null, emptyList()).items, dst.list(1, 100, null, emptyList()).items)
            assertEquals(4L, dst.create(body("name" to "yeni"))["id"]!!.jsonPrimitive.long, "kimlik sayacı ayarlanmalı")
        } finally {
            source.close(); dest.close()
        }
    }

    @Test
    fun `bos olmayan hedefe tasima reddedilir ve hedefte yarim veri kalmaz`() {
        val source = TestDb.create(Dialect.SQLITE).open()
        val dest = TestDb.create(target).open()
        try {
            SchemaMigrator(source).migrate(listOf(events, tags))
            RecordService(source, events, clock).create(body("name" to "a"))
            RecordService(source, tags, clock).create(body("label" to "x"))
            SchemaMigrator(dest).migrate(listOf(tags))
            RecordService(dest, tags, clock).create(body("label" to "önceden var"))

            val e = assertFailsWith<IllegalArgumentException> { DataCopier.copy(source, dest, listOf(events, tags), listOf(events, tags)) }
            assertTrue(e.message!!.contains("boş değil"), e.message)
            // events önce kopyalanmıştı; işlem geri alındığı için hedefte events tablosu da yok.
            assertTrue("events" !in dest.dialect.listTables(dest))
            assertEquals(1, RecordService(dest, tags, clock).list(1, 10, null, emptyList()).totalItems)

            // Kullanıcı hedefteki kayıtların silinmesini seçerse kaynaktakiler yazılır.
            DataCopier.copy(source, dest, listOf(events, tags), listOf(events, tags), replaceExisting = true)
            val labels = RecordService(dest, tags, clock).list(1, 10, null, emptyList()).items.map { it["label"] }
            assertEquals(listOf(JsonPrimitive("x")), labels)
        } finally {
            source.close(); dest.close()
        }
    }

    @Test
    fun `tip degisikligi tasimada kullanicinin secimine gore uygulanir`() {
        val source = TestDb.create(Dialect.SQLITE).open()
        val dest = TestDb.create(target).open()
        try {
            SchemaMigrator(source).migrate(listOf(events))
            val src = RecordService(source, events, clock)
            src.create(body("name" to "a", "count" to 3, "ratio" to 1.0))
            src.create(body("name" to "b", "count" to 4, "ratio" to 2.5))

            fun changed(count: TypeChange?, ratio: TypeChange?) = events.copy(fields = events.fields.map {
                when (it.name) {
                    "count" -> it.copy(type = FieldType.TEXT, typeChange = count)
                    "ratio" -> it.copy(type = FieldType.INTEGER, typeChange = ratio)
                    else -> it
                }
            })
            // Seçim yoksa taşıma başlamaz; hedefte hiçbir şey kalmaz.
            val e = assertFailsWith<IllegalArgumentException> {
                DataCopier.copy(source, dest, listOf(events), listOf(changed(null, TypeChange(FieldType.REAL, TypeChangeAction.CONVERT))))
            }
            assertTrue(e.message!!.contains("events.count") && e.message!!.contains("seçim yapın"), e.message)
            assertTrue("events" !in dest.dialect.listTables(dest))

            val next = changed(TypeChange(FieldType.INTEGER, TypeChangeAction.CONVERT), TypeChange(FieldType.REAL, TypeChangeAction.CONVERT))
            DataCopier.copy(source, dest, listOf(events), listOf(next))
            val rows = RecordService(dest, next, clock).list(1, 10, "id", emptyList()).items
            assertEquals(listOf(JsonPrimitive("3"), JsonPrimitive("4")), rows.map { it["count"] })
            assertEquals(listOf(JsonPrimitive(1), JsonNull), rows.map { it["ratio"] }) // 2.5 tam sayı değil: boşalır
        } finally {
            source.close(); dest.close()
        }
    }
}
