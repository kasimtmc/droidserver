package dev.droidserver.core.data

import dev.droidserver.core.api.ApiException
import dev.droidserver.core.api.ApiNames
import dev.droidserver.core.api.RecordService
import dev.droidserver.core.api.Scope
import dev.droidserver.core.api.WriteGate
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.FieldVisibility
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.files.FileStore
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.security.FieldEncryption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

enum class TransferFormat(val extension: String, val mime: String) {
    CSV("csv", "text/csv"), JSON("json", "application/json");

    companion object {
        fun of(s: String?): TransferFormat = entries.firstOrNull { it.extension == s?.lowercase() }
            ?: throw ApiException(400, tr("format csv veya json olmalı"))
    }
}

/** İçe aktarmada `id` sütunu: yok sayılır (hep yeni kayıt) veya varsa o kayıt güncellenir. */
enum class ImportMode(val key: String, val title: String) {
    INSERT("insert", tr("Hepsini yeni kayıt olarak ekle")),
    UPSERT("upsert", tr("id'si olan kaydı güncelle, olmayanı ekle"));

    companion object {
        fun of(s: String?): ImportMode = if (s == null) INSERT else entries.firstOrNull { it.key == s }
            ?: throw ApiException(400, tr("mode insert veya upsert olmalı"))
    }
}

data class RowError(val row: Int, val message: String, val details: Map<String, String> = emptyMap()) {
    fun toJson() = buildJsonObject {
        put("row", JsonPrimitive(row))
        put("message", JsonPrimitive(message))
        if (details.isNotEmpty()) put("details", JsonObject(details.mapValues { JsonPrimitive(it.value) }))
    }
}

data class ImportResult(
    /** Dosyadaki sütunlar (JSON'da anahtarlar). */
    val columns: List<String>,
    /** Uygulanan eşleme: sütun → alan (null: alınmadı). */
    val mapping: Map<String, String?>,
    val total: Int,
    val created: Int,
    val updated: Int,
    val errors: List<RowError>,
    /** Dönüştürülmüş ilk kayıtlar (önizleme). */
    val sample: List<JsonObject>,
    val dryRun: Boolean,
    /** Veritabanına yazıldı mı (deneme değil ve hata yok). */
    val applied: Boolean,
) {
    fun toJson() = buildJsonObject {
        put("columns", JsonArray(columns.map(::JsonPrimitive)))
        put("mapping", JsonObject(mapping.mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull }))
        put("total", JsonPrimitive(total))
        put("created", JsonPrimitive(created))
        put("updated", JsonPrimitive(updated))
        put("errors", JsonArray(errors.map { it.toJson() }))
        put("sample", JsonArray(sample))
        put("dryRun", JsonPrimitive(dryRun))
        put("applied", JsonPrimitive(applied))
    }
}

/**
 * Koleksiyonu CSV/JSON olarak dışa aktarma ve dosyadan içe aktarma. Dışa aktarma liste gibi filtre, arama ve kurallarla
 * (gizli alanlar hariç) çalışır. İçe aktarma tek işlemdir: bir satır bile hatalıysa hiçbiri yazılmaz; [dryRun] aynı
 * denetimleri (benzersizlik ve ilişkiler dahil) yapıp geri alır. İçe aktarılan kayıtlar canlı abonelik, webhook ve
 * script olayı üretmez. Dosya alanları içe aktarılmaz (adları dışa aktarılır).
 */
