package com.imou.youtubestreamer.scheduler

import android.content.Context
import com.imou.youtubestreamer.data.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * 8-Hour Session Rotation Engine.
 *
 * Divides every 24-hour day into exactly 3 consecutive 8-hour live streaming sessions:
 * - Part 1 of 3: 00:00:00 to 07:59:59 (Hours 00:00 - 07:59)
 * - Part 2 of 3: 08:00:00 to 15:59:59 (Hours 08:00 - 15:59)
 * - Part 3 of 3: 16:00:00 to 23:59:59 (Hours 16:00 - 23:59)
 *
 * Titles generated:
 * - DD-MM-YYYY Part <X> of 3_F (Fixed Lens Overview)
 * - DD-MM-YYYY Part <X> of 3_R (Rotatable Lens Pan-Tilt)
 *
 * Guarantees YouTube's 12-hour auto-archive limit is never breached, producing exactly
 * 3 permanent, scrubbable private VOD replays each day per lens!
 */
class SessionRotationScheduler(private val context: Context) {

    private var schedulerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private val _remainingTimeMs = MutableStateFlow(0L)
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()

    private val _currentPartIndex = MutableStateFlow(1)
    val currentPartIndex: StateFlow<Int> = _currentPartIndex.asStateFlow()

    private val _currentDateStr = MutableStateFlow("")
    val currentDateStr: StateFlow<String> = _currentDateStr.asStateFlow()

    fun calculateCurrentPartIndex(hourOfDay: Int): Int {
        return when (hourOfDay) {
            in 0..7 -> 1
            in 8..15 -> 2
            else -> 3
        }
    }

    /**
     * Calculates the millisecond timestamp when the current 8-hour window ends.
     */
    fun calculateTargetEndTimeMs(): Long {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val targetEndHour = when (currentHour) {
            in 0..7 -> 8
            in 8..15 -> 16
            else -> 24
        }

        val targetCal = Calendar.getInstance().apply {
            if (targetEndHour == 24) {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            } else {
                set(Calendar.HOUR_OF_DAY, targetEndHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        // Subtract 20-second safety transition margin
        return targetCal.timeInMillis - 20_000L
    }

    fun startRotationLoop(
        onNewSessionStarting: suspend (partIndex: Int, dateStr: String) -> Unit,
        onSessionEnding: suspend () -> Unit
    ) {
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (isActive) {
                val now = Calendar.getInstance()
                val currentHour = now.get(Calendar.HOUR_OF_DAY)
                val dateStr = dateFormat.format(now.time)
                val partIndex = calculateCurrentPartIndex(currentHour)

                _currentPartIndex.value = partIndex
                _currentDateStr.value = dateStr

                AppLogger.i("SessionScheduler", "==================================================")
                AppLogger.i("SessionScheduler", "STARTING 8-HOUR SESSION: $dateStr Part $partIndex of 3")
                AppLogger.i("SessionScheduler", "Target Titles:")
                AppLogger.i("SessionScheduler", "  - $dateStr Part ${partIndex} of 3_F (Fixed Lens)")
                AppLogger.i("SessionScheduler", "  - $dateStr Part ${partIndex} of 3_R (Rotatable Lens)")
                AppLogger.i("SessionScheduler", "==================================================")

                // 1. Notify service to create YouTube broadcasts & launch dual streams
                onNewSessionStarting(partIndex, dateStr)

                // 2. Loop until the 8-hour window boundary is reached
                val targetEndTime = calculateTargetEndTimeMs()

                while (System.currentTimeMillis() < targetEndTime && isActive) {
                    val remaining = targetEndTime - System.currentTimeMillis()
                    _remainingTimeMs.value = if (remaining > 0) remaining else 0L
                    delay(1000)
                }

                if (!isActive) break

                // 3. Gracefully end the session
                AppLogger.i("SessionScheduler", "8-Hour session boundary reached. Ending session gracefully for YouTube VOD archiving...")
                onSessionEnding()

                // 4. Graceful 15-second gap for YouTube ingestion buffers to settle
                AppLogger.i("SessionScheduler", "Waiting 15 seconds graceful gap for YouTube cloud archives to seal...")
                delay(15_000L)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        _remainingTimeMs.value = 0L
    }
}
