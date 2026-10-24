package com.plyg.droidserver.core.db

import com.plyg.droidserver.core.api.SearchTerms
import com.plyg.droidserver.core.api.SearchText
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.TypeChangeAction
import com.plyg.droidserver.core.config.TypeConversions
import com.plyg.droidserver.core.i18n.tr
import java.security.MessageDigest

/** Bir tablodaki bir sütun; "tablo.sütun" olarak yazılır. */
data class ColumnRef(val table: String, val column: String) {
    override fun toString() = "$table.$column"
}

data class NewColumn(val ref: ColumnRef, val sqlType: String) {
    override fun toString() = "$ref $sqlType"
}

/**
 * Mevcut bir alanın tip değişikliği. Değerler kullanıcının seçimine ([FieldConfig.typeChange]) göre çevrilir veya
 * boşaltılır; sütun hiç değer içermiyorsa seçim gerekmez ([action] null).
 */
data class ColumnTypeChange(
    val ref: ColumnRef,
    /** Değerlerin okunacağı eski tip; [action] null ise bilinmeyebilir. */
    val from: FieldType?,
    val to: FieldType,
    val action: TypeChangeAction?,
    /** Dolu (boş olmayan) değer sayısı. */
    val values: Long = 0,
    /** Boşalacak değer sayısı: boşaltmada hepsi, dönüştürmede çevrilemeyenler. */
    val cleared: Long = 0,
    /** Boşalacak değerlerden örnekler. */
    val samples: List<String> = emptyList(),
) {
    override fun toString() = "$ref ${from?.label ?: "?"} → ${to.label}"
}

/**
 * [SchemaMigrator.plan] sonucu: veritabanına hiçbir şey yazmadan yapılacakların listesi.
 * [blockers] boş değilse plan uygulanamaz ve deploy reddedilir.
 */
