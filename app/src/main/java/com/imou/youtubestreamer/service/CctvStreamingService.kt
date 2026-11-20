package com.imou.youtubestreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.imou.youtubestreamer.MainActivity
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.PipelineStatus
import com.imou.youtubestreamer.data.SystemClockImpl
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.engine.DualStreamOrchestrator
import com.imou.youtubestreamer.net.ConnectivityMonitor
import com.imou.youtubestreamer.resume.GapLedger
import com.imou.youtubestreamer.resume.Heartbeat
import com.imou.youtubestreamer.supervisor.StreamSupervisor
import com.imou.youtubestreamer.supervisor.SupervisorEvent
import com.imou.youtubestreamer.supervisor.SupervisorStore
import com.imou.youtubestreamer.sync.OnvifCameraClient
import com.imou.youtubestreamer.watchdog.ServiceWatchdogScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Thin lifecycle host for the 24/7 surveillance foreground service (`specialUse`).
 *
 * All streaming, rotation, connectivity hysteresis, and resumable SD-card backfill logic
 * runs inside [StreamSupervisor] (single-threaded actor).
 */
class CctvStreamingService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var store: SupervisorStore
    private lateinit var heartbeat: Heartbeat
    private lateinit var ledger: GapLedger
    private lateinit var connectivity: ConnectivityMonitor
    private lateinit var supervisor: StreamSupervisor

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var thermalListener: Any? = null

    private val clock = SystemClockImpl

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.exception(TAG, "serviceScope.CoroutineExceptionHandler", throwable)
        if (::supervisor.isInitialized) {
            supervisor.send(SupervisorEvent.Tick(clock.nowMs(), clock.elapsedMs()))
        }
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    companion object {
        private const val TAG = "CctvService"
        const val NOTIFICATION_CHANNEL_ID = "cctv_streaming_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_STREAMING"
        const val ACTION_STOP = "ACTION_STOP_STREAMING"
        const val ACTION_RESTART = "ACTION_RESTART_STREAMING"
        const val ACTION_MANUAL_BACKFILL = "ACTION_MANUAL_BACKFILL"
        const val EXTRA_EXPLICIT_START = "EXTRA_EXPLICIT_START"
        const val EXTRA_HOURS_BACK = "EXTRA_HOURS_BACK"

        private val _systemState = MutableStateFlow(SystemStreamState())
        val systemState: StateFlow<SystemStreamState> = _systemState.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, explicit: Boolean = true) {
            AppLogger.enter(TAG, "Companion.start", "explicit=$explicit, isRunning=$isRunning, SDK=${Build.VERSION.SDK_INT}")
            try {
                val appCtx = context.applicationContext
                if (explicit) {
                    SupervisorStore(appCtx).desiredRunning = true
                    ServiceWatchdogScheduler.scheduleAll(appCtx)
                }
                val intent = Intent(appCtx, CctvStreamingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_EXPLICIT_START, explicit)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
                AppLogger.exit(TAG, "Companion.start", "Service start requested")
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "Companion.start", t)
                ServiceWatchdogScheduler.scheduleAlarm(context.applicationContext, 30_000L)
            }
        }

        fun stop(context: Context) {
            AppLogger.enter(TAG, "Companion.stop", "isRunning=$isRunning")
            try {
                val appCtx = context.applicationContext
                SupervisorStore(appCtx).desiredRunning = false
                ServiceWatchdogScheduler.cancelAll(appCtx)

                if (isRunning) {
                    val intent = Intent(appCtx, CctvStreamingService::class.java).apply {
                        action = ACTION_STOP
                    }
                    appCtx.startService(intent)
                    AppLogger.exit(TAG, "Companion.stop", "Sent ACTION_STOP")
                } else {
                    appCtx.stopService(Intent(appCtx, CctvStreamingService::class.java))
                    _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
                    AppLogger.exit(TAG, "Companion.stop", "Service already stopped")
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

        fun restart(context: Context) {
            AppLogger.enter(TAG, "Companion.restart", "isRunning=$isRunning")
            val appCtx = context.applicationContext
            SupervisorStore(appCtx).desiredRunning = true
            ServiceWatchdogScheduler.scheduleAll(appCtx)
            if (isRunning) {
                val intent = Intent(appCtx, CctvStreamingService::class.java).apply {
                    action = ACTION_RESTART
                }
                appCtx.startService(intent)
            } else {
                start(appCtx, explicit = true)
            }
        }

        fun enqueueManualBackfill(context: Context, hoursBack: Int = 1) {
            AppLogger.enter(TAG, "Companion.enqueueManualBackfill", "hoursBack=$hoursBack, isRunning=$isRunning")
            val appCtx = context.applicationContext
            val ledger = GapLedger(File(appCtx.filesDir, "gap_ledger.json"), SystemClockImpl)
            val settings = AppSettings(appCtx)
            val now = SystemClockImpl.nowMs()
            val start = now - (hoursBack.coerceIn(1, 72) * 3600_000L)
            if (settings.getFormattedFixedRtmp().isNotBlank()) {
                ledger.recordOutage(com.imou.youtubestreamer.data.Lens.FIXED, start, now, minGapSec = 10)
            }
            if (settings.getFormattedRotatableRtmp().isNotBlank()) {
                ledger.recordOutage(com.imou.youtubestreamer.data.Lens.ROTATABLE, start, now, minGapSec = 10)
            }

            if (isRunning) {
                val intent = Intent(appCtx, CctvStreamingService::class.java).apply {
                    action = ACTION_MANUAL_BACKFILL
                    putExtra(EXTRA_HOURS_BACK, hoursBack)
                }
                appCtx.startService(intent)
            } else {
                start(appCtx, explicit = true)
            }
        }
    }

    override fun onCreate() {
        AppLogger.enter(TAG, "onCreate")
        super.onCreate()
        try {
            createNotificationChannel()
            promoteToForeground(
                "Initializing Surveillance Pipeline...",
                "Starting monitor, gap ledger & supervisor"
            )

            settings = AppSettings(applicationContext)
            store = SupervisorStore(applicationContext)
            heartbeat = Heartbeat(store, clock)
            ledger = GapLedger(File(filesDir, "gap_ledger.json"), clock)
            val probe = OnvifCameraClient(settings)

            connectivity = ConnectivityMonitor(
                context = applicationContext,
                settings = settings,
                clock = clock,
                scope = serviceScope,
                onLinkChanged = { newState, netChanged ->
                    if (netChanged) refreshWifiLock()
                    if (::supervisor.isInitialized) {
                        supervisor.send(SupervisorEvent.LinkChanged(newState, netChanged))
                    }
                }
            )

            supervisor = StreamSupervisor(
                scope = serviceScope,
                clock = clock,
                settings = settings,
                store = store,
                heartbeat = heartbeat,
                ledger = ledger,
                probe = probe,
                connectivity = connectivity,
                systemStateFlow = _systemState,
                onNotificationUpdate = { title, text -> updateNotification(title, text) },
                onWakeLockRefreshNeeded = { ensurePowerLocksHeld() },
                onAllStopped = {
                    DualStreamOrchestrator.logRunningFfmpegSessions("onAllStopped")
                }
            )

            ensurePowerLocksHeld()
            registerThermalMonitor()
            connectivity.start()
            supervisor.startActor()

            isRunning = true
            AppLogger.i(TAG, "CctvStreamingService initialized cleanly.")
            AppLogger.exit(TAG, "onCreate", "isRunning=true")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "onCreate", t)
            isRunning = false
            val retryDelay = if (::store.isInitialized && store.isCrashLoopActive(clock.nowMs())) {
                ServiceWatchdogScheduler.CRASH_LOOP_BACKOFF_MS
            } else {
                30_000L
            }
            if (::store.isInitialized && store.desiredRunning) {
                ServiceWatchdogScheduler.scheduleAlarm(applicationContext, retryDelay)
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "<NULL_ACTION>"
        AppLogger.enter(TAG, "onStartCommand", "action=$action, flags=$flags, startId=$startId, isRunning=$isRunning")
        super.onStartCommand(intent, flags, startId)

        if (!::supervisor.isInitialized) {
            AppLogger.unexpected(TAG, "onStartCommand", "Supervisor not initialized; aborting startId=$startId")
            return START_NOT_STICKY
        }

        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    AppLogger.i(TAG, "Stopping pipeline per ACTION_STOP...")
                    store.desiredRunning = false
                    ServiceWatchdogScheduler.cancelAll(applicationContext)
                    isRunning = false
                    runBlocking {
                        withTimeoutOrNull(6_000L) {
                            supervisor.send(SupervisorEvent.UserStop("ACTION_STOP"))
                            supervisor.shutdown("ACTION_STOP")
                        }
                    }
                    _systemState.value = _systemState.value.copy(status = PipelineStatus.IDLE)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    AppLogger.exit(TAG, "onStartCommand", "START_NOT_STICKY (stopped)")
                    return START_NOT_STICKY
                }

                ACTION_RESTART -> {
                    promoteToForeground("Restarting Pipeline...", "Recycling camera & YouTube workers")
                    store.desiredRunning = true
                    ServiceWatchdogScheduler.scheduleAll(applicationContext)
                    supervisor.send(SupervisorEvent.RestartPipeline("notification_or_user_restart"))
                }

                ACTION_MANUAL_BACKFILL -> {
                    promoteToForeground("Surveillance Pipeline Active", "Scheduling SD card backfill...")
                    store.desiredRunning = true
                    ServiceWatchdogScheduler.scheduleAll(applicationContext)
                    supervisor.send(SupervisorEvent.Tick(clock.nowMs(), clock.elapsedMs()))
                }

                ACTION_START, null -> {
                    val explicit = intent?.getBooleanExtra(EXTRA_EXPLICIT_START, intent.action == ACTION_START) ?: false
                    if (explicit) {
                        store.desiredRunning = true
                        ServiceWatchdogScheduler.scheduleAll(applicationContext)
                    } else if (!store.desiredRunning) {
                        AppLogger.i(TAG, "Sticky/watchdog start ignored because desiredRunning=false")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }

                    // Idempotent re-promote + UserStart (if workers already match desired state, reconcile is a no-op)
                    promoteToForeground(
                        "Surveillance Pipeline Active",
                        "Monitoring camera RTSP -> YouTube RTMPS"
                    )
                    ServiceWatchdogScheduler.scheduleAll(applicationContext)
                    supervisor.send(SupervisorEvent.UserStart(explicit = explicit))
                }

                else -> {
                    AppLogger.unexpected(TAG, "onStartCommand", "Unrecognized intent action", action)
                }
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "onStartCommand", t, "action=$action")
            supervisor.send(SupervisorEvent.Tick(clock.nowMs(), clock.elapsedMs()))
        }

        AppLogger.exit(TAG, "onStartCommand", "START_STICKY")
        return START_STICKY
    }

    private fun promoteToForeground(title: String, text: String) {
        try {
            val notification = buildNotification(title, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
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
        AppLogger.unexpected(TAG, "onTimeout(startId=$startId)", "OS signaled foreground service timeout; cycling safely")
        handleFgsTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.unexpected(TAG, "onTimeout(startId=$startId, fgsType=$fgsType)", "OS signaled foreground service timeout; cycling safely")
        handleFgsTimeout(startId)
    }

    private fun handleFgsTimeout(startId: Int) {
        try {
            val shouldRestart = ::store.isInitialized && store.desiredRunning
            val appCtx = applicationContext
            isRunning = false
            runBlocking {
                withTimeoutOrNull(4_000L) {
                    if (::supervisor.isInitialized) supervisor.shutdown("onTimeout")
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            if (shouldRestart) {
                ServiceWatchdogScheduler.scheduleAlarm(appCtx, 5_000L)
                Handler(Looper.getMainLooper()).postDelayed({
                    AppLogger.i(TAG, "Re-launching CctvStreamingService after OS timeout cycle...")
                    start(appCtx, explicit = false)
                }, 2_000L)
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "handleFgsTimeout", t)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLogger.step(TAG, "onTaskRemoved", "App swiped from recents; desiredRunning=${::store.isInitialized && store.desiredRunning}")
        super.onTaskRemoved(rootIntent)
        if (::store.isInitialized && store.desiredRunning) {
            ServiceWatchdogScheduler.scheduleAlarm(applicationContext, 10_000L)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && ::supervisor.isInitialized) {
            AppLogger.w(TAG, "onTrimMemory(level=$level) CRITICAL — requesting substream fallback for transcoding lenses")
            supervisor.send(SupervisorEvent.MemoryOrThermalPressure(forceSubstream = true, source = "onTrimMemory($level)"))
        }
    }

    private fun registerThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                if (!::supervisor.isInitialized) return@OnThermalStatusChangedListener
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    AppLogger.w(TAG, "Thermal status $status >= SEVERE — enabling copy-mode substream")
                    supervisor.send(SupervisorEvent.MemoryOrThermalPressure(forceSubstream = true, source = "thermal($status)"))
                } else if (status <= PowerManager.THERMAL_STATUS_MODERATE) {
                    supervisor.send(SupervisorEvent.MemoryOrThermalPressure(forceSubstream = false, source = "thermal($status)"))
                }
            }
            pm.addThermalStatusListener(listener)
            thermalListener = listener
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "registerThermalMonitor", t)
        }
    }

    private fun unregisterThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.removeThermalStatusListener(listener)
        } catch (_: Throwable) {
        }
        thermalListener = null
    }

    @Synchronized
    private fun ensurePowerLocksHeld() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Surveillance::WakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire()
                    AppLogger.step(TAG, "ensurePowerLocksHeld", "WakeLock (re)acquired=true")
                }
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "ensurePowerLocksHeld:WakeLock", t)
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockMode, "Surveillance::WifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let { wfl ->
                if (!wfl.isHeld) {
                    wfl.acquire()
                    AppLogger.step(TAG, "ensurePowerLocksHeld", "WifiLock (re)acquired=true")
                }
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "ensurePowerLocksHeld:WifiLock", t)
        }
    }

    @Synchronized
    private fun refreshWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
                it.acquire()
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "refreshWifiLock", t)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(this, CctvStreamingService::class.java).apply {
            action = ACTION_RESTART
        }
        val restartPendingIntent = PendingIntent.getService(
            this, 2, restartIntent,
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Restart Pipeline", restartPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "updateNotification", t, "title=$title")
        }
    }

    private fun createNotificationChannel() {
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
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "createNotificationChannel", t)
        }
    }

    override fun onDestroy() {
        AppLogger.enter(TAG, "onDestroy")
        super.onDestroy()
        try {
            isRunning = false
            unregisterThermalMonitor()
            if (::connectivity.isInitialized) connectivity.stop()
            runBlocking {
                withTimeoutOrNull(4_000L) {
                    if (::supervisor.isInitialized) supervisor.shutdown("onDestroy")
                }
            }
            serviceScope.cancel()
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Throwable) {
            }
            try {
                wifiLock?.let { if (it.isHeld) it.release() }
            } catch (_: Throwable) {
            }
            AppLogger.i(TAG, "CctvStreamingService destroyed.")
            AppLogger.exit(TAG, "onDestroy", "Cleanup complete")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "onDestroy", t)
        }
    }
}
