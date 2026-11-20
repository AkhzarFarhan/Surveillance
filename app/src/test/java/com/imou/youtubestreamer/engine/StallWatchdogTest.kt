package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StallWatchdogTest {

    @Test
    fun `detects frozen frames after startup grace without false-triggering when healthy`() = runTest {
        val fakeClock = object : Clock {
            override fun nowMs(): Long = testScheduler.currentTime
            override fun elapsedMs(): Long = testScheduler.currentTime
        }

        var lastFrameEl = 0L
        var lastBytesEl = 0L
        var stalledReason: String? = null

        val watchJob = launch {
            StallWatchdog.watchForStall(
                clock = fakeClock,
                startedAtElapsedMs = 0L,
                lastFrameProgressElapsedMs = { lastFrameEl },
                lastBytesProgressElapsedMs = { lastBytesEl },
                startupGraceMs = 25_000L,
                stallThresholdMs = 20_000L,
                checkIntervalMs = 5_000L,
                onStall = { stalledReason = it }
            )
        }

        // Advance 35s while frames & bytes keep advancing every 4s
        val tickerJob = launch {
            repeat(9) {
                delay(4_000L)
                lastFrameEl = fakeClock.elapsedMs()
                lastBytesEl = fakeClock.elapsedMs()
            }
        }
        advanceTimeBy(36_000L)
        runCurrent()
        assertFalse("Should not stall while progress advances", stalledReason != null)
        tickerJob.cancel()

        // Freeze bytes/frames for 25s (past 20s threshold)
        advanceTimeBy(25_000L)
        runCurrent()
        assertTrue("Expected stall notification when progress freezes", stalledReason?.contains("STALLED") == true)
        watchJob.cancel()
    }
}
