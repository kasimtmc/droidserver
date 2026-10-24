package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.Retention
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.RetentionConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.TrashConfig
import com.plyg.droidserver.core.db.SchemaMigrator
import com.plyg.droidserver.core.db.SqlDriver
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Saklama süresi: eski kayıtlar normal silme yolundan (ilişkiler, çöp kutusu) silinir, iki lehçede. */
@RunWith(Parameterized::class)
class RetentionTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val tasks = CollectionConfig(
        "tasks",
        listOf(FieldConfig("title", FieldType.TEXT), FieldConfig("status", FieldType.TEXT), FieldConfig("due", FieldType.DATETIME)),
        retention = RetentionConfig(days = 7, filter = "status=done"),
        trash = TrashConfig(),
    )
    private val notes = CollectionConfig(
        "notes",
        listOf(FieldConfig("text", FieldType.TEXT), FieldConfig("task", FieldType.RELATION, collection = "tasks", onDelete = OnDelete.RESTRICT)),
    )
    private val config = ServerConfig(collections = listOf(tasks, notes))
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
        SchemaMigrator(db).migrate(config.schemaCollections())
    }

    @After
    fun close() = db.close()

    private fun service(c: CollectionConfig, at: Instant) = RecordService(db, c, clock = { at }, collections = config.schemaCollections())

    private fun add(at: Instant, title: String, status: String, due: Instant? = null): Long =
        service(tasks, at).create(buildJsonObject {
            put("title", JsonPrimitive(title))
            put("status", JsonPrimitive(status))
            due?.let { put("due", JsonPrimitive(it.toString())) }
        }).getValue("id").jsonPrimitive.content.toLong()

    @Test
    fun `sure dolan ve kosula uyan kayitlar silinir`() {
        val old = now.minusSeconds(10 * 86_400L)
        add(old, "eski bitti", "done")
        add(old, "eski açık", "open")
        add(now.minusSeconds(86_400L), "yeni bitti", "done")
        val linked = add(old, "eski bitti, notlu", "done")
        service(notes, old).create(buildJsonObject { put("text", JsonPrimitive("not")); put("task", JsonPrimitive(linked)) })

        val logs = mutableListOf<String>()
        val services = mapOf("tasks" to service(tasks, now), "notes" to service(notes, now))
        assertEquals(mapOf("tasks" to 1), Retention.run(config, services, now, logs::add))
        val left = services.getValue("tasks").list(1, 100, null, emptyList()).items.map { it.getValue("title").jsonPrimitive.content }.sorted()
        assertEquals(listOf("eski açık", "eski bitti, notlu", "yeni bitti"), left)
        assertEquals(1, logs.size, "bağlı kaydı olduğu için silinemeyen bildirilir: $logs")
        // Çöp kutusu açık: silinen oraya gider, geri yüklenebilir.
        assertEquals("eski bitti", services.getValue("tasks").trash().getValue("items").jsonArray.single()
            .let { (it as kotlinx.serialization.json.JsonObject).getValue("data") as kotlinx.serialization.json.JsonObject }
            .getValue("title").jsonPrimitive.content)
        assertEquals(emptyMap(), Retention.run(config, services, now), "ikinci çalışmada silinecek yok")
    }

    @Test
    fun `tarih alanina gore ve dogrulama`() {
        val byDue = config.copy(collections = listOf(tasks.copy(retention = RetentionConfig(days = 1, field = "due")), notes))
        add(now, "süresi geçmiş", "open", due = now.minusSeconds(3 * 86_400L))
        add(now, "süresi gelmemiş", "open", due = now.plusSeconds(86_400L))
        add(now, "tarihsiz", "open")
        val svc = RecordService(db, byDue.collections[0], clock = { now }, collections = byDue.schemaCollections())
        assertEquals(mapOf("tasks" to 1), Retention.run(byDue, mapOf("tasks" to svc), now))
        assertEquals(2, svc.list(1, 100, null, emptyList()).items.size)

        val errors = { r: RetentionConfig -> ConfigValidator.validateDetailed(config.copy(collections = listOf(tasks.copy(retention = r), notes))).map { it.path }.toSet() }
        assertEquals(emptySet(), errors(RetentionConfig(30, "updated_at", "status=done&title[ne]=x")))
        assertEquals(setOf("collections[0].retention.days"), errors(RetentionConfig(0)))
        assertEquals(setOf("collections[0].retention.field"), errors(RetentionConfig(5, field = "title")))
        assertEquals(setOf("collections[0].retention.filter"), errors(RetentionConfig(5, filter = "yok=1")))
        assertEquals(setOf("collections[0].retention.filter"), errors(RetentionConfig(5, filter = "status=@me")))
    }
}
