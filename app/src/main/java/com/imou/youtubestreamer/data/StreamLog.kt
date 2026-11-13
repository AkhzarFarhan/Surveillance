package com.imou.youtubestreamer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, FFMPEG
}

data class LogEntry(
    val id: Long = ID_GEN.incrementAndGet(),
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    companion object {
        private val ID_GEN = AtomicLong(System.nanoTime())
    }
}

/**
 * Extreme-detail diagnostic & trace logger with synchronous disk persistence.
 * Every function entry, parameter summary, intermediate branch, unexpected value,
 * return value, and full exception stack trace is persisted to `surveillance_debug.log`
 * so logs survive process kills or crashes and appear immediately in the Diagnostics tab.
 */
object AppLogger {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _lastCopiedLogId = MutableStateFlow(0L)
    val lastCopiedLogId: StateFlow<Long> = _lastCopiedLogId.asStateFlow()

    private const val MAX_LOGS = 800
    private const val MAX_LOG_FILE_BYTES = 350 * 1024L
    private const val LOG_FILE_NAME = "surveillance_debug.log"
    private const val LAST_COPIED_FILE_NAME = "surveillance_last_copied.txt"

    @Volatile
    private var persistentLogFile: File? = null

    @Volatile
    private var lastCopiedMarkerFile: File? = null

