package com.imou.youtubestreamer.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.imou.youtubestreamer.MainActivity
import com.imou.youtubestreamer.R
import com.imou.youtubestreamer.auth.YouTubeAuthManager
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
    private lateinit var authManager: YouTubeAuthManager
    private lateinit var syncManager: DahuaSdCardSyncManager
    private lateinit var rotationScheduler: SessionRotationScheduler
    private lateinit var streamOrchestrator: DualStreamOrchestrator

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var currentFixedBroadcastId: String = ""
    private var currentRotatableBroadcastId: String = ""
    private var currentFixedRtmp: String = ""
    private var currentRotatableRtmp: String = ""

    private var isWifiConnected = false
    private var wifiDisconnectTimestamp: Long = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "cctv_streaming_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_STREAMING"
        const val ACTION_STOP = "ACTION_STOP_STREAMING"

        private val _systemState = MutableStateFlow(SystemStreamState())
        val systemState: StateFlow<SystemStreamState> = _systemState.asStateFlow()

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CctvStreamingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CctvStreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(applicationContext)
        authManager = YouTubeAuthManager(applicationContext, settings)
        syncManager = DahuaSdCardSyncManager(applicationContext, settings)
        rotationScheduler = SessionRotationScheduler(applicationContext)
        streamOrchestrator = DualStreamOrchestrator(applicationContext, settings)

        acquirePowerLocks()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Initializing Surveillance Streamer...", "Preparing camera & YouTube connection")
        )

        registerNetworkMonitoring()
        observeOrchestratorStates()

        isRunning = true
        AppLogger.i("CctvService", "CctvStreamingService created and running in foreground.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startPipeline()
            ACTION_STOP -> stopPipeline()
        }
        return START_STICKY // Android OS restarts service if killed under memory pressure
    }

    private fun startPipeline() {
        serviceScope.launch {
            _systemState.value = _systemState.value.copy(status = PipelineStatus.INITIALIZING)
            updateNotification("Authenticating...", "Checking YouTube credentials...")

            // Check if user is authenticated or using manual stream keys
            if (settings.useAutoBroadcast && !authManager.isUserSignedIn()) {
                if (settings.manualRtmpFixed.isBlank() && settings.manualRtmpRotatable.isBlank()) {
                    _systemState.value = _systemState.value.copy(status = PipelineStatus.ERROR)
                    updateNotification("Auth Required", "Please sign in to Google in the app.")
                    AppLogger.w("CctvService", "YouTube authentication required. Awaiting sign-in.")
                    return@launch
                }
            }

            _systemState.value = _systemState.value.copy(status = PipelineStatus.STREAMING)

            // Start 8-Hour Session Rotation Loop
            rotationScheduler.startRotationLoop(
                onNewSessionStarting = { partIndex, dateStr ->
                    handleNewSession(partIndex, dateStr)
                },
                onSessionEnding = {
                    handleSessionEnding()
                }
            )
        }
    }

    private suspend fun handleNewSession(partIndex: Int, dateStr: String) {
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

        // 1. Create Private YouTube Live Broadcasts
        val fixedBroadcastResult = authManager.createPrivateBroadcast(titleFixed, "F")
        val rotatableBroadcastResult = authManager.createPrivateBroadcast(titleRotatable, "R")

        val fixedInfo = fixedBroadcastResult.getOrNull()
        val rotatableInfo = rotatableBroadcastResult.getOrNull()

        currentFixedBroadcastId = fixedInfo?.broadcastId ?: ""
        currentRotatableBroadcastId = rotatableInfo?.broadcastId ?: ""

        currentFixedRtmp = fixedInfo?.rtmpIngestionEndpoint ?: settings.manualRtmpFixed
        currentRotatableRtmp = rotatableInfo?.rtmpIngestionEndpoint ?: settings.manualRtmpRotatable

        // 2. Start Dual Streaming Workers
        streamOrchestrator.startDualStreaming(
            fixedTargetRtmp = currentFixedRtmp,
            rotatableTargetRtmp = currentRotatableRtmp,
            fixedTitle = titleFixed,
            rotatableTitle = titleRotatable,
            fixedWatchUrl = fixedInfo?.watchUrl ?: "",
            rotatableWatchUrl = rotatableInfo?.watchUrl ?: ""
        )
    }

    private suspend fun handleSessionEnding() {
        _systemState.value = _systemState.value.copy(status = PipelineStatus.ROTATING)
        updateNotification("Rotating Session", "Finalizing 8-hour archive. 15s break...")

        // Gracefully stop FFmpeg streams
        streamOrchestrator.stopLiveStreamsGracefully()

        // Transition YouTube broadcasts to complete for permanent VOD archiving
        if (currentFixedBroadcastId.isNotBlank()) {
            authManager.finalizeBroadcast(currentFixedBroadcastId)
        }
        if (currentRotatableBroadcastId.isNotBlank()) {
            authManager.finalizeBroadcast(currentRotatableBroadcastId)
        }
    }

    private fun registerNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
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
                        val minutes = offlineDurationMs / 60000
                        AppLogger.i("CctvService", "Wi-Fi restored! Outage was $minutes minutes. Commencing SD card catch-up...")
                        updateNotification(
                            "Wi-Fi Restored: Syncing SD Card",
                            "Catching up $minutes mins of offline footage at ${settings.catchupSpeed}x speed..."
                        )

                        // Execute SD Card Catchup
                        syncManager.syncMissingFootageTillNow(
                            startTimeMs = reconnectTimestamp - offlineDurationMs,
                            endTimeMs = reconnectTimestamp,
                            rtmpTargetFixed = currentFixedRtmp,
                            rtmpTargetRotatable = currentRotatableRtmp
                        )

                        // Resume real-time live streaming
                        AppLogger.i("CctvService", "Catch-up completed. Resuming regular live stream.")
                        val currentPart = _systemState.value.activePartIndex
                        val currentDate = _systemState.value.activeDateStr
                        handleNewSession(currentPart, currentDate)
                    }
                } else {
                    AppLogger.i("CctvService", "Wi-Fi network connected.")
                }
            }

            override fun onLost(network: Network) {
                isWifiConnected = false
                wifiDisconnectTimestamp = System.currentTimeMillis()
                settings.lastOutageTimestamp = wifiDisconnectTimestamp

                _systemState.value = _systemState.value.copy(
                    isWifiConnected = false,
                    status = PipelineStatus.OUTAGE_RECORDING
                )

                AppLogger.w("CctvService", "Home Wi-Fi lost! Pausing live streams. CCTV is recording to SD card.")
                updateNotification(
                    "Home Wi-Fi Disconnected",
                    "CCTV recording safely to MicroSD card. Pausing stream..."
                )
                streamOrchestrator.pauseLiveStreams()
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun observeOrchestratorStates() {
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
    }

    private fun acquirePowerLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Surveillance::WakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Surveillance::WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
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
        val notification = buildNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Imou CCTV YouTube Streamer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "24/7 CCTV background streaming and SD card sync status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopPipeline() {
        AppLogger.i("CctvService", "Stopping streaming pipeline...")
        rotationScheduler.stop()
        streamOrchestrator.stopLiveStreamsGracefully()
        _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
        serviceScope.cancel()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        AppLogger.i("CctvService", "CctvStreamingService destroyed.")
    }
}