data class MigrationPlan(
    val createTables: List<String> = emptyList(),
    /** Eklenecek sütunlar (yeni tabloların alanları dahil). */
    val addColumns: List<NewColumn> = emptyList(),
    val addUniqueIndexes: List<ColumnRef> = emptyList(),
    /**
     * Benzersiz olmayan arama indeksleri: ilişki alanları (silmede bağlı kayıtları ve expand'i hızlandırır) ve
     * `index` işaretli alanlar.
     */
    val addRelationIndexes: List<ColumnRef> = emptyList(),
    /** Artık istenmeyen (benzersiz olmayan) arama indeksleri. */
    val dropLookupIndexes: List<ColumnRef> = emptyList(),
    /** Arama sütunu (`_search`) yeniden hesaplanacak koleksiyonlar → arama alanları. */
    val rebuildSearch: Map<String, List<String>> = emptyMap(),
    /** `_ds_meta`'ya yazılacak arama alanı listeleri (null: kaydı sil). */
    val searchMeta: Map<String, String?> = emptyMap(),
    /** Tam metin dizini (`_search_terms`) yeniden kurulacak / silinecek koleksiyonlar. */
    val rebuildTerms: List<String> = emptyList(),
    val dropTerms: List<String> = emptyList(),
    /** `_ds_meta`'ya yazılacak tam metin dizini durumu (dizinlenen arama alanları; null: kapalı). */
    val fullTextMeta: Map<String, String?> = emptyMap(),
    val dropUniqueIndexes: List<ColumnRef> = emptyList(),
    /** Kaldırılacak indekslerin veritabanındaki adları (eski adlandırmayla oluşturulmuş olabilirler). */
    val dropIndexNames: List<String> = emptyList(),
    /** Tipi değişecek alanlar (sütun yeni tiple yeniden kurulur). */
    val changeTypes: List<ColumnTypeChange> = emptyList(),
    /** Konfigürasyondan çıkarılmış ama veritabanında duran sütun ve tablolar (silinmez). */
    val orphanColumns: List<ColumnRef> = emptyList(),
    val orphanTables: List<String> = emptyList(),
    /** Uygulanamaz değişiklikler, örn. tip değişikliği veya tekrar eden veriye `unique`. */
    val blockers: List<String> = emptyList(),
    /** Deploy'u engellemeyen ama kullanıcının bilmesi gereken durumlar. */
    val warnings: List<String> = emptyList(),
    /** `_ds_meta`'ya kaydedilecek alan tipleri; sonraki deploy'larda tip değişikliğini yakalamak için. */
    val recordFieldTypes: Map<ColumnRef, FieldType> = emptyMap(),
) {
    val hasBlockers: Boolean get() = blockers.isNotEmpty()

    /** Örn. "2 tablo oluşturulacak, 1 sütun eklenecek, 1 sütun artık kullanılmıyor". */
    fun summary(): String {
        // Yeni tabloların sütun ve indeksleri "tablo oluşturulacak" içinde sayılır.
        // Tipi değişen alanın indeksi yeniden kurulur; "eklenecek" sayılmaz.
        val retyped = changeTypes.map { it.ref }.toSet()
        val newColumnsOnExisting = addColumns.count { it.ref.table !in createTables }
        val newIndexesOnExisting = addUniqueIndexes.count { it.table !in createTables && it !in retyped }
        val newLookupOnExisting = addRelationIndexes.count { it.table !in createTables && it !in retyped }
        val parts = buildList {
            if (createTables.isNotEmpty()) add(tr("{0} tablo oluşturulacak", createTables.size))
            if (newColumnsOnExisting > 0) add(tr("{0} sütun eklenecek", newColumnsOnExisting))
            if (changeTypes.isNotEmpty()) add(tr("{0} alanın tipi değişecek", changeTypes.size))
            if (newIndexesOnExisting > 0) add(tr("{0} benzersiz indeks eklenecek", newIndexesOnExisting))
            if (dropUniqueIndexes.isNotEmpty()) add(tr("{0} benzersiz indeks kaldırılacak", dropUniqueIndexes.size))
            if (newLookupOnExisting > 0) add(tr("{0} indeks eklenecek", newLookupOnExisting))
            if (dropLookupIndexes.isNotEmpty()) add(tr("{0} indeks kaldırılacak", dropLookupIndexes.size))
            if (rebuildSearch.isNotEmpty()) add(tr("arama dizini yeniden hesaplanacak ({0})", rebuildSearch.keys.joinToString()))
            if (rebuildTerms.isNotEmpty()) add(tr("tam metin dizini kurulacak ({0})", rebuildTerms.joinToString()))
            if (orphanColumns.isNotEmpty()) add(tr("{0} sütun artık kullanılmıyor", orphanColumns.size))
            if (orphanTables.isNotEmpty()) add(tr("{0} tablo artık kullanılmıyor", orphanTables.size))
            if (blockers.isNotEmpty()) add(tr("{0} engelleyici sorun", blockers.size))
        }
        return parts.joinToString(", ").ifEmpty { tr("Şema güncel") }
    }
}

/** Plan engelleyici sorun içerdiği için uygulanmadı. */
class SchemaBlockedException(val blockers: List<String>) :
    RuntimeException(tr("Şema değişikliği engellendi: {0}", blockers.joinToString("; ")))

/**
 * Konfigürasyondaki koleksiyonları veritabanına uygular. Motor farkları [SqlDialect]'tedir.
 *
 * Kurallar (veri kaybını önlemek için bilinçli olarak tutucu):
 *  - Eksik tablo -> oluşturulur.
 *  - Eksik alan  -> ALTER TABLE ADD COLUMN.
 *  - Konfigürasyondan çıkarılmış alan/tablo -> SİLİNMEZ, rapora "orphan" olarak yazılır.
 *  - Mevcut alanın tipi değiştiyse: sütun boşsa yeni tiple kurulur; doluysa kullanıcının seçimine
 *    ([FieldConfig.typeChange]: dönüştür / boşalt) göre yapılır, seçim yoksa engelleyici sorundur.
 *    Alan tipleri `_ds_meta`'da saklanır; böylece aynı veritabanı tipine eşlenen değişiklikler de
 *    (örn. SQLite'ta text -> json) yakalanır.
 *  - Tekrar eden veriye `unique` ekleniyorsa -> engelleyici sorun, hiçbir şey uygulanmaz.
 *  - `required` kuralı veritabanında değil API katmanında uygulanır
 *    (SQLite mevcut tabloya varsayılansız NOT NULL sütun ekleyemez). Sonradan eklenirse ve
 *    mevcut satırlarda boş değer varsa yalnızca uyarı verilir.
 */
