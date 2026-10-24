package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.auth.Caller
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.ViewConfig
import com.plyg.droidserver.core.data.Csv
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.Writer

/**
 * Salt okunur görünümler ve raporlar ([ViewConfig]): sorgu yönetici kapsamında çalışır, çıktı yalnızca seçilen
 * sütunlardır. İstemci yalnızca görünümdeki temel sütunlara göre ek filtre verebilir (gizli bir alanın değeri filtreyle
 * tahmin edilemesin diye) ve `q` ile arayabilir.
 */
class ViewService(private val config: ServerConfig, private val services: Map<String, RecordService>) {
    fun find(name: String): ViewConfig = config.views.firstOrNull { it.name == name } ?: throw ApiException(404, tr("'{0}' görünümü yok", name))

    /** Çağıranın görebildiği görünümler. */
    fun visible(caller: Caller): List<ViewConfig> = config.views.filter { runCatching { authorize(it, caller) }.isSuccess }

    fun authorize(view: ViewConfig, caller: Caller) {
        if (caller.isAdmin) return
        val user = caller as? Caller.User
        if (user?.restricted == true) throw ApiException(403, tr("İki adımlı doğrulama zorunlu; önce kurun (/auth/2fa/setup ve /auth/2fa/enable)"))
        val allowed = when (view.access) {
            Rule.PUBLIC -> true
            Rule.AUTH -> caller !is Caller.Anonymous && (caller !is Caller.Key || caller.allows(view.collection, write = false))
            else -> user != null && user.role in view.roles
        }
        if (allowed) return
        if (caller is Caller.Anonymous) throw ApiException(401, tr("Bu işlem için giriş yapın (Authorization: Bearer <token>)"))
        throw ApiException(403, tr("Bu görünüm için yetkiniz yok"))
    }

    fun columns(view: ViewConfig): List<String> = view.columns.ifEmpty { defaultColumns(config, view) }

    /** Kayıtlar: `{items, page, perPage, totalItems}`; rapor görünümünde özet. */
    fun run(view: ViewConfig, caller: Caller, query: List<Pair<String, String>>): JsonObject {
        authorize(view, caller)
        val params = params(view, caller, query)
        val svc = service(view)
        view.report?.let { return svc.aggregate(params + it.params()) }
        val page = query.lastOrNull { it.first == "page" }?.second?.toIntOrNull() ?: 1
        val perPage = query.lastOrNull { it.first == "perPage" }?.second?.toIntOrNull() ?: 50
        val result = svc.list(page, perPage, view.sort, params, expand = expandOf(view))
        return buildJsonObject {
            put("items", JsonArray(result.items.map { project(view, it) }))
            put("page", JsonPrimitive(result.page))
            put("perPage", JsonPrimitive(result.perPage))
            put("totalItems", JsonPrimitive(result.totalItems))
        }
    }

    /** CSV (Excel uyumlu, BOM'lu); en çok [MAX_ROWS] satır. Rapor görünümünde gruplar. */
    fun csv(view: ViewConfig, caller: Caller, query: List<Pair<String, String>>, out: Writer, delimiter: Char = ',') {
        authorize(view, caller)
        val params = params(view, caller, query)
        val svc = service(view)
        out.write(Csv.BOM.code)
        val report = view.report
        if (report != null) {
            val result = svc.aggregate(params + report.params())
            val groups = (result["groups"] as? JsonArray)?.map { it as JsonObject } ?: listOf(result)
            val header = groups.firstOrNull()?.keys?.toList() ?: listOf("count")
            out.write(Csv.row(header, delimiter) + "\r\n")
            groups.forEach { g -> out.write(Csv.row(header.map { cell(g[it]) }, delimiter) + "\r\n") }
        } else {
            val cols = columns(view)
            out.write(Csv.row(cols, delimiter) + "\r\n")
            var page = 1
            var count = 0
            while (count < MAX_ROWS) {
                val items = svc.list(page, PAGE, view.sort, params, expand = expandOf(view)).items
                for (item in items) {
                    if (count >= MAX_ROWS) break
                    val row = project(view, item)
                    out.write(Csv.row(cols.map { cell(row[it]) }, delimiter) + "\r\n")
                    count++
                }
                if (items.size < PAGE) break
                page++
            }
        }
        out.flush()
    }

