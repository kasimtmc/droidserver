package com.plyg.droidserver.core.data

import com.plyg.droidserver.core.config.GeoPoint
import com.plyg.droidserver.core.api.ApiException
import com.plyg.droidserver.core.api.ApiNames
import com.plyg.droidserver.core.api.RecordService
import com.plyg.droidserver.core.api.Scope
import com.plyg.droidserver.core.api.WriteGate
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.FieldVisibility
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.db.SqlDriver
import com.plyg.droidserver.core.db.quoteIdent
import com.plyg.droidserver.core.files.FileStore
import com.plyg.droidserver.core.files.MimeTypes
import com.plyg.droidserver.core.files.Upload
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.security.FieldEncryption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class TransferFormat(val extension: String, val mime: String) {
    CSV("csv", "text/csv"), JSON("json", "application/json"),

    /** Kayıtlar (`data.json` veya `data.csv`) ve dosya alanlarının dosyaları (`files/<id>/<ad>`). */
    ZIP("zip", "application/zip");

    companion object {
        fun of(s: String?): TransferFormat = entries.firstOrNull { it.extension == s?.lowercase() }
            ?: throw ApiException(400, tr("format csv, json veya zip olmalı"))
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
 * script olayı üretmez. Dosya alanları yalnızca zip ile taşınır ([exportZip], [importZip]); CSV/JSON'da adları yazılır.
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
        onItem: (JsonObject) -> Unit = {},
    ): Int {
        require(format != TransferFormat.ZIP) { "exportZip kullanın" }
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
                    TransferFormat.ZIP -> {}
                }
                onItem(item)
                count++
            }
            if (items.size < PAGE) break
            page++
        }
        if (format == TransferFormat.JSON) out.write(if (count == 0) "]\n" else "\n]\n")
        out.flush()
        return count
    }

    /**
     * [out]'a zip yazar: `data.json` ([export] JSON'u) ve kayıtların dosyaları `files/<id>/<ad>` altında (diskte
     * bulunmayan dosya atlanır). Kayıt sayısını döndürür.
     */
    fun exportZip(name: String, out: OutputStream, params: List<Pair<String, String>> = emptyList(), sort: String? = null, scope: Scope = Scope.ADMIN): Int {
        val c = collection(name)
        val fileFields = c.fields.filter { it.type == FieldType.FILE && (scope.admin || it.visibility != FieldVisibility.ADMIN) }
        val refs = mutableListOf<Pair<Long, String>>()
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(DATA_JSON))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        val count = export(name, TransferFormat.JSON, writer, params, sort, scope) { item ->
            val id = (item["id"] as? JsonPrimitive)?.longOrNull ?: return@export
            fileFields.forEach { f -> fileNames(item[f.name]).forEach { refs += id to it } }
        }
        zip.closeEntry()
        for ((id, file) in refs) {
            val source = files?.file(c.name, id, file)?.takeIf { it.isFile } ?: continue
            zip.putNextEntry(ZipEntry("files/$id/$file"))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return count
    }

    /**
     * İçe aktarma şablonu (CSV): yazılabilir alanların başlıkları, parantez içinde ipucu (zorunlu, seçenekler, biçim; içe
     * aktarmada yok sayılır) ve kurallara uyan bir örnek satır. Dosya ve hesaplanan alanlar yok.
     */
    fun template(name: String, delimiter: Char = ','): String {
        val c = collection(name)
        val fields = c.fields.filter { it.type != FieldType.FILE && it.computed == null }
        val ids = { target: String -> db.query("SELECT \"id\" FROM ${quoteIdent(target)} ORDER BY \"id\" LIMIT 5").map { (it["id"] as Number).toLong() } }
        // Zorunluluk kaldırılır: üretilemeyen alan (boş hedefli ilişki, desen) hata yerine boş kalır.
        val sample = SampleData.record(c.copy(fields = fields.map { it.copy(required = false) }), 0, ids)
        val header = fields.map { f ->
            val hints = listOfNotNull(
                tr("zorunlu").takeIf { f.required && f.default == null },
                f.options?.joinToString("|"),
                tr("birden çok: virgülle").takeIf { f.type == FieldType.MULTISELECT },
                when (f.type) {
                    FieldType.BOOLEAN -> "true/false"
                    FieldType.DATETIME -> "2026-10-14 09:30"
                    FieldType.RELATION -> tr("{0} kimliği", f.collection ?: "")
                    FieldType.JSON -> "JSON"
                    FieldType.GEO -> tr("enlem,boylam")
                    else -> null
                },
            )
            if (hints.isEmpty()) f.name else "${f.name} (${hints.joinToString("; ")})"
        }
        val row = fields.map { f ->
            when (val v = sample[f.name]) {
                is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                else -> cell(v, f.type)
            }
        }
        return Csv.BOM + Csv.row(header, delimiter) + "\r\n" + Csv.row(row, delimiter) + "\r\n"
    }

    /** Dosya alanı değerindeki adlar: tek ad, ad dizisi veya (CSV'de) JSON dizi metni. */
    private fun fileNames(v: JsonElement?): List<String> = when {
        v == null || v is JsonNull -> emptyList()
        v is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        v is JsonPrimitive && v.isString && v.content.trimStart().startsWith("[") ->
            fileNames(runCatching { Json.parseToJsonElement(v.content) }.getOrElse { throw IllegalArgumentException(tr("geçerli JSON dizi değil")) })
        v is JsonPrimitive && v.isString -> listOfNotNull(v.content.takeIf { it.isNotBlank() })
        else -> throw IllegalArgumentException(tr("dosya adı bekleniyor"))
    }

    private fun cell(v: JsonElement?, type: FieldType?): String? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && v.isString -> if (type == FieldType.TEXT) Csv.guard(v.content) else v.content
        v is JsonPrimitive -> v.content
        type == FieldType.GEO -> runCatching { GeoPoint.of(v).text() }.getOrElse { v.toString() }
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
        require(format != TransferFormat.ZIP) { "importZip kullanın" }
        val c = collection(name)
        return importRows(c, read(format, text), mapping, mode, dryRun, attachments = null)
    }

    /**
     * Zip'i ([exportZip] biçimi) aktarır: `data.json` veya `data.csv` ve `files/<eski-id>/<ad>` dosyaları. Kayıttaki
     * dosya adları zip'teki dosyalarla eşleşir ve normal yükleme gibi denetlenir (boyut, içerik tipi); güncellenen
     * kaydın dosyaları zip'tekilerle değişir. Aktarma geri alınırsa (deneme veya hata) taşınan dosyalar silinir. Zip
     * en çok [MAX_ZIP_BYTES] (sıkıştırılmış ve açılmış).
     */
    fun importZip(
        name: String,
        input: InputStream,
        mapping: Map<String, String?>? = null,
        mode: ImportMode = ImportMode.INSERT,
        dryRun: Boolean = false,
    ): ImportResult {
        val c = collection(name)
        val attachments = HashMap<String, Attachment>()
        try {
            var data: Pair<TransferFormat, String>? = null
            var total = 0L
            var entries = 0
            try {
                ZipInputStream(Capped(input, MAX_ZIP_BYTES)).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (++entries > MAX_ZIP_ENTRIES) throw ApiException(400, tr("Zip'te çok fazla öğe var (en çok {0})", MAX_ZIP_ENTRIES))
                        if (e.isDirectory) continue
                        val file = FILE_ENTRY.matchEntire(e.name)
                        val format = DATA_ENTRIES[e.name]
                        val temp = if (file != null) (files ?: throw ApiException(503, tr("Dosya deposu yok; dosya yüklenemez"))).newTemp() else null
                        val text = if (format != null) ByteArrayOutputStream() else null
                        val head = ByteArrayOutputStream()
                        var size = 0L
                        try {
                            (temp?.outputStream() ?: text).use { out ->
                                // Tanınmayan öğeler de sayılarak okunur (sıkıştırma bombası sınırı hepsine).
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    size += n
                                    total += n
                                    if (total > MAX_ZIP_BYTES) throw ApiException(413, tr("Zip açıldığında çok büyük (en çok {0} MB)", MAX_ZIP_BYTES / 1024 / 1024))
                                    if (text != null && size > MAX_IMPORT_BYTES) throw ApiException(413, tr("{0} çok büyük (en çok {1} MB)", e.name, MAX_IMPORT_BYTES / 1024 / 1024))
                                    if (temp != null && head.size() < MimeTypes.HEAD_BYTES) head.write(buffer, 0, minOf(n, MimeTypes.HEAD_BYTES - head.size()))
                                    out?.write(buffer, 0, n)
                                }
                            }
                        } catch (t: Throwable) {
                            temp?.delete()
                            throw t
                        }
                        if (text != null) data = format!! to text.toString(Charsets.UTF_8)
                        if (file != null && temp != null) {
                            attachments.put(file.groupValues[1] + "/" + file.groupValues[2], Attachment(temp, size, head.toByteArray()))?.temp?.delete()
                        }
                    }
                }
            } catch (e: ZipException) {
                throw ApiException(400, tr("Zip okunamadı: {0}", e.message))
            }
            val (format, text) = data ?: throw ApiException(400, tr("Zip'te data.json veya data.csv yok"))
            return importRows(c, read(format, text), mapping, mode, dryRun, attachments)
        } finally {
            attachments.values.forEach { it.temp.delete() }
        }
    }

    private fun importRows(
        c: CollectionConfig,
        parsed: Pair<List<String>, List<List<Pair<String, JsonElement>>>>,
        mapping: Map<String, String?>?,
        mode: ImportMode,
        dryRun: Boolean,
        attachments: Map<String, Attachment>?,
    ): ImportResult {
        val name = c.name
        val svc = service(c)
        val fields = c.fields.associateBy { it.name }
        val (columns, rows) = parsed
        if (rows.size > MAX_ROWS) throw ApiException(400, tr("En çok {0} satır içe aktarılabilir (dosyada {1})", MAX_ROWS, rows.size))
        val targets = fields.keys + "id"
        val n = names(name)
        val effective = columns.associateWith { col ->
            if (mapping != null) mapping[col]?.takeIf { it.isNotEmpty() }?.let(n::canonical)
            else HINT.replace(col.trim(), "").let { h -> targets.firstOrNull { it.equals(n.canonical(h), ignoreCase = true) || it.equals(h, ignoreCase = true) } }
        }
        effective.values.filterNotNull().groupingBy { it }.eachCount().filter { it.value > 1 }.keys.firstOrNull()?.let {
            throw ApiException(400, tr("'{0}' alanına birden çok sütun eşlenmiş", it))
        }
        effective.values.filterNotNull().forEach { f ->
            if (f !in targets) throw ApiException(400, tr("Eşlemede bilinmeyen alan: {0}", f))
            if (fields[f]?.type == FieldType.FILE && attachments == null) throw ApiException(400, tr("'{0}' dosya alanı; dosyalar yalnızca zip ile içe aktarılır", f))
        }
        if (effective.values.none { it != null && it != "id" }) throw ApiException(400, tr("Hiçbir sütun bir alana eşlenmedi"))

        val errors = mutableListOf<RowError>()
        val uploads = HashMap<Int, List<Upload>>()
        val used = HashSet<String>()
        val converted = rows.mapIndexed { i, raw ->
            val body = linkedMapOf<String, JsonElement>()
            val bad = linkedMapOf<String, String>()
            for ((col, value) in raw) {
                val target = effective[col] ?: continue
                if (fields[target]?.computed != null) continue // hesaplanır; dışa aktarılan dosyada olsa da yazılmaz
                if (fields[target]?.type == FieldType.FILE && attachments != null) {
                    // Zip'teki dosya kaydın eski kimliğiyle bulunur; alan zip'tekilerle değişir.
                    try {
                        val f = fields.getValue(target)
                        val names = fileNames(value)
                        val old = raw.firstOrNull { n.canonical(it.first) == "id" }?.second?.let { (it as? JsonPrimitive)?.content?.trim() }
                        if (names.isNotEmpty() && old.isNullOrEmpty()) throw IllegalArgumentException(tr("satırda id yok; dosyaları zip'te bulunamaz"))
                        val list = names.map { file ->
                            val key = "$old/$file"
                            val a = attachments[key] ?: throw IllegalArgumentException(tr("'{0}' zip'te yok (files/{1})", file, key))
                            if (!used.add(key)) throw IllegalArgumentException(tr("'{0}' birden çok satırda kullanılmış", file))
                            files!!.accept(f, a.temp, a.size, a.head, file)
                        }
                        uploads[i] = uploads[i].orEmpty() + list
                        body[target] = JsonNull
                    } catch (e: IllegalArgumentException) {
                        bad[target] = e.message ?: tr("geçersiz değer")
                    } catch (e: ApiException) {
                        bad[target] = e.details[target] ?: e.message ?: tr("geçersiz değer")
                    }
                    continue
                }
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
        val committed = mutableListOf<Pair<Long, String>>()
        val replaced = mutableListOf<Pair<Long, String>>()
        val write = {
            db.transaction {
                for ((i, body) in converted.withIndex()) {
                    if (errors.size >= MAX_ERRORS) break
                    if (errors.any { it.row == i + 1 }) continue
                    val id = (body["id"] as? JsonPrimitive)?.longOrNull
                    try {
                        val data = JsonObject(body - "id")
                        val rowFiles = uploads[i].orEmpty()
                        val removed = mutableListOf<String>()
                        val written = if (mode == ImportMode.UPSERT && id != null && exists(c, id)) {
                            svc.update(id, data, uploads = rowFiles, removedFiles = removed).also { updated++ }
                        } else {
                            svc.create(data, uploads = rowFiles).also { created++ }
                        }
                        (written["id"] as? JsonPrimitive)?.longOrNull?.let { w ->
                            rowFiles.forEach { committed += w to it.storedName }
                            removed.forEach { replaced += w to it }
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
        // Geri alınan işlemin kayıt klasörüne taşıdığı dosyalar silinir (kimlik sonra başka kayda verilebilir); güncellenen
        // kayıtların eski dosyaları yalnızca işlem kalıcı olunca.
        fun discard() = committed.forEach { (id, file) -> files?.delete(c.name, id, file) }
        val applied = try {
            writes.write(write)
            true
        } catch (_: Rollback) {
            discard()
            false
        } catch (t: Throwable) {
            discard()
            throw t
        }
        if (applied) replaced.forEach { (id, file) -> files?.delete(c.name, id, file) }
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

    /** Zip'ten açılmış dosya. */
    private class Attachment(val temp: File, val size: Long, val head: ByteArray)

    /** Okunan bayt sayısını sınırlar ([max] aşılınca 413). */
    private class Capped(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var read = 0L

        private fun count(n: Int): Int {
            if (n > 0) {
                read += n
                if (read > max) throw ApiException(413, tr("Zip çok büyük (en çok {0} MB)", max / 1024 / 1024))
            }
            return n
        }

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }

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
        TransferFormat.ZIP -> error("zip")
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
            // "41.01,28.97" veya JSON nesne.
            FieldType.GEO -> if (s.trimStart().startsWith("{")) {
                runCatching { Json.parseToJsonElement(s) }.getOrElse { throw IllegalArgumentException(tr("geçerli JSON değil")) }
            } else {
                GeoPoint.parse(s)?.json() ?: throw IllegalArgumentException(tr("konum bekleniyor: enlem,boylam (örn. 41.01,28.97)"))
            }
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

        /** Zip içe aktarma sınırı: sıkıştırılmış gövde ve açılmış toplam boyut. */
        const val MAX_ZIP_BYTES = 512L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 50_000
        private const val DATA_JSON = "data.json"
        private val DATA_ENTRIES = mapOf(DATA_JSON to TransferFormat.JSON, "data.csv" to TransferFormat.CSV)
        private val FILE_ENTRY = Regex("^files/(\\d{1,19})/([^/\\\\]{1,200})$")
        private const val PAGE = 500
        private const val SAMPLE = 5
        private val DECIMAL_COMMA = Regex("^-?\\d+,\\d+$")

        /** Şablon başlığındaki ipucu: `durum (zorunlu; açık|bitti)` → `durum`. */
        private val HINT = Regex("\\s*\\([^()]*\\)$")

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
