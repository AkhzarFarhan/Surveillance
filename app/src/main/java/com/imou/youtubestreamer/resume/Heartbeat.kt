package com.imou.youtubestreamer.resume

import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.supervisor.SupervisorStore

/**
 * Tracks supervisor liveness across process kills, OS kills, and device reboots.
 */
class Heartbeat(
    private val store: SupervisorStore,
    private val clock: Clock
) {
    fun beat(): Long {
        val now = clock.nowMs()
        store.heartbeatWallMs = now
        return now
    }

    fun lastHeartbeatWallMs(): Long = store.heartbeatWallMs

    fun isStale(thresholdMs: Long = 60_000L): Boolean {
        val last = store.heartbeatWallMs
        if (last <= 0L) return true
        return (clock.nowMs() - last) > thresholdMs
    }

    /**
     * If [store.desiredRunning] was true and the previous process left a heartbeat
     * older than [gapThresholdMs], returns the uncovered `[startWallMs, nowWallMs]` interval
     * so `GapLedger` can enqueue backfill for downtime across kills/reboots.
     */
    fun staleOutageWindow(gapThresholdMs: Long = 30_000L): Pair<Long, Long>? {
        val last = store.heartbeatWallMs
        val now = clock.nowMs()
        if (last <= 0L || now <= last) return null
        return if ((now - last) >= gapThresholdMs) {
            last to now
        } else {
            null
        }
    }
}
