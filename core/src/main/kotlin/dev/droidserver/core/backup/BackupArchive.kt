package dev.droidserver.core.backup

import dev.droidserver.core.config.GeoPoint
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.db.quoteIdent
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.security.FieldEncryption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Yedek: tek zip.
 *
 *     manifest.json            biçim, zaman, konfigürasyon sürümü, tablo başına kayıt sayısı
 *     server.json              konfigürasyon (sırlar `secret:` referansı olarak; değerleri yedekte yok)
 *     db/<tablo>.jsonl         her satır bir kayıt (ortak biçimde: sayı, metin, true/false, ISO zaman, JSON)
 *     files/storage/…          yüklenen dosyalar
 *     files/scripts/…          script'ler
 *     files/www/…              statik site
 *
 * Veritabanı motora özgü bir döküm (VACUUM INTO, pg_dump) yerine kayıt kayıt yazılır: aynı yedek SQLite'a da
 * PostgreSQL'e de geri yüklenir, Android'in eski SQLite sürümlerinde de çalışır ve PostgreSQL bileşenine ek araç
 * gerekmez. Okuma tek işlem içinde yapılır (PostgreSQL'de REPEATABLE READ): yedek tutarlı bir anın görüntüsüdür.
 */
object BackupArchive {
    const val FORMAT = 1

    /** Yedeğe girecek klasörler: zip'teki ad → yerel klasör. */
    val DIRS = listOf("storage", "scripts", "www")

    /** [fieldKeyId]: şifreli alanların anahtar kimliği (anahtarın kendisi yedeğe girmez); şifreleme yoksa null. */
    data class Manifest(val format: Int, val createdAt: Instant, val configVersion: Int, val tables: Map<String, Long>, val fieldKeyId: String? = null)

    /**
     * Yedeği [out]'a yazar. [configText] diskteki konfigürasyon metnidir (çözülmüş sırlar içermemeli). [dirs]:
     * `storage`, `scripts`, `www` → yerel klasör (olmayan atlanır).
     */
    fun create(out: OutputStream, db: SqlDriver, config: ServerConfig, configText: String, dirs: Map<String, File?>, now: Instant = Instant.now()): Manifest {
        val counts = linkedMapOf<String, Long>()
        ZipOutputStream(out).use { zip ->
            zip.entry("server.json") { it.write(configText.toByteArray()) }
            db.transaction {
                db.dialect.snapshotSql()?.let { db.execute(it) }
                val tables = db.dialect.listTables(db).toSet()
                for (c in config.schemaCollections().filter { it.name in tables }) {
                    var n = 0L
                    zip.entry("db/${c.name}.jsonl") { o ->
                        n = exportTable(db, c) { line -> o.write(line.toByteArray()); o.write('\n'.code) }
                    }
                    counts[c.name] = n
                }
            }
            for ((name, dir) in dirs) {
                if (dir == null || !dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.split(File.separatorChar).any { p -> p.startsWith(".") } }
                    .forEach { f -> zip.entry("files/$name/" + f.relativeTo(dir).invariantSeparatorsPath) { o -> f.inputStream().use { it.copyTo(o) } } }
            }
            val keyId = FieldEncryption.cipher(config)?.keyId?.takeIf { config.schemaCollections().any { c -> c.fields.any { it.encrypted } } }
            val manifest = Manifest(FORMAT, now, config.version, counts, keyId)
            zip.entry("manifest.json") { it.write(manifestJson(manifest).toString().toByteArray()) }
            return manifest
        }
    }

    private inline fun ZipOutputStream.entry(name: String, write: (OutputStream) -> Unit) {
        putNextEntry(ZipEntry(name))
        write(this)
        closeEntry()
    }

    private fun exportTable(db: SqlDriver, c: CollectionConfig, line: (String) -> Unit): Long {
        val columns = db.dialect.listColumns(db, c.name).keys
        val fields = c.fields.filter { it.name in columns }
        val select = "SELECT ${(listOf("id", "created_at", "updated_at") + fields.map { it.name }).joinToString { quoteIdent(it) }} " +
            "FROM ${quoteIdent(c.name)} WHERE \"id\" > ? ORDER BY \"id\" LIMIT $PAGE"
        var last = Long.MIN_VALUE
        var n = 0L
        while (true) {
            val rows = db.query(select, listOf(last))
            if (rows.isEmpty()) break
            for (row in rows) {
                line(buildJsonObject {
                    put("id", JsonPrimitive((row["id"] as Number).toLong()))
                    put("created_at", toJson(FieldType.DATETIME, db.dialect.decode(FieldType.DATETIME, row["created_at"])))
                    put("updated_at", toJson(FieldType.DATETIME, db.dialect.decode(FieldType.DATETIME, row["updated_at"])))
                    for (f in fields) put(f.name, toJson(f.type, runCatching { db.dialect.decode(f.type, row[f.name]) }.getOrNull()))
                }.toString())
                n++
            }
            last = (rows.last()["id"] as Number).toLong()
        }
        return n
    }

    /**
     * Zip'i baştan sona okuyup doğrular (hiçbir şeye yazmaz): biçim, konfigürasyon, tablo dosyaları ve kayıt sayıları,
     * dosya adlarının klasör dışına çıkmadığı. Geçerliyse manifesti ve konfigürasyonu döndürür.
     */
    fun inspect(file: File): Pair<Manifest, ServerConfig> = ZipFile(file).use { zip ->
        val manifest = zip.getEntry("manifest.json")?.let { e -> parseManifest(zip.getInputStream(e).readBytes().decodeToString()) }
            ?: throw IOException(tr("Bu dosya bir DroidServer yedeği değil (manifest.json yok)"))
        if (manifest.format != FORMAT) throw IOException(tr("Yedek biçimi desteklenmiyor: {0}", manifest.format))
        val configText = zip.getEntry("server.json")?.let { zip.getInputStream(it).readBytes().decodeToString() }
            ?: throw IOException(tr("Yedekte server.json yok"))
        val config = runCatching { ServerConfig.fromJson(configText) }.getOrElse { throw IOException(tr("Yedekteki konfigürasyon okunamadı: {0}", it.message)) }
        for ((table, expected) in manifest.tables) {
            val entry = zip.getEntry("db/$table.jsonl") ?: throw IOException(tr("Yedekte {0} tablosu eksik", table))
            var n = 0L
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { l ->
                    runCatching { Json.parseToJsonElement(l).jsonObject }.getOrElse { throw IOException(tr("{0}: bozuk kayıt (satır {1})", table, n + 1)) }
                    n++
                }
            }
            if (n != expected) throw IOException(tr("{0}: {1} kayıt bekleniyordu, {2} bulundu (yedek eksik)", table, expected, n))
        }
        for (e in zip.entries()) {
            val parts = e.name.split('/')
            if (e.name.startsWith("/") || parts.any { it == ".." } || '\\' in e.name) throw IOException(tr("Yedekte güvenli olmayan ad: {0}", e.name))
        }
        manifest to config
    }

    /**
     * Veritabanını ve klasörleri yedekteki hale getirir. Veritabanı tek işlemde: yedekteki koleksiyonların tabloları
     * boşaltılır, şema yedeğin konfigürasyonuna göre kurulur, kayıtlar kimlikleriyle yazılır, sayaçlar ayarlanır;
     * hata olursa hiçbir şey değişmez. Klasörler veritabanı başarılı olunca, ayrı klasöre açılıp yer değiştirerek.
     * [config] yedekteki konfigürasyondur ([inspect]).
     */
    fun restore(file: File, db: SqlDriver, config: ServerConfig, dirs: Map<String, File?>, log: (String) -> Unit = {}) {
        val (manifest, _) = inspect(file)
        ZipFile(file).use { zip ->
            val collections = config.schemaCollections().filter { it.name in manifest.tables }
            db.transaction {
                val existing = db.dialect.listTables(db).toSet()
                for (c in collections) if (c.name in existing) db.execute("DELETE FROM ${quoteIdent(c.name)}")
                SchemaMigrator(db).migrate(config.schemaCollections())
                for (c in collections) {
                    val cols = listOf("id", "created_at", "updated_at") + c.fields.map { it.name }
                    val types = mapOf("id" to FieldType.INTEGER, "created_at" to FieldType.DATETIME, "updated_at" to FieldType.DATETIME) +
                        c.fields.associate { it.name to it.type }
                    val insert = db.dialect.insertWithIdSql(c.name, cols)
                    var n = 0L
                    zip.getInputStream(zip.getEntry("db/${c.name}.jsonl")).bufferedReader().useLines { lines ->
                        lines.filter { it.isNotBlank() }.forEach { l ->
                            val row = Json.parseToJsonElement(l).jsonObject
                            db.execute(insert, cols.map { col -> db.dialect.encode(types.getValue(col), fromJson(types.getValue(col), row[col])) })
                            n++
                        }
                    }
                    db.dialect.resetIdSequenceSql(c.name)?.let { db.query(it) }
                    log("Geri yüklendi: ${c.name} ($n kayıt)")
                }
            }
            for ((name, dir) in dirs) {
                if (dir == null) continue
                val staging = File(dir.absoluteFile.parentFile, "${dir.name}.geri-yukleme").apply { deleteRecursively(); mkdirs() }
                val prefix = "files/$name/"
                for (e in zip.entries().toList().filter { it.name.startsWith(prefix) && !it.isDirectory }) {
                    val out = File(staging, e.name.removePrefix(prefix))
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw IOException(tr("Güvenli olmayan ad: {0}", e.name))
                    out.parentFile.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                val old = File(dir.absoluteFile.parentFile, "${dir.name}.eski")
                old.deleteRecursively()
                if (dir.exists() && !dir.renameTo(old)) throw IOException(tr("{0} klasörü değiştirilemedi", name))
                if (!staging.renameTo(dir)) {
                    old.renameTo(dir)
                    throw IOException(tr("{0} klasörü değiştirilemedi", name))
                }
                old.deleteRecursively()
            }
        }
    }

    /** Ortak biçimdeki değeri JSON'a (bkz. SqlDialect): JSON alanı gömülü JSON, zaman ISO metin. */
    private fun toJson(type: FieldType, v: Any?): JsonElement = when {
        v == null -> JsonNull
        type == FieldType.JSON -> runCatching { Json.parseToJsonElement(v.toString()) }.getOrElse { JsonPrimitive(v.toString()) }
        v is Boolean -> JsonPrimitive(v)
        v is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun fromJson(type: FieldType, v: JsonElement?): Any? {
        if (v == null || v is JsonNull) return null
        val p = v as? JsonPrimitive
        return when (type) {
            FieldType.JSON -> v.toString()
            FieldType.INTEGER, FieldType.RELATION -> p?.longOrNull
            FieldType.REAL -> p?.doubleOrNull
            FieldType.BOOLEAN -> p?.booleanOrNull
            FieldType.DATETIME -> p?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }
            FieldType.TEXT, FieldType.FILE, FieldType.SELECT, FieldType.MULTISELECT, FieldType.GEO -> p?.content
        }
    }

    private fun manifestJson(m: Manifest) = buildJsonObject {
        put("format", JsonPrimitive(m.format))
        put("createdAt", JsonPrimitive(m.createdAt.toString()))
        put("configVersion", JsonPrimitive(m.configVersion))
        put("tables", JsonObject(m.tables.mapValues { JsonPrimitive(it.value) }))
        m.fieldKeyId?.let { put("fieldKeyId", JsonPrimitive(it)) }
    }

    private fun parseManifest(text: String): Manifest {
        val o = Json.parseToJsonElement(text).jsonObject
        return Manifest(
            format = o.getValue("format").jsonPrimitive.int,
            createdAt = Instant.parse(o.getValue("createdAt").jsonPrimitive.content),
            configVersion = o.getValue("configVersion").jsonPrimitive.int,
            tables = o.getValue("tables").jsonObject.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L },
            fieldKeyId = o["fieldKeyId"]?.jsonPrimitive?.content,
        )
    }

    private const val PAGE = 500
}