    private fun service(view: ViewConfig): RecordService =
        services[view.collection] ?: throw ApiException(500, tr("'{0}' görünümünün koleksiyonu '{1}' yok", view.name, view.collection))

    /** Sabit koşul (`@me` istekteki kullanıcı) ve istemcinin görünümdeki temel sütunlara göre verdiği filtreler. */
    private fun params(view: ViewConfig, caller: Caller, query: List<Pair<String, String>>): List<Pair<String, String>> {
        val me = ((caller as? Caller.User)?.id ?: 0L).toString()
        val fixed = view.filter.orEmpty().split('&').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            part.substringBefore('=').trim() to part.substringAfter('=', "").trim().split(',').joinToString(",") { if (it.trim() == "@me") me else it }
        }
        val base = columns(view).filter { '.' !in it }.toSet()
        val extra = query.filter { it.first !in CONTROL }.onEach { (key, _) ->
            val name = key.substringBefore('[')
            if (name != "q" && name !in base) throw ApiException(400, tr("Bu görünümde '{0}' ile filtrelenemez (görünümün sütunları: {1})", name, base.joinToString()))
        }
        return fixed + extra
    }

    private fun expandOf(view: ViewConfig): Map<String, Scope?> =
        columns(view).filter { '.' in it }.map { it.substringBefore('.') }.distinct().associateWith { null }

    private fun project(view: ViewConfig, item: JsonObject): JsonObject = buildJsonObject {
        val expand = item["expand"] as? JsonObject
        for (col in columns(view)) {
            val value = if ('.' in col) {
                (expand?.get(col.substringBefore('.')) as? JsonObject)?.get(col.substringAfter('.')) ?: JsonNull
            } else item[col] ?: JsonNull
            put(col, value)
        }
    }

    private fun cell(v: JsonElement?): String? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && v.isString -> Csv.guard(v.content)
        v is JsonPrimitive -> v.content
        else -> v.toString()
    }

    companion object {
        const val MAX_ROWS = 50_000
        private const val PAGE = 500
        private val CONTROL = setOf("page", "perPage", "format", "delimiter")

        /** Sütun verilmemiş görünümde: kimlik, yalnızca yöneticiye/sahibine görünmeyen alanlar, oluşturulma zamanı. */
        fun defaultColumns(config: ServerConfig, view: ViewConfig): List<String> {
            val c = config.collections.firstOrNull { it.name == view.collection } ?: return listOf("id")
            val admin = view.access == Rule.ADMIN && view.roles.isEmpty()
            return listOf("id") + c.fields.filter { admin || it.visibility == null }.map { it.name } + "created_at"
        }

        /**
         * Sütunun doğrulama sorunu; yoksa null. Yönetici dışındakilere açık görünümde yöneticiye/sahibine özel alanlar ve
         * kullanıcıların gizli alanları (e-posta vb.) seçilemez.
         */
        fun columnProblem(config: ServerConfig, view: ViewConfig, column: String): String? {
            val c = config.collections.firstOrNull { it.name == view.collection } ?: return null
            val restricted = !(view.access == Rule.ADMIN && view.roles.isEmpty())
            fun check(target: CollectionConfig?, name: String, label: String): String? {
                if (name in SYSTEM) return null
                if (target == null) return tr("'{0}': bilinmeyen koleksiyon", label)
                val f = target.fields.firstOrNull { it.name == name } ?: return tr("'{0}': bilinmeyen alan", label)
                if (restricted && f.visibility != null) return tr("'{0}': gizli alan yalnızca yöneticiye özel görünümde gösterilebilir", label)
                return null
            }
            if ('.' !in column) return check(c, column, column)
            val rel = c.fields.firstOrNull { it.name == column.substringBefore('.') }
            if (rel == null || rel.type != FieldType.RELATION) return tr("'{0}': nokta öncesi bir ilişki alanı olmalı", column)
            val sub = column.substringAfter('.')
            if ('.' in sub) return tr("'{0}': ilişki üzerinden yalnızca bir adım", column)
            if (rel.collection == SystemCollections.USERS) {
                return if (sub in SYSTEM || sub in config.auth.users.publicFields) null
                else tr("'{0}': kullanıcılardan yalnızca herkese görünür alanlar gösterilir", column)
            }
            return check(config.collections.firstOrNull { it.name == rel.collection }, sub, column)
        }

        private val SYSTEM = setOf("id", "created_at", "updated_at")
    }
}
