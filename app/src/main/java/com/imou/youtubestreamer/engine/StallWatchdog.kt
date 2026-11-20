package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.Clock
import kotlinx.coroutines.delay

/**
 * Monitors progress of a running FFmpeg streaming session.
 * After a startup grace period, verifies every [checkIntervalMs] that BOTH video frame count
 * and upstream output bytes have advanced within [stallThresholdMs].
 */
object StallWatchdog {

    suspend fun watchForStall(
        clock: Clock,
        startedAtElapsedMs: Long,
        lastFrameProgressElapsedMs: () -> Long,
        lastBytesProgressElapsedMs: () -> Long,
        startupGraceMs: Long = 25_000L,
        stallThresholdMs: Long = 20_000L,
        checkIntervalMs: Long = 5_000L,
        onStall: (reason: String) -> Unit
    ) {
        while (true) {
            delay(checkIntervalMs)
            val now = clock.elapsedMs()
            if (now - startedAtElapsedMs < startupGraceMs) {
                continue
            }

            val frameIdleMs = now - lastFrameProgressElapsedMs()
            val bytesIdleMs = now - lastBytesProgressElapsedMs()

            if (frameIdleMs >= stallThresholdMs || bytesIdleMs >= stallThresholdMs) {
                val detail = "STALLED (no frame progress for ${frameIdleMs / 1000}s, no bytes progress for ${bytesIdleMs / 1000}s)"
                onStall(detail)
                return
            }
        }
    }
}
