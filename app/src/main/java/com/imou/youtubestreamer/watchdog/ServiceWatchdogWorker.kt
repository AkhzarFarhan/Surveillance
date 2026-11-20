package com.imou.youtubestreamer.watchdog

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.imou.youtubestreamer.data.AppLogger

/**
 * WorkManager periodic fallback watchdog (every 15 minutes).
 * Survives cases where OEM ROM task cleaners purge AlarmManager alarms.
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        AppLogger.step("WatchdogWorker", "doWork", "WorkManager 15-min check running")
        ServiceWatchdogScheduler.evaluateAndHeal(applicationContext, "WorkManager")
        return Result.success()
    }
}
