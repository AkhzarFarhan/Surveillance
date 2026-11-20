package com.imou.youtubestreamer.supervisor

import com.imou.youtubestreamer.data.WorkerExitReason
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Exponential backoff calculator with jitter and auth-failure cooldown.
 *
 * - Base 2 s, multiplier 2x, cap 60 s, ±20% jitter
 * - Resets to attempt 0 after >= 60 s of healthy streaming
 * - Forces >= 5 min delay on CAMERA_AUTH_FAILED (401) so Dahua/Imou accounts don't lock out
 */
class Backoff(
    private val baseMs: Long = 2_000L,
    private val factor: Double = 2.0,
    private val capMs: Long = 60_000L,
    private val jitterFraction: Double = 0.20,
    private val healthyResetMs: Long = 60_000L,
    private val authFailureDelayMs: Long = 300_000L,
    private val randomDouble: () -> Double = { Math.random() }
) {
    private var failures: Int = 0

    @Synchronized
    fun nextDelayMs(reason: WorkerExitReason): Long {
        if (reason == WorkerExitReason.CAMERA_AUTH_FAILED) {
            failures++
            return applyJitter(authFailureDelayMs, minFloorMs = authFailureDelayMs)
        }
        val exponent = failures.coerceAtMost(10)
        failures++
        val raw = (baseMs * factor.pow(exponent.toDouble())).roundToLong().coerceAtMost(capMs)
        return applyJitter(raw, minFloorMs = (baseMs * (1.0 - jitterFraction)).roundToLong())
    }

    @Synchronized
    fun recordHealthyDuration(durationMs: Long) {
        if (durationMs >= healthyResetMs) {
            failures = 0
        }
    }

    @Synchronized
    fun reset() {
        failures = 0
    }

    private fun applyJitter(delayMs: Long, minFloorMs: Long): Long {
        val rand = randomDouble().coerceIn(0.0, 1.0)
        val multiplier = (1.0 - jitterFraction) + (2.0 * jitterFraction * rand)
        return (delayMs * multiplier).roundToLong().coerceAtLeast(minFloorMs)
    }
}
