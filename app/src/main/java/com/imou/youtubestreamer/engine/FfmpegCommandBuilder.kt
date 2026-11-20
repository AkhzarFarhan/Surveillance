package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.WorkerExitReason
import com.imou.youtubestreamer.sync.RtspProbeResult

/**
 * Pure, unit-testable builder for FFmpeg live and SD-card backfill argument arrays
 * and exit-reason classifier.
 *
 * Uses argument lists (never shell string concatenation) so URLs containing special
 * characters execute safely via `FFmpegKit.executeWithArgumentsAsync`.
 */
object FfmpegCommandBuilder {

    const val RTSP_TIMEOUT_US = "10000000"   // 10 s TCP socket I/O timeout for RTSP
    const val RTMP_RW_TIMEOUT_US = "15000000" // 15 s socket write/read timeout on RTMP output

    fun buildLiveCommand(
        rtspSource: String,
        outputUrl: String,
        tcUrl: String?,
        silentAudioEnabled: Boolean,
        useH264Transcode: Boolean
    ): List<String> {
        val args = mutableListOf(
            "-hide_banner", "-loglevel", "warning",
            "-rtsp_transport", "tcp",
            "-timeout", RTSP_TIMEOUT_US,
            "-fflags", "+genpts+discardcorrupt",
            "-use_wallclock_as_timestamps", "1",
            "-i", rtspSource
        )

        val videoCodecArgs = if (useH264Transcode) {
            listOf(
                "-c:v", "libopenh264",
                "-b:v", "1500k",
                "-maxrate", "2000k",
                "-bufsize", "3000k",
                "-g", "28",
                "-pix_fmt", "yuv420p"
            )
        } else {
            listOf("-c:v", "copy")
        }

        if (silentAudioEnabled) {
            args.addAll(
                listOf(
                    "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                    "-map", "0:v", "-map", "1:a"
                )
            )
        }

        args.addAll(videoCodecArgs)
        args.addAll(listOf("-c:a", "aac", "-b:a", "128k", "-ar", "44100"))
        args.addAll(
            listOf(
                "-flvflags", "no_duration_filesize",
                "-rtmp_live", "live",
                "-rw_timeout", RTMP_RW_TIMEOUT_US
            )
        )
        if (!tcUrl.isNullOrBlank()) {
            args.addAll(listOf("-rtmp_tcurl", tcUrl))
        }
        args.addAll(listOf("-f", "flv", outputUrl))
        return args
    }

    fun buildBackfillCommand(
        playbackRtspUrl: String,
        outputUrl: String,
        tcUrl: String?,
        catchupSpeed: Float,
        useH264Transcode: Boolean
    ): List<String> {
        val safeSpeed = catchupSpeed.coerceIn(1.0f, 8.0f)
        val args = mutableListOf(
            "-hide_banner", "-loglevel", "warning",
            "-readrate", safeSpeed.toString(),
            "-rtsp_transport", "tcp",
            "-timeout", RTSP_TIMEOUT_US,
            "-fflags", "+genpts+discardcorrupt",
            "-use_wallclock_as_timestamps", "0",
            "-i", playbackRtspUrl,
            "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
            "-map", "0:v", "-map", "1:a"
        )

        val videoCodecArgs = if (useH264Transcode) {
            listOf(
                "-c:v", "libopenh264",
                "-b:v", "1500k",
                "-g", "28",
                "-pix_fmt", "yuv420p"
            )
        } else {
            listOf("-c:v", "copy")
        }

        args.addAll(videoCodecArgs)
        args.addAll(
            listOf(
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100",
                "-shortest",
                "-flvflags", "no_duration_filesize",
                "-rw_timeout", RTMP_RW_TIMEOUT_US
            )
        )
        if (!tcUrl.isNullOrBlank()) {
            args.addAll(listOf("-rtmp_tcurl", tcUrl))
        }
        args.addAll(listOf("-f", "flv", outputUrl))
        return args
    }

    fun classifyOutcome(
        isSuccess: Boolean,
        isCancel: Boolean,
        stalled: Boolean,
        probeResult: RtspProbeResult? = null,
        bridgeErrorType: BridgeErrorType = BridgeErrorType.NONE,
        logTail: String? = null
    ): WorkerExitReason {
        if (stalled || bridgeErrorType == BridgeErrorType.STALLED) {
            return WorkerExitReason.STALLED
        }
        if (isCancel) {
            return WorkerExitReason.CANCELLED
        }
        if (isSuccess) {
            return WorkerExitReason.SUCCESS
        }

        if (probeResult != null && !probeResult.success) {
            when (probeResult.statusCode) {
                401 -> return WorkerExitReason.CAMERA_AUTH_FAILED
                404 -> return WorkerExitReason.NO_SD_RECORDING
            }
            val msg = probeResult.errorMessage.orEmpty().lowercase()
            if (msg.contains("401") || msg.contains("unauthorized")) {
                return WorkerExitReason.CAMERA_AUTH_FAILED
            }
            if (msg.contains("404") || msg.contains("not found")) {
                return WorkerExitReason.NO_SD_RECORDING
            }
            return WorkerExitReason.CAMERA_UNREACHABLE
        }

        when (bridgeErrorType) {
            BridgeErrorType.DNS_UNREACHABLE,
            BridgeErrorType.CONNECT_UNREACHABLE -> return WorkerExitReason.INGEST_UNREACHABLE
            BridgeErrorType.TLS_HANDSHAKE -> return WorkerExitReason.TLS_ERROR
            BridgeErrorType.STALLED -> return WorkerExitReason.STALLED
            else -> {}
        }

        val tailLower = logTail.orEmpty().lowercase()
        return when {
            tailLower.contains("401 unauthorized") -> WorkerExitReason.CAMERA_AUTH_FAILED
            tailLower.contains("404 not found") || tailLower.contains("server returned 404") ->
                WorkerExitReason.NO_SD_RECORDING
            tailLower.contains("rtsp://") && (
                tailLower.contains("connection refused") ||
                    tailLower.contains("timed out") ||
                    tailLower.contains("no route to host") ||
                    tailLower.contains("network is unreachable") ||
                    tailLower.contains("end of file")
                ) -> WorkerExitReason.CAMERA_UNREACHABLE
            tailLower.contains("rtmp://") || tailLower.contains("rtmps://") ||
                tailLower.contains("broken pipe") ||
                tailLower.contains("connection reset by peer") ||
                tailLower.contains("failed to update header") ||
                tailLower.contains("connection refused") ||
                tailLower.contains("cannot open connection") -> WorkerExitReason.INGEST_UNREACHABLE
            tailLower.contains("unknownhostexception") || tailLower.contains("name or service not known") ->
                WorkerExitReason.INGEST_UNREACHABLE
            else -> WorkerExitReason.FFMPEG_ERROR
        }
    }
}
