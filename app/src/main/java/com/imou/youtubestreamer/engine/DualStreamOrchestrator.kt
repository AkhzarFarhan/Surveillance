package com.imou.youtubestreamer.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.LensStreamState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual Stream Orchestrator.
 * Spawns and supervises two concurrent, ultra-lightweight FFmpeg streaming workers:
 * 1. Fixed Overview Lens (_F - Channel 2)
 * 2. Rotatable Pan-Tilt PTZ Lens (_R - Channel 1)
 *
 * Utilizes TCP transport for zero video smearing, stream copy (-c:v copy) for ~2% CPU usage,
 * and generates silent stereo audio to eliminate Content ID ambient copyright risks.
 */
class DualStreamOrchestrator(
    private val context: Context,
    private val settings: AppSettings
) {

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sessionFixed: FFmpegSession? = null
    private var sessionRotatable: FFmpegSession? = null

    private var fixedStartTime = 0L
    private var rotatableStartTime = 0L

    private val _fixedState = MutableStateFlow(
        LensStreamState("Fixed Overview", "_F", settings.fixedChannel)
    )
    val fixedState: StateFlow<LensStreamState> = _fixedState.asStateFlow()

    private val _rotatableState = MutableStateFlow(
        LensStreamState("Rotatable PTZ", "_R", settings.rotatableChannel)
    )
    val rotatableState: StateFlow<LensStreamState> = _rotatableState.asStateFlow()

    fun getRtspUrl(channel: Int): String {
        return "rtsp://${settings.username}:${settings.safetyCode}@${settings.cameraIp}:${settings.rtspPort}/cam/realmonitor?channel=$channel&subtype=${settings.streamSubtype}"
    }

    /**
     * Starts dual streaming workers for both lenses.
     */
    fun startDualStreaming(
        fixedTargetRtmp: String,
        rotatableTargetRtmp: String,
        fixedTitle: String,
        rotatableTitle: String,
        fixedWatchUrl: String = "",
        rotatableWatchUrl: String = ""
    ) {
        engineScope.launch {
            AppLogger.i("DualOrchestrator", "Launching Dual Streaming Workers:")
            AppLogger.i("DualOrchestrator", "  - Fixed Lens (_F): '$fixedTitle'")
            AppLogger.i("DualOrchestrator", "  - Rotatable Lens (_R): '$rotatableTitle'")

            stopLiveStreamsGracefully()
            delay(500) // Brief pause to allow ports to free

            // 1. Launch Fixed Lens Worker (_F)
            if (fixedTargetRtmp.isNotBlank()) {
                launch {
                    startSingleWorker(
                        lensCode = "_F",
                        lensName = "Fixed Overview",
                        channel = settings.fixedChannel,
                        rtspSource = getRtspUrl(settings.fixedChannel),
                        rtmpTarget = fixedTargetRtmp,
                        title = fixedTitle,
                        watchUrl = fixedWatchUrl,
                        onSessionAssigned = { sessionFixed = it },
                        stateFlow = _fixedState,
                        setStartTime = { fixedStartTime = it }
                    )
                }
            } else {
                AppLogger.w("DualOrchestrator", "Fixed lens RTMP target empty, skipping _F worker.")
            }

            // 2. Launch Rotatable Lens Worker (_R)
            if (rotatableTargetRtmp.isNotBlank()) {
                launch {
                    startSingleWorker(
                        lensCode = "_R",
                        lensName = "Rotatable PTZ",
                        channel = settings.rotatableChannel,
                        rtspSource = getRtspUrl(settings.rotatableChannel),
                        rtmpTarget = rotatableTargetRtmp,
                        title = rotatableTitle,
                        watchUrl = rotatableWatchUrl,
                        onSessionAssigned = { sessionRotatable = it },
                        stateFlow = _rotatableState,
                        setStartTime = { rotatableStartTime = it }
                    )
                }
            } else {
                AppLogger.w("DualOrchestrator", "Rotatable lens RTMP target empty, skipping _R worker.")
            }
        }
    }

    private fun startSingleWorker(
        lensCode: String,
        lensName: String,
        channel: Int,
        rtspSource: String,
        rtmpTarget: String,
        title: String,
        watchUrl: String,
        onSessionAssigned: (FFmpegSession) -> Unit,
        stateFlow: MutableStateFlow<LensStreamState>,
        setStartTime: (Long) -> Unit
    ) {
        val now = System.currentTimeMillis()
        setStartTime(now)

        stateFlow.value = LensStreamState(
            lensName = lensName,
            lensCode = lensCode,
            channel = channel,
            isStreaming = true,
            activeTitle = title,
            broadcastUrl = watchUrl,
            lastError = null
        )

        val cmdParts = mutableListOf(
            "-hide_banner", "-loglevel", "warning",
            "-rtsp_transport", "tcp",
            "-i", rtspSource
        )

        if (settings.silentAudioEnabled) {
            // Injects synthetic silent stereo AAC audio (anullsrc)
            cmdParts.addAll(
                listOf(
                    "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                    "-map", "0:v", "-map", "1:a",
                    "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "128k", "-ar", "44100"
                )
            )
        } else {
            // Direct stream copy
            cmdParts.addAll(listOf("-c:v", "copy", "-c:a", "copy"))
        }

        cmdParts.addAll(listOf("-f", "flv", rtmpTarget))
        val fullCmd = cmdParts.joinToString(" ")

        AppLogger.i("DualOrchestrator", "[$lensCode] Starting pipeline: ffmpeg $fullCmd")

        val session = FFmpegKit.executeAsync(
            fullCmd,
            { completedSession ->
                val returnCode = completedSession.returnCode
                val isSuccess = ReturnCode.isSuccess(returnCode)
                val isCancel = ReturnCode.isCancel(returnCode)

                AppLogger.i("DualOrchestrator", "[$lensCode] Worker finished. Success=$isSuccess, Cancelled=$isCancel, Code=$returnCode")

                stateFlow.value = stateFlow.value.copy(
                    isStreaming = false,
                    lastError = if (!isSuccess && !isCancel) "Exited with code $returnCode" else null
                )
            },
            { log ->
                val msg = log.message.trim()
                if (msg.contains("error", ignoreCase = true) || msg.contains("fatal", ignoreCase = true)) {
                    AppLogger.e("FFmpeg-$lensCode", msg)
                } else {
                    AppLogger.ffmpeg("FFmpeg-$lensCode", msg)
                }
            },
            { statistics ->
                val fps = statistics.videoFps.toDouble()
                val bitrate = statistics.bitrate.toDouble()
                val uptimeSec = (System.currentTimeMillis() - now) / 1000

                stateFlow.value = stateFlow.value.copy(
                    isStreaming = true,
                    fps = fps,
                    bitrateKbps = bitrate,
                    uptimeSeconds = uptimeSec
                )
            }
        )

        onSessionAssigned(session)
    }

    fun pauseLiveStreams() {
        AppLogger.i("DualOrchestrator", "Pausing live streams due to Wi-Fi outage...")
        stopLiveStreamsGracefully()
    }

    fun stopLiveStreamsGracefully() {
        sessionFixed?.let {
            AppLogger.i("DualOrchestrator", "Cancelling Fixed lens session ${it.sessionId}...")
            FFmpegKit.cancel(it.sessionId)
        }
        sessionRotatable?.let {
            AppLogger.i("DualOrchestrator", "Cancelling Rotatable lens session ${it.sessionId}...")
            FFmpegKit.cancel(it.sessionId)
        }

        sessionFixed = null
        sessionRotatable = null

        _fixedState.value = _fixedState.value.copy(isStreaming = false)
        _rotatableState.value = _rotatableState.value.copy(isStreaming = false)
    }

    fun isAnyStreaming(): Boolean {
        return _fixedState.value.isStreaming || _rotatableState.value.isStreaming
    }
}
