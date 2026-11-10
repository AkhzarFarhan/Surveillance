package com.imou.youtubestreamer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.service.CctvStreamingService
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SurveillanceApp : Application() {

    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        AppLogger.i("SurveillanceApp", "Application initialized.")
        replayPreviousCrashIfAny()
        installCrashRecorder()
        createNotificationChannels()
    }

    private fun replayPreviousCrashIfAny() {
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                val trace = crashFile.readText().trim()
                if (trace.isNotEmpty()) {
                    AppLogger.e("LastCrash", trace)
                }
                crashFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun installCrashRecorder() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText(
                    "Thread: ${thread.name}\n${sw.toString().take(3500)}"
                )
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CctvStreamingService.NOTIFICATION_CHANNEL_ID,
                getString(R.string.cctv_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.cctv_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
