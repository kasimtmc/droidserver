package com.plyg.droidserver.core.graphql

import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.ApiNames
import com.plyg.droidserver.core.api.Page
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Kayıt işlemleri; REST uçlarıyla aynı kurallar, kancalar ve olaylar (ServerModule istek başına kurar). Dönen kayıtlar
 * API adlarıyladır (uzman modu) ve genişletilen ilişkiler `expand` altındadır.
 */
interface GqlBackend {
    fun list(c: CollectionConfig, page: Int, perPage: Int, sort: String?, params: List<Pair<String, String>>, expand: Set<String>): Page
    fun get(c: CollectionConfig, id: Long, expand: Set<String>): JsonObject
    fun create(c: CollectionConfig, data: JsonObject): JsonObject
    fun update(c: CollectionConfig, id: Long, data: JsonObject): JsonObject
    fun delete(c: CollectionConfig, id: Long)
}

/** Hangi kökler açık: REST'te kapatılan uç GraphQL'de de yoktur. */
class GqlOps(val list: Boolean, val view: Boolean, val create: Boolean, val update: Boolean, val delete: Boolean)

private class GqlError(message: String, val path: List<Any>, val extensions: JsonObject? = null) : RuntimeException(message)

/**
 * Koleksiyonlardan türetilen GraphQL şeması ve yürütücü. Her koleksiyon için:
 *   query    `<ad>(filter, sort, page, perPage, q)` → sayfa, `<ad>_by_id(id)` → kayıt
 *   mutation `create_<ad>(data)`, `update_<ad>(id, data)`, `delete_<ad>(id)`
 *   subscription `<ad>(id, events)` → `{action, record}` ([subscriptions] açıksa; `graphql-transport-ws` ile, bkz. [subscribe])
 * İlişki alanı alt seçimle istenirse genişletilir (REST'teki `expand`, aynı görüntüleme kuralıyla). İçgözlem
 * (`__schema`) yerine SDL `/graphql/schema` adresindedir; kod üreticiler bu dosyayı okuyabilir.
 */
