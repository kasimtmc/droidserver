package com.plyg.droidserver.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Arayüzde gösterilen son 300 log satırı (ayrıca logcat'e de yazılır). */
class LogBuffer(private val max: Int = 300) {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Şimdiye kadar eklenen satır sayısı (web panelinin canlı log akışı kaldığı yeri bilsin). */
    private var total = 0L

    fun add(message: String) {
        Log.i("DroidServer", message)
        val line = "${LocalTime.now().format(fmt)}  $message"
        synchronized(this) {
            total++
            _lines.update { (it + line).takeLast(max) }
        }
    }

    /** [after]'dan sonraki satırlar (arabellekte kalanlar) ve yeni sıra. */
    @Synchronized
    fun since(after: Long): Pair<Long, List<String>> {
        val lines = _lines.value
        val first = total - lines.size
        val skip = (after - first).coerceIn(0, lines.size.toLong()).toInt()
        return total to lines.drop(skip)
    }
}
