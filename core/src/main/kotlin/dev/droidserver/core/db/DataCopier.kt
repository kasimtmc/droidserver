package dev.droidserver.core.db

import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.TypeChangeAction
import dev.droidserver.core.config.TypeConversions
import dev.droidserver.core.i18n.tr

/**
 * Koleksiyon verisini bir veritabanından diğerine kopyalar (örn. SQLite → PostgreSQL):
 *  1. hedefte yeni konfigürasyonun şeması kurulur,
 *  2. her tablo sayfa sayfa (id sırasıyla), kimlikler korunarak kopyalanır,
 *  3. hedefin kimlik sayacı en büyük id'ye getirilir (`setval`),
 *  4. satır sayıları karşılaştırılır.
 * Bir alanın tipi de değişiyorsa değerler kullanıcının seçimine göre çevrilir veya boşaltılır
 * (bkz. [TypeConversions]); kaynakta değer varken seçim yoksa taşıma başlamaz.
 * Hedefteki her şey tek işlemde: bir adım başarısız olursa hedefte hiçbir şey kalmaz. Kaynak yalnızca okunur.
 * Hedefte kayıt varsa ne olacağı kullanıcının seçimidir ([copy]'nin `replaceExisting` parametresi).
 */
object DataCopier {
    data class Report(val rows: Map<String, Long>) {
        override fun toString() = rows.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "kopyalanacak tablo yok" }
    }

    fun copy(
        source: SqlDriver,
        target: SqlDriver,
        from: List<CollectionConfig>,
        to: List<CollectionConfig>,
        /** Hedefte kayıt varsa: true → kopyalanacak tablolardaki kayıtlar silinir; false → taşıma durur. */
        replaceExisting: Boolean = false,
        pageSize: Int = 500,
        log: (String) -> Unit = {},
    ): Report {
        val fromByName = from.associateBy { it.name }
        val sourceTables = source.dialect.listTables(source)

        return target.transaction {
            if (replaceExisting) {
                // Önce silinir: boşalan sütunların tipi seçim gerekmeden yeni konfigürasyona uyar.
                val targetTables = target.dialect.listTables(target)
                for (c in to) {
                    if (c.name !in targetTables || c.name !in fromByName || c.name !in sourceTables) continue
                    val deleted = target.execute("DELETE FROM ${quoteIdent(c.name)}")
                    if (deleted > 0) log(tr("Hedefteki kayıtlar silindi: {0} ({1} kayıt)", c.name, deleted))
                }
            }
            SchemaMigrator(target).migrate(to)
            val counts = linkedMapOf<String, Long>()
            for (c in to) {
                val old = fromByName[c.name] ?: continue
                if (c.name !in sourceTables) continue
                val existing = count(target, c.name)
                require(existing == 0L) {
                    tr("Hedefte '{0}' tablosunda {1} kayıt var (boş değil); taşımak için hedefteki kayıtların silinmesini seçin", c.name, existing)
                }

                val sourceColumns = source.dialect.listColumns(source, c.name).keys
                val oldTypes = old.fields.associate { it.name to it.type }
                val system = listOf(
                    Column("id", FieldType.INTEGER, FieldType.INTEGER, null),
                    Column("created_at", FieldType.DATETIME, FieldType.DATETIME, null),
                    Column("updated_at", FieldType.DATETIME, FieldType.DATETIME, null),
                )
                val columns = system + c.fields.filter { it.name in sourceColumns }.mapNotNull { f ->
                    val oldType = oldTypes[f.name] ?: return@mapNotNull null
                    Column(f.name, oldType, f.type, if (oldType == f.type) null else typeChoice(source, c, f, oldType))
                }
                val cols = columns.map { it.name }
                var cleared = 0L
                val select = "SELECT ${cols.joinToString { quoteIdent(it) }} FROM ${quoteIdent(c.name)} " +
                    "WHERE \"id\" > ? ORDER BY \"id\" LIMIT $pageSize"
                val insert = target.dialect.insertWithIdSql(c.name, cols)

                var lastId = Long.MIN_VALUE
                while (true) {
                    val rows = source.query(select, listOf(lastId))
                    if (rows.isEmpty()) break
                    for (row in rows) {
                        target.execute(insert, columns.map { col ->
                            val value = source.dialect.decode(col.from, row[col.name])
                            val converted = when {
                                value == null || col.choice == null -> value
                                col.choice == TypeChangeAction.CONVERT -> TypeConversions.convert(col.from, col.to, value)
                                else -> null
                            }
                            if (value != null && converted == null) cleared++
                            target.dialect.encode(col.to, converted)
                        })
                    }
                    lastId = (rows.last()["id"] as Number).toLong()
                }
                target.dialect.resetIdSequenceSql(c.name)?.let { target.query(it) }

                val expected = count(source, c.name)
                val actual = count(target, c.name)
                check(expected == actual) { tr("'{0}': kaynakta {1}, hedefte {2} kayıt", c.name, expected, actual) }
                counts[c.name] = actual
                log(tr("Taşındı: {0} ({1} kayıt)", c.name, actual) + if (cleared > 0) tr(", tip değişikliğinde {0} değer boşaldı", cleared) else "")
            }
            Report(counts)
        }
    }

    /** Kopyalanan sütun: kaynaktaki tip, hedefteki tip ve tip değiştiyse kullanıcının seçimi. */
    private class Column(val name: String, val from: FieldType, val to: FieldType, val choice: TypeChangeAction?)

    /**
     * Tipi değişen alan için kullanıcının seçimi ([dev.droidserver.core.config.FieldConfig.typeChange]).
     * Kaynakta değer yoksa seçim gerekmez (boşaltma ile aynı sonuç).
     */
    private fun typeChoice(source: SqlDriver, c: CollectionConfig, f: FieldConfig, oldType: FieldType): TypeChangeAction {
        val ref = "${c.name}.${f.name}"
        val choice = f.typeChange?.takeIf { it.from == oldType }?.action
        if (choice == null) {
            val col = quoteIdent(f.name)
            val filled = (source.query("SELECT COUNT(*) AS n FROM ${quoteIdent(c.name)} WHERE $col IS NOT NULL").first()["n"] as Number).toLong()
            require(filled == 0L) {
                tr("{0}: tip değişikliği ({1} → {2}), {3} kayıtta değer var; mevcut değerler için seçim yapın (dönüştür / boşalt)", ref, oldType.label, f.type.label, filled)
            }
            return TypeChangeAction.CLEAR
        }
        require(choice != TypeChangeAction.CONVERT || TypeConversions.canConvert(oldType, f.type)) {
            tr("{0}: {1} → {2} dönüştürülemez; boşaltmayı seçin", ref, oldType.label, f.type.label)
        }
        return choice
    }

    private fun count(db: SqlDriver, table: String): Long =
        (db.query("SELECT COUNT(*) AS n FROM ${quoteIdent(table)}").first()["n"] as Number).toLong()
}
