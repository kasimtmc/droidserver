package com.plyg.droidserver.core

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigMerge
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigMergeTest {
    private fun col(name: String, vararg fields: String) = CollectionConfig(name, fields.map { FieldConfig(it, FieldType.TEXT) })

    private val base = ServerConfig(name = "ev", collections = listOf(col("notes", "title")))

    @Test
    fun `paketin eklediği koleksiyon taslaktaki düzenlemeyle birleşir`() {
        val mine = base.copy(name = "dükkan", collections = listOf(col("notes", "title", "body")))
        val theirs = base.copy(collections = base.collections + col("orders", "total"))
        val r = ConfigMerge.threeWay(base, mine, theirs)
        assertEquals(emptyList(), r.conflicts)
        assertEquals("dükkan", r.config.name)
        assertEquals(listOf(col("notes", "title", "body"), col("orders", "total")), r.config.collections)
    }

    @Test
    fun `iki tarafın eklediği farklı koleksiyonlar ve dosyada silinen`() {
        val start = base.copy(collections = base.collections + col("old", "x"))
        val mine = start.copy(collections = start.collections + col("a", "x"))
        val theirs = start.copy(collections = listOf(col("notes", "title"), col("b", "y")))
        val r = ConfigMerge.threeWay(start, mine, theirs)
        assertEquals(emptyList(), r.conflicts)
        assertEquals(listOf("notes", "a", "b"), r.config.collections.map { it.name })
    }

    @Test
    fun `aynı yer iki tarafta farklı değiştiyse taslaktaki kalır`() {
        val mine = base.copy(name = "a", collections = listOf(col("notes", "title", "body")))
        val theirs = base.copy(name = "b", collections = emptyList())
        val r = ConfigMerge.threeWay(base, mine, theirs)
        assertEquals(listOf("name", "collections[notes]"), r.conflicts)
        assertEquals(mine, r.config)
    }

    @Test
    fun `taslak değişmediyse dosya, aynı değişiklikse çakışma yok`() {
        val theirs = base.copy(name = "b")
        assertEquals(ConfigMerge.Result(theirs, emptyList()), ConfigMerge.threeWay(base, base, theirs))
        assertEquals(ConfigMerge.Result(theirs, emptyList()), ConfigMerge.threeWay(base, theirs, theirs))
    }
}
