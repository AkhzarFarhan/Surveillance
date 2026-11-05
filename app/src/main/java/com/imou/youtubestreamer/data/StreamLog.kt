package com.imou.youtubestreamer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, FFMPEG
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

object AppLogger {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private const val MAX_LOGS = 300

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )
        synchronized(this) {
            val current = _logs.value.toMutableList()
            if (current.size >= MAX_LOGS) {
                current.removeAt(0)
            }
            current.add(entry)
            _logs.value = current
        }

        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, message)
            LogLevel.INFO -> android.util.Log.i(tag, message)
            LogLevel.WARN -> android.util.Log.w(tag, message)
            LogLevel.ERROR -> android.util.Log.e(tag, message)
            LogLevel.FFMPEG -> android.util.Log.v(tag, message)
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun ffmpeg(tag: String, message: String) = log(LogLevel.FFMPEG, tag, message)

    fun clear() {
        _logs.value = emptyList()
    }
}
