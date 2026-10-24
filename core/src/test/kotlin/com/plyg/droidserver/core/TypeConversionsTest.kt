package com.plyg.droidserver.core

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldDefaults
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.StrictJson
import com.plyg.droidserver.core.config.TypeChange
import com.plyg.droidserver.core.config.TypeChangeAction
import com.plyg.droidserver.core.config.TypeConversions
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypeConversionsTest {
    private fun conv(from: FieldType, to: FieldType, v: Any) = TypeConversions.convert(from, to, v)

    @Test
    fun `metinden cevirme yalnizca kesin degerleri kabul eder`() {
        assertEquals(42L, conv(FieldType.TEXT, FieldType.INTEGER, " 42 "))
        assertEquals(2L, conv(FieldType.TEXT, FieldType.INTEGER, "2.0"))
        assertNull(conv(FieldType.TEXT, FieldType.INTEGER, "2.5"))
        assertNull(conv(FieldType.TEXT, FieldType.INTEGER, "abc"))
        assertEquals(3.14, conv(FieldType.TEXT, FieldType.REAL, "3.14"))
        assertNull(conv(FieldType.TEXT, FieldType.REAL, "NaN"))
        assertNull(conv(FieldType.TEXT, FieldType.REAL, "1d"))
        assertEquals(true, conv(FieldType.TEXT, FieldType.BOOLEAN, "TRUE"))
        assertEquals(false, conv(FieldType.TEXT, FieldType.BOOLEAN, "0"))
        assertNull(conv(FieldType.TEXT, FieldType.BOOLEAN, "evet"))
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), conv(FieldType.TEXT, FieldType.DATETIME, "2026-10-13"))
        assertEquals(Instant.parse("2026-10-13T07:00:00Z"), conv(FieldType.TEXT, FieldType.DATETIME, "2026-10-13T10:00:00+03:00"))
        assertEquals("""{"a":1}""", conv(FieldType.TEXT, FieldType.JSON, """{"a": 1}"""))
        assertEquals("\"düz\"", conv(FieldType.TEXT, FieldType.JSON, "düz"))
    }

    @Test
    fun `sayilar, mantiksal ve JSON`() {
        assertEquals("2.5", conv(FieldType.REAL, FieldType.TEXT, 2.5))
        assertEquals("10000000000", conv(FieldType.REAL, FieldType.TEXT, 1e10))
        assertEquals(2L, conv(FieldType.REAL, FieldType.INTEGER, 2.0))
        assertNull(conv(FieldType.REAL, FieldType.INTEGER, 2.5))
        assertEquals(true, conv(FieldType.INTEGER, FieldType.BOOLEAN, 1L))
        assertNull(conv(FieldType.INTEGER, FieldType.BOOLEAN, 5L))
        assertEquals(1L, conv(FieldType.BOOLEAN, FieldType.INTEGER, true))
        assertEquals("true", conv(FieldType.BOOLEAN, FieldType.JSON, true))
        assertEquals(5L, conv(FieldType.JSON, FieldType.INTEGER, "5"))
        assertEquals(5L, conv(FieldType.JSON, FieldType.INTEGER, "\"5\""))
        assertNull(conv(FieldType.JSON, FieldType.INTEGER, "[5]"))
        assertEquals("2026-10-13T10:00:00Z", conv(FieldType.DATETIME, FieldType.TEXT, Instant.parse("2026-10-13T10:00:00Z")))
    }

    @Test
    fun `standart disi JSON varsayilan degerde de reddedilir`() {
        assertNull(StrictJson.parse("abc"))
        assertNull(StrictJson.parse("""{"a": [1, abc]}"""))
        assertEquals("""{"a":[1,"x",null]}""", StrictJson.parse("""{"a": [1, "x", null]}""").toString())
        assertFailsWith<IllegalArgumentException> { FieldDefaults.parse(FieldType.JSON, "abc") }
        assertTrue(FieldDefaults.problem(FieldType.JSON, Json.parseToJsonElement("abc"))!!.contains("tırnak"))
        assertTrue(FieldDefaults.problem(FieldType.REAL, Json.parseToJsonElement("NaN")) != null)
        assertNull(FieldDefaults.problem(FieldType.JSON, Json.parseToJsonElement("""{"a": 1}""")))
    }

    @Test
    fun `anlamsiz donusumler yalnizca bosaltilabilir`() {
        assertFalse(TypeConversions.canConvert(FieldType.INTEGER, FieldType.DATETIME))
        assertFalse(TypeConversions.canConvert(FieldType.DATETIME, FieldType.BOOLEAN))
        assertNull(conv(FieldType.INTEGER, FieldType.DATETIME, 1L))
        assertTrue(TypeConversions.describe(FieldType.INTEGER, FieldType.DATETIME).contains("boşaltılabilir"))

        fun config(action: TypeChangeAction) = ServerConfig(
            collections = listOf(
                CollectionConfig("c", listOf(FieldConfig("f", FieldType.DATETIME, typeChange = TypeChange(FieldType.INTEGER, action)))),
            ),
        )
        assertEquals(listOf("collections[0].fields[0].typeChange"), ConfigValidator.validateDetailed(config(TypeChangeAction.CONVERT)).map { it.path })
        assertTrue(ConfigValidator.validateDetailed(config(TypeChangeAction.CLEAR)).isEmpty())
    }

    @Test
    fun `secim yalnizca verildiginde JSONa yazilir`() {
        val plain = ServerConfig(collections = listOf(CollectionConfig("c", listOf(FieldConfig("f", FieldType.TEXT)))))
        assertFalse(plain.toJson().contains("typeChange"))
        val chosen = plain.copy(
            collections = listOf(CollectionConfig("c", listOf(FieldConfig("f", FieldType.TEXT, typeChange = TypeChange(FieldType.INTEGER, TypeChangeAction.CONVERT))))),
        )
        assertTrue(chosen.toJson().contains("\"action\": \"convert\""), chosen.toJson())
        assertEquals(chosen, ServerConfig.fromJson(chosen.toJson()))
    }
}
