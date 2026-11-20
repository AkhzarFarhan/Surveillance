package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.WorkerExitReason
import com.imou.youtubestreamer.sync.RtspProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    @Test
    fun `buildLiveCommand includes timeouts, wallclock timestamps, silent audio and tcurl`() {
        val args = FfmpegCommandBuilder.buildLiveCommand(
            rtspSource = "rtsp://admin:pass@192.168.1.2:554/cam/realmonitor?channel=2&subtype=0",
            outputUrl = "rtmp://127.0.0.1:45123/live2/abc-123",
            tcUrl = "rtmps://a.rtmps.youtube.com/live2",
            silentAudioEnabled = true,
            useH264Transcode = false
        )
        assertTrue(args.contains("-rtsp_transport") && args.contains("tcp"))
        assertTrue(args.contains("-timeout") && args.contains(FfmpegCommandBuilder.RTSP_TIMEOUT_US))
        assertTrue(args.contains("-rw_timeout") && args.contains(FfmpegCommandBuilder.RTMP_RW_TIMEOUT_US))
        assertTrue(args.contains("-use_wallclock_as_timestamps"))
        assertEquals("1", args[args.indexOf("-use_wallclock_as_timestamps") + 1])
        assertTrue(args.contains("anullsrc=channel_layout=stereo:sample_rate=44100"))
        assertTrue(args.contains("-c:v") && args.contains("copy"))
        assertTrue(args.contains("-rtmp_tcurl") && args.contains("rtmps://a.rtmps.youtube.com/live2"))
        assertEquals("rtmp://127.0.0.1:45123/live2/abc-123", args.last())
    }

    @Test
    fun `buildLiveCommand transcode uses libopenh264 when needed`() {
        val args = FfmpegCommandBuilder.buildLiveCommand(
            rtspSource = "rtsp://cam/live",
            outputUrl = "rtmp://127.0.0.1:5000/live2/key",
            tcUrl = null,
            silentAudioEnabled = false,
            useH264Transcode = true
        )
        assertTrue(args.contains("libopenh264"))
        assertFalse(args.contains("-rtmp_tcurl"))
        assertFalse(args.contains("anullsrc=channel_layout=stereo:sample_rate=44100"))
    }

    @Test
    fun `buildBackfillCommand uses readrate, shortest, source timestamps 0 and timeouts`() {
        val args = FfmpegCommandBuilder.buildBackfillCommand(
            playbackRtspUrl = "rtsp://cam/cam/playback",
            outputUrl = "rtmp://127.0.0.1:45123/live2/bf-key",
            tcUrl = "rtmps://a.rtmps.youtube.com/live2",
            catchupSpeed = 3.0f,
            useH264Transcode = false
        )
        assertEquals("3.0", args[args.indexOf("-readrate") + 1])
        assertEquals("0", args[args.indexOf("-use_wallclock_as_timestamps") + 1])
        assertTrue(args.contains("-shortest"))
        assertTrue(args.contains("-timeout"))
        assertTrue(args.contains("-rw_timeout"))
    }

    @Test
    fun `classifyOutcome distinguishes 401 auth, 404 empty SD, DNS unreachable, TLS handshake, stall, and cancel`() {
        assertEquals(
            WorkerExitReason.CAMERA_AUTH_FAILED,
            FfmpegCommandBuilder.classifyOutcome(
                isSuccess = false, isCancel = false, stalled = false,
                probeResult = RtspProbeResult(false, 401, "RTSP/1.0 401 Unauthorized", errorMessage = "401 Unauthorized")
            )
        )
        assertEquals(
            WorkerExitReason.NO_SD_RECORDING,
            FfmpegCommandBuilder.classifyOutcome(
                isSuccess = false, isCancel = false, stalled = false,
                probeResult = RtspProbeResult(false, 404, "RTSP/1.0 404 Not Found", errorMessage = "Not found")
            )
        )
        assertEquals(
            WorkerExitReason.INGEST_UNREACHABLE,
            FfmpegCommandBuilder.classifyOutcome(
                isSuccess = false, isCancel = false, stalled = false,
                bridgeErrorType = BridgeErrorType.DNS_UNREACHABLE
            )
        )
        assertEquals(
            WorkerExitReason.TLS_ERROR,
            FfmpegCommandBuilder.classifyOutcome(
                isSuccess = false, isCancel = false, stalled = false,
                bridgeErrorType = BridgeErrorType.TLS_HANDSHAKE
            )
        )
        assertEquals(
            WorkerExitReason.STALLED,
            FfmpegCommandBuilder.classifyOutcome(
                isSuccess = false, isCancel = true, stalled = true
            )
        )
        assertEquals(
            WorkerExitReason.CANCELLED,
            FfmpegCommandBuilder.classifyOutcome(
                isSuccess = false, isCancel = true, stalled = false
            )
        )
    }
}
