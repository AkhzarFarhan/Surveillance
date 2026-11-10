package com.imou.youtubestreamer.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.LensStreamState
import com.imou.youtubestreamer.sync.OnvifCameraClient
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
    companion object {
        private const val TAG = "DualOrchestrator"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.exception(TAG, "engineScope.CoroutineExceptionHandler", throwable)
    }
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private var sessionFixed: FFmpegSession? = null
    private var sessionRotatable: FFmpegSession? = null

    private var fixedStartTime = 0L
    private var rotatableStartTime = 0L

    private val onvifClient = OnvifCameraClient(settings)

    private val _fixedState = MutableStateFlow(
        LensStreamState("Fixed Overview", "_F", settings.fixedChannel)
    )
    val fixedState: StateFlow<LensStreamState> = _fixedState.asStateFlow()

    private val _rotatableState = MutableStateFlow(
        LensStreamState("Rotatable PTZ", "_R", settings.rotatableChannel)
    )
    val rotatableState: StateFlow<LensStreamState> = _rotatableState.asStateFlow()

    fun getRtspUrl(channel: Int): String {
        val url = settings.buildLiveRtspUrl(channel)
        val sanitized = url.replace(Regex("://[^@]+@"), "://***:***@")
        AppLogger.step(TAG, "getRtspUrl", "channel=$channel -> $sanitized")
        return url
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
        AppLogger.enter(
            TAG,
            "startDualStreaming",
            "fixedRtmp=${AppLogger.maskSecret(fixedTargetRtmp)}, rotatableRtmp=${AppLogger.maskSecret(rotatableTargetRtmp)}, fixedTitle='$fixedTitle', rotatableTitle='$rotatableTitle'"
        )
        engineScope.launch {
            try {
                AppLogger.i(TAG, "Launching Dual Streaming Workers:")
                AppLogger.i(TAG, "  - Fixed Lens (_F): '$fixedTitle' (Ch ${settings.fixedChannel})")
                AppLogger.i(TAG, "  - Rotatable Lens (_R): '$rotatableTitle' (Ch ${settings.rotatableChannel})")

                stopLiveStreamsGracefully()
                delay(500) // Brief pause to allow sockets/ports to free

                supervisorScope {
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
                        AppLogger.w(TAG, "Fixed lens RTMP target empty, skipping _F worker.")
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
                        AppLogger.w(TAG, "Rotatable lens RTMP target empty, skipping _R worker.")
                    }
                }
                AppLogger.exit(TAG, "startDualStreaming.coroutine", "Workers launched")
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "startDualStreaming.coroutine", t)
            }
        }
        AppLogger.exit(TAG, "startDualStreaming", "Dispatched to engineScope")
    }

    private suspend fun startSingleWorker(
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
        val fn = "startSingleWorker[$lensCode]"
        val sanitizedRtsp = rtspSource.replace(Regex("://[^@]+@"), "://***:***@")
        val sanitizedRtmp = rtmpTarget.replace(Regex("/live2/.*"), "/live2/${AppLogger.maskSecret(rtmpTarget.substringAfterLast("/"))}")
        AppLogger.enter(
            TAG,
            fn,
            "lensName='$lensName', channel=$channel, rtsp='$sanitizedRtsp', rtmp='$sanitizedRtmp'"
        )

        try {
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

            // Step 1: Probe RTSP stream & codec
            AppLogger.step(TAG, fn, "Probing RTSP DESCRIBE on channel=$channel, subtype=${settings.streamSubtype}...")
            var useH264Transcode = false
            val probe = onvifClient.testRtspDescribe(channel, settings.streamSubtype)
            AppLogger.step(
                TAG,
                fn,
                "RTSP probe result: success=${probe.success}, status=${probe.statusCode} (${probe.statusLine}), videoCodec=${probe.videoCodec}, audioCodec=${probe.audioCodec}, err=${probe.errorMessage}"
            )

            if (!probe.success && !probe.errorMessage.isNullOrBlank()) {
                AppLogger.unexpected(TAG, fn, "RTSP pre-check failed", probe)
                AppLogger.e(TAG, "[$lensCode] RTSP pre-check failed: ${probe.errorMessage}")
                stateFlow.value = stateFlow.value.copy(isStreaming = false, lastError = probe.errorMessage)
                AppLogger.exit(TAG, fn, "ABORTED_RTSP_PROBE_FAILED (${probe.errorMessage})")
                return
            }

            if (probe.videoCodec == "H265" || probe.videoCodec == "HEVC") {
                AppLogger.w(TAG, "[$lensCode] Camera stream is ${probe.videoCodec}; requesting H.264 via ONVIF for zero-CPU YouTube copy...")
                val switched = onvifClient.switchMainStreamsToH264()
                AppLogger.step(TAG, fn, "ONVIF switchMainStreamsToH264 result: isSuccess=${switched.isSuccess}, val=${switched.getOrNull() ?: switched.exceptionOrNull()?.message}")
                if (switched.isSuccess) {
                    delay(600)
                    val recheck = onvifClient.testRtspDescribe(channel, settings.streamSubtype)
                    AppLogger.step(TAG, fn, "RTSP re-probe after ONVIF switch: videoCodec=${recheck.videoCodec}, status=${recheck.statusCode}")
                    if (recheck.videoCodec == "H265" || recheck.videoCodec == "HEVC") {
                        useH264Transcode = true
                    }
                } else {
                    useH264Transcode = true
                }
                if (useH264Transcode) {
                    AppLogger.w(TAG, "[$lensCode] Falling back to OpenH264 transcoding because camera remained on H.265.")
                }
            }

            val cmdParts = mutableListOf(
                "-hide_banner", "-loglevel", "warning",
                "-rtsp_transport", "tcp",
                "-i", rtspSource
            )

            val videoCodecArgs = if (useH264Transcode) {
                listOf("-c:v", "libopenh264", "-b:v", "1500k", "-maxrate", "2000k", "-bufsize", "3000k", "-g", "28", "-pix_fmt", "yuv420p")
            } else {
                listOf("-c:v", "copy")
            }

            if (settings.silentAudioEnabled) {
                cmdParts.addAll(
                    listOf("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100", "-map", "0:v", "-map", "1:a") +
                        videoCodecArgs +
                        listOf("-c:a", "aac", "-b:a", "128k", "-ar", "44100")
                )
            } else {
                cmdParts.addAll(videoCodecArgs + listOf("-c:a", "aac", "-b:a", "128k", "-ar", "44100"))
            }

            cmdParts.addAll(listOf("-f", "flv", rtmpTarget))
            val fullCmd = cmdParts.joinToString(" ")
            val sanitizedCmd = fullCmd
                .replace(Regex("://[^@]+@"), "://***:***@")
                .replace(Regex("/live2/\\S+"), "/live2/***")

            AppLogger.i(TAG, "[$lensCode] Starting pipeline: ffmpeg $sanitizedCmd")
            var lastStatsLogMs = 0L

            val session = FFmpegKit.executeAsync(
                fullCmd,
                { completedSession ->
                    try {
                        val returnCode = completedSession.returnCode
                        val isSuccess = ReturnCode.isSuccess(returnCode)
                        val isCancel = ReturnCode.isCancel(returnCode)
                        val failTrace = completedSession.failStackTrace

                        AppLogger.i(
                            TAG,
                            "[$lensCode] Worker finished (sessionId=${completedSession.sessionId}). Success=$isSuccess, Cancelled=$isCancel, Code=$returnCode, State=${completedSession.state}"
                        )

                        if (!isSuccess && !isCancel) {
                            val tailLogs = try {
                                completedSession.allLogsAsString?.takeLast(600)?.trim()
                            } catch (_: Throwable) {
                                null
                            }
                            AppLogger.unexpected(
                                TAG,
                                "$fn.onComplete",
                                "FFmpeg exited with non-success code $returnCode",
                                "failStackTrace=${failTrace ?: "none"}, tailLogs=${tailLogs ?: "none"}"
                            )
                        }

                        stateFlow.value = stateFlow.value.copy(
                            isStreaming = false,
                            lastError = if (!isSuccess && !isCancel) "Exited with code $returnCode" else null
                        )
                    } catch (t: Throwable) {
                        AppLogger.exception(TAG, "$fn.onComplete", t)
                    }
                },
                { log ->
                    try {
                        val msg = log.message.trim()
                        if (msg.isNotEmpty()) {
                            if (msg.contains("error", ignoreCase = true) ||
                                msg.contains("fatal", ignoreCase = true) ||
                                msg.contains("failed", ignoreCase = true)
                            ) {
                                AppLogger.e("FFmpeg-$lensCode", msg)
                            } else {
                                AppLogger.ffmpeg("FFmpeg-$lensCode", msg)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                },
                { statistics ->
                    try {
                        val fps = statistics.videoFps.toDouble()
                        val bitrate = statistics.bitrate.toDouble()
                        val uptimeSec = (System.currentTimeMillis() - now) / 1000

                        stateFlow.value = stateFlow.value.copy(
                            isStreaming = true,
                            fps = fps,
                            bitrateKbps = bitrate,
                            uptimeSeconds = uptimeSec
                        )

                        val curMs = System.currentTimeMillis()
                        if (curMs - lastStatsLogMs >= 15_000L) {
                            lastStatsLogMs = curMs
                            AppLogger.step(
                                TAG,
                                "$fn.stats",
                                "frame=${statistics.videoFrameNumber}, fps=${"%.1f".format(fps)}, bitrate=${"%.0f".format(bitrate)}kbps, size=${statistics.size}B, uptime=${uptimeSec}s"
                            )
                        }
                    } catch (_: Throwable) {
                    }
                }
            )

            onSessionAssigned(session)
            AppLogger.exit(TAG, fn, "FFmpegSession launched (sessionId=${session.sessionId}, state=${session.state})")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, fn, t, "Failed launching FFmpeg worker for $lensCode")
            stateFlow.value = stateFlow.value.copy(
                isStreaming = false,
                lastError = "${t::class.java.simpleName}: ${t.localizedMessage ?: "Native FFmpeg error"}"
            )
        }
    }

    fun pauseLiveStreams() {
        AppLogger.enter(TAG, "pauseLiveStreams")
        AppLogger.i(TAG, "Pausing live streams due to Wi-Fi outage...")
        stopLiveStreamsGracefully()
        AppLogger.exit(TAG, "pauseLiveStreams", "Paused")
    }

    fun stopLiveStreamsGracefully() {
        AppLogger.enter(
            TAG,
            "stopLiveStreamsGracefully",
            "fixedSession=${sessionFixed?.sessionId}, rotatableSession=${sessionRotatable?.sessionId}"
        )
        try {
            sessionFixed?.let {
                AppLogger.i(TAG, "Cancelling Fixed lens session ${it.sessionId}...")
                FFmpegKit.cancel(it.sessionId)
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "stopLiveStreamsGracefully:Fixed", t)
        }

        try {
            sessionRotatable?.let {
                AppLogger.i(TAG, "Cancelling Rotatable lens session ${it.sessionId}...")
                FFmpegKit.cancel(it.sessionId)
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "stopLiveStreamsGracefully:Rotatable", t)
        }

        sessionFixed = null
        sessionRotatable = null

        _fixedState.value = _fixedState.value.copy(isStreaming = false)
        _rotatableState.value = _rotatableState.value.copy(isStreaming = false)
        AppLogger.exit(TAG, "stopLiveStreamsGracefully", "All sessions cleared")
    }

    fun isAnyStreaming(): Boolean {
        val res = _fixedState.value.isStreaming || _rotatableState.value.isStreaming
        AppLogger.step(TAG, "isAnyStreaming", "fixed=${_fixedState.value.isStreaming}, rotatable=${_rotatableState.value.isStreaming} -> $res")
        return res
    }
}
