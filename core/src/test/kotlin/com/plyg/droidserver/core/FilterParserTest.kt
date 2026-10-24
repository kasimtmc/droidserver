package com.plyg.droidserver.core

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.FilterParser
import com.plyg.droidserver.core.api.FilterParser.Op
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterParserTest {
    private val c = CollectionConfig(
        name = "todos",
        fields = listOf(
            FieldConfig("title", FieldType.TEXT),
            FieldConfig("done", FieldType.BOOLEAN),
            FieldConfig("priority", FieldType.INTEGER),
            FieldConfig("score", FieldType.REAL),
            FieldConfig("due", FieldType.DATETIME),
            FieldConfig("meta", FieldType.JSON),
        ),
    )

    private fun parse(vararg p: Pair<String, String>) = FilterParser.parse(p.toList(), c)

    private fun fails(vararg p: Pair<String, String>): String = assertFailsWith<ApiException> { parse(*p) }.message!!

    @Test
    fun `operatorler ve degerler alan tipine gore ayristirilir`() {
        val conds = parse(
            "done" to "true",
            "priority[gte]" to " 3",
            "score[lt]" to "1.5",
            "title[like]" to "süt",
            "title[in]" to "a,b c",
            "due[gt]" to "2026-10-13T10:00:00Z",
            "due[null]" to "false",
            "id[in]" to "1,2,3",
            "created_at[lte]" to "2026-10-13T10:00:00.5Z",
            "title[ne]" to "x",
        )
        assertEquals(
            listOf(
                FilterParser.Condition("done", FieldType.BOOLEAN, Op.EQ, listOf(true)),
                FilterParser.Condition("priority", FieldType.INTEGER, Op.GTE, listOf(3L)),
                FilterParser.Condition("score", FieldType.REAL, Op.LT, listOf(1.5)),
                FilterParser.Condition("title", FieldType.TEXT, Op.LIKE, listOf("süt")),
                FilterParser.Condition("title", FieldType.TEXT, Op.IN, listOf("a", "b c")),
                FilterParser.Condition("due", FieldType.DATETIME, Op.GT, listOf(Instant.parse("2026-10-13T10:00:00Z"))),
                FilterParser.Condition("due", FieldType.DATETIME, Op.NULL, listOf(false)),
                FilterParser.Condition("id", FieldType.INTEGER, Op.IN, listOf(1L, 2L, 3L)),
                FilterParser.Condition("created_at", FieldType.DATETIME, Op.LTE, listOf(Instant.parse("2026-10-13T10:00:00.5Z"))),
                FilterParser.Condition("title", FieldType.TEXT, Op.NE, listOf("x")),
            ),
            conds,
        )
    }

    @Test
    fun `ayrilmis ve bilinmeyen duz parametreler yok sayilir`() {
        assertTrue(parse("page" to "2", "perPage" to "5", "sort" to "-id", "fields" to "title", "expand" to "x", "utm_source" to "y").isEmpty())
    }

    @Test
    fun `tipe uymayan operator ve deger reddedilir`() {
        assertTrue("json" in fails("meta" to "1"))
        assertTrue("json" in fails("meta[null]" to "true"))
        assertTrue("like" in fails("priority[like]" to "1"))
        assertTrue("büyük/küçük" in fails("title[gt]" to "a"))
        assertTrue("büyük/küçük" in fails("done[lt]" to "true"))
        assertTrue("id" in fails("id[null]" to "true"))
        assertTrue("eq" in fails("done[in]" to "true,false"))
        assertTrue("Bilinmeyen operatör" in fails("priority[between]" to "1"))
        assertTrue("Bilinmeyen alan" in fails("yok[eq]" to "1"))
        assertTrue("tam sayı" in fails("priority" to "bir"))
        assertTrue("sayı" in fails("score[gt]" to "NaN"))
        assertTrue("true/false" in fails("done" to "evet") || "true veya false" in fails("done" to "evet"))
        assertTrue("ISO-8601" in fails("due[gt]" to "dün"))
        assertTrue("null" in fails("due[null]" to "evet"))
        assertTrue("boş" in fails("title[like]" to ""))
        assertTrue("en az bir" in fails("title[in]" to ""))
        assertTrue("en çok" in fails("id[in]" to (1..101).joinToString(",")))
    }

    @Test
    fun `alan secimi`() {
        assertNull(FilterParser.parseFields(null, c))
        assertNull(FilterParser.parseFields(" ", c))
        assertEquals(listOf("id", "title", "done", "created_at"), FilterParser.parseFields("title, done,created_at,title", c))
        assertTrue("Bilinmeyen alan: yok" in assertFailsWith<ApiException> { FilterParser.parseFields("title,yok", c) }.message!!)
    }
}