class SchemaMigrator(private val db: SqlDriver) {
    private val dialect = db.dialect

    data class Report(
        val createdTables: List<String>,
        val addedColumns: List<String>,
        val orphanColumns: List<String>,
        val orphanTables: List<String>,
        val addedIndexes: List<String> = emptyList(),
        val droppedIndexes: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val changedTypes: List<String> = emptyList(),
        val rebuiltSearch: List<String> = emptyList(),
    ) {
        override fun toString() = buildString {
            append(tr("tablo+: {0}, sütun+: {1}", createdTables, addedColumns))
            if (changedTypes.isNotEmpty()) append(tr(", tip: {0}", changedTypes))
            if (addedIndexes.isNotEmpty()) append(tr(", benzersiz+: {0}", addedIndexes))
            if (droppedIndexes.isNotEmpty()) append(tr(", benzersiz-: {0}", droppedIndexes))
            if (rebuiltSearch.isNotEmpty()) append(tr(", arama dizini: {0}", rebuiltSearch))
            if (orphanColumns.isNotEmpty()) append(tr(", kullanılmayan sütunlar: {0}", orphanColumns))
            if (orphanTables.isNotEmpty()) append(tr(", kullanılmayan tablolar: {0}", orphanTables))
        }
    }

    /** Planla ve uygula; ikisi aynı işlem içinde. Engelleyici sorun varsa [SchemaBlockedException]. */
    fun migrate(collections: List<CollectionConfig>): Report = db.transaction { apply(plan(collections)) }

