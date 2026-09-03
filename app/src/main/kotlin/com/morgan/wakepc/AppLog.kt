package com.morgan.wakepc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LogLine(val at: Long, val text: String, val ok: Boolean?)

/** In-memory terminal: every request the app makes, newest last. */
object AppLog {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    @Synchronized
    fun log(text: String, ok: Boolean? = null) {
        _lines.value = (_lines.value + LogLine(System.currentTimeMillis(), text, ok)).takeLast(300)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
fun LogLine.timestamp(): String = timeFormat.format(Date(at))
