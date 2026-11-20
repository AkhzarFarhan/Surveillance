package com.imou.youtubestreamer.scheduler

import com.imou.youtubestreamer.data.Lens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class RotationSlot(
    val dateStr: String,
    val partIndex: Int,
    val totalParts: Int,
    val nextBoundaryWallMs: Long
) {
    fun titleFor(lens: Lens): String = "$dateStr Part $partIndex of $totalParts${lens.code}"
}

/**
 * Pure calendar/clock computation for YouTube VOD session rotation.
 *
 * Divides every day into 3 consecutive 8-hour blocks by default:
 * - Part 1 of 3: 00:00 – 08:00
 * - Part 2 of 3: 08:00 – 16:00
 * - Part 3 of 3: 16:00 – 24:00
 *
 * When [overridePeriodMinutes] > 0 (Verification / Debug setting), divides the day into
 * equal N-minute parts so rotation boundaries can be tested without waiting 8 hours.
 *
 * Applies a 20-second pre-boundary safety margin ([BOUNDARY_MARGIN_MS]) consistently
 * so boundary detection and next-title selection stay monotonically aligned without double-triggers.
 */
object RotationClock {

    const val BOUNDARY_MARGIN_MS = 20_000L
    const val SEAL_GAP_MS = 15_000L

    fun slotFor(
        wallMs: Long,
        overridePeriodMinutes: Int = 0,
        timeZone: TimeZone = TimeZone.getDefault()
    ): RotationSlot {
        val shiftedWallMs = wallMs + BOUNDARY_MARGIN_MS
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = shiftedWallMs }
        val fmt = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { this.timeZone = timeZone }
        val dateStr = fmt.format(Date(shiftedWallMs))

        if (overridePeriodMinutes > 0) {
            val periodMin = overridePeriodMinutes.coerceIn(2, 720)
            val totalParts = (1440 + periodMin - 1) / periodMin
            val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val blockIdx = (minuteOfDay / periodMin).coerceIn(0, totalParts - 1)
            val partIndex = blockIdx + 1

            val nextStartMinute = (blockIdx + 1) * periodMin
            val boundaryCal = Calendar.getInstance(timeZone).apply {
                timeInMillis = shiftedWallMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (nextStartMinute >= 1440) {
                    add(Calendar.DAY_OF_YEAR, 1)
                } else {
                    add(Calendar.MINUTE, nextStartMinute)
                }
            }
            val nextBoundaryWallMs = boundaryCal.timeInMillis - BOUNDARY_MARGIN_MS
            return RotationSlot(
                dateStr = dateStr,
                partIndex = partIndex,
                totalParts = totalParts,
                nextBoundaryWallMs = nextBoundaryWallMs
            )
        }

        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val partIndex = partFor(hourOfDay)
        val targetEndHour = when (partIndex) {
            1 -> 8
            2 -> 16
            else -> 24
        }

        val boundaryCal = Calendar.getInstance(timeZone).apply {
            timeInMillis = shiftedWallMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (targetEndHour == 24) {
                set(Calendar.HOUR_OF_DAY, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            } else {
                set(Calendar.HOUR_OF_DAY, targetEndHour)
            }
        }
        val nextBoundaryWallMs = boundaryCal.timeInMillis - BOUNDARY_MARGIN_MS

        return RotationSlot(
            dateStr = dateStr,
            partIndex = partIndex,
            totalParts = 3,
            nextBoundaryWallMs = nextBoundaryWallMs
        )
    }

    fun partFor(hourOfDay: Int): Int = when (hourOfDay) {
        in 0..7 -> 1
        in 8..15 -> 2
        else -> 3
    }

    fun remainingToBoundaryMs(wallMs: Long, slot: RotationSlot): Long {
        return (slot.nextBoundaryWallMs - wallMs).coerceAtLeast(0L)
    }
}