class GraphQL(
    private val collections: List<CollectionConfig>,
    private val ops: (CollectionConfig) -> GqlOps,
    /** İlişkinin hedefi kullanıcılarsa görünür alanlar (uzman modu adlarıyla). */
    private val userFields: Set<String> = emptySet(),
    /** Abonelikler (canlı abonelik açıksa). */
    private val subscriptions: Boolean = false,
) {
    private val exposed = collections.filter { c -> ops(c).let { it.list || it.view || it.create || it.update || it.delete } }
    private val byName = collections.associateBy { it.name }

    /** Kaydın sorgulanabilir alanları: API adlarıyla sistem alanları ve koleksiyonun alanları. */
    private val fieldsOf: Map<String, Set<String>> = collections.associate { c ->
        val system = ApiNames.of(c).out(JsonObject(mapOf("id" to JsonNull, "created_at" to JsonNull, "updated_at" to JsonNull))).keys
        c.name to (system + c.fields.map { it.name })
    }

    fun typeName(c: String) = if (c == SystemCollections.USERS) "User" else c.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /** Şema tanım dili (SDL); kod üreticiler ve IDE'ler için. */
    fun sdl(): String = buildString {
        appendLine("# DroidServer GraphQL şeması: koleksiyonlardan otomatik üretilir.")
        appendLine("scalar JSON")
        appendLine()
        appendLine("type Query {")
        for (c in exposed) {
            val t = typeName(c.name)
            if (ops(c).list) appendLine("  ${c.name}(filter: String, sort: String, page: Int, perPage: Int, q: String): ${t}Page!")
            if (ops(c).view) appendLine("  ${c.name}_by_id(id: ID!): $t")
        }
        if (exposed.none { ops(it).list || ops(it).view }) appendLine("  _empty: Boolean")
        appendLine("}")
        val mutations = exposed.filter { ops(it).create || ops(it).update || ops(it).delete }
        if (mutations.isNotEmpty()) {
            appendLine()
            appendLine("type Mutation {")
            for (c in mutations) {
                val t = typeName(c.name)
                if (ops(c).create) appendLine("  create_${c.name}(data: JSON!): $t!")
                if (ops(c).update) appendLine("  update_${c.name}(id: ID!, data: JSON!): $t!")
                if (ops(c).delete) appendLine("  delete_${c.name}(id: ID!): DeleteResult!")
            }
            appendLine("}")
            appendLine()
            appendLine("type DeleteResult {\n  id: ID!\n  deleted: Boolean!\n}")
        }
        val types = (exposed + exposed.flatMap { c -> c.fields.mapNotNull { f -> f.collection?.takeIf { f.type == FieldType.RELATION }?.let(byName::get) } }).distinctBy { it.name }
        for (c in types) {
            val t = typeName(c.name)
            appendLine()
            appendLine("type $t {")
            fieldsOf.getValue(c.name).filter { n -> c.fields.none { it.name == n } }.forEach { appendLine("  $it: ${if (it == "id" || it == ApiNames.of(c).out(JsonObject(mapOf("id" to JsonNull))).keys.firstOrNull()) "ID" else "String"}") }
            for (f in c.fields) {
                val type = when (f.type) {
                    FieldType.TEXT, FieldType.DATETIME, FieldType.SELECT -> "String"
                    FieldType.INTEGER -> "Int"
                    FieldType.REAL -> "Float"
                    FieldType.BOOLEAN -> "Boolean"
                    FieldType.RELATION -> f.collection?.let { target -> if (target == SystemCollections.USERS) "User" else byName[target]?.let { typeName(it.name) } } ?: "ID"
                    else -> "JSON"
                }
                appendLine("  ${f.name}: $type")
            }
            appendLine("}")
            if (exposed.any { it.name == c.name } && ops(c).list) {
                appendLine()
                appendLine("type ${t}Page {\n  items: [$t!]!\n  totalItems: Int!\n  page: Int!\n  perPage: Int!\n}")
            }
        }
        val subscribable = exposed.filter { subscriptions && ops(it).view }
        if (subscribable.isNotEmpty()) {
            appendLine()
            appendLine("type Subscription {")
            for (c in subscribable) appendLine("  ${c.name}(id: ID, events: [String!]): ${typeName(c.name)}Event!")
            appendLine("}")
            for (c in subscribable) {
                appendLine()
                appendLine("# action: create, update veya delete; silmede record yalnızca id taşır, ilişkiler genişletilmez.")
                appendLine("type ${typeName(c.name)}Event {\n  action: String!\n  record: ${typeName(c.name)}\n}")
            }
        }
        if (collections.any { c -> c.fields.any { it.type == FieldType.RELATION && it.collection == SystemCollections.USERS } }) {
            appendLine()
            appendLine("type User {")
            (setOf("id") + userFields).forEach { appendLine("  $it: ${if (it == "id") "ID" else "JSON"}") }
            appendLine("}")
        }
    }

    private class Prepared(val doc: GqlDocument, val op: GqlOperation, val vars: Map<String, JsonElement>)

    /** İstekten çalıştırılacak işlem ve değişkenler; hatalıysa `{errors}` yanıtı. */
    private fun prepare(request: JsonObject): Pair<Prepared?, JsonObject?> {
        val query = (request["query"] as? JsonPrimitive)?.contentOrNull ?: return null to failure(tr("'query' alanı gerekli"))
        val vars = (request["variables"] as? JsonObject).orEmpty()
        val opName = (request["operationName"] as? JsonPrimitive)?.contentOrNull
        val doc = try {
            GqlParser.parse(query)
        } catch (e: GqlSyntaxException) {
            return null to failure(e.message.orEmpty())
        }
        val op = when {
            opName != null -> doc.operations.firstOrNull { it.name == opName } ?: return null to failure(tr("'{0}' adlı işlem yok", opName))
            doc.operations.size == 1 -> doc.operations.single()
            else -> return null to failure(tr("Belgede birden çok işlem var; operationName verin"))
        }
        val variables = op.variables.associate { v -> v.name to (vars[v.name] ?: v.default?.resolve(emptyMap()) ?: JsonNull) }
        return Prepared(doc, op, variables) to null
    }

    /**
     * Abonelik: koleksiyon, kayıt kimliği ve olay süzgeci; [payload] olayı istemcinin seçimine göre `{data}` yanıtına
     * çevirir ([record] istemciye görünür haliyle, API adlarıyla).
     */
    class Subscription internal constructor(val collection: String, val id: Long?, val events: Set<String>, private val render: (String, JsonObject) -> JsonObject) {
        fun payload(action: String, record: JsonObject): JsonObject = render(action, record)
    }

    /** İstek bir abonelikse planı; abonelik değilse (sorgu, mutasyon) ikisi de null; hatalıysa `{errors}` yanıtı. */
    fun subscribe(request: JsonObject): Pair<Subscription?, JsonObject?> {
        val (p, failure) = prepare(request)
        if (p == null) return null to failure
        if (p.op.type != "subscription") return null to null
        if (!subscriptions) return null to failure(tr("Abonelikler kapalı; canlı aboneliği açın"))
        val ctx = Ctx(p.doc, p.vars, NoBackend)
        return try {
            val f = ctx.collect(p.op.selection, "Subscription").singleOrNull() ?: throw GqlError(tr("Abonelikte tek kök alan olmalı"), emptyList())
            val path = listOf<Any>(f.key)
            val c = byName[f.name]?.takeIf { c -> exposed.any { it.name == c.name } && ops(c).view }
                ?: throw GqlError(tr("'{0}' türünde '{1}' alanı yok", "Subscription", f.name), path)
            val eventType = typeName(c.name) + "Event"
            val fields = ctx.collect(f.selection ?: throw GqlError(tr("'{0}' için alt seçim gerekli", f.name), path), eventType, path)
            val recordFields = fields.filter { it.name == "record" }.associate { r ->
                r.key to ctx.collect(r.selection ?: throw GqlError(tr("'record' için alt seçim gerekli"), path + r.key), typeName(c.name), path + r.key)
            }
            fields.firstOrNull { it.name !in setOf("action", "record", "__typename") }?.let {
                throw GqlError(tr("'{0}' türünde '{1}' alanı yok", eventType, it.name), path + it.key)
            }
            val id = ctx.arg(f, "id")?.let { (it as? JsonPrimitive)?.let { v -> v.longOrNull ?: v.content.toLongOrNull() } ?: throw GqlError(tr("'id' bir sayı olmalı"), path) }
            val events = (ctx.arg(f, "events") as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() }?.toSet() ?: EVENTS
            if (events.isEmpty() || !EVENTS.containsAll(events)) throw GqlError(tr("Olaylar: {0}", EVENTS.joinToString()), path)
            val render = { action: String, rec: JsonObject ->
                val event = JsonObject(fields.associate { ef ->
                    ef.key to when (ef.name) {
                        "action" -> JsonPrimitive(action)
                        "__typename" -> JsonPrimitive(eventType)
                        else -> record(ctx, c, rec, recordFields.getValue(ef.key), path + ef.key)
                    }
                })
                JsonObject(mapOf("data" to JsonObject(mapOf(f.key to event))))
            }
            render("create", JsonObject(emptyMap())) // seçimdeki alan adları şimdi denetlenir
            Subscription(c.name, id, events, render) to null
        } catch (e: GqlError) {
            null to JsonObject(mapOf("errors" to JsonArray(listOf(e.toJson()))))
        }
    }

    /** `{query, variables, operationName}` → `{data, errors}`. */
    fun execute(request: JsonObject, backend: GqlBackend): JsonObject {
        val (p, failure) = prepare(request)
        if (p == null) return failure!!
        if (p.op.type == "subscription") return failure(tr("Abonelik için WebSocket ({0}) kullanın", "/graphql/ws"))
        val doc = p.doc
        val op = p.op
        val ctx = Ctx(doc, p.vars, backend)
        val errors = mutableListOf<GqlError>()
        val data = LinkedHashMap<String, JsonElement>()
        val roots = try {
            ctx.collect(op.selection, if (op.type == "mutation") "Mutation" else "Query")
        } catch (e: GqlError) {
            return JsonObject(mapOf("errors" to JsonArray(listOf(e.toJson()))))
        }
        if (roots.size > MAX_ROOTS) return failure(tr("Bir istekte en çok {0} kök alan", MAX_ROOTS))
        for (f in roots) {
            data[f.key] = try {
                if (op.type == "mutation") mutation(ctx, f) else query(ctx, f)
            } catch (e: GqlError) {
                errors += e
                JsonNull
            } catch (e: ApiException) {
                errors += apiError(e, listOf(f.key))
                JsonNull
            }
        }
        return JsonObject(buildMap {
            put("data", JsonObject(data))
            if (errors.isNotEmpty()) put("errors", JsonArray(errors.map { it.toJson() }))
        })
    }

    private inner class Ctx(val doc: GqlDocument, val vars: Map<String, JsonElement>, val backend: GqlBackend) {
        var fields = 0

        /** Parçaları açar, yönergeleri uygular: sırayla alanlar. */
        fun collect(selection: List<GqlSelection>, type: String, path: List<Any> = emptyList(), seen: Set<String> = emptySet()): List<GqlField> = buildList {
            for (s in selection) {
                if (!included(s.directives)) continue
                when (s) {
                    is GqlField -> {
                        if (++fields > MAX_FIELDS) throw GqlError(tr("Sorguda çok fazla alan (en çok {0})", MAX_FIELDS), path)
                        add(s)
                    }
                    is GqlInlineFragment -> if (s.typeCondition == null || s.typeCondition == type) addAll(collect(s.selection, type, path, seen))
                    is GqlFragmentSpread -> {
                        val fr = doc.fragments[s.name] ?: throw GqlError(tr("'{0}' parçası tanımlı değil", s.name), path)
                        if (s.name in seen) throw GqlError(tr("'{0}' parçası kendini içeriyor", s.name), path)
                        if (fr.typeCondition == type) addAll(collect(fr.selection, type, path, seen + s.name))
                    }
                }
            }
        }

        private fun included(dirs: List<GqlDirective>): Boolean = dirs.all { d ->
            val cond = d.args["if"]?.resolve(vars)?.let { (it as? JsonPrimitive)?.booleanOrNull }
            when (d.name) {
                "include" -> cond == true
                "skip" -> cond != true
                else -> true
            }
        }

        fun arg(f: GqlField, name: String): JsonElement? = f.args[name]?.resolve(vars)?.takeIf { it !is JsonNull }
    }

    private fun collection(f: GqlField, prefix: String, suffix: String): CollectionConfig? {
        if (!f.name.startsWith(prefix) || !f.name.endsWith(suffix)) return null
        return byName[f.name.removePrefix(prefix).removeSuffix(suffix)]?.takeIf { c -> exposed.any { it.name == c.name } }
    }

    private fun query(ctx: Ctx, f: GqlField): JsonElement {
        val path = listOf<Any>(f.key)
        if (f.name == "__typename") return JsonPrimitive("Query")
        if (f.name == "__schema" || f.name == "__type") throw GqlError(tr("İçgözlem desteklenmiyor; şema için GET {0} kullanın", "/graphql/schema"), path)
        collection(f, "", "_by_id")?.takeIf { ops(it).view }?.let { c ->
            val sel = f.selection ?: throw GqlError(tr("'{0}' için alt seçim gerekli", f.name), path)
            val fields = ctx.collect(sel, typeName(c.name), path)
            val record = ctx.backend.get(c, id(ctx, f, path), expandOf(c, fields))
            return record(ctx, c, record, fields, path)
        }
        byName[f.name]?.takeIf { c -> exposed.any { it.name == c.name } && ops(c).list }?.let { c ->
            val sel = f.selection ?: throw GqlError(tr("'{0}' için alt seçim gerekli", f.name), path)
            val pageType = typeName(c.name) + "Page"
            val pageFields = ctx.collect(sel, pageType, path)
            val itemFields = pageFields.filter { it.name == "items" }.flatMap { it.selection?.let { s -> ctx.collect(s, typeName(c.name), path + it.key) }.orEmpty() }
            val filter = ctx.arg(f, "filter")?.let { (it as? JsonPrimitive)?.content }
            val params = buildList {
                filter?.split('&')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { add(it.substringBefore('=').trim() to it.substringAfter('=', "").trim()) }
                ctx.arg(f, "q")?.let { add("q" to (it as JsonPrimitive).content) }
            }
            val page = ctx.backend.list(
                c,
                page = int(ctx.arg(f, "page"), 1, path),
                perPage = int(ctx.arg(f, "perPage"), 20, path),
                sort = ctx.arg(f, "sort")?.let { (it as JsonPrimitive).content },
                params = params,
                expand = expandOf(c, itemFields),
            )
            return JsonObject(pageFields.associate { pf ->
                pf.key to when (pf.name) {
                    "items" -> {
                        val sub = pf.selection ?: throw GqlError(tr("'items' için alt seçim gerekli"), path + pf.key)
                        val fs = ctx.collect(sub, typeName(c.name), path + pf.key)
                        JsonArray(page.items.mapIndexed { i, r -> record(ctx, c, r, fs, path + pf.key + i) })
                    }
                    "totalItems" -> JsonPrimitive(page.totalItems)
                    "page" -> JsonPrimitive(page.page)
                    "perPage" -> JsonPrimitive(page.perPage)
                    "__typename" -> JsonPrimitive(pageType)
                    else -> throw GqlError(tr("'{0}' türünde '{1}' alanı yok", pageType, pf.name), path + pf.key)
                }
            })
        }
        throw GqlError(tr("'{0}' türünde '{1}' alanı yok", "Query", f.name), path)
    }

    private fun mutation(ctx: Ctx, f: GqlField): JsonElement {
        val path = listOf<Any>(f.key)
        if (f.name == "__typename") return JsonPrimitive("Mutation")
        fun data(): JsonObject = ctx.arg(f, "data") as? JsonObject ?: throw GqlError(tr("'data' argümanı bir nesne olmalı"), path)
        fun selected(c: CollectionConfig, record: JsonObject): JsonElement {
            val sel = f.selection ?: throw GqlError(tr("'{0}' için alt seçim gerekli", f.name), path)
            return record(ctx, c, record, ctx.collect(sel, typeName(c.name), path), path)
        }
        collection(f, "create_", "")?.takeIf { ops(it).create }?.let { c -> return selected(c, ctx.backend.create(c, data())) }
        collection(f, "update_", "")?.takeIf { ops(it).update }?.let { c -> return selected(c, ctx.backend.update(c, id(ctx, f, path), data())) }
        collection(f, "delete_", "")?.takeIf { ops(it).delete }?.let { c ->
            val id = id(ctx, f, path)
            ctx.backend.delete(c, id)
            val sel = f.selection?.let { ctx.collect(it, "DeleteResult", path) } ?: throw GqlError(tr("'{0}' için alt seçim gerekli", f.name), path)
            return JsonObject(sel.associate { s ->
                s.key to when (s.name) {
                    "id" -> JsonPrimitive(id.toString())
                    "deleted" -> JsonPrimitive(true)
                    "__typename" -> JsonPrimitive("DeleteResult")
                    else -> throw GqlError(tr("'{0}' türünde '{1}' alanı yok", "DeleteResult", s.name), path + s.key)
                }
            })
        }
        throw GqlError(tr("'{0}' türünde '{1}' alanı yok", "Mutation", f.name), path)
    }

    /** Alt seçimle istenen ilişki alanları: genişletilecekler. */
    private fun expandOf(c: CollectionConfig, fields: List<GqlField>): Set<String> =
        fields.filter { f -> f.selection != null && c.fields.any { it.name == f.name && it.type == FieldType.RELATION } }.map { it.name }.toSet()

    private fun record(ctx: Ctx, c: CollectionConfig, record: JsonObject, fields: List<GqlField>, path: List<Any>): JsonObject {
        val known = fieldsOf.getValue(c.name)
        val expanded = record["expand"] as? JsonObject
        return JsonObject(fields.associate { f ->
            val p = path + f.key
            f.key to when {
                f.name == "__typename" -> JsonPrimitive(typeName(c.name))
                f.name !in known -> throw GqlError(tr("'{0}' türünde '{1}' alanı yok", typeName(c.name), f.name), p)
                f.selection != null -> {
                    val rel = c.fields.firstOrNull { it.name == f.name && it.type == FieldType.RELATION }
                        ?: throw GqlError(tr("'{0}' alanı skaler; alt seçim verilemez", f.name), p)
                    when (val v = expanded?.get(f.name)) {
                        is JsonObject -> nested(ctx, rel.collection!!, v, f.selection, p)
                        is JsonArray -> JsonArray(v.mapIndexed { i, x -> (x as? JsonObject)?.let { nested(ctx, rel.collection!!, it, f.selection, p + i) } ?: JsonNull })
                        else -> JsonNull
                    }
                }
                else -> {
                    val isRelation = c.fields.any { it.name == f.name && it.type == FieldType.RELATION }
                    record[f.name] ?: if (isRelation) (expanded?.get(f.name) as? JsonObject)?.get("id") ?: JsonNull else JsonNull
                }
            }
        })
    }

    private fun nested(ctx: Ctx, target: String, v: JsonObject, selection: List<GqlSelection>, path: List<Any>): JsonObject {
        val c = byName[target]
        if (c != null) return record(ctx, c, v, ctx.collect(selection, typeName(c.name), path), path)
        // Kullanıcı: yalnızca görünür alanlar; bilinmeyen alan null.
        return JsonObject(ctx.collect(selection, "User", path).associate { f ->
            f.key to if (f.name == "__typename") JsonPrimitive("User") else v[f.name] ?: JsonNull
        })
    }

    private fun id(ctx: Ctx, f: GqlField, path: List<Any>): Long {
        val v = ctx.arg(f, "id") as? JsonPrimitive ?: throw GqlError(tr("'id' argümanı gerekli"), path)
        return v.longOrNull ?: v.content.toLongOrNull() ?: throw GqlError(tr("'id' bir sayı olmalı"), path)
    }

    private fun int(v: JsonElement?, default: Int, path: List<Any>): Int =
        if (v == null) default else (v as? JsonPrimitive)?.longOrNull?.toInt() ?: throw GqlError(tr("Tam sayı bekleniyordu"), path)

    private fun apiError(e: ApiException, path: List<Any>) = GqlError(
        e.message ?: tr("Hata"), path,
        buildJsonObject {
            put("code", JsonPrimitive(e.status))
            if (e.details.isNotEmpty()) put("details", JsonObject(e.details.mapValues { JsonPrimitive(it.value) }))
        },
    )

    private fun GqlError.toJson() = buildJsonObject {
        put("message", JsonPrimitive(message))
        if (path.isNotEmpty()) put("path", JsonArray(path.map { if (it is Int) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))
        extensions?.let { put("extensions", it) }
    }

    private fun failure(message: String) = JsonObject(mapOf("errors" to JsonArray(listOf(JsonObject(mapOf("message" to JsonPrimitive(message)))))))

    /** Abonelik seçimi doğrulanırken kayıt okunmaz. */
    private object NoBackend : GqlBackend {
        override fun list(c: CollectionConfig, page: Int, perPage: Int, sort: String?, params: List<Pair<String, String>>, expand: Set<String>) = error("abonelik")
        override fun get(c: CollectionConfig, id: Long, expand: Set<String>) = error("abonelik")
        override fun create(c: CollectionConfig, data: JsonObject) = error("abonelik")
        override fun update(c: CollectionConfig, id: Long, data: JsonObject) = error("abonelik")
        override fun delete(c: CollectionConfig, id: Long) = error("abonelik")
    }

    companion object {
        private val EVENTS = setOf("create", "update", "delete")
        const val MAX_ROOTS = 20
        const val MAX_FIELDS = 1_000
    }
}