    /** Veritabanına hiçbir şey yazmadan yapılacakları hesaplar (dry-run). */
    fun plan(collections: List<CollectionConfig>): MigrationPlan {
        val allTables = dialect.listTables(db)
        val existingTables = allTables.filterNot { it.startsWith(SYSTEM_PREFIX) }.toSet()
        val knownTypes = if (META_TABLE in allTables) readFieldTypes() else emptyMap()
        val knownSearch = if (META_TABLE in allTables) readMeta(SEARCH_KEY_PREFIX) else emptyMap()
        val knownFullText = if (META_TABLE in allTables) readMeta(FULLTEXT_KEY_PREFIX) else emptyMap()
        val rebuildTerms = mutableListOf<String>()
        val dropTerms = mutableListOf<String>()
        val fullTextMeta = linkedMapOf<String, String?>()

        val createTables = mutableListOf<String>()
        val addColumns = mutableListOf<NewColumn>()
        val addIndexes = mutableListOf<ColumnRef>()
        val addRelationIndexes = mutableListOf<ColumnRef>()
        val dropLookupIndexes = mutableListOf<ColumnRef>()
        val rebuildSearch = linkedMapOf<String, List<String>>()
        val searchMeta = linkedMapOf<String, String?>()
        val dropIndexes = mutableListOf<ColumnRef>()
        val dropIndexNames = mutableListOf<String>()
        val changeTypes = mutableListOf<ColumnTypeChange>()
        val orphanColumns = mutableListOf<ColumnRef>()
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val recordTypes = linkedMapOf<ColumnRef, FieldType>()

        for (c in collections) {
            val isNew = c.name !in existingTables
            if (isNew) createTables += c.name
            val columns = if (isNew) emptyMap() else dialect.listColumns(db, c.name)
            val indexes = if (isNew) emptySet() else dialect.listIndexes(db, c.name)
            val rowCount by lazy { if (isNew) 0L else count("SELECT COUNT(*) AS n FROM ${quoteIdent(c.name)}") }

            for (f in c.fields) {
                val ref = ColumnRef(c.name, f.name)
                val dbType = columns[f.name]
                val known = knownTypes[ref]
                if (known != f.type) recordTypes[ref] = f.type

                if (dbType == null) {
                    addColumns += NewColumn(ref, dialect.columnType(f.type))
                    if (f.required && rowCount > 0) {
                        warnings += tr("{0} zorunlu ama mevcut {1} kayıtta boş olacak (API yeni yazmalarda uygular)", ref, rowCount)
                    }
                } else {
                    if ((known != null && known != f.type) || !dialect.matchesType(dbType, f.type)) {
                        val indexNames = listOf(indexName(ref), legacyIndexName(ref), relationIndexName(ref)).filter { it in indexes }
                        val change = planTypeChange(ref, f, known, dbType, blockers, warnings) ?: continue
                        changeTypes += change.first
                        // Sütun yeniden kurulduğu için indeksi kaldırılıp (benzersizse) yeniden oluşturulur.
                        dropIndexNames += indexNames
                        if (f.unique) {
                            if (change.second.isEmpty()) addIndexes += ref
                            else blockers += tr("{0} benzersiz yapılamaz: dönüştürülen değerler tekrar ediyor ({1})", ref, change.second.joinToString())
                        } else if (f.type == FieldType.RELATION || f.index) {
                            addRelationIndexes += ref
                        }
                        continue
                    }
                    if (f.required) {
                        val nulls = count("SELECT COUNT(*) AS n FROM ${quoteIdent(c.name)} WHERE ${quoteIdent(f.name)} IS NULL")
                        if (nulls > 0) warnings += tr("{0} zorunlu ama {1} kayıtta boş (API yeni yazmalarda uygular)", ref, nulls)
                    }
                }

                // Eski adlandırmayla (ux_<tablo>_<alan>) oluşturulmuş indeks de sayılır; indeks listesi tabloya özel
                // olduğu için bu ad başka bir tablonun indeksiyle karışmaz.
                val existing = listOf(indexName(ref), legacyIndexName(ref)).filter { it in indexes }
                val hasIndex = existing.isNotEmpty()
                if (f.unique && !hasIndex) {
                    val dups = if (dbType == null) emptyList() else duplicates(ref)
                    if (dups.isEmpty()) addIndexes += ref
                    else blockers += tr("{0} benzersiz yapılamaz: tekrar eden değerler var ({1})", ref, dups.joinToString())
                } else if (!f.unique && hasIndex) {
                    dropIndexes += ref
                    dropIndexNames += existing
                }
                // Benzersiz indeks aramayı zaten hızlandırır; ilişki alanı değilse ve indeks istenmiyorsa kaldırılır.
                val wantsLookupIndex = (f.type == FieldType.RELATION || f.index) && !f.unique
                val hasLookupIndex = relationIndexName(ref) in indexes
                if (wantsLookupIndex && !hasLookupIndex) addRelationIndexes += ref
                if (!wantsLookupIndex && hasLookupIndex) {
                    dropIndexNames += relationIndexName(ref)
                    if (!f.unique) dropLookupIndexes += ref
                }
            }

            // Arama: gizli sütun; alan listesi değiştiyse, arama alanının tipi değiştiyse veya hesaplanmamış satır
            // varsa (veri taşıma, geri yükleme) yeniden hesaplanır.
            val wantedSearch = c.search.joinToString(",").takeIf { it.isNotEmpty() }
            if (wantedSearch != null) {
                val hasColumn = SEARCH_COLUMN in columns
                if (!hasColumn) addColumns += NewColumn(ColumnRef(c.name, SEARCH_COLUMN), dialect.columnType(FieldType.TEXT))
                val retyped = changeTypes.any { it.ref.table == c.name && it.ref.column in c.search }
                val stale = hasColumn && rowCount > 0 &&
                    count("SELECT COUNT(*) AS n FROM ${quoteIdent(c.name)} WHERE ${quoteIdent(SEARCH_COLUMN)} IS NULL") > 0
                if (!isNew && rowCount > 0 && (knownSearch[c.name] != wantedSearch || retyped || stale)) rebuildSearch[c.name] = c.search
            }
            if (knownSearch[c.name] != wantedSearch) searchMeta[c.name] = wantedSearch
            // Tam metin dizini: açıldıysa, arama alanları değiştiyse veya arama metni yeniden hesaplanıyorsa kurulur.
            val wantedFullText = wantedSearch?.takeIf { c.fullText }
            if (wantedFullText != null && !isNew && rowCount > 0 && (knownFullText[c.name] != wantedFullText || c.name in rebuildSearch)) rebuildTerms += c.name
            if (wantedFullText == null && knownFullText[c.name] != null && SystemCollections.SEARCH_TERMS in allTables) dropTerms += c.name
            if (knownFullText[c.name] != wantedFullText) fullTextMeta[c.name] = wantedFullText

            val wanted = c.fields.map { it.name }.toSet() + SYSTEM_COLUMNS
            orphanColumns += (columns.keys - wanted).map { ColumnRef(c.name, it) }
        }

        return MigrationPlan(
            createTables = createTables,
            addColumns = addColumns,
            addUniqueIndexes = addIndexes,
            addRelationIndexes = addRelationIndexes,
            dropUniqueIndexes = dropIndexes,
            dropIndexNames = dropIndexNames,
            dropLookupIndexes = dropLookupIndexes,
            rebuildSearch = rebuildSearch,
            searchMeta = searchMeta,
            rebuildTerms = rebuildTerms,
            dropTerms = dropTerms,
            fullTextMeta = fullTextMeta,
            changeTypes = changeTypes,
            orphanColumns = orphanColumns,
            orphanTables = (existingTables - collections.map { it.name }.toSet()).sorted(),
            blockers = blockers,
            warnings = warnings,
            recordFieldTypes = recordTypes,
        )
    }

