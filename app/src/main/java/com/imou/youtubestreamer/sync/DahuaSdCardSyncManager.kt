package com.imou.youtubestreamer.sync

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.CatchupStatus
import com.imou.youtubestreamer.engine.RtmpsTlsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * MicroSD Card Offline Backlog Catch-Up Engine.
 * When the phone reconnects to Wi-Fi after an outage, this manager queries the camera's
 * onboard MicroSD card and streams missing footage at accelerated speed (2.0x - 4.0x)
 * directly into YouTube Live via RtmpsTlsBridge until playback reaches real-time.
 */
class DahuaSdCardSyncManager(
    private val context: Context,
    private val settings: AppSettings
) {

    companion object {
        private const val TAG = "SdCardSync"
    }

    private val cgiClient = DahuaCgiClient(settings)
    private val rtspTimeFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _catchupState = MutableStateFlow(CatchupStatus())
    val catchupState: StateFlow<CatchupStatus> = _catchupState.asStateFlow()

    /**
     * Primary catch-up orchestration entry point.
     * Takes the outage interval and catches up to live video.
     */
    suspend fun syncMissingFootageTillNow(
        startTimeMs: Long,
        endTimeMs: Long,
        rtmpTargetFixed: String,
        rtmpTargetRotatable: String
    ): Boolean = withContext(Dispatchers.IO) {
        val gapMinutes = (endTimeMs - startTimeMs) / 60000
        val startStr = synchronized(logTimeFormat) { logTimeFormat.format(Date(startTimeMs)) }
        val endStr = synchronized(logTimeFormat) { logTimeFormat.format(Date(endTimeMs)) }

        AppLogger.enter(
            TAG,
            "syncMissingFootageTillNow",
            "start='$startStr', end='$endStr', gapMin=$gapMinutes, fixedRtmp=${AppLogger.maskSecret(rtmpTargetFixed)}, rotRtmp=${AppLogger.maskSecret(rtmpTargetRotatable)}"
        )

        _catchupState.value = CatchupStatus(
            isCatchingUp = true,
            gapMinutes = gapMinutes,
            speedMultiplier = settings.catchupSpeed,
            statusMessage = "Catching up $gapMinutes minutes of SD card footage..."
        )

        var success = false
        try {
            val fixedSuccess = if (rtmpTargetFixed.isNotBlank()) {
                syncChannelViaRtspPlayback(
                    channel = settings.fixedChannel,
                    lensCode = "F",
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    rtmpTarget = rtmpTargetFixed
                )
            } else {
                true
            }

            val rotatableSuccess = if (rtmpTargetRotatable.isNotBlank()) {
                syncChannelViaRtspPlayback(
                    channel = settings.rotatableChannel,
                    lensCode = "R",
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    rtmpTarget = rtmpTargetRotatable
                )
            } else {
                true
            }

            success = fixedSuccess && rotatableSuccess

            if (!success) {
                AppLogger.w(TAG, "RTSP playback had warnings/errors. Checking ONVIF/CGI device info...")
                runCgiBacklogCheck(startTimeMs, endTimeMs)
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "syncMissingFootageTillNow", t)
        } finally {
            _catchupState.value = CatchupStatus(
                isCatchingUp = false,
                gapMinutes = gapMinutes,
                speedMultiplier = settings.catchupSpeed,
                statusMessage = if (success) "Catch-up completed. Handed off to Live." else "Catch-up finished with warnings."
            )
        }

        AppLogger.exit(TAG, "syncMissingFootageTillNow", "success=$success")
        success
    }

    /**
     * Fast-forwards footage stored on the SD card directly into YouTube Live at accelerated speed.
     */
    private suspend fun syncChannelViaRtspPlayback(
        channel: Int,
        lensCode: String,
        startTimeMs: Long,
        endTimeMs: Long,
        rtmpTarget: String
    ): Boolean = withContext(Dispatchers.IO) {
        AppLogger.enter(TAG, "syncChannelViaRtspPlayback[$lensCode]", "channel=$channel, rtmp=${AppLogger.maskSecret(rtmpTarget)}")
        if (rtmpTarget.isBlank()) {
            AppLogger.w(TAG, "Channel $channel ($lensCode): No RTMP target provided for catch-up.")
            AppLogger.exit(TAG, "syncChannelViaRtspPlayback[$lensCode]", false)
            return@withContext false
        }

        var bridge: RtmpsTlsBridge? = null
        try {
            val startRtsp = synchronized(rtspTimeFormat) { rtspTimeFormat.format(Date(startTimeMs)) }
            val endRtsp = synchronized(rtspTimeFormat) { rtspTimeFormat.format(Date(endTimeMs)) }

            val playbackRtspUrl = settings.buildPlaybackRtspUrl(channel, startRtsp, endRtsp)
            val probeUri = "rtsp://${settings.cameraIp}:${settings.rtspPort}/cam/playback?channel=$channel&subtype=${settings.streamSubtype}&starttime=$startRtsp&endtime=$endRtsp"
            val onvifClient = OnvifCameraClient(settings)
            val probe = onvifClient.testRtspDescribeUrl(probeUri)
            if (!probe.success) {
                AppLogger.w(TAG, "Channel $channel ($lensCode) SD playback unavailable (${probe.statusCode}: ${probe.errorMessage ?: probe.statusLine}).")
                AppLogger.exit(TAG, "syncChannelViaRtspPlayback[$lensCode]", false)
                return@withContext false
            }

            val videoCodecArgs = if (probe.videoCodec == "H265" || probe.videoCodec == "HEVC") {
                arrayOf("-c:v", "libopenh264", "-b:v", "1500k", "-g", "28", "-pix_fmt", "yuv420p")
            } else {
                arrayOf("-c:v", "copy")
            }

            val prepared = RtmpsTlsBridge.prepare(rtmpTarget, "SdBridge-$lensCode")
            bridge = prepared.bridge

            AppLogger.i(TAG, "Streaming SD backlog for Channel $channel ($lensCode) at ${settings.catchupSpeed}x speed (codec=${probe.videoCodec})...")

            val cmdParts = mutableListOf(
                "-hide_banner", "-loglevel", "warning",
                "-readrate", "${settings.catchupSpeed}",
                "-rtsp_transport", "tcp",
                "-i", playbackRtspUrl,
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-map", "0:v", "-map", "1:a"
            )
            cmdParts.addAll(videoCodecArgs)
            cmdParts.addAll(
                listOf(
                    "-c:a", "aac", "-b:a", "128k", "-ar", "44100",
                    "-shortest",
                    "-flvflags", "no_duration_filesize"
                )
            )
            prepared.tcUrl?.let { tc ->
                cmdParts.addAll(listOf("-rtmp_tcurl", tc))
            }
            cmdParts.addAll(listOf("-f", "flv", prepared.ffmpegUrl))

            val session = FFmpegKit.execute(cmdParts.joinToString(" "))
            val returnCode = session.returnCode
            val isSuccess = ReturnCode.isSuccess(returnCode)

            if (isSuccess) {
                AppLogger.i(TAG, "Channel $channel ($lensCode) backlog catch-up completed successfully. Bridge=(${bridge?.getStatsSummary() ?: "direct"})")
            } else {
                val tailLogs = try {
                    session.allLogsAsString?.takeLast(400)?.trim()
                } catch (_: Throwable) {
                    null
                }
                AppLogger.unexpected(
                    TAG,
                    "syncChannelViaRtspPlayback[$lensCode]",
                    "Catch-up FFmpeg returned non-success code $returnCode (bridge=${bridge?.getStatsSummary() ?: "direct"})",
                    tailLogs
                )
            }
            AppLogger.exit(TAG, "syncChannelViaRtspPlayback[$lensCode]", isSuccess)
            isSuccess
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "syncChannelViaRtspPlayback[$lensCode]", t)
            false
        } finally {
            try {
                bridge?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Secondary CGI check for hardware models that expose /cgi-bin/ (consumer Imou IPC-S7XE uses RTSP+ONVIF instead).
     */
    private suspend fun runCgiBacklogCheck(startTimeMs: Long, endTimeMs: Long) = withContext(Dispatchers.IO) {
        AppLogger.enter(TAG, "runCgiBacklogCheck")
        try {
            val deviceInfo = OnvifCameraClient(settings).getDeviceInfo().getOrNull()
            if (deviceInfo != null && deviceInfo.model.contains("IPC-S7XE", ignoreCase = true)) {
                AppLogger.step(TAG, "runCgiBacklogCheck", "Skipping HTTP /cgi-bin/ check: ${deviceInfo.model} serves recordings strictly over RTSP /cam/playback")
                AppLogger.exit(TAG, "runCgiBacklogCheck", "SkippedCGI_ImouModel")
                return@withContext
            }

            val objectId = cgiClient.createSearchSession()
            if (objectId != null) {
                val startFormatted = synchronized(logTimeFormat) { logTimeFormat.format(Date(startTimeMs)) }
                val endFormatted = synchronized(logTimeFormat) { logTimeFormat.format(Date(endTimeMs)) }

                val foundFixed = cgiClient.findFiles(objectId, settings.fixedChannel - 1, startFormatted, endFormatted)
                if (foundFixed) {
                    val clips = cgiClient.fetchFoundFiles(objectId, 50)
                    AppLogger.i(TAG, "Dahua CGI verified ${clips.size} clips stored on SD card for fixed lens.")
                }
                cgiClient.destroySearchSession(objectId)
            }
            AppLogger.exit(TAG, "runCgiBacklogCheck", "objectId=$objectId")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "runCgiBacklogCheck", t)
        }
    }
}
