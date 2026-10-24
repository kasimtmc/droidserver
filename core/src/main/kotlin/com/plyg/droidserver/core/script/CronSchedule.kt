package com.plyg.droidserver.core.script

import com.plyg.droidserver.core.i18n.tr
import java.time.LocalDateTime

/**
 * Beş alanlı cron ifadesi: `dakika saat gün ay haftanın-günü` (telefonun saat diliminde).
 * Her alan `*`, sayı, aralık (`1-5`), liste (`1,15`) ve adım (`*` + `/15`, `0-30/10`) olabilir. Haftanın günü 0-7
 * (0 ve 7 pazar). Gün ve haftanın günü ikisi de kısıtlıysa biri tutması yeter (klasik cron davranışı).
 */
class CronSchedule private constructor(
    val expression: String,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val days: Set<Int>,
    private val months: Set<Int>,
    private val weekdays: Set<Int>,
    private val anyDay: Boolean,
    private val anyWeekday: Boolean,
) {
    fun matches(t: LocalDateTime): Boolean {
        if (t.minute !in minutes || t.hour !in hours || t.monthValue !in months) return false
        val day = t.dayOfMonth in days
        val weekday = (t.dayOfWeek.value % 7) in weekdays
        return when {
            anyDay && anyWeekday -> true
            anyDay -> weekday
            anyWeekday -> day
            else -> day || weekday
        }
    }

    companion object {
        /** Geçersizse açıklamalı [IllegalArgumentException]. */
        fun parse(expression: String): CronSchedule {
            val parts = expression.trim().split(Regex("\\s+"))
            require(parts.size == 5) { tr("cron ifadesi 5 alan olmalı (dakika saat gün ay haftanın-günü): '{0}'", expression) }
            val weekdays = field(parts[4], 0, 7, tr("haftanın günü")).map { it % 7 }.toSet()
            return CronSchedule(
                expression,
                field(parts[0], 0, 59, tr("dakika")),
                field(parts[1], 0, 23, tr("saat")),
                field(parts[2], 1, 31, tr("gün")),
                field(parts[3], 1, 12, tr("ay")),
                weekdays,
                anyDay = parts[2] == "*",
                anyWeekday = parts[4] == "*",
            )
        }

        private fun field(text: String, min: Int, max: Int, name: String): Set<Int> = text.split(',').flatMap { item ->
            val (range, stepText) = item.split('/').let { it[0] to it.getOrNull(1) }
            require(item.count { it == '/' } <= 1) { tr("{0}: '{1}' geçersiz", name, item) }
            val step = stepText?.toIntOrNull()?.takeIf { it > 0 } ?: if (stepText == null) 1 else throw IllegalArgumentException(tr("{0}: adım geçersiz: '{1}'", name, item))
            val (from, to) = when {
                range == "*" -> min to max
                '-' in range -> range.split('-').let { (a, b) -> number(a, min, max, name) to number(b, min, max, name) }
                else -> number(range, min, max, name).let { if (stepText != null) it to max else it to it }
            }
            require(from <= to) { tr("{0}: aralık ters: '{1}'", name, item) }
            (from..to step step).toList()
        }.toSet()

        private fun number(s: String, min: Int, max: Int, name: String): Int =
            s.toIntOrNull()?.takeIf { it in min..max } ?: throw IllegalArgumentException(tr("{0} {1}-{2} arasında olmalı: '{3}'", name, min, max, s))
    }
}
