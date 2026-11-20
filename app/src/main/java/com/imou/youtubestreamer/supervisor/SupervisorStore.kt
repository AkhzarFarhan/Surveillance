package com.imou.youtubestreamer.supervisor

import android.content.Context
import android.content.SharedPreferences

/**
 * Fast, unencrypted runtime state store for desired service state, heartbeat,
 * and crash-loop protection. Must stay readable early in Application/BroadcastReceiver
 * startup without waiting for EncryptedSharedPreferences keystore unlock.
 */
class SupervisorStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasDesiredRunningKey(): Boolean = prefs.contains(KEY_DESIRED_RUNNING)

    var desiredRunning: Boolean
        get() = prefs.getBoolean(KEY_DESIRED_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_DESIRED_RUNNING, value).apply()

    var heartbeatWallMs: Long
        get() = prefs.getLong(KEY_HEARTBEAT_WALL_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_HEARTBEAT_WALL_MS, value).apply()

    @Synchronized
    fun recordCrash(nowWallMs: Long): Int {
        val recent = pruneCrashTimestamps(loadCrashTimestamps(), nowWallMs) + nowWallMs
        saveCrashTimestamps(recent)
        return recent.size
    }

    @Synchronized
    fun recentCrashCount(nowWallMs: Long): Int {
        val recent = pruneCrashTimestamps(loadCrashTimestamps(), nowWallMs)
        return recent.size
    }

    @Synchronized
    fun isCrashLoopActive(nowWallMs: Long): Boolean {
        return recentCrashCount(nowWallMs) > CRASH_LOOP_THRESHOLD
    }

    @Synchronized
    fun clearCrashHistory() {
        prefs.edit().remove(KEY_CRASH_TIMESTAMPS).apply()
    }

    private fun pruneCrashTimestamps(list: List<Long>, nowWallMs: Long): List<Long> {
        val cutoff = nowWallMs - CRASH_WINDOW_MS
        return list.filter { it >= cutoff && it <= nowWallMs + 60_000L }
    }

    private fun loadCrashTimestamps(): List<Long> {
        val raw = prefs.getString(KEY_CRASH_TIMESTAMPS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    private fun saveCrashTimestamps(list: List<Long>) {
        prefs.edit().putString(KEY_CRASH_TIMESTAMPS, list.joinToString(",")).apply()
    }

    companion object {
        private const val PREFS_NAME = "surveillance_runtime"
        private const val KEY_DESIRED_RUNNING = "desired_running"
        private const val KEY_HEARTBEAT_WALL_MS = "heartbeat_wall_ms"
        private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"

        const val CRASH_WINDOW_MS = 10L * 60_000L // 10 minutes
        const val CRASH_LOOP_THRESHOLD = 5        // > 5 crashes within 10 min
    }
}
