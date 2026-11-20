package com.imou.youtubestreamer.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.SessionState
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.Lens
import com.imou.youtubestreamer.data.LensStreamState
import com.imou.youtubestreamer.data.SystemClockImpl
import com.imou.youtubestreamer.supervisor.SupervisorEvent
import com.imou.youtubestreamer.sync.OnvifCameraClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Compatibility façade delegating dual-lens streaming to two independent [LensWorker] instances.
 * When driven by `StreamSupervisor`, both workers report events directly to the supervisor channel.
 */
class DualStreamOrchestrator(
    context: Context,
    private val settings: AppSettings,
    clock: Clock = SystemClockImpl,
    targetGuard: ActiveTargetGuard = ActiveTargetGuard(),
    eventsChannel: SendChannel<SupervisorEvent>? = null,
    scope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "DualOrchestrator"

        fun logRunningFfmpegSessions(caller: String) {
            try {
                val running = FFmpegKit.listSessions().filter { it.state == SessionState.RUNNING }
                AppLogger.step(
                    TAG,
                    caller,
                    "Running FFmpegKit sessions count=${running.size}" +
                        if (running.isNotEmpty()) " (ids=${running.map { it.sessionId }})" else ""
                )
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "$caller:listSessions", t)
            }
        }
    }

    private val internalScope = scope ?: CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            AppLogger.exception(TAG, "engineScope", t)
        }
    )
    private val sinkChannel: SendChannel<SupervisorEvent> = eventsChannel ?: Channel(Channel.UNLIMITED)
    private val probe = OnvifCameraClient(settings)

    val fixedWorker = LensWorker(
        lens = Lens.FIXED,
        settings = settings,
        probe = probe,
        clock = clock,
        targetGuard = targetGuard,
        events = sinkChannel,
        scope = internalScope
    )

    val rotatableWorker = LensWorker(
        lens = Lens.ROTATABLE,
        settings = settings,
        probe = probe,
        clock = clock,
        targetGuard = targetGuard,
        events = sinkChannel,
        scope = internalScope
    )

    val fixedState: StateFlow<LensStreamState> = fixedWorker.state
    val rotatableState: StateFlow<LensStreamState> = rotatableWorker.state

    fun workerFor(lens: Lens): LensWorker = when (lens) {
        Lens.FIXED -> fixedWorker
        Lens.ROTATABLE -> rotatableWorker
    }

    fun getRtspUrl(channel: Int): String {
        val url = settings.buildLiveRtspUrl(channel)
        val sanitized = url.replace(Regex("://[^@]+@"), "://***:***@")
        AppLogger.step(TAG, "getRtspUrl", "channel=$channel -> $sanitized")
        return url
    }

    fun startDualStreaming(
        fixedTargetRtmp: String,
        rotatableTargetRtmp: String,
        fixedTitle: String,
        rotatableTitle: String,
        @Suppress("UNUSED_PARAMETER") fixedWatchUrl: String = "",
        @Suppress("UNUSED_PARAMETER") rotatableWatchUrl: String = ""
    ) {
        AppLogger.enter(
            TAG,
            "startDualStreaming",
            "fixedRtmp=${AppLogger.maskSecret(fixedTargetRtmp)}, rotRtmp=${AppLogger.maskSecret(rotatableTargetRtmp)}"
        )
        if (fixedTargetRtmp.isNotBlank()) {
            fixedWorker.startLive(fixedTargetRtmp, fixedTitle)
        }
        if (rotatableTargetRtmp.isNotBlank()) {
            rotatableWorker.startLive(rotatableTargetRtmp, rotatableTitle)
        }
        AppLogger.exit(TAG, "startDualStreaming", "Delegated to LensWorkers")
    }

    suspend fun stopAll(reason: String = "orchestrator_stop") {
        AppLogger.enter(TAG, "stopAll", "reason=$reason")
        fixedWorker.stop(reason)
        rotatableWorker.stop(reason)
        logRunningFfmpegSessions("stopAll")
        AppLogger.exit(TAG, "stopAll", "All LensWorkers stopped")
    }

    fun stopLiveStreamsGracefully() {
        runBlocking {
            stopAll("stopLiveStreamsGracefully")
        }
    }

    fun isAnyStreaming(): Boolean =
        fixedState.value.isStreaming || rotatableState.value.isStreaming
}
