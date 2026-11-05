package com.imou.youtubestreamer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.service.CctvStreamingService

class SurveillanceApp : Application() {

    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        AppLogger.i("SurveillanceApp", "Application initialized.")
        createNotificationChannels()
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
