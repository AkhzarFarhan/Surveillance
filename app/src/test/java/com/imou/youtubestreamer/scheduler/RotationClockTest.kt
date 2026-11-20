package com.imou.youtubestreamer.scheduler

import com.imou.youtubestreamer.data.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RotationClockTest {

    private fun wallAt(hour: Int, min: Int, sec: Int, tz: TimeZone = TimeZone.getTimeZone("UTC")): Long {
        return Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 20, hour, min, sec)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `standard 8h slots map hours cleanly with 20s pre-boundary transition`() {
        val tz = TimeZone.getTimeZone("UTC")
        // 07:59:39 -> still Part 1, boundary in 1s (07:59:40)
        val beforeBoundary = wallAt(7, 59, 39, tz)
        val slot1 = RotationClock.slotFor(beforeBoundary, 0, tz)
        assertEquals(1, slot1.partIndex)
        assertEquals(3, slot1.totalParts)
        assertEquals("20-11-2026 Part 1 of 3_F", slot1.titleFor(Lens.FIXED))
        assertEquals(1_000L, RotationClock.remainingToBoundaryMs(beforeBoundary, slot1))

        // At 07:59:40 -> advances monotonically to Part 2 (prevents double-trigger!)
        val atBoundary = wallAt(7, 59, 40, tz)
        val slot2 = RotationClock.slotFor(atBoundary, 0, tz)
        assertEquals(2, slot2.partIndex)
        assertEquals("20-11-2026 Part 2 of 3_R", slot2.titleFor(Lens.ROTATABLE))
        assertTrue(RotationClock.remainingToBoundaryMs(atBoundary, slot2) > 7L * 3600_000L)

        // Day rollover 23:59:45 -> moves to next day's Part 1
        val rollover = wallAt(23, 59, 45, tz)
        val nextDaySlot = RotationClock.slotFor(rollover, 0, tz)
        assertEquals("21-11-2026", nextDaySlot.dateStr)
        assertEquals(1, nextDaySlot.partIndex)
    }

    @Test
    fun `debug override period splits day into N-minute blocks`() {
        val tz = TimeZone.getTimeZone("UTC")
        val t1 = wallAt(10, 5, 0, tz) // minute 605 -> block 60 -> Part 61 of 144
        val slot1 = RotationClock.slotFor(t1, overridePeriodMinutes = 10, timeZone = tz)
        assertEquals(144, slot1.totalParts)
        assertEquals(61, slot1.partIndex)
        val rem = RotationClock.remainingToBoundaryMs(t1, slot1)
        assertEquals(4 * 60_000L + 40_000L, rem)
    }
}