    fun init(context: Context) {
        synchronized(this) {
            try {
                val appDir = context.applicationContext.filesDir
                val file = File(appDir, LOG_FILE_NAME)
                val markerFile = File(appDir, LAST_COPIED_FILE_NAME)
                persistentLogFile = file
                lastCopiedMarkerFile = markerFile

                val savedMarkerLine = if (markerFile.exists()) {
                    try {
                        markerFile.readText(Charsets.UTF_8).trim()
                    } catch (_: Throwable) {
                        ""
                    }
                } else {
                    ""
                }

                if (file.exists() && file.length() > 0L) {
                    val lines = file.readLines(Charsets.UTF_8).takeLast(250)
                    val restored = mutableListOf<LogEntry>()
                    var matchedBookmarkId = 0L
                    for (line in lines) {
                        parsePersistedLine(line)?.let { entry ->
                            restored.add(entry)
                            if (savedMarkerLine.isNotEmpty() && line.trim() == savedMarkerLine) {
                                matchedBookmarkId = entry.id
                            }
                        }
                    }
                    if (restored.isNotEmpty()) {
                        restored.add(
                            LogEntry(
                                timestamp = formatNow(),
                                level = LogLevel.INFO,
                                tag = "AppLogger",
                                message = "─── Restored ${restored.size} persistent log entries from previous session ───"
                            )
                        )
                        _logs.value = restored.takeLast(MAX_LOGS)
                        if (matchedBookmarkId > 0L) {
                            _lastCopiedLogId.value = matchedBookmarkId
                        }
                    }
                    if (file.length() > MAX_LOG_FILE_BYTES) {
                        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("AppLogger", "Failed to initialize persistent log file", t)
            }
        }
    }

    fun formatEntryLine(entry: LogEntry): String {
        val sanitizedMsg = entry.message.replace("\r\n", " ⏎ ").replace("\n", " ⏎ ")
        return "[${entry.timestamp}][${entry.level}][${entry.tag}] $sanitizedMsg"
    }

    fun markLogsCopied(lastEntry: LogEntry) {
        synchronized(this) {
            _lastCopiedLogId.value = lastEntry.id
            try {
                lastCopiedMarkerFile?.writeText(formatEntryLine(lastEntry), Charsets.UTF_8)
            } catch (_: Throwable) {
            }
        }
    }

    fun resetCopyBookmark() {
        synchronized(this) {
            _lastCopiedLogId.value = 0L
            try {
                lastCopiedMarkerFile?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun parsePersistedLine(line: String): LogEntry? {
        // Format: [HH:mm:ss.SSS][LEVEL][TAG] message
        if (!line.startsWith("[")) return null
        val firstClose = line.indexOf(']')
        if (firstClose <= 1) return null
        val secondOpen = line.indexOf('[', firstClose + 1)
        val secondClose = line.indexOf(']', secondOpen + 1)
        if (secondOpen < 0 || secondClose < 0) return null
        val thirdOpen = line.indexOf('[', secondClose + 1)
        val thirdClose = line.indexOf(']', thirdOpen + 1)
        if (thirdOpen < 0 || thirdClose < 0) return null

        val ts = line.substring(1, firstClose)
        val lvlStr = line.substring(secondOpen + 1, secondClose)
        val tag = line.substring(thirdOpen + 1, thirdClose)
        val msg = if (thirdClose + 2 <= line.length) line.substring(thirdClose + 2) else ""
        val level = try {
            LogLevel.valueOf(lvlStr)
        } catch (_: Exception) {
            LogLevel.DEBUG
        }
        return LogEntry(timestamp = ts, level = level, tag = tag, message = msg)
    }

    private fun formatNow(): String = synchronized(timeFormat) {
        timeFormat.format(Date())
    }

    private fun sanitizeSecrets(text: String): String {
        var clean = text.replace(Regex("(rtsp://)([^:@\\s]+):([^@\\s]+)@"), "$1***:***@")
        clean = clean.replace(Regex("(rtmps?://[^/\\s]+/live2/)([^\\s:]+)")) { match ->
            "${match.groupValues[1]}${maskSecret(match.groupValues[2])}"
        }
        return clean
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val ts = formatNow()
        val safeMessage = sanitizeSecrets(message)
        val entry = LogEntry(
            timestamp = ts,
            level = level,
            tag = tag,
            message = safeMessage
        )

        synchronized(this) {
            val current = _logs.value.toMutableList()
            while (current.size >= MAX_LOGS) {
                current.removeAt(0)
            }
            current.add(entry)
            _logs.value = current

            persistentLogFile?.let { file ->
                try {
                    val sanitizedMsg = safeMessage.replace("\r\n", " ⏎ ").replace("\n", " ⏎ ")
                    val rawLine = "[$ts][$level][$tag] $sanitizedMsg\n"
                    FileOutputStream(file, true).use { fos ->
                        fos.write(rawLine.toByteArray(Charsets.UTF_8))
                        fos.flush()
                    }
                } catch (_: Throwable) {
                }
            }
        }

        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, safeMessage)
            LogLevel.INFO -> android.util.Log.i(tag, safeMessage)
            LogLevel.WARN -> android.util.Log.w(tag, safeMessage)
            LogLevel.ERROR -> android.util.Log.e(tag, safeMessage)
            LogLevel.FFMPEG -> android.util.Log.v(tag, safeMessage)
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun ffmpeg(tag: String, message: String) = log(LogLevel.FFMPEG, tag, message)

    /**
     * Logs function entry with parameter values and active thread name.
     */
    fun enter(tag: String, fn: String, params: String = "") {
        val thread = Thread.currentThread().name
        val paramStr = if (params.isBlank()) "()" else "($params)"
        log(LogLevel.DEBUG, tag, "▶ ENTER $fn$paramStr [thread=$thread]")
    }

    /**
     * Logs function return/exit with the returned value.
     */
    fun exit(tag: String, fn: String, result: Any? = "Unit") {
        log(LogLevel.DEBUG, tag, "◀ EXIT $fn -> $result")
    }

    /**
     * Logs an intermediate step or state check inside a function.
     */
    fun step(tag: String, fn: String, detail: String) {
        log(LogLevel.DEBUG, tag, "• [$fn] $detail")
    }

    /**
     * Logs when a function encounters an unexpected return value, status code, or state.
     */
    fun unexpected(tag: String, fn: String, reason: String, value: Any? = null) {
        val valSuffix = if (value != null) " | actual=$value" else ""
        log(LogLevel.WARN, tag, "⚠ UNEXPECTED in $fn: $reason$valSuffix")
    }

    /**
     * Logs a caught Throwable (Exception or Error such as NoClassDefFoundError / UnsatisfiedLinkError)
     * with its exact class name, message, cause chain, and top stack frames.
     */
    fun exception(tag: String, fn: String, throwable: Throwable, contextInfo: String = "") {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val topFrames = sw.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(14)
            .joinToString(" | ")
        val ctxPrefix = if (contextInfo.isNotBlank()) "[$contextInfo] " else ""
        log(
            LogLevel.ERROR,
            tag,
            "❌ EXCEPTION in $fn: $ctxPrefix${throwable::class.java.name}: ${throwable.message} || Stack: $topFrames"
        )
    }

    /**
     * Masks sensitive passwords or stream keys while still showing length and edge characters for debugging.
     */
    fun maskSecret(secret: String): String {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return "<EMPTY len=0>"
        if (trimmed.length <= 4) return "<SET len=${trimmed.length}>"
        return "${trimmed.take(2)}***${trimmed.takeLast(2)} (len=${trimmed.length})"
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
            _lastCopiedLogId.value = 0L
            try {
                persistentLogFile?.writeText("", Charsets.UTF_8)
                lastCopiedMarkerFile?.delete()
            } catch (_: Throwable) {
            }
        }
    }
}
