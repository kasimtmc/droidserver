package dev.droidserver.core.config

import dev.droidserver.core.i18n.tr

/** İki konfigürasyon sürümü arasındaki satır farkı (Geçmiş ekranı). */
object ConfigDiff {
    enum class Kind { SAME, ADDED, REMOVED }

    data class Line(val kind: Kind, val text: String)

    /** Biçim farkları görünmesin diye iki metin de standart biçime getirilir (ayrıştırılamazsa olduğu gibi). */
    fun configs(old: String, new: String): List<Line> = lines(canonical(old), canonical(new))

    /** En uzun ortak alt dizi ile satır farkı; konfigürasyonlar küçük olduğu için O(n·m) yeterli. */
    fun lines(old: String, new: String): List<Line> {
        val a = old.lines()
        val b = new.lines()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Line>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += Line(Kind.SAME, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> out += Line(Kind.REMOVED, a[i++])
                else -> out += Line(Kind.ADDED, b[j++])
            }
        }
        while (i < a.size) out += Line(Kind.REMOVED, a[i++])
        while (j < b.size) out += Line(Kind.ADDED, b[j++])
        return out
    }

    /**
     * Yalnızca değişen satırlar ve çevresindeki [context] satır; aradaki atlanan bölümler `null` ile gösterilir.
     */
    fun hunks(diff: List<Line>, context: Int = 2): List<Line?> {
        val changed = diff.indices.filter { diff[it].kind != Kind.SAME }
        if (changed.isEmpty()) return emptyList()
        val keep = BooleanArray(diff.size)
        for (i in changed) for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(diff.lastIndex)) keep[j] = true
        val out = mutableListOf<Line?>()
        for (i in diff.indices) {
            if (keep[i]) out += diff[i] else if (out.isEmpty() || out.last() != null) out += null
        }
        return out
    }

    private fun canonical(text: String) = runCatching { ServerConfig.fromJson(text).toJson() }.getOrDefault(text)
}

/** Etkin konfigürasyona göre veri kaybettirebilecek veya deploy'u engelleyecek düzenlemeler (sihirbaz uyarıları). */
object ChangeWarnings {
    /** [typeChange]: tip değişikliği uyarısı (sihirbaz bunun için ayrıca seçim kartı gösterir). */
    data class FieldWarning(val field: String, val message: String, val typeChange: Boolean = false)

    fun forCollection(active: CollectionConfig?, draft: CollectionConfig): List<FieldWarning> {
        if (active == null) return emptyList()
        val draftFields = draft.fields.associateBy { it.name }
        return buildList {
            for (old in active.fields) {
                val new = draftFields[old.name]
                when {
                    new == null -> add(FieldWarning(old.name, tr("Alan kaldırıldı: sütun ve verisi veritabanında kalır ama API'de görünmez.")))
                    new.type != old.type -> add(
                        FieldWarning(
                            old.name,
                            tr("Tip değişikliği ({0} → {1}): kayıtlı değerler ", old.type.name.lowercase(), new.type.name.lowercase()) +
                                when (new.typeChange?.takeIf { it.from == old.type }?.action) {
                                    TypeChangeAction.CONVERT -> tr("yeni tipe çevrilecek, çevrilemeyenler boşalacak (sayısı Özet'teki planda).")
                                    TypeChangeAction.CLEAR -> tr("silinecek.")
                                    null -> tr("için seçim yapın.")
                                },
                            typeChange = true,
                        ),
                    )
                    new.unique && !old.unique -> add(FieldWarning(old.name, tr("Mevcut kayıtlarda tekrar eden değer varsa deploy engellenir.")))
                }
            }
        }
    }

    /** Etkin konfigürasyonda olup taslakta olmayan koleksiyonlar (yeniden adlandırma dahil). */
    fun removedCollections(active: ServerConfig?, draft: ServerConfig): List<String> =
        active?.collections?.map { it.name }.orEmpty() - draft.collections.map { it.name }.toSet()
}
