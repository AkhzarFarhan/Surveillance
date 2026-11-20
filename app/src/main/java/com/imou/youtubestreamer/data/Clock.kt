package com.imou.youtubestreamer.data

import android.os.SystemClock

/**
 * Time source abstraction allowing deterministic JVM unit tests and immunity
 * to wall-clock jumps when measuring intervals, stalls, and backoffs.
 *
 * - [nowMs]: wall-clock epoch milliseconds (for VOD titles, 8h boundaries, SD timestamps, GapLedger)
 * - [elapsedMs]: monotonic elapsed milliseconds since boot (for backoff, stall watchdogs, debounce)
 */
interface Clock {
    fun nowMs(): Long
    fun elapsedMs(): Long
}

object SystemClockImpl : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}