    /** Planı uygular. Plan veritabanı değişmeden hemen önce hesaplanmış olmalı (bkz. [migrate]). */
    fun apply(plan: MigrationPlan): Report = db.transaction {
        if (plan.hasBlockers) throw SchemaBlockedException(plan.blockers)

        db.execute("""CREATE TABLE IF NOT EXISTS ${quoteIdent(META_TABLE)} ("key" TEXT PRIMARY KEY, "value" TEXT NOT NULL)""")
        for (t in plan.createTables) db.execute(dialect.createTableSql(t))
        for (col in plan.addColumns) {
            db.execute("ALTER TABLE ${quoteIdent(col.ref.table)} ADD COLUMN ${quoteIdent(col.ref.column)} ${col.sqlType}")
        }
        for (name in plan.dropIndexNames) db.execute("DROP INDEX IF EXISTS ${quoteIdent(name)}")
        for (change in plan.changeTypes) changeType(change)
        for (ref in plan.addUniqueIndexes) {
            db.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ${quoteIdent(indexName(ref))} " +
                    "ON ${quoteIdent(ref.table)} (${quoteIdent(ref.column)})",
            )
        }
        for (ref in plan.addRelationIndexes) {
            db.execute(
                "CREATE INDEX IF NOT EXISTS ${quoteIdent(relationIndexName(ref))} " +
                    "ON ${quoteIdent(ref.table)} (${quoteIdent(ref.column)})",
            )
        }
        for ((table, fields) in plan.rebuildSearch) rebuildSearch(table, fields)
        for (table in plan.dropTerms) SearchTerms.deleteAll(db, table)
        for (table in plan.rebuildTerms) rebuildTerms(table)
        for ((table, value) in plan.fullTextMeta) {
            val key = FULLTEXT_KEY_PREFIX + table
            db.execute("""DELETE FROM ${quoteIdent(META_TABLE)} WHERE "key" = ?""", listOf(key))
            if (value != null) db.execute("""INSERT INTO ${quoteIdent(META_TABLE)} ("key", "value") VALUES (?, ?)""", listOf(key, value))
        }
        for ((table, value) in plan.searchMeta) {
            val key = SEARCH_KEY_PREFIX + table
            db.execute("""DELETE FROM ${quoteIdent(META_TABLE)} WHERE "key" = ?""", listOf(key))
            if (value != null) db.execute("""INSERT INTO ${quoteIdent(META_TABLE)} ("key", "value") VALUES (?, ?)""", listOf(key, value))
        }
        // UPSERT Android 8'in SQLite'ında (3.18) yok; sil + ekle aynı işlem içinde.
        for ((ref, type) in plan.recordFieldTypes) {
            val key = FIELD_KEY_PREFIX + ref
            db.execute("""DELETE FROM ${quoteIdent(META_TABLE)} WHERE "key" = ?""", listOf(key))
            db.execute("""INSERT INTO ${quoteIdent(META_TABLE)} ("key", "value") VALUES (?, ?)""", listOf(key, type.label))
        }

