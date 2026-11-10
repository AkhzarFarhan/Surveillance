package com.imou.youtubestreamer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.arthenica.ffmpegkit.AbiDetect
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Packages
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
        AppLogger.init(this)
        AppLogger.enter("SurveillanceApp", "onCreate", "SDK=${Build.VERSION.SDK_INT}, Model=${Build.MANUFACTURER} ${Build.MODEL}, ABIs=${Build.SUPPORTED_ABIS.joinToString()}")

        try {
            settings = AppSettings(this)
            AppLogger.step(
                "SurveillanceApp",
                "onCreate",
                "Settings loaded: cameraIp=${settings.cameraIp}, rtspPort=${settings.rtspPort}, httpPort=${settings.httpPort}, user=${settings.username}, pass=${AppLogger.maskSecret(settings.safetyCode)}, fixedKey=${AppLogger.maskSecret(settings.manualRtmpFixed)}, rotKey=${AppLogger.maskSecret(settings.manualRtmpRotatable)}"
            )
        } catch (t: Throwable) {
            AppLogger.exception("SurveillanceApp", "onCreate:AppSettings", t)
        }

        replayPreviousCrashIfAny()
        installCrashRecorder()
        createNotificationChannels()
        verifyFFmpegKitLinkage()

        AppLogger.exit("SurveillanceApp", "onCreate", "Application initialized")
    }

    private fun verifyFFmpegKitLinkage() {
        AppLogger.enter("SurveillanceApp", "verifyFFmpegKitLinkage")
        try {
            val version = FFmpegKitConfig.getVersion()
            val ffmpegVer = FFmpegKitConfig.getFFmpegVersion()
            val abi = AbiDetect.getAbi()
            val pkg = Packages.getPackageName()
            AppLogger.i(
                "SurveillanceApp",
                "FFmpegKit native engine ready: version=$version, ffmpeg=$ffmpegVer, abi=$abi, package=$pkg"
            )
            AppLogger.exit("SurveillanceApp", "verifyFFmpegKitLinkage", "OK ($abi)")
        } catch (t: Throwable) {
            AppLogger.exception("SurveillanceApp", "verifyFFmpegKitLinkage", t, "FFmpegKit native load failed")
        }
    }

    private fun replayPreviousCrashIfAny() {
        AppLogger.enter("SurveillanceApp", "replayPreviousCrashIfAny")
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                val trace = crashFile.readText().trim()
                if (trace.isNotEmpty()) {
                    AppLogger.e("LastCrash", "🚨 PREVIOUS PROCESS CRASH DETECTED:\n$trace")
                }
                crashFile.delete()
                AppLogger.exit("SurveillanceApp", "replayPreviousCrashIfAny", "Crash log replayed & cleared")
            } else {
                AppLogger.exit("SurveillanceApp", "replayPreviousCrashIfAny", "No previous crash file")
            }
        } catch (t: Throwable) {
            AppLogger.exception("SurveillanceApp", "replayPreviousCrashIfAny", t)
        }
    }

    private fun installCrashRecorder() {
        AppLogger.enter("SurveillanceApp", "installCrashRecorder")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.exception("UncaughtCrash", "uncaughtException", throwable, "thread=${thread.name}")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText(
                    "Thread: ${thread.name} (SDK ${Build.VERSION.SDK_INT})\n${sw.toString().take(4000)}"
                )
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        AppLogger.exit("SurveillanceApp", "installCrashRecorder", "Installed")
    }

    private fun createNotificationChannels() {
        AppLogger.enter("SurveillanceApp", "createNotificationChannels")
        try {
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
                manager?.createNotificationChannel(channel)
            }
            AppLogger.exit("SurveillanceApp", "createNotificationChannels", "Channel ready")
        } catch (t: Throwable) {
            AppLogger.exception("SurveillanceApp", "createNotificationChannels", t)
        }
    }
}