class DataTransfer(
    private val db: SqlDriver,
    private val config: ServerConfig,
    private val files: FileStore? = null,
    private val writes: WriteGate = WriteGate(),
    /** Uzman modu: sistem alanlarının API adları (başlıkta, JSON'da ve eşlemede). */
    private val names: (String) -> ApiNames = { n -> config.collections.firstOrNull { it.name == n }?.let(ApiNames::of) ?: ApiNames.of(CollectionConfig(n, emptyList())) },
) {
    private fun collection(name: String) = config.collections.firstOrNull { it.name == name }
        ?: throw ApiException(404, tr("'{0}' koleksiyonu yok", name))

    private fun service(c: CollectionConfig) =
        RecordService(db, c, collections = config.schemaCollections(), publicUserFields = config.auth.users.publicFields, files = files, cipher = FieldEncryption.cipher(config))

    /** Dışa aktarılan sütunlar: yöneticiye özel alanlar yalnızca yöneticiye. */
    fun exportColumns(name: String, scope: Scope = Scope.ADMIN): List<String> {
        val c = collection(name)
        val n = names(name)
        return (listOf("id") + c.fields.filter { scope.admin || it.visibility != FieldVisibility.ADMIN }.map { it.name } + listOf("created_at", "updated_at"))
            .filter { n.api(it) != null }
    }

    /**
     * [out]'a yazar, kayıt sayısını döndürür. CSV'de Excel'in Türkçe karakterleri tanıması için BOM, formül olarak
     * çalışabilecek metinlerin başına `'` (bkz. [Csv.guard]). En çok [MAX_EXPORT] kayıt.
     */
    fun export(
        name: String,
        format: TransferFormat,
        out: Writer,
        params: List<Pair<String, String>> = emptyList(),
        sort: String? = null,
        scope: Scope = Scope.ADMIN,
        delimiter: Char = ',',
    ): Int {
        val c = collection(name)
        val svc = service(c)
        val columns = exportColumns(name, scope)
        val n = names(name)
        val types = c.fields.associate { it.name to it.type }
        var page = 1
        var count = 0
        if (format == TransferFormat.CSV) {
            out.write(Csv.BOM.code)
            out.write(Csv.row(columns.map { n.api(it) ?: it }, delimiter) + "\r\n")
        } else {
            out.write("[")
        }
        while (count < MAX_EXPORT) {
            val items = svc.list(page, PAGE, sort, params, scope = scope).items
            for (item in items) {
                if (count >= MAX_EXPORT) break
                when (format) {
                    TransferFormat.CSV -> out.write(Csv.row(columns.map { col -> cell(item[col], types[col]) }, delimiter) + "\r\n")
                    TransferFormat.JSON -> out.write((if (count == 0) "\n" else ",\n") + n.out(item).toString())
                }
                count++
            }
            if (items.size < PAGE) break
            page++
        }
        if (format == TransferFormat.JSON) out.write(if (count == 0) "]\n" else "\n]\n")
        out.flush()
        return count
    }

    private fun cell(v: JsonElement?, type: FieldType?): String? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && v.isString -> if (type == FieldType.TEXT) Csv.guard(v.content) else v.content
        v is JsonPrimitive -> v.content
        else -> v.toString() // json alanı, çoklu dosya
    }

    /**
     * [text]'i ([format]) [name] koleksiyonuna aktarır. [mapping] sütun → alan; null ise sütun adı alan adıyla
     * (büyük/küçük harf duyarsız) eşleşir. Satır numaraları CSV'de başlıktan sonra 1'den, JSON'da dizideki sıradan.
     */
    fun import(
        name: String,
        format: TransferFormat,
        text: String,
        mapping: Map<String, String?>? = null,
        mode: ImportMode = ImportMode.INSERT,
        dryRun: Boolean = false,
    ): ImportResult {
        val c = collection(name)
        val svc = service(c)
        val fields = c.fields.associateBy { it.name }
        val (columns, rows) = read(format, text)
        if (rows.size > MAX_ROWS) throw ApiException(400, tr("En çok {0} satır içe aktarılabilir (dosyada {1})", MAX_ROWS, rows.size))
        val targets = fields.keys + "id"
        val n = names(name)
        val effective = columns.associateWith { col ->
            if (mapping != null) mapping[col]?.takeIf { it.isNotEmpty() }?.let(n::canonical)
            else targets.firstOrNull { it.equals(n.canonical(col.trim()), ignoreCase = true) || it.equals(col.trim(), ignoreCase = true) }
        }
        effective.values.filterNotNull().groupingBy { it }.eachCount().filter { it.value > 1 }.keys.firstOrNull()?.let {
            throw ApiException(400, tr("'{0}' alanına birden çok sütun eşlenmiş", it))
        }
        effective.values.filterNotNull().forEach { f ->
            if (f !in targets) throw ApiException(400, tr("Eşlemede bilinmeyen alan: {0}", f))
            if (fields[f]?.type == FieldType.FILE) throw ApiException(400, tr("'{0}' dosya alanı; dosyalar içe aktarılamaz", f))
        }
        if (effective.values.none { it != null && it != "id" }) throw ApiException(400, tr("Hiçbir sütun bir alana eşlenmedi"))

        val errors = mutableListOf<RowError>()
        val converted = rows.mapIndexed { i, raw ->
            val body = linkedMapOf<String, JsonElement>()
            val bad = linkedMapOf<String, String>()
            for ((col, value) in raw) {
                val target = effective[col] ?: continue
                if (fields[target]?.computed != null) continue // hesaplanır; dışa aktarılan dosyada olsa da yazılmaz
                if (target == "id") {
                    if (value is JsonNull || (value as? JsonPrimitive)?.content?.isBlank() == true) continue
                    (value as? JsonPrimitive)?.let { it.longOrNull ?: it.content.trim().toLongOrNull() }?.let { body["id"] = JsonPrimitive(it) }
                        ?: run { bad["id"] = tr("tam sayı bekleniyor") }
                    continue
                }
                try {
                    convert(fields.getValue(target), value)?.let { body[target] = it }
                } catch (e: IllegalArgumentException) {
                    bad[target] = e.message ?: tr("geçersiz değer")
                }
            }
            if (bad.isNotEmpty() && errors.size < MAX_ERRORS) errors += RowError(i + 1, tr("Dönüştürme hatası"), bad)
            JsonObject(body)
        }

        var created = 0
        var updated = 0
        val write = {
            db.transaction {
                for ((i, body) in converted.withIndex()) {
                    if (errors.size >= MAX_ERRORS) break
                    if (errors.any { it.row == i + 1 }) continue
                    val id = (body["id"] as? JsonPrimitive)?.longOrNull
                    try {
                        val data = JsonObject(body - "id")
                        if (mode == ImportMode.UPSERT && id != null && exists(c, id)) {
                            svc.update(id, data)
                            updated++
                        } else {
                            svc.create(data)
                            created++
                        }
                    } catch (e: ApiException) {
                        errors += RowError(i + 1, e.message ?: tr("Hata"), e.details)
                        // Kısıt ihlali (PostgreSQL'de) işlemi bozar: sonraki satırlar denenmez.
                        if (e.status == 409) break
                    }
                }
                if (dryRun || errors.isNotEmpty()) throw Rollback()
            }
        }
        val applied = try {
            writes.write(write)
            true
        } catch (_: Rollback) {
            false
        }
        return ImportResult(
            columns = columns,
            mapping = effective,
            total = rows.size,
            created = created,
            updated = updated,
            errors = errors.sortedBy { it.row },
            sample = converted.take(SAMPLE),
            dryRun = dryRun,
            applied = applied,
        )
    }

    private class Rollback : RuntimeException(null, null, false, false)

    private fun exists(c: CollectionConfig, id: Long): Boolean =
        db.query("SELECT \"id\" FROM \"${c.name}\" WHERE \"id\" = ?", listOf(id)).isNotEmpty()

    /** Sütunlar ve satırlar (sütun → ham değer: CSV'de metin, JSON'da öğe). */
    private fun read(format: TransferFormat, text: String): Pair<List<String>, List<List<Pair<String, JsonElement>>>> = when (format) {
        TransferFormat.CSV -> {
            val all = try {
                Csv.parse(text)
            } catch (e: IllegalArgumentException) {
                throw ApiException(400, tr("CSV okunamadı: {0}", e.message))
            }
            val header = all.firstOrNull() ?: throw ApiException(400, tr("Dosya boş"))
            val columns = header.map { it.trim() }
            columns.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.firstOrNull()?.let { throw ApiException(400, tr("'{0}' sütunu iki kez var", it)) }
            columns to all.drop(1).mapIndexed { i, r ->
                if (r.size > columns.size) throw ApiException(400, tr("{0}. satırda başlıktan fazla sütun var ({1} > {2})", i + 1, r.size, columns.size))
                r.mapIndexed { j, v -> columns[j] to JsonPrimitive(v) }
            }
        }
        TransferFormat.JSON -> {
            val root = try {
                Json.parseToJsonElement(text.removePrefix(Csv.BOM.toString()))
            } catch (e: Exception) {
                throw ApiException(400, tr("JSON okunamadı: {0}", e.message?.lineSequence()?.firstOrNull()))
            }
            val array = root as? JsonArray ?: throw ApiException(400, tr("JSON bir nesne dizisi olmalı: [{...}, {...}]"))
            val objects = array.mapIndexed { i, e -> e as? JsonObject ?: throw ApiException(400, tr("{0}. öğe nesne değil", i + 1)) }
            objects.flatMap { it.keys }.distinct() to objects.map { o -> o.entries.map { it.key to it.value } }
        }
    }

    /**
     * Ham değeri alanın JSON değerine çevirir; boş metin null (alan gönderilmez: yeni kayıtta varsayılan, güncellemede
     * değişmez). CSV'den gelen metinler tipe göre ayrıştırılır; JSON'dan gelen değerler olduğu gibi (doğrulamayı
     * kayıt servisi yapar).
     */
    private fun convert(f: FieldConfig, raw: JsonElement): JsonElement? {
        if (raw is JsonNull) return JsonNull
        val p = raw as? JsonPrimitive
        if (p == null || !p.isString) return raw
        val s = p.content
        if (s.isEmpty()) return null
        return when (f.type) {
            FieldType.TEXT -> JsonPrimitive(Csv.unguard(s))
            FieldType.INTEGER, FieldType.RELATION -> JsonPrimitive(s.trim().toLongOrNull() ?: throw IllegalArgumentException(tr("tam sayı bekleniyor: {0}", s)))
            FieldType.REAL -> {
                val t = s.trim().let { if (DECIMAL_COMMA.matches(it)) it.replace(',', '.') else it }
                JsonPrimitive(t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException(tr("sayı bekleniyor: {0}", s)))
            }
            FieldType.BOOLEAN -> JsonPrimitive(
                when (s.trim().lowercase()) {
                    "true", "1", "evet", "yes", "doğru" -> true
                    "false", "0", "hayır", "hayir", "no", "yanlış" -> false
                    else -> throw IllegalArgumentException(tr("true/false bekleniyor: {0}", s))
                },
            )
            FieldType.DATETIME -> JsonPrimitive(parseTime(s.trim()).toString())
            FieldType.JSON -> try {
                Json.parseToJsonElement(s)
            } catch (_: Exception) {
                throw IllegalArgumentException(tr("geçerli JSON değil"))
            }
            FieldType.FILE -> null
            FieldType.SELECT -> JsonPrimitive(s.trim())
            // JSON dizi veya virgülle ayrılmış seçimler ("kırmızı, mavi").
            FieldType.MULTISELECT -> if (s.trimStart().startsWith("[")) {
                runCatching { Json.parseToJsonElement(s) }.getOrElse { throw IllegalArgumentException(tr("geçerli JSON dizi değil")) }
            } else {
                JsonArray(s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
            }
        }
    }

    private fun parseTime(s: String): Instant {
        runCatching { return Instant.parse(s) }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException(tr("zaman bekleniyor (2026-10-14, 2026-10-14 09:30 veya ISO-8601): {0}", s))
        }
    }

    companion object {
        const val MAX_ROWS = 10_000
        const val MAX_EXPORT = 100_000
        const val MAX_ERRORS = 100

        /** İçe aktarma gövdesi sınırı (genel istek sınırından bağımsız). */
        const val MAX_IMPORT_BYTES = 20L * 1024 * 1024
        private const val PAGE = 500
        private const val SAMPLE = 5
        private val DECIMAL_COMMA = Regex("^-?\\d+,\\d+$")

        /** İçe aktarma ucu, [RequestGuard] gövde sınırını aşabilsin diye. */
        fun isImportPath(path: String): Boolean = path.endsWith("/import") && "/collections/" in path

        fun parseMapping(raw: String?): Map<String, String?>? = raw?.takeIf { it.isNotBlank() }?.let {
            val o = try {
                Json.parseToJsonElement(it) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw ApiException(400, tr("mapping bir JSON nesnesi olmalı: {\"Sütun\": \"alan\"}"))
            o.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }
        }
    }
}