        Report(
            createdTables = plan.createTables,
            addedColumns = plan.addColumns.map { it.ref.toString() },
            orphanColumns = plan.orphanColumns.map { it.toString() },
            orphanTables = plan.orphanTables,
            addedIndexes = plan.addUniqueIndexes.map { it.toString() },
            droppedIndexes = plan.dropUniqueIndexes.map { it.toString() },
            warnings = plan.warnings,
            changedTypes = plan.changeTypes.map { it.toString() },
            rebuiltSearch = plan.rebuildSearch.keys.toList(),
        )
    }

    /** Tam metin dizinini `_search`'ten yeniden kurar (sayfa sayfa). */
    private fun rebuildTerms(table: String) {
        SearchTerms.deleteAll(db, table)
        val now = java.time.Instant.now()
        var lastId = 0L
        while (true) {
            val rows = db.query(
                "SELECT \"id\", ${quoteIdent(SEARCH_COLUMN)} AS s FROM ${quoteIdent(table)} WHERE \"id\" > ? ORDER BY \"id\" LIMIT $PAGE_SIZE",
                listOf(lastId),
            )
            if (rows.isEmpty()) break
            for (row in rows) SearchTerms.index(db, table, (row["id"] as Number).toLong(), row["s"] as String?, now)
            lastId = (rows.last()["id"] as Number).toLong()
        }
    }

    /** Tablonun tüm satırlarında `_search`'ü arama alanlarından yeniden hesaplar (sayfa sayfa). */
    private fun rebuildSearch(table: String, fields: List<String>) {
        val t = quoteIdent(table)
        val cols = fields.joinToString { quoteIdent(it) }
        var lastId = 0L
        while (true) {
            val rows = db.query("SELECT \"id\", $cols FROM $t WHERE \"id\" > ? ORDER BY \"id\" LIMIT $PAGE_SIZE", listOf(lastId))
            if (rows.isEmpty()) break
            for (row in rows) {
                val text = fields.mapNotNull { (row[it] as? String)?.takeIf(String::isNotBlank) }.joinToString(" ") { SearchText.normalize(it) }
                db.execute("UPDATE $t SET ${quoteIdent(SEARCH_COLUMN)} = ? WHERE \"id\" = ?", listOf(text, row["id"]))
            }
            lastId = (rows.last()["id"] as Number).toLong()
        }
    }

    /**
     * Tip değişikliğini planlar. Uygulanamıyorsa [blockers]'a yazar ve null döner; aksi halde değişiklik ve
     * (benzersizlik kontrolü için) dönüştürülmüş değerlerde tekrar edenler.
     */
    private fun planTypeChange(
        ref: ColumnRef,
        f: FieldConfig,
        known: FieldType?,
        dbType: String,
        blockers: MutableList<String>,
        warnings: MutableList<String>,
    ): Pair<ColumnTypeChange, List<String>>? {
        // Eski tip: _ds_meta'daki kayıt; yoksa (eski sürümlerden kalan veritabanı) sütun tipinden çıkarılır.
        val old = known ?: FieldType.entries.firstOrNull { dialect.matchesType(dbType, it) }
        val oldLabel = old?.label ?: dbType
        val choice = f.typeChange?.takeIf { if (known != null) it.from == known else dialect.matchesType(dbType, it.from) }
        val col = quoteIdent(ref.column)
        val filled = count("SELECT COUNT(*) AS n FROM ${quoteIdent(ref.table)} WHERE $col IS NOT NULL")

        if (filled == 0L) return ColumnTypeChange(ref, old, f.type, action = null) to emptyList()
        if (choice == null) {
            val stale = f.typeChange?.let { tr(" (kayıtlı seçim {0} tipinden değişiklik içindi)", it.from.label) }.orEmpty()
            blockers += tr("{0}: tip değişikliği ({1} → {2}), {3} kayıtta değer var{4}.", ref, oldLabel, f.type.label, filled, stale) + " " +
                tr("Mevcut değerler için seçim yapın: dönüştür veya boşalt — ya da yeni adla bir alan ekleyin.") + " " +
                tr("JSON'da:") + " \"typeChange\": {\"from\": \"$oldLabel\", \"action\": \"convert\" | \"clear\"}"
            return null
        }
        if (choice.action == TypeChangeAction.CONVERT && !TypeConversions.canConvert(choice.from, f.type)) {
            blockers += tr("{0}: {1} → {2} dönüştürülemez; boşaltmayı seçin veya yeni adla bir alan ekleyin.", ref, choice.from.label, f.type.label)
            return null
        }

        var cleared = 0L
        val samples = mutableListOf<String>()
        val seen = if (f.unique) HashMap<Any, Int>() else null
        forEachValue(ref, choice.from) { _, raw, value ->
            val converted = if (choice.action == TypeChangeAction.CONVERT) value?.let { TypeConversions.convert(choice.from, f.type, it) } else null
            if (converted == null) {
                cleared++
                if (samples.size < 3) samples += "'${(value ?: raw).toString().take(40)}'"
            } else {
                seen?.merge(converted, 1, Int::plus)
            }
        }
        when {
            choice.action == TypeChangeAction.CLEAR -> warnings += tr("{0}: tip değişikliği için {1} kayıttaki değer silinecek", ref, filled)
            cleared > 0 -> warnings += tr("{0}: {1} değer {2} tipine çevrilemiyor ve boşalacak (örn. {3})", ref, cleared, f.type.label, samples.joinToString())
        }
        if (f.required && cleared > 0) warnings += tr("{0} zorunlu ama tip değişikliğinden sonra {1} kayıtta boş olacak", ref, cleared)
        val duplicates = seen.orEmpty().filterValues { it > 1 }.entries.take(3).map { "'${it.key.toString().take(40)}' ×${it.value}" }
        return ColumnTypeChange(ref, choice.from, f.type, choice.action, filled, cleared, samples) to duplicates
    }

    /**
     * Sütunu yeni tiple kurar ve değerleri seçime göre doldurur. Veritabanı tipi aynıysa (SQLite'ta text → json)
     * değerler yerinde güncellenir; değilse geçici sütuna yazılıp [SqlDialect.replaceColumn] ile yer değiştirilir.
     */
    private fun changeType(c: ColumnTypeChange) {
        val table = quoteIdent(c.ref.table)
        val dbType = dialect.listColumns(db, c.ref.table)[c.ref.column]
        val inPlace = dbType != null && dialect.matchesType(dbType, c.to)
        val target = quoteIdent(if (inPlace) c.ref.column else RETYPE_COLUMN)
        if (!inPlace) db.execute("ALTER TABLE $table ADD COLUMN $target ${dialect.columnType(c.to)}")

        when (c.action) {
            TypeChangeAction.CONVERT -> forEachValue(c.ref, requireNotNull(c.from)) { id, _, value ->
                val converted = value?.let { TypeConversions.convert(c.from, c.to, it) }
                if (inPlace || converted != null) {
                    db.execute("UPDATE $table SET $target = ? WHERE \"id\" = ?", listOf(dialect.encode(c.to, converted), id))
                }
            }
            TypeChangeAction.CLEAR, null -> if (inPlace) db.execute("UPDATE $table SET $target = NULL")
        }
        if (!inPlace) dialect.replaceColumn(db, c.ref.table, c.ref.column, RETYPE_COLUMN)
    }

    /**
     * Sütunun dolu değerlerini id sırasıyla, sayfa sayfa gezer: (id, ham değer, [type] olarak okunmuş değer).
     * Okunamayan değer (örn. SQLite'ta tamsayı sütununda metin) null verilir.
     */
    private fun forEachValue(ref: ColumnRef, type: FieldType, block: (Long, Any, Any?) -> Unit) {
        val col = quoteIdent(ref.column)
        val sql = "SELECT \"id\", $col AS v FROM ${quoteIdent(ref.table)} WHERE $col IS NOT NULL AND \"id\" > ? " +
            "ORDER BY \"id\" LIMIT $PAGE_SIZE"
        var lastId = Long.MIN_VALUE
        while (true) {
            val rows = db.query(sql, listOf(lastId))
            if (rows.isEmpty()) break
            for (row in rows) {
                val raw = row["v"] ?: continue
                block((row["id"] as Number).toLong(), raw, runCatching { dialect.decode(type, raw) }.getOrNull())
            }
            lastId = (rows.last()["id"] as Number).toLong()
        }
    }

    private fun readMeta(prefix: String): Map<String, String> =
        db.query("""SELECT "key", "value" FROM ${quoteIdent(META_TABLE)}""").mapNotNull { row ->
            val key = (row["key"] as String).takeIf { it.startsWith(prefix) } ?: return@mapNotNull null
            key.removePrefix(prefix) to row["value"].toString()
        }.toMap()

    private fun readFieldTypes(): Map<ColumnRef, FieldType> =
        db.query("""SELECT "key", "value" FROM ${quoteIdent(META_TABLE)}""").mapNotNull { row ->
            val key = (row["key"] as String).takeIf { it.startsWith(FIELD_KEY_PREFIX) } ?: return@mapNotNull null
            val (table, column) = key.removePrefix(FIELD_KEY_PREFIX).split('.', limit = 2).takeIf { it.size == 2 }
                ?: return@mapNotNull null
            val type = FieldType.entries.firstOrNull { it.label == row["value"] } ?: return@mapNotNull null
            ColumnRef(table, column) to type
        }.toMap()

    /** Tekrar eden ilk birkaç değer, örn. "'sut' ×2". NULL'lar benzersiz indekste çakışmaz. */
    private fun duplicates(ref: ColumnRef): List<String> {
        val col = quoteIdent(ref.column)
        return db.query(
            "SELECT $col AS v, COUNT(*) AS n FROM ${quoteIdent(ref.table)} WHERE $col IS NOT NULL " +
                "GROUP BY $col HAVING COUNT(*) > 1 LIMIT 3",
        ).map { "'${it["v"].toString().take(40)}' ×${it["n"]}" }
    }

    private fun count(sql: String): Long = (db.query(sql).first()["n"] as Number).toLong()

    internal companion object {
        const val SYSTEM_PREFIX = "_ds_"
        const val META_TABLE = "_ds_meta"
        const val FIELD_KEY_PREFIX = "field:"
        const val SEARCH_KEY_PREFIX = "search:"
        const val FULLTEXT_KEY_PREFIX = "fulltext:"
        const val SEARCH_COLUMN = SearchText.COLUMN
        /** Tip değişikliğinde geçici sütun; alan adları harfle başladığı için kullanıcı alanıyla çakışmaz. */
        const val RETYPE_COLUMN = "_ds_retype"
        const val PAGE_SIZE = 500
        val SYSTEM_COLUMNS = setOf("id", "created_at", "updated_at", SearchText.COLUMN)

        /**
         * Benzersiz indeks adı. İndeks adları veritabanında tablo değil şema genelinde tekildir; `ux_<tablo>_<alan>`
         * farklı çiftlerde aynı çıkabiliyordu (`a_b.c` ile `a.b_c`), ikincisi sessizce oluşmuyordu. Ayrım için
         * `tablo.alan`'ın özetinden 8 karakter eklenir; PostgreSQL'in 63 karakter sınırı için ad kısaltılır.
         */
        fun indexName(ref: ColumnRef): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(ref.toString().toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            return "ux_${"${ref.table}_${ref.column}".take(50)}_$hash"
        }

        /** İlişki alanının (benzersiz olmayan) arama indeksi; ad [indexName] ile aynı yolla tekilleştirilir. */
        fun relationIndexName(ref: ColumnRef): String = "ix" + indexName(ref).removePrefix("ux")

        /** Önceki sürümlerin kullandığı ad; mevcut veritabanlarında bu adla indeks olabilir. */
        fun legacyIndexName(ref: ColumnRef) = "ux_${ref.table}_${ref.column}"
    }
}

/** Konfigürasyondaki adı (`text`, `integer` ...). */
internal val FieldType.label: String get() = name.lowercase()
