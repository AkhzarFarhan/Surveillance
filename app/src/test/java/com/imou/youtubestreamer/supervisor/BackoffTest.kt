package com.imou.youtubestreamer.supervisor

import com.imou.youtubestreamer.data.WorkerExitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun `exponential growth caps at 60s and obeys plus or minus 20 percent jitter`() {
        // Zero jitter midpoint (rand = 0.5 -> multiplier = 1.0)
        val exact = Backoff(baseMs = 2_000L, factor = 2.0, capMs = 60_000L, jitterFraction = 0.20, randomDouble = { 0.5 })
        assertEquals(2_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))
        assertEquals(4_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))
        assertEquals(8_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))
        assertEquals(16_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))
        assertEquals(32_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))
        assertEquals(60_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))
        assertEquals(60_000L, exact.nextDelayMs(WorkerExitReason.FFMPEG_ERROR))

        // Min jitter (rand = 0.0 -> 0.8x) and Max jitter (rand = 1.0 -> 1.2x)
        val minJit = Backoff(randomDouble = { 0.0 })
        assertEquals(1_600L, minJit.nextDelayMs(WorkerExitReason.INGEST_UNREACHABLE))
        val maxJit = Backoff(randomDouble = { 1.0 })
        assertEquals(2_400L, maxJit.nextDelayMs(WorkerExitReason.INGEST_UNREACHABLE))
    }

    @Test
    fun `healthy duration of 60s resets backoff`() {
        val backoff = Backoff(randomDouble = { 0.5 })
        assertEquals(2_000L, backoff.nextDelayMs(WorkerExitReason.STALLED))
        assertEquals(4_000L, backoff.nextDelayMs(WorkerExitReason.STALLED))

        backoff.recordHealthyDuration(59_999L)
        assertEquals(8_000L, backoff.nextDelayMs(WorkerExitReason.STALLED))

        backoff.recordHealthyDuration(60_000L)
        assertEquals(2_000L, backoff.nextDelayMs(WorkerExitReason.STALLED))
    }

    @Test
    fun `auth failure spaces retries at least 5 minutes apart`() {
        val minAuth = Backoff(randomDouble = { 0.0 })
        val d = minAuth.nextDelayMs(WorkerExitReason.CAMERA_AUTH_FAILED)
        assertTrue("Expected >= 300000ms, got $d", d >= 300_000L)

        val maxAuth = Backoff(randomDouble = { 1.0 })
        assertEquals(360_000L, maxAuth.nextDelayMs(WorkerExitReason.CAMERA_AUTH_FAILED))
    }
}
