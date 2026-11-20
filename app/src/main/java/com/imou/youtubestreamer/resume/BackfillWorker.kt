package com.imou.youtubestreamer.resume

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.Lens
import com.imou.youtubestreamer.data.WorkerExitReason
import com.imou.youtubestreamer.engine.ActiveTargetGuard
import com.imou.youtubestreamer.engine.FfmpegCommandBuilder
import com.imou.youtubestreamer.engine.RtmpsTlsBridge
import com.imou.youtubestreamer.engine.StallWatchdog
import com.imou.youtubestreamer.supervisor.SupervisorEvent
import com.imou.youtubestreamer.sync.OnvifCameraClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Resumable 10-minute chunked MicroSD card backfill worker for a single camera lens.
 *
 * Supports both:
 * - Parallel `SEPARATE_KEY` execution alongside live streaming (owned directly by supervisor)
 * - Sequential `SAME_KEY` execution via `runGapSuspend` (hosted inside `LensWorker` so live XOR backfill holds the key)
 */
class BackfillWorker(
    val lens: Lens,
    private val settings: AppSettings,
    private val probe: OnvifCameraClient,
    private val clock: Clock,
    private val targetGuard: ActiveTargetGuard,
    private val events: SendChannel<SupervisorEvent>,
    private val scope: CoroutineScope
) {
    companion object {
        private const val CHUNK_DURATION_MS = 10L * 60_000L // 10-minute resumable chunks
        private const val MAX_CHUNK_ATTEMPTS = 3
        private const val PLAIN_RTMP_STICKY_MS = 10L * 60_000L
        private const val CANCEL_WAIT_TIMEOUT_MS = 5_000L
    }

    private val tag = "Backfill${lens.code}"
    private val rtspTimeFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
    private val generation = AtomicLong(0L)
    private val currentSession = AtomicReference<FFmpegSession?>(null)
    private val currentBridge = AtomicReference<RtmpsTlsBridge?>(null)

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var activeGapId: String? = null

    @Volatile
    private var activeTarget: String = ""

    @Volatile
    private var plainRtmpUntilElapsedMs: Long = 0L

    @Volatile
    private var h265RefusedSwitch: Boolean = false

    fun isActive(): Boolean = workerJob?.isActive == true

    fun isRunningGap(gapId: String, target: String): Boolean =
        workerJob?.isActive == true && activeGapId == gapId && activeTarget == target

    fun startAsync(
        gap: Gap,
        targetRtmp: String,
        maxWallEndMs: Long? = null
    ) {
        if (isRunningGap(gap.id, targetRtmp)) {
            return
        }
        val prevJob = workerJob
        val myGen = generation.incrementAndGet()
        activeGapId = gap.id
        activeTarget = targetRtmp

        cancelCurrentHandles("restart_backfill")

        workerJob = scope.launch {
            if (prevJob != null && prevJob.isActive) {
                prevJob.cancel()
                withTimeoutOrNull(CANCEL_WAIT_TIMEOUT_MS) { prevJob.join() }
            }
            if (generation.get() != myGen) return@launch

            var outcome = WorkerExitReason.CANCELLED
            try {
                outcome = runGapSuspend(gap, targetRtmp, maxWallEndMs)
            } catch (_: CancellationException) {
                outcome = WorkerExitReason.CANCELLED
            } catch (t: Throwable) {
                AppLogger.exception(tag, "startAsync", t, "gapId=${gap.id}")
                outcome = WorkerExitReason.FFMPEG_ERROR
            } finally {
                if (generation.get() == myGen) {
                    activeGapId = null
                    activeTarget = ""
                }
                events.trySend(SupervisorEvent.BackfillFinished(lens, gap.id, outcome))
            }
        }
    }

    suspend fun stop(reason: String) {
        AppLogger.enter(tag, "stop", "reason='$reason', active=${workerJob?.isActive}")
        generation.incrementAndGet()
        activeGapId = null
        activeTarget = ""
        val job = workerJob
        workerJob = null
        job?.cancel()
        cancelCurrentHandles(reason)
        if (job != null && job.isActive) {
            val joined = withTimeoutOrNull(CANCEL_WAIT_TIMEOUT_MS) {
                job.join()
                true
            } ?: false
            if (!joined) {
                AppLogger.unexpected(tag, "stop", "Timed out awaiting backfill coroutine exit after 5s")
            }
        }
        AppLogger.exit(tag, "stop", "Stopped")
    }

    suspend fun runGapSuspend(
        gap: Gap,
        targetRtmp: String,
        maxWallEndMs: Long? = null
    ): WorkerExitReason {
        val fn = "runGapSuspend[${gap.id.take(8)}]"
        val gapEnd = gap.endWallMs ?: clock.nowMs()
        if (gapEnd <= gap.doneUpToWallMs) {
            events.trySend(
                SupervisorEvent.BackfillChunkDone(
                    lens = lens,
                    gapId = gap.id,
                    newDoneUpToWallMs = gapEnd,
                    chunkEndWallMs = gapEnd,
                    statusText = "Gap already caught up"
                )
            )
            return WorkerExitReason.SUCCESS
        }

        AppLogger.enter(
            tag,
            fn,
            "gapId=${gap.id}, remainingMin=${(gapEnd - gap.doneUpToWallMs) / 60_000L}, speed=${settings.catchupSpeed}x"
        )

        var cursor = gap.doneUpToWallMs
        val ctx = currentCoroutineContext()

        while (ctx.isActive && cursor < gapEnd) {
            if (maxWallEndMs != null && clock.nowMs() >= maxWallEndMs) {
                AppLogger.i(tag, "Rotation boundary approaching; pausing backfill cleanly at cursor=$cursor")
                AppLogger.exit(tag, fn, "PausedForRotation")
                return WorkerExitReason.CANCELLED
            }

            val chunkStart = cursor
            val chunkEnd = minOf(chunkStart + CHUNK_DURATION_MS, gapEnd)
            val startRtsp = synchronized(rtspTimeFormat) { rtspTimeFormat.format(Date(chunkStart)) }
            val endRtsp = synchronized(rtspTimeFormat) { rtspTimeFormat.format(Date(chunkEnd)) }

            val channel = settings.channelFor(lens)
            val probeUri = "rtsp://${settings.cameraIp}:${settings.rtspPort}/cam/playback?channel=$channel&subtype=${settings.streamSubtype}&starttime=$startRtsp&endtime=$endRtsp"
            val playbackRtspUrl = settings.buildPlaybackRtspUrl(channel, startRtsp, endRtsp)

            val probeRes = probe.testRtspDescribeUrl(probeUri)
            if (!probeRes.success) {
                if (probeRes.statusCode == 401) {
                    AppLogger.unexpected(tag, fn, "Playback DESCRIBE returned 401 Unauthorized")
                    return WorkerExitReason.CAMERA_AUTH_FAILED
                }
                if (probeRes.statusCode == 404 || probeRes.statusCode == 400 ||
                    probeRes.errorMessage.orEmpty().contains("404")
                ) {
                    AppLogger.i(
                        tag,
                        "No SD recording for chunk $startRtsp -> $endRtsp (status=${probeRes.statusCode}); marking chunk done."
                    )
                    cursor = chunkEnd
                    events.trySend(
                        SupervisorEvent.BackfillChunkDone(
                            lens = lens,
                            gapId = gap.id,
                            newDoneUpToWallMs = chunkEnd,
                            chunkEndWallMs = gapEnd,
                            statusText = "No SD recording for $startRtsp (skipped)"
                        )
                    )
                    continue
                }
                AppLogger.w(tag, "Camera unavailable probing SD chunk ($startRtsp): ${probeRes.errorMessage}")
                return WorkerExitReason.CAMERA_UNREACHABLE
            }

            var useH264Transcode = false
            if (probeRes.videoCodec == "H265" || probeRes.videoCodec == "HEVC") {
                if (!h265RefusedSwitch) {
                    val switched = probe.switchMainStreamsToH264()
                    if (switched.isSuccess) {
                        delay(600L)
                        val recheck = probe.testRtspDescribeUrl(probeUri)
                        if (recheck.videoCodec == "H265" || recheck.videoCodec == "HEVC") {
                            h265RefusedSwitch = true
                            useH264Transcode = true
                        }
                    } else {
                        h265RefusedSwitch = true
                        useH264Transcode = true
                    }
                } else {
                    useH264Transcode = true
                }
            }

            var chunkOutcome = WorkerExitReason.FFMPEG_ERROR
            for (chunkAttempt in 1..MAX_CHUNK_ATTEMPTS) {
                if (!ctx.isActive) return WorkerExitReason.CANCELLED
                if (maxWallEndMs != null && clock.nowMs() >= maxWallEndMs) {
                    return WorkerExitReason.CANCELLED
                }

                val effectiveTarget = resolveEffectiveTarget(targetRtmp)
                chunkOutcome = runSingleChunkAttempt(
                    playbackRtspUrl = playbackRtspUrl,
                    targetRtmp = effectiveTarget,
                    useH264Transcode = useH264Transcode,
                    chunkLabel = "$startRtsp..$endRtsp#$chunkAttempt"
                )

                when (chunkOutcome) {
                    WorkerExitReason.SUCCESS,
                    WorkerExitReason.NO_SD_RECORDING,
                    WorkerExitReason.CAMERA_AUTH_FAILED,
                    WorkerExitReason.CANCELLED -> break
                    WorkerExitReason.TLS_ERROR -> {
                        if (targetRtmp.contains("youtube.com", ignoreCase = true)) {
                            plainRtmpUntilElapsedMs = clock.elapsedMs() + PLAIN_RTMP_STICKY_MS
                            AppLogger.w(tag, "Switching backfill to plain RTMP for 10 min after TLS error")
                        }
                    }
                    else -> {}
                }

                if (chunkAttempt < MAX_CHUNK_ATTEMPTS) {
                    AppLogger.w(tag, "Chunk $startRtsp attempt #$chunkAttempt failed ($chunkOutcome); retrying in 3s...")
                    delay(3_000L)
                }
            }

            when (chunkOutcome) {
                WorkerExitReason.SUCCESS -> {
                    cursor = chunkEnd
                    val remMin = ((gapEnd - cursor).coerceAtLeast(0L)) / 60_000L
                    events.trySend(
                        SupervisorEvent.BackfillChunkDone(
                            lens = lens,
                            gapId = gap.id,
                            newDoneUpToWallMs = chunkEnd,
                            chunkEndWallMs = gapEnd,
                            statusText = if (remMin > 0) "$remMin min remaining" else "Completed"
                        )
                    )
                }
                WorkerExitReason.NO_SD_RECORDING -> {
                    cursor = chunkEnd
                    events.trySend(
                        SupervisorEvent.BackfillChunkDone(
                            lens = lens,
                            gapId = gap.id,
                            newDoneUpToWallMs = chunkEnd,
                            chunkEndWallMs = gapEnd,
                            statusText = "Chunk empty on SD (skipped)"
                        )
                    )
                }
                WorkerExitReason.CAMERA_AUTH_FAILED,
                WorkerExitReason.CANCELLED -> {
                    AppLogger.exit(tag, fn, "Aborted($chunkOutcome)")
                    return chunkOutcome
                }
                WorkerExitReason.CAMERA_UNREACHABLE,
                WorkerExitReason.INGEST_UNREACHABLE -> {
                    // Network/link issue — do NOT skip footage; pause at cursor so it resumes cleanly
                    AppLogger.w(tag, "Chunk $startRtsp paused due to link fault ($chunkOutcome); keeping resume cursor=$cursor")
                    return chunkOutcome
                }
                else -> {
                    // Per plan §5.2: after 3 local FFmpeg errors on the same chunk, skip chunk & log so queue never deadlocks
                    AppLogger.unexpected(
                        tag,
                        fn,
                        "Chunk $startRtsp failed $MAX_CHUNK_ATTEMPTS times ($chunkOutcome); skipping chunk to avoid head-of-line block"
                    )
                    cursor = chunkEnd
                    events.trySend(
                        SupervisorEvent.BackfillChunkDone(
                            lens = lens,
                            gapId = gap.id,
                            newDoneUpToWallMs = chunkEnd,
                            chunkEndWallMs = gapEnd,
                            statusText = "Skipped corrupt chunk ($startRtsp)"
                        )
                    )
                }
            }
        }

        AppLogger.exit(tag, fn, "SUCCESS")
        return WorkerExitReason.SUCCESS
    }

    private suspend fun runSingleChunkAttempt(
        playbackRtspUrl: String,
        targetRtmp: String,
        useH264Transcode: Boolean,
        chunkLabel: String
    ): WorkerExitReason {
        val owner = "BACKFILL-${lens.code}"
        if (!targetGuard.tryAcquire(targetRtmp, owner)) {
            delay(500L)
            if (!targetGuard.tryAcquire(targetRtmp, owner)) {
                return WorkerExitReason.FFMPEG_ERROR
            }
        }

        var bridge: RtmpsTlsBridge? = null
        var session: FFmpegSession? = null
        var watchdogJob: Job? = null
        val startedElapsed = clock.elapsedMs()
        val lastFrameElapsed = AtomicLong(startedElapsed)
        val lastSizeElapsed = AtomicLong(startedElapsed)
        val lastFrameNum = AtomicLong(-1L)
        val lastBytesSize = AtomicLong(-1L)
        val stalledFlag = AtomicBoolean(false)

        try {
            val prepared = RtmpsTlsBridge.prepare(targetRtmp, "SdBridge-${lens.code}", clock)
            bridge = prepared.bridge
            currentBridge.set(bridge)

            val args = FfmpegCommandBuilder.buildBackfillCommand(
                playbackRtspUrl = playbackRtspUrl,
                outputUrl = prepared.ffmpegUrl,
                tcUrl = prepared.tcUrl,
                catchupSpeed = settings.catchupSpeed,
                useH264Transcode = useH264Transcode
            )

            AppLogger.i(tag, "Streaming SD chunk $chunkLabel at ${settings.catchupSpeed}x")
            val finishedDeferred = CompletableDeferred<FFmpegSession>()

            val created = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { completed -> finishedDeferred.complete(completed) },
                { log ->
                    try {
                        val msg = log.message.trim()
                        if (msg.isNotEmpty()) {
                            if (msg.contains("error", ignoreCase = true) ||
                                msg.contains("fatal", ignoreCase = true) ||
                                msg.contains("failed", ignoreCase = true)
                            ) {
                                AppLogger.e("FFmpeg-Bf${lens.code}", msg)
                            } else {
                                AppLogger.ffmpeg("FFmpeg-Bf${lens.code}", msg)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                },
                { stats ->
                    val now = clock.elapsedMs()
                    val frame = stats.videoFrameNumber.toLong()
                    if (frame > lastFrameNum.get()) {
                        lastFrameNum.set(frame)
                        lastFrameElapsed.set(now)
                    }
                    if (stats.size > lastBytesSize.get()) {
                        lastBytesSize.set(stats.size)
                        lastSizeElapsed.set(now)
                    }
                }
            )
            session = created
            currentSession.set(created)

            val activeBridge = bridge
            watchdogJob = scope.launch {
                StallWatchdog.watchForStall(
                    clock = clock,
                    startedAtElapsedMs = startedElapsed,
                    lastFrameProgressElapsedMs = { lastFrameElapsed.get() },
                    lastBytesProgressElapsedMs = { activeBridge?.lastUpstreamProgressElapsedMs ?: lastSizeElapsed.get() }
                ) { detail ->
                    stalledFlag.set(true)
                    AppLogger.unexpected(tag, "chunkWatchdog[$chunkLabel]", detail)
                    try {
                        FFmpegKit.cancel(created.sessionId)
                    } catch (_: Throwable) {
                    }
                    try {
                        activeBridge?.close()
                    } catch (_: Throwable) {
                    }
                }
            }

            val completed = finishedDeferred.await()
            watchdogJob.cancel()

            val rc = completed.returnCode
            val isSuccess = ReturnCode.isSuccess(rc)
            val isCancel = ReturnCode.isCancel(rc)
            val tail = if (!isSuccess && !isCancel) {
                try {
                    completed.allLogsAsString?.takeLast(600)?.trim()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }

            val reason = FfmpegCommandBuilder.classifyOutcome(
                isSuccess = isSuccess,
                isCancel = isCancel,
                stalled = stalledFlag.get(),
                probeResult = null,
                bridgeErrorType = bridge?.lastErrorType ?: com.imou.youtubestreamer.engine.BridgeErrorType.NONE,
                logTail = tail
            )
            AppLogger.step(tag, "chunk[$chunkLabel]", "Completed: reason=$reason, rc=$rc, bridge=(${bridge?.getStatsSummary() ?: "direct"})")
            return reason
        } catch (ce: CancellationException) {
            session?.let { s ->
                try {
                    FFmpegKit.cancel(s.sessionId)
                } catch (_: Throwable) {
                }
            }
            bridge?.close()
            throw ce
        } catch (t: Throwable) {
            AppLogger.exception(tag, "runSingleChunkAttempt[$chunkLabel]", t)
            return WorkerExitReason.FFMPEG_ERROR
        } finally {
            watchdogJob?.cancel()
            session?.let { s ->
                currentSession.compareAndSet(s, null)
                try {
                    val state = s.state
                    if (state == SessionState.RUNNING || state == SessionState.CREATED) {
                        FFmpegKit.cancel(s.sessionId)
                    }
                } catch (_: Throwable) {
                }
            }
            bridge?.let { b ->
                currentBridge.compareAndSet(b, null)
                try {
                    b.close()
                } catch (_: Throwable) {
                }
            }
            targetGuard.release(targetRtmp, owner)
        }
    }

    private fun resolveEffectiveTarget(rawTarget: String): String {
        if (clock.elapsedMs() < plainRtmpUntilElapsedMs &&
            rawTarget.startsWith("rtmps://a.rtmps.youtube.com/", ignoreCase = true)
        ) {
            return "rtmp://a.rtmp.youtube.com/" + rawTarget.substringAfter("rtmps://a.rtmps.youtube.com/")
        }
        return rawTarget
    }

    private fun cancelCurrentHandles(reason: String) {
        currentSession.get()?.let { s ->
            try {
                AppLogger.step(tag, "cancelCurrentHandles", "Cancelling sessionId=${s.sessionId} ($reason)")
                FFmpegKit.cancel(s.sessionId)
            } catch (_: Throwable) {
            }
        }
        currentBridge.get()?.let { b ->
            try {
                b.close()
            } catch (_: Throwable) {
            }
        }
    }
}
