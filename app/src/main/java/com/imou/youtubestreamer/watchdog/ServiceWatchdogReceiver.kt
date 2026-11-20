package com.imou.youtubestreamer.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.SystemClockImpl
import com.imou.youtubestreamer.resume.Heartbeat
import com.imou.youtubestreamer.service.CctvStreamingService
import com.imou.youtubestreamer.supervisor.SupervisorStore
import java.util.concurrent.TimeUnit

object ServiceWatchdogScheduler {
    private const val TAG = "WatchdogSched"
    private const val WORK_NAME = "surveillance_process_watchdog"
    private const val ALARM_REQUEST_CODE = 4201
    const val DEFAULT_INTERVAL_MS = 15L * 60_000L
    const val CRASH_LOOP_BACKOFF_MS = 5L * 60_000L

    fun scheduleAll(context: Context, alarmDelayMs: Long = DEFAULT_INTERVAL_MS) {
        val appCtx = context.applicationContext
        scheduleAlarm(appCtx, alarmDelayMs)
        schedulePeriodicWork(appCtx)
    }

    fun scheduleAlarm(context: Context, delayMs: Long = DEFAULT_INTERVAL_MS) {
        try {
            val appCtx = context.applicationContext
            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = buildAlarmPendingIntent(appCtx)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(5_000L)
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            AppLogger.step(TAG, "scheduleAlarm", "Armed AlarmManager watchdog in ${delayMs / 1000}s")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "scheduleAlarm", t)
        }
    }

    fun schedulePeriodicWork(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val workReq = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workReq
            )
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "schedulePeriodicWork", t)
        }
    }

    fun cancelAll(context: Context) {
        val appCtx = context.applicationContext
        try {
            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.cancel(buildAlarmPendingIntent(appCtx))
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "cancelAll:alarm", t)
        }
        try {
            WorkManager.getInstance(appCtx).cancelUniqueWork(WORK_NAME)
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "cancelAll:work", t)
        }
    }

    fun evaluateAndHeal(
        context: Context,
        source: String,
        clock: Clock = SystemClockImpl
    ) {
        val appCtx = context.applicationContext
        val store = SupervisorStore(appCtx)
        if (!store.desiredRunning) {
            AppLogger.step(TAG, "evaluateAndHeal[$source]", "desiredRunning=false; skipping restart")
            return
        }

        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(appCtx.packageName) ?: false
        } else {
            true
        }
        if (!exempt) {
            AppLogger.w(
                TAG,
                "Battery optimization is NOT ignored for Surveillance — Android may block background FGS restart after OEM kill."
            )
        }

        val nowWall = clock.nowMs()
        if (store.isCrashLoopActive(nowWall)) {
            AppLogger.e(
                TAG,
                "Crash loop detected (>5 crashes in 10m); delaying watchdog restart by 5 minutes ($source)."
            )
            scheduleAlarm(appCtx, CRASH_LOOP_BACKOFF_MS)
            return
        }

        // Re-arm next 15-min check
        scheduleAlarm(appCtx, DEFAULT_INTERVAL_MS)

        val heartbeat = Heartbeat(store, clock)
        val needsHeal = !CctvStreamingService.isRunning || heartbeat.isStale(60_000L)
        if (needsHeal) {
            AppLogger.i(
                TAG,
                "Self-heal triggered by $source (isRunning=${CctvStreamingService.isRunning}, heartbeatStale=${heartbeat.isStale(60_000L)})"
            )
            CctvStreamingService.start(appCtx, explicit = false)
        } else {
            AppLogger.step(TAG, "evaluateAndHeal[$source]", "Service & heartbeat healthy; no action needed")
        }
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ServiceWatchdogReceiver::class.java).apply {
            action = ACTION_WATCHDOG_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION_WATCHDOG_ALARM = "com.imou.youtubestreamer.WATCHDOG_ALARM"
}

class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.enter("WatchdogReceiver", "onReceive", "action=${intent.action}")
        ServiceWatchdogScheduler.evaluateAndHeal(context, "AlarmManager")
    }
}
