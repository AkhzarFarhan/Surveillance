package com.imou.youtubestreamer.sync

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.CatchupStatus
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
 * directly into YouTube Live until playback reaches real-time.
 */
class DahuaSdCardSyncManager(
    private val context: Context,
    private val settings: AppSettings
) {

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
        val startStr = logTimeFormat.format(Date(startTimeMs))
        val endStr = logTimeFormat.format(Date(endTimeMs))

        AppLogger.i("SdCardSync", "Initiating MicroSD catch-up for outage: $startStr -> $endStr ($gapMinutes mins)")

        _catchupState.value = CatchupStatus(
            isCatchingUp = true,
            gapMinutes = gapMinutes,
            speedMultiplier = settings.catchupSpeed,
            statusMessage = "Catching up $gapMinutes minutes of SD card footage..."
        )

        var success = false
        try {
            // Strategy A: Direct High-Speed RTSP Playback Stream (Fastest & Zero Disk I/O)
            val fixedSuccess = syncChannelViaRtspPlayback(
                channel = settings.fixedChannel,
                lensCode = "F",
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                rtmpTarget = rtmpTargetFixed
            )

            val rotatableSuccess = syncChannelViaRtspPlayback(
                channel = settings.rotatableChannel,
                lensCode = "R",
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                rtmpTarget = rtmpTargetRotatable
            )

            success = fixedSuccess && rotatableSuccess

            // Strategy B Fallback: If RTSP playback failed, use Dahua CGI file query
            if (!success) {
                AppLogger.w("SdCardSync", "RTSP playback had errors. Attempting Dahua CGI fallback check...")
                runCgiBacklogCheck(startTimeMs, endTimeMs)
            }
        } catch (e: Exception) {
            AppLogger.e("SdCardSync", "Error during backlog catchup: ${e.message}")
        } finally {
            _catchupState.value = CatchupStatus(
                isCatchingUp = false,
                gapMinutes = gapMinutes,
                speedMultiplier = settings.catchupSpeed,
                statusMessage = if (success) "Catch-up completed. Handed off to Live." else "Catch-up finished with warnings."
            )
        }

        AppLogger.i("SdCardSync", "MicroSD catch-up finished. Ready for real-time live video.")
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
        if (rtmpTarget.isBlank()) {
            AppLogger.w("SdCardSync", "Channel $channel ($lensCode): No RTMP target provided for catch-up.")
            return@withContext false
        }

        val startRtsp = rtspTimeFormat.format(Date(startTimeMs))
        val endRtsp = rtspTimeFormat.format(Date(endTimeMs))

        val playbackRtspUrl = settings.buildPlaybackRtspUrl(channel, startRtsp, endRtsp)
        val onvifClient = OnvifCameraClient(settings)
        val probe = onvifClient.testRtspDescribe(channel, settings.streamSubtype)
        val videoCodecArgs = if (probe.videoCodec == "H265" || probe.videoCodec == "HEVC") {
            arrayOf("-c:v", "libopenh264", "-b:v", "1500k", "-g", "28", "-pix_fmt", "yuv420p")
        } else {
            arrayOf("-c:v", "copy")
        }

        AppLogger.i("SdCardSync", "Streaming SD backlog for Channel $channel ($lensCode) at ${settings.catchupSpeed}x speed...")

        val cmd = (arrayOf(
            "-hide_banner", "-loglevel", "warning",
            "-readrate", "${settings.catchupSpeed}",
            "-rtsp_transport", "tcp",
            "-i", playbackRtspUrl,
            "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
            "-map", "0:v", "-map", "1:a"
        ) + videoCodecArgs + arrayOf(
            "-c:a", "aac", "-b:a", "128k", "-ar", "44100",
            "-f", "flv", rtmpTarget
        )).joinToString(" ")

        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        val isSuccess = ReturnCode.isSuccess(returnCode)

        if (isSuccess) {
            AppLogger.i("SdCardSync", "Channel $channel ($lensCode) backlog catch-up completed successfully.")
        } else {
            AppLogger.w("SdCardSync", "Channel $channel ($lensCode) catch-up ended with code: $returnCode (State: ${session.state})")
        }
        isSuccess
    }

    /**
     * Dahua CGI Fallback: queries the file index on the SD card for diagnostic verification.
     */
    private suspend fun runCgiBacklogCheck(startTimeMs: Long, endTimeMs: Long) = withContext(Dispatchers.IO) {
        try {
            val objectId = cgiClient.createSearchSession()
            if (objectId != null) {
                val startFormatted = logTimeFormat.format(Date(startTimeMs))
                val endFormatted = logTimeFormat.format(Date(endTimeMs))

                val foundFixed = cgiClient.findFiles(objectId, settings.fixedChannel - 1, startFormatted, endFormatted)
                if (foundFixed) {
                    val clips = cgiClient.fetchFoundFiles(objectId, 50)
                    AppLogger.i("SdCardSync", "Dahua CGI verified ${clips.size} clips stored on SD card for fixed lens.")
                }
                cgiClient.destroySearchSession(objectId)
            }
        } catch (e: Exception) {
            AppLogger.w("SdCardSync", "CGI backlog query error: ${e.message}")
        }
    }
}
