package com.imou.youtubestreamer.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.imou.youtubestreamer.MainActivity
import com.imou.youtubestreamer.data.*
import com.imou.youtubestreamer.engine.DualStreamOrchestrator
import com.imou.youtubestreamer.scheduler.SessionRotationScheduler
import com.imou.youtubestreamer.sync.DahuaSdCardSyncManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 24/7 Persistent Android Foreground Service.
 * Manages the entire surveillance streaming pipeline:
 * - Doze-mode bypass (WakeLock & WifiLock)
 * - Network outage detection & SD-card backlog catch-up
 * - 8-hour session rotation cycler with private YouTube live broadcasts
 * - Dual FFmpeg stream workers for Fixed and Rotatable camera lenses
 */
class CctvStreamingService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var syncManager: DahuaSdCardSyncManager
    private lateinit var rotationScheduler: SessionRotationScheduler
    private lateinit var streamOrchestrator: DualStreamOrchestrator

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var currentFixedRtmp: String = ""
    private var currentRotatableRtmp: String = ""

    private var isWifiConnected = false
    private var wifiDisconnectTimestamp: Long = 0L

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.exception("CctvService", "serviceScope.CoroutineExceptionHandler", throwable)
        _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    companion object {
        private const val TAG = "CctvService"
        const val NOTIFICATION_CHANNEL_ID = "cctv_streaming_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_STREAMING"
        const val ACTION_STOP = "ACTION_STOP_STREAMING"

        private val _systemState = MutableStateFlow(SystemStreamState())
        val systemState: StateFlow<SystemStreamState> = _systemState.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            AppLogger.enter(TAG, "Companion.start", "isRunning=$isRunning, SDK=${Build.VERSION.SDK_INT}")
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CctvStreamingService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AppLogger.step(TAG, "Companion.start", "Calling startForegroundService(ACTION_START)")
                    appCtx.startForegroundService(intent)
                } else {
                    AppLogger.step(TAG, "Companion.start", "Calling startService(ACTION_START)")
                    appCtx.startService(intent)
                }
                AppLogger.exit(TAG, "Companion.start", "Service start requested")
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "Companion.start", t)
                _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
            }
        }

        fun stop(context: Context) {
            AppLogger.enter(TAG, "Companion.stop", "isRunning=$isRunning")
            try {
                val appCtx = context.applicationContext
                if (isRunning) {
                    val intent = Intent(appCtx, CctvStreamingService::class.java).apply {
                        action = ACTION_STOP
                    }
                    appCtx.startService(intent)
                    AppLogger.exit(TAG, "Companion.stop", "Sent ACTION_STOP to running service")
                } else {
                    appCtx.stopService(Intent(appCtx, CctvStreamingService::class.java))
                    _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
                    AppLogger.exit(TAG, "Companion.stop", "Service already not running; called stopService")
                }
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "Companion.stop", t)
                try {
                    context.applicationContext.stopService(Intent(context.applicationContext, CctvStreamingService::class.java))
                } catch (_: Throwable) {
                }
                isRunning = false
                _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
            }
        }
    }

    override fun onCreate() {
        AppLogger.enter(TAG, "onCreate")
        super.onCreate()
        try {
            settings = AppSettings(applicationContext)
            syncManager = DahuaSdCardSyncManager(applicationContext, settings)
            rotationScheduler = SessionRotationScheduler(applicationContext)
            streamOrchestrator = DualStreamOrchestrator(applicationContext, settings)
            AppLogger.step(TAG, "onCreate", "Sub-components initialized (AppSettings, SyncManager, RotationScheduler, DualOrchestrator)")

            createNotificationChannel()
            promoteToForeground(
                "Initializing Surveillance Streamer...",
                "Preparing camera & YouTube connection"
            )
            acquirePowerLocks()
            registerNetworkMonitoring()
            observeOrchestratorStates()

            isRunning = true
            AppLogger.i(TAG, "CctvStreamingService created and running in foreground.")
            AppLogger.exit(TAG, "onCreate", "isRunning=$isRunning")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "onCreate", t)
            _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
        }
    }

    private fun promoteToForeground(title: String, text: String) {
        AppLogger.enter(TAG, "promoteToForeground", "title='$title', text='$text', SDK=${Build.VERSION.SDK_INT}")
        try {
            val notification = buildNotification(title, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                AppLogger.exit(TAG, "promoteToForeground", "Started foreground with FOREGROUND_SERVICE_TYPE_SPECIAL_USE")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                AppLogger.exit(TAG, "promoteToForeground", "Started foreground (2-arg)")
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "promoteToForeground", t, "Fallback to 2-arg startForeground")
            try {
                startForeground(NOTIFICATION_ID, buildNotification(title, text))
            } catch (t2: Throwable) {
                AppLogger.exception(TAG, "promoteToForeground:fallback", t2)
            }
        }
    }

    override fun onTimeout(startId: Int) {
        AppLogger.unexpected(TAG, "onTimeout(startId=$startId)", "OS signaled foreground service timeout; stopping gracefully and scheduling restart")
        handleFgsTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.unexpected(TAG, "onTimeout(startId=$startId, fgsType=$fgsType)", "OS signaled foreground service timeout; stopping gracefully and scheduling restart")
        handleFgsTimeout(startId)
    }

    private fun handleFgsTimeout(startId: Int) {
        try {
            val shouldRestart = isRunning
            val appCtx = applicationContext
            if (::rotationScheduler.isInitialized) rotationScheduler.stop()
            if (::streamOrchestrator.isInitialized) streamOrchestrator.stopLiveStreamsGracefully()
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            if (shouldRestart) {
                Handler(Looper.getMainLooper()).postDelayed({
                    AppLogger.i(TAG, "Re-launching CctvStreamingService after OS timeout cycle...")
                    start(appCtx)
                }, 2000L)
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "handleFgsTimeout", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "<NULL_ACTION>"
        AppLogger.enter(TAG, "onStartCommand", "action=$action, flags=$flags, startId=$startId, isRunning=$isRunning")
        super.onStartCommand(intent, flags, startId)

        try {
            when (intent?.action) {
                ACTION_START, null -> {
                    promoteToForeground(
                        "Surveillance Pipeline Active",
                        "Starting camera RTSP -> YouTube RTMPS engine..."
                    )
                    startPipeline()
                }
                ACTION_STOP -> {
                    stopPipeline()
                }
                else -> {
                    AppLogger.unexpected(TAG, "onStartCommand", "Unrecognized intent action", action)
                }
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "onStartCommand", t, "action=$action")
            _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
        }

        AppLogger.exit(TAG, "onStartCommand", "START_STICKY")
        return START_STICKY
    }

    private fun startPipeline() {
        AppLogger.enter(TAG, "startPipeline")
        serviceScope.launch {
            AppLogger.enter(TAG, "startPipeline.coroutine")
            try {
                _systemState.value = _systemState.value.copy(status = PipelineStatus.INITIALIZING)
                updateNotification("Initializing...", "Validating YouTube Stream Key & Camera Settings...")

                val fixedRtmp = settings.getFormattedFixedRtmp()
                val rotatableRtmp = settings.getFormattedRotatableRtmp()

                AppLogger.step(
                    TAG,
                    "startPipeline",
                    "Camera=${settings.cameraIp}:${settings.rtspPort} (HTTP:${settings.httpPort}), user='${settings.username}', pass=${AppLogger.maskSecret(settings.safetyCode)}, fixedRtmp=${AppLogger.maskSecret(fixedRtmp)}, rotatableRtmp=${AppLogger.maskSecret(rotatableRtmp)}"
                )

                if (fixedRtmp.isBlank() && rotatableRtmp.isBlank()) {
                    AppLogger.unexpected(TAG, "startPipeline", "Both Fixed and Rotatable RTMP keys are blank!")
                    _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
                    updateNotification("Stream Key Missing", "Enter your YouTube Stream Key in the app.")
                    AppLogger.w(TAG, "Cannot start streaming: YouTube Stream Key is not configured.")
                    AppLogger.exit(TAG, "startPipeline.coroutine", "ABORTED_NO_STREAM_KEY")
                    return@launch
                }

                if (settings.safetyCode.isBlank()) {
                    AppLogger.unexpected(TAG, "startPipeline", "Camera password/safetyCode is blank! RTSP authentication will likely fail.")
                }

                _systemState.value = _systemState.value.copy(status = PipelineStatus.STREAMING)
                AppLogger.i(TAG, "YouTube Stream Key validated. Starting 24/7 session rotation loop...")

                rotationScheduler.startRotationLoop(
                    onNewSessionStarting = { partIndex, dateStr ->
                        handleNewSession(partIndex, dateStr)
                    },
                    onSessionEnding = {
                        handleSessionEnding()
                    }
                )
                AppLogger.exit(TAG, "startPipeline.coroutine", "Rotation loop launched")
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "startPipeline.coroutine", t)
                _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
                updateNotification("Pipeline Error", t.localizedMessage ?: "Unexpected error starting stream")
            }
        }
        AppLogger.exit(TAG, "startPipeline", "Dispatched to serviceScope")
    }

    private suspend fun handleNewSession(partIndex: Int, dateStr: String) {
        AppLogger.enter(TAG, "handleNewSession", "partIndex=$partIndex, dateStr='$dateStr'")
        try {
            val titleFixed = "$dateStr Part ${partIndex} of 3_F"
            val titleRotatable = "$dateStr Part ${partIndex} of 3_R"

            _systemState.value = _systemState.value.copy(
                status = PipelineStatus.STREAMING,
                activePartIndex = partIndex,
                activeDateStr = dateStr
            )

            updateNotification(
                "Streaming 8h Block (Part $partIndex of 3)",
                "Active: $titleFixed & _R"
            )

            currentFixedRtmp = settings.getFormattedFixedRtmp()
            currentRotatableRtmp = settings.getFormattedRotatableRtmp()

            AppLogger.i(
                TAG,
                "Starting 8h session $partIndex/3 via YouTube Stream Key: Fixed=${if (currentFixedRtmp.isNotBlank()) "Configured" else "Disabled"}, Rotatable=${if (currentRotatableRtmp.isNotBlank()) "Configured" else "Disabled"}"
            )

            streamOrchestrator.startDualStreaming(
                fixedTargetRtmp = currentFixedRtmp,
                rotatableTargetRtmp = currentRotatableRtmp,
                fixedTitle = titleFixed,
                rotatableTitle = titleRotatable,
                fixedWatchUrl = "",
                rotatableWatchUrl = ""
            )
            AppLogger.exit(TAG, "handleNewSession", "Dual streaming triggered for Part $partIndex")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "handleNewSession", t, "partIndex=$partIndex, dateStr=$dateStr")
        }
    }

    private suspend fun handleSessionEnding() {
        AppLogger.enter(TAG, "handleSessionEnding")
        try {
            _systemState.value = _systemState.value.copy(status = PipelineStatus.ROTATING)
            updateNotification("Rotating Session", "Finalizing 8-hour archive. 15s break...")
            AppLogger.i(TAG, "8-Hour session complete. Disconnecting RTMP for 15s graceful break so YouTube finalizes the VOD replay...")

            streamOrchestrator.stopLiveStreamsGracefully()
            AppLogger.exit(TAG, "handleSessionEnding", "Streams stopped gracefully")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "handleSessionEnding", t)
        }
    }

    private fun registerNetworkMonitoring() {
        AppLogger.enter(TAG, "registerNetworkMonitoring")
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                AppLogger.unexpected(TAG, "registerNetworkMonitoring", "ConnectivityManager is null!")
                return
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLogger.enter(TAG, "NetworkCallback.onAvailable", "network=$network, wasWifiConnected=$isWifiConnected, disconnectTs=$wifiDisconnectTimestamp")
                    try {
                        val wasDisconnected = !isWifiConnected && wifiDisconnectTimestamp > 0L
                        isWifiConnected = true

                        _systemState.value = _systemState.value.copy(
                            isWifiConnected = true,
                            status = if (wasDisconnected) PipelineStatus.CATCHING_UP else _systemState.value.status
                        )

                        if (wasDisconnected) {
                            val reconnectTimestamp = System.currentTimeMillis()
                            val offlineDurationMs = reconnectTimestamp - wifiDisconnectTimestamp
                            wifiDisconnectTimestamp = 0L

                            serviceScope.launch {
                                try {
                                    val minutes = offlineDurationMs / 60000
                                    AppLogger.i(TAG, "Wi-Fi restored! Outage was $minutes minutes. Commencing SD card catch-up...")
                                    updateNotification(
                                        "Wi-Fi Restored: Syncing SD Card",
                                        "Catching up $minutes mins of offline footage at ${settings.catchupSpeed}x speed..."
                                    )

                                    syncManager.syncMissingFootageTillNow(
                                        startTimeMs = reconnectTimestamp - offlineDurationMs,
                                        endTimeMs = reconnectTimestamp,
                                        rtmpTargetFixed = currentFixedRtmp,
                                        rtmpTargetRotatable = currentRotatableRtmp
                                    )

                                    AppLogger.i(TAG, "Catch-up completed. Resuming regular live stream.")
                                    val currentPart = _systemState.value.activePartIndex
                                    val currentDate = _systemState.value.activeDateStr
                                    handleNewSession(currentPart, currentDate)
                                } catch (t: Throwable) {
                                    AppLogger.exception(TAG, "NetworkCallback.onAvailable.catchup", t)
                                }
                            }
                        } else {
                            AppLogger.i(TAG, "Wi-Fi network connected ($network).")
                        }
                        AppLogger.exit(TAG, "NetworkCallback.onAvailable", "wasDisconnected=$wasDisconnected")
                    } catch (t: Throwable) {
                        AppLogger.exception(TAG, "NetworkCallback.onAvailable", t)
                    }
                }

                override fun onLost(network: Network) {
                    AppLogger.enter(TAG, "NetworkCallback.onLost", "network=$network")
                    try {
                        isWifiConnected = false
                        wifiDisconnectTimestamp = System.currentTimeMillis()
                        settings.lastOutageTimestamp = wifiDisconnectTimestamp

                        _systemState.value = _systemState.value.copy(
                            isWifiConnected = false,
                            status = PipelineStatus.OUTAGE_RECORDING
                        )

                        AppLogger.w(TAG, "Home Wi-Fi lost! Pausing live streams. CCTV is recording to SD card.")
                        updateNotification(
                            "Home Wi-Fi Disconnected",
                            "CCTV recording safely to MicroSD card. Pausing stream..."
                        )
                        streamOrchestrator.pauseLiveStreams()
                        AppLogger.exit(TAG, "NetworkCallback.onLost", "Paused streams at ts=$wifiDisconnectTimestamp")
                    } catch (t: Throwable) {
                        AppLogger.exception(TAG, "NetworkCallback.onLost", t)
                    }
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            AppLogger.exit(TAG, "registerNetworkMonitoring", "Registered WIFI NetworkCallback")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "registerNetworkMonitoring", t)
        }
    }

    private fun observeOrchestratorStates() {
        AppLogger.enter(TAG, "observeOrchestratorStates")
        serviceScope.launch {
            streamOrchestrator.fixedState.collect { fixed ->
                _systemState.value = _systemState.value.copy(fixedLens = fixed)
            }
        }
        serviceScope.launch {
            streamOrchestrator.rotatableState.collect { rot ->
                _systemState.value = _systemState.value.copy(rotatableLens = rot)
            }
        }
        serviceScope.launch {
            rotationScheduler.remainingTimeMs.collect { remMs ->
                _systemState.value = _systemState.value.copy(nextRotationRemainingMs = remMs)
            }
        }
        serviceScope.launch {
            syncManager.catchupState.collect { catchup ->
                _systemState.value = _systemState.value.copy(catchup = catchup)
            }
        }
        AppLogger.exit(TAG, "observeOrchestratorStates", "State collectors active")
    }

    private fun acquirePowerLocks() {
        AppLogger.enter(TAG, "acquirePowerLocks")
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Surveillance::WakeLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.step(TAG, "acquirePowerLocks", "WakeLock acquired=${wakeLock?.isHeld}")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "acquirePowerLocks:WakeLock", t)
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "Surveillance::WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.step(TAG, "acquirePowerLocks", "WifiLock acquired=${wifiLock?.isHeld}")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "acquirePowerLocks:WifiLock", t)
        }
        AppLogger.exit(TAG, "acquirePowerLocks", "Done")
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CctvStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val notification = buildNotification(title, text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "updateNotification", t, "title=$title")
        }
    }

    private fun createNotificationChannel() {
        AppLogger.enter(TAG, "createNotificationChannel")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Imou CCTV YouTube Streamer",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "24/7 CCTV background streaming and SD card sync status"
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.createNotificationChannel(channel)
            }
            AppLogger.exit(TAG, "createNotificationChannel", "Channel created")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "createNotificationChannel", t)
        }
    }

    private fun stopPipeline() {
        AppLogger.enter(TAG, "stopPipeline")
        try {
            AppLogger.i(TAG, "Stopping streaming pipeline...")
            if (::rotationScheduler.isInitialized) rotationScheduler.stop()
            if (::streamOrchestrator.isInitialized) streamOrchestrator.stopLiveStreamsGracefully()
            _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            AppLogger.exit(TAG, "stopPipeline", "Stopped & removed foreground service")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "stopPipeline", t)
        }
    }

    override fun onDestroy() {
        AppLogger.enter(TAG, "onDestroy")
        super.onDestroy()
        try {
            isRunning = false
            _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
            if (::rotationScheduler.isInitialized) rotationScheduler.stop()
            if (::streamOrchestrator.isInitialized) streamOrchestrator.stopLiveStreamsGracefully()
            serviceScope.cancel()
            try {
                networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "onDestroy:unregisterNetworkCallback", t)
            }
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "onDestroy:wakeLock", t)
            }
            try {
                wifiLock?.let { if (it.isHeld) it.release() }
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "onDestroy:wifiLock", t)
            }
            AppLogger.i(TAG, "CctvStreamingService destroyed.")
            AppLogger.exit(TAG, "onDestroy", "Cleanup complete")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "onDestroy", t)
        }
    }
}
