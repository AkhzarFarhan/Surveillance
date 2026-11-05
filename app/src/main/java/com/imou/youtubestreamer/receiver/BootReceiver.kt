package com.imou.youtubestreamer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.service.CctvStreamingService

/**
 * Automatically restarts CctvStreamingService when the Android phone reboots or powers on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val settings = AppSettings(context)
            if (settings.autoStartOnBoot) {
                AppLogger.i("BootReceiver", "Boot completed detected! Auto-starting 24/7 CCTV Streaming Service...")
                CctvStreamingService.start(context)
            } else {
                AppLogger.i("BootReceiver", "Boot completed detected, but autoStartOnBoot is disabled in settings.")
            }
        }
    }
}
