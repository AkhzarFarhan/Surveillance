package com.imou.youtubestreamer.resume

import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GapLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeClock(var now: Long, var elapsed: Long = 0L) : Clock {
        override fun nowMs(): Long = now
        override fun elapsedMs(): Long = elapsed
    }

    @Test
    fun `discards gaps shorter than minGapSec and merges adjacent gaps keeping earliest progress cursor`() {
        val file = File(tempFolder.root, "gap_ledger.json")
        val clock = FakeClock(now = 1_000_000L)
        val ledger = GapLedger(file, clock)

        // Short 20s gap discarded (< 60s)
        ledger.openGap(Lens.FIXED, 900_000L)
        clock.now = 920_000L
        ledger.closeGap(Lens.FIXED, 920_000L, minGapSec = 60)
        assertNull(ledger.nextPendingGap(Lens.FIXED))

        // Two nearby gaps separated by 5s (<= 10s merge proximity)
        ledger.recordOutage(Lens.FIXED, 1_000_000L, 1_600_000L, minGapSec = 60)
        val g1 = ledger.nextPendingGap(Lens.FIXED)!!
        ledger.markInProgress(g1.id)
        ledger.advanceProgress(g1.id, 1_300_000L)

        // Adjacent second gap starts 5s later (1_605_000) -> merges with g1, preserving cursor 1_300_000!
        ledger.recordOutage(Lens.FIXED, 1_605_000L, 2_000_000L, minGapSec = 60)
        val merged = ledger.nextPendingGap(Lens.FIXED)!!
        assertEquals(1_000_000L, merged.startWallMs)
        assertEquals(2_000_000L, merged.endWallMs)
        assertEquals(1_300_000L, merged.doneUpToWallMs)
    }

    @Test
    fun `persists chunk progress across process restart and prunes old gaps beyond SD retention`() {
        val file = File(tempFolder.root, "gap_ledger.json")
        val t0 = 1_700_000_000_000L
        val clock = FakeClock(now = t0 + 1800_000L)

        val ledger1 = GapLedger(file, clock)
        ledger1.recordOutage(Lens.ROTATABLE, t0, t0 + 1200_000L, minGapSec = 60)
        val gapId = ledger1.nextPendingGap(Lens.ROTATABLE)!!.id
        ledger1.markInProgress(gapId)
        ledger1.advanceProgress(gapId, t0 + 600_000L)

        // Simulate process restart by constructing a new GapLedger from same file
        val ledger2 = GapLedger(file, clock)
        val resumed = ledger2.nextPendingGap(Lens.ROTATABLE)
        assertNotNull(resumed)
        assertEquals(GapStatus.PENDING, resumed!!.status) // recovered IN_PROGRESS -> PENDING
        assertEquals(t0 + 600_000L, resumed.doneUpToWallMs)

        // Prune after 73 hours (> 72h retention) -> abandoned
        ledger2.pruneExpired(t0 + 73L * 3600_000L, sdRetentionHours = 72)
        assertNull(ledger2.nextPendingGap(Lens.ROTATABLE))
        assertTrue(ledger2.allGapsSnapshot().any { it.id == gapId && it.status == GapStatus.ABANDONED })
    }
}
