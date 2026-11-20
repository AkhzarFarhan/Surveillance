package com.imou.youtubestreamer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.service.CctvStreamingService
import com.imou.youtubestreamer.supervisor.SupervisorStore
import com.imou.youtubestreamer.watchdog.ServiceWatchdogScheduler

/**
 * Automatically restores `CctvStreamingService` on device boot (`BOOT_COMPLETED` / `QUICKBOOT_POWERON`)
 * and immediately after an in-app self-update or ADB APK install (`MY_PACKAGE_REPLACED`).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val store = SupervisorStore(context)
            val settings = AppSettings(context)

            // Upgrade migration: initialize desiredRunning from legacy autoStartOnBoot on first run
            if (!store.hasDesiredRunningKey()) {
                store.desiredRunning = settings.autoStartOnBoot
            }

            val shouldRun = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                store.desiredRunning
            } else {
                store.desiredRunning && settings.autoStartOnBoot
            }

            if (shouldRun) {
                if (!store.desiredRunning) {
                    store.desiredRunning = true
                }
                AppLogger.i("BootReceiver", "Trigger '$action' restoring 24/7 CCTV Streaming Service...")
                ServiceWatchdogScheduler.scheduleAll(context)
                CctvStreamingService.start(context, explicit = false)
            } else {
                AppLogger.i("BootReceiver", "Trigger '$action' ignored (desiredRunning=${store.desiredRunning}, autoStartOnBoot=${settings.autoStartOnBoot}).")
            }
        }
    }
}
