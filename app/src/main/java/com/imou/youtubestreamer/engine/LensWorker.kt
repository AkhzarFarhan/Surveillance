package com.imou.youtubestreamer.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.Lens
import com.imou.youtubestreamer.data.LensMode
import com.imou.youtubestreamer.data.LensStreamState
import com.imou.youtubestreamer.data.WorkerExitReason
import com.imou.youtubestreamer.resume.BackfillWorker
import com.imou.youtubestreamer.resume.Gap
import com.imou.youtubestreamer.supervisor.Backoff
import com.imou.youtubestreamer.supervisor.SupervisorEvent
import com.imou.youtubestreamer.supervisor.WorkerMode
import com.imou.youtubestreamer.sync.OnvifCameraClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-contained streaming worker for a single camera lens (`_F` or `_R`).
 *
 * Enforces:
 * - Zero shared mutable state across lenses or attempts (`AtomicReference` compare-and-clear + generation token)
 * - Live XOR Same-Key Backfill execution within one Job so two FFmpeg publishers can never touch one key
 * - Stall watchdog monitoring both frame advance & upstream byte throughput
 * - Classified errors & exponential backoff with jitter (including >= 5 min cooldown on 401 auth errors)
 */
class LensWorker(
    val lens: Lens,
    private val settings: AppSettings,
    private val probe: OnvifCameraClient,
    private val clock: Clock,
    private val targetGuard: ActiveTargetGuard,
    private val events: SendChannel<SupervisorEvent>,
    private val scope: CoroutineScope
) {
    companion object {
        private const val CANCEL_AWAIT_MS = 5_000L
        private const val PLAIN_RTMP_STICKY_MS = 10L * 60_000L
        private const val HEALTHY_HEARTBEAT_INTERVAL_MS = 10_000L
    }

    private val tag = "Worker${lens.code}"

    private val _state = MutableStateFlow(
        LensStreamState(
            lensName = lens.displayName,
            lensCode = lens.code,
            channel = settings.channelFor(lens)
        )
    )
    val state: StateFlow<LensStreamState> = _state.asStateFlow()

    @Volatile
    var lastHealthyFrameWallMs: Long = 0L
        private set

    @Volatile
    private var forceSubstream: Boolean = false

    @Volatile
    private var activeMode: WorkerMode? = null

    @Volatile
    private var activeTarget: String = ""

    @Volatile
    private var activeTitle: String = ""

    @Volatile
    private var activeGapId: String? = null

    @Volatile
    private var workerJob: Job? = null

    private val generation = AtomicLong(0L)
    private val currentSession = AtomicReference<FFmpegSession?>(null)
    private val currentBridge = AtomicReference<RtmpsTlsBridge?>(null)
    private val backoff = Backoff()

    @Volatile
    private var h265RefusedSwitch: Boolean = false

    @Volatile
    private var plainRtmpUntilElapsedMs: Long = 0L

    fun isRunningLive(target: String, title: String): Boolean {
        return workerJob?.isActive == true &&
            activeMode == WorkerMode.LIVE &&
            activeTarget == target &&
            activeTitle == title
    }

    fun isRunningSameKeyBackfill(gapId: String, target: String): Boolean {
        return workerJob?.isActive == true &&
            activeMode == WorkerMode.BACKFILL &&
            activeGapId == gapId &&
            activeTarget == target
    }

    fun setForceSubstream(force: Boolean) {
        if (forceSubstream != force) {
            forceSubstream = force
            AppLogger.w(tag, "Thermal/Memory pressure: forceSubstream=$forceSubstream")
        }
    }

    fun startLive(target: String, title: String) {
        if (target.isBlank()) {
            AppLogger.w(tag, "Blank target RTMP for ${lens.code}; ignoring startLive.")
            return
        }
        if (isRunningLive(target, title)) {
            return // Idempotent no-op
        }

        val prevJob = workerJob
        val myGen = generation.incrementAndGet()
        activeMode = WorkerMode.LIVE
        activeTarget = target
        activeTitle = title
        activeGapId = null
        backoff.reset()

        cancelActiveHandles("startLive_reconfigure")

        workerJob = scope.launch {
            if (prevJob != null && prevJob.isActive) {
                prevJob.cancel()
                withTimeoutOrNull(CANCEL_AWAIT_MS) { prevJob.join() }
            }
            if (generation.get() == myGen) {
                runLiveLoop(myGen, target, title)
            }
        }
    }

    fun startBackfill(
        gap: Gap,
        target: String,
        backfillWorker: BackfillWorker,
        maxWallEndMs: Long? = null
    ) {
        if (target.isBlank()) return
        if (isRunningSameKeyBackfill(gap.id, target)) {
            return
        }

        val prevJob = workerJob
        val myGen = generation.incrementAndGet()
        activeMode = WorkerMode.BACKFILL
        activeTarget = target
        activeTitle = "SD Catch-Up"
        activeGapId = gap.id

        cancelActiveHandles("startBackfill_switch")

        workerJob = scope.launch {
            if (prevJob != null && prevJob.isActive) {
                prevJob.cancel()
                withTimeoutOrNull(CANCEL_AWAIT_MS) { prevJob.join() }
            }
            if (generation.get() != myGen) return@launch

            _state.value = _state.value.copy(
                channel = settings.channelFor(lens),
                isStreaming = false,
                isBackfilling = true,
                activeTitle = "SD Backfill (${gap.remainingMs(clock.nowMs()) / 60_000L}m left)",
                mode = LensMode.BACKFILL,
                lastError = null
            )

            var exitReason = WorkerExitReason.CANCELLED
            val startWall = clock.nowMs()
            try {
                exitReason = backfillWorker.runGapSuspend(gap, target, maxWallEndMs)
            } catch (_: CancellationException) {
                exitReason = WorkerExitReason.CANCELLED
            } catch (t: Throwable) {
                AppLogger.exception(tag, "startBackfill", t, "gapId=${gap.id}")
                exitReason = WorkerExitReason.FFMPEG_ERROR
            } finally {
                val endWall = clock.nowMs()
                if (generation.get() == myGen) {
                    activeMode = null
                    activeGapId = null
                    activeTarget = ""
                }
                _state.value = _state.value.copy(
                    isBackfilling = false,
                    lastExitReason = exitReason,
                    lastError = if (exitReason != WorkerExitReason.SUCCESS && exitReason != WorkerExitReason.CANCELLED) {
                        exitReason.userMessage
                    } else {
                        null
                    }
                )
                events.trySend(SupervisorEvent.BackfillFinished(lens, gap.id, exitReason))
                events.trySend(
                    SupervisorEvent.WorkerExited(
                        lens = lens,
                        mode = WorkerMode.BACKFILL,
                        reason = exitReason,
                        startedAtWallMs = startWall,
                        endedAtWallMs = endWall,
                        wasEverHealthy = exitReason == WorkerExitReason.SUCCESS,
                        lastHealthyWallMs = lastHealthyFrameWallMs,
                        detail = "gapId=${gap.id}"
                    )
                )
            }
        }
    }

    suspend fun stop(reason: String) {
        AppLogger.enter(tag, "stop", "reason='$reason', active=${workerJob?.isActive}")
        generation.incrementAndGet()
        activeMode = null
        activeTarget = ""
        activeTitle = ""
        activeGapId = null

        val job = workerJob
        workerJob = null
        job?.cancel()
        cancelActiveHandles(reason)

        if (job != null && job.isActive) {
            val joined = withTimeoutOrNull(CANCEL_AWAIT_MS) {
                job.join()
                true
            } ?: false
            if (!joined) {
                AppLogger.unexpected(tag, "stop", "Abandoning wedged native worker handle after 5s timeout")
            }
        }

        _state.value = _state.value.copy(
            isStreaming = false,
            isBackfilling = false,
            mode = LensMode.OFF
        )
        AppLogger.exit(tag, "stop", "Stopped")
    }

    fun updateMeta(
        mode: LensMode,
        pendingGapMinutes: Long,
        isBackfilling: Boolean = _state.value.isBackfilling,
        backfillStatus: String = _state.value.backfillStatus
    ) {
        _state.value = _state.value.copy(
            channel = settings.channelFor(lens),
            mode = mode,
            pendingGapMinutes = pendingGapMinutes,
            isBackfilling = isBackfilling,
            backfillStatus = backfillStatus
        )
    }

    private suspend fun runLiveLoop(
        myGen: Long,
        target: String,
        title: String
    ) {
        val ctx = currentCoroutineContext()
        var attempt = 0

        while (ctx.isActive && generation.get() == myGen) {
            attempt++
            val effectiveTarget = if (clock.elapsedMs() < plainRtmpUntilElapsedMs &&
                target.startsWith("rtmps://a.rtmps.youtube.com/", ignoreCase = true)
            ) {
                "rtmp://a.rtmp.youtube.com/" + target.substringAfter("rtmps://a.rtmps.youtube.com/")
            } else {
                target
            }

            val attemptStartElapsed = clock.elapsedMs()
            val result = runSingleLiveAttempt(
                myGen = myGen,
                attempt = attempt,
                target = effectiveTarget,
                title = title
            )

            if (generation.get() != myGen || !ctx.isActive || result.reason == WorkerExitReason.CANCELLED) {
                AppLogger.step(tag, "runLiveLoop", "Exiting loop cleanly (genMatch=${generation.get() == myGen}, reason=${result.reason})")
                break
            }

            if (result.reason == WorkerExitReason.TLS_ERROR &&
                target.contains("youtube.com", ignoreCase = true)
            ) {
                plainRtmpUntilElapsedMs = clock.elapsedMs() + PLAIN_RTMP_STICKY_MS
                AppLogger.w(tag, "TLS handshake failed; activating plain RTMP fallback for 10 minutes")
            }

            val ranMs = clock.elapsedMs() - attemptStartElapsed
            if (result.wasEverHealthy) {
                backoff.recordHealthyDuration(ranMs)
            }

            val delayMs = backoff.nextDelayMs(result.reason)
            AppLogger.w(
                tag,
                "Attempt #$attempt exited (${result.reason}); retrying in ${delayMs / 1000}s..."
            )
            delay(delayMs)
        }
    }

    private data class AttemptOutcome(
        val reason: WorkerExitReason,
        val wasEverHealthy: Boolean
    )

    private suspend fun runSingleLiveAttempt(
        myGen: Long,
        attempt: Int,
        target: String,
        title: String
    ): AttemptOutcome {
        val fn = "attempt#$attempt"
        val channel = settings.channelFor(lens)
        val subtype = if (forceSubstream) 1 else settings.streamSubtype
        val attemptStartWall = clock.nowMs()
        val attemptStartElapsed = clock.elapsedMs()

        AppLogger.enter(tag, fn, "channel=$channel, subtype=$subtype, scheme=${target.substringBefore("://")}")

        val everHealthy = AtomicBoolean(false)
        val lastHealthyElapsed = AtomicLong(0L)
        val lastHealthyEmitElapsed = AtomicLong(0L)
        val lastFrameNum = AtomicLong(-1L)
        val lastFrameElapsed = AtomicLong(attemptStartElapsed)
        val lastBytesSize = AtomicLong(-1L)
        val lastSizeElapsed = AtomicLong(attemptStartElapsed)
        val stalledFlag = AtomicBoolean(false)

        var bridge: RtmpsTlsBridge? = null
        var session: FFmpegSession? = null
        var watchdogJob: Job? = null
        var acquiredGuard = false
        val owner = "LIVE-${lens.code}"

        _state.value = _state.value.copy(
            channel = channel,
            isStreaming = true,
            activeTitle = title,
            mode = LensMode.LIVE,
            lastError = null
        )

        try {
            // Step 1: Probe RTSP & codec
            var useH264Transcode = false
            val probeResult = probe.testRtspDescribe(channel, subtype)
            AppLogger.step(
                tag,
                fn,
                "RTSP probe: ok=${probeResult.success}, code=${probeResult.statusCode}, video=${probeResult.videoCodec}, err=${probeResult.errorMessage}"
            )

            if (!probeResult.success) {
                val reason = FfmpegCommandBuilder.classifyOutcome(
                    isSuccess = false,
                    isCancel = generation.get() != myGen,
                    stalled = false,
                    probeResult = probeResult
                )
                _state.value = _state.value.copy(
                    isStreaming = false,
                    lastExitReason = reason,
                    lastError = reason.userMessage
                )
                events.trySend(
                    SupervisorEvent.WorkerExited(
                        lens = lens,
                        mode = WorkerMode.LIVE,
                        reason = reason,
                        startedAtWallMs = attemptStartWall,
                        endedAtWallMs = clock.nowMs(),
                        wasEverHealthy = false,
                        lastHealthyWallMs = lastHealthyFrameWallMs,
                        detail = probeResult.errorMessage.orEmpty()
                    )
                )
                return AttemptOutcome(reason, false)
            }

            if (probeResult.videoCodec == "H265" || probeResult.videoCodec == "HEVC") {
                if (!forceSubstream && !h265RefusedSwitch) {
                    val switched = probe.switchMainStreamsToH264()
                    if (switched.isSuccess) {
                        delay(600L)
                        val recheck = probe.testRtspDescribe(channel, subtype)
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
                if (useH264Transcode) {
                    AppLogger.w(tag, "Camera stream remained H.265; using OpenH264 transcoding (substream=$forceSubstream)")
                }
            }

            // Step 2: Acquire single-publisher target guard & prepare RTMPS TLS bridge
            if (!targetGuard.tryAcquire(target, owner)) {
                delay(400L)
                if (!targetGuard.tryAcquire(target, owner)) {
                    val err = "Stream key already held by another publisher"
                    _state.value = _state.value.copy(isStreaming = false, lastExitReason = WorkerExitReason.FFMPEG_ERROR, lastError = err)
                    return AttemptOutcome(WorkerExitReason.FFMPEG_ERROR, false)
                }
            }
            acquiredGuard = true

            val prepared = RtmpsTlsBridge.prepare(target, "Bridge-${lens.code}", clock)
            bridge = prepared.bridge
            currentBridge.set(bridge)

            val rtspUrl = settings.buildLiveRtspUrl(channel, subtype)
            val cmdArgs = FfmpegCommandBuilder.buildLiveCommand(
                rtspSource = rtspUrl,
                outputUrl = prepared.ffmpegUrl,
                tcUrl = prepared.tcUrl,
                silentAudioEnabled = settings.silentAudioEnabled,
                useH264Transcode = useH264Transcode
            )

            AppLogger.i(tag, "Launching live FFmpeg pipeline (args=${cmdArgs.size}, transcode=$useH264Transcode, subtype=$subtype)")
            val finishedDeferred = CompletableDeferred<FFmpegSession>()
            var lastStatsLogElapsed = 0L

            val createdSession = FFmpegKit.executeWithArgumentsAsync(
                cmdArgs.toTypedArray(),
                { completed -> finishedDeferred.complete(completed) },
                { log ->
                    try {
                        val msg = log.message.trim()
                        if (msg.isNotEmpty()) {
                            if (msg.contains("error", ignoreCase = true) ||
                                msg.contains("fatal", ignoreCase = true) ||
                                msg.contains("failed", ignoreCase = true) ||
                                msg.contains("protocol not found", ignoreCase = true)
                            ) {
                                AppLogger.e("FFmpeg-${lens.code}", msg)
                            } else {
                                AppLogger.ffmpeg("FFmpeg-${lens.code}", msg)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                },
                { stats ->
                    try {
                        val elapsed = clock.elapsedMs()
                        val wall = clock.nowMs()
                        val frame = stats.videoFrameNumber.toLong()
                        val fps = stats.videoFps.toDouble()
                        val bitrate = stats.bitrate.toDouble()
                        val uptimeSec = (elapsed - attemptStartElapsed) / 1000L

                        if (frame > lastFrameNum.get()) {
                            lastFrameNum.set(frame)
                            lastFrameElapsed.set(elapsed)
                            lastHealthyElapsed.set(elapsed)
                            lastHealthyFrameWallMs = wall
                            everHealthy.set(true)

                            if (elapsed - lastHealthyEmitElapsed.get() >= HEALTHY_HEARTBEAT_INTERVAL_MS) {
                                lastHealthyEmitElapsed.set(elapsed)
                                events.trySend(SupervisorEvent.WorkerHealthyTick(lens, frame, wall))
                            }
                        }
                        if (stats.size > lastBytesSize.get()) {
                            lastBytesSize.set(stats.size)
                            lastSizeElapsed.set(elapsed)
                        }

                        _state.value = _state.value.copy(
                            isStreaming = true,
                            fps = fps,
                            bitrateKbps = bitrate,
                            uptimeSeconds = uptimeSec,
                            lastError = null
                        )

                        if (elapsed - lastStatsLogElapsed >= 15_000L) {
                            lastStatsLogElapsed = elapsed
                            AppLogger.step(
                                tag,
                                "$fn.stats",
                                "frame=$frame, fps=${"%.1f".format(fps)}, bitrate=${"%.0f".format(bitrate)}kbps, size=${stats.size}B, uptime=${uptimeSec}s, bridge=(${bridge?.getStatsSummary() ?: "direct"})"
                            )
                        }
                    } catch (_: Throwable) {
                    }
                }
            )

            session = createdSession
            currentSession.set(createdSession)

            // Step 3: Per-attempt stall watchdog
            val activeBridge = bridge
            watchdogJob = scope.launch {
                StallWatchdog.watchForStall(
                    clock = clock,
                    startedAtElapsedMs = attemptStartElapsed,
                    lastFrameProgressElapsedMs = { lastFrameElapsed.get() },
                    lastBytesProgressElapsedMs = { activeBridge?.lastUpstreamProgressElapsedMs ?: lastSizeElapsed.get() }
                ) { detail ->
                    stalledFlag.set(true)
                    AppLogger.unexpected(tag, "StallWatchdog", detail)
                    try {
                        FFmpegKit.cancel(createdSession.sessionId)
                    } catch (_: Throwable) {
                    }
                    try {
                        activeBridge?.close()
                    } catch (_: Throwable) {
                    }
                }
            }

            val completedSession = finishedDeferred.await()
            watchdogJob.cancel()

            val rc = completedSession.returnCode
            val isSuccess = ReturnCode.isSuccess(rc)
            val isCancel = ReturnCode.isCancel(rc) || generation.get() != myGen
            val tail = if (!isSuccess && !isCancel) {
                try {
                    completedSession.allLogsAsString?.takeLast(600)?.trim()
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
                bridgeErrorType = bridge?.lastErrorType ?: BridgeErrorType.NONE,
                logTail = tail
            )

            val healthyEndWall = if (everHealthy.get() && lastHealthyFrameWallMs > 0L) {
                lastHealthyFrameWallMs
            } else {
                attemptStartWall
            }

            _state.value = _state.value.copy(
                isStreaming = false,
                lastExitReason = reason,
                lastError = if (reason == WorkerExitReason.SUCCESS || reason == WorkerExitReason.CANCELLED) null else reason.userMessage
            )

            events.trySend(
                SupervisorEvent.WorkerExited(
                    lens = lens,
                    mode = WorkerMode.LIVE,
                    reason = reason,
                    startedAtWallMs = attemptStartWall,
                    endedAtWallMs = clock.nowMs(),
                    wasEverHealthy = everHealthy.get(),
                    lastHealthyWallMs = healthyEndWall,
                    detail = bridge?.lastError ?: tail?.lineSequence()?.lastOrNull()?.take(140).orEmpty()
                )
            )

            AppLogger.exit(tag, fn, "reason=$reason, rc=$rc, everHealthy=${everHealthy.get()}")
            return AttemptOutcome(reason, everHealthy.get())
        } catch (ce: CancellationException) {
            AppLogger.step(tag, fn, "Coroutine cancelled")
            session?.let { s ->
                try {
                    FFmpegKit.cancel(s.sessionId)
                } catch (_: Throwable) {
                }
            }
            bridge?.close()
            events.trySend(
                SupervisorEvent.WorkerExited(
                    lens = lens,
                    mode = WorkerMode.LIVE,
                    reason = WorkerExitReason.CANCELLED,
                    startedAtWallMs = attemptStartWall,
                    endedAtWallMs = clock.nowMs(),
                    wasEverHealthy = everHealthy.get(),
                    lastHealthyWallMs = lastHealthyFrameWallMs
                )
            )
            throw ce
        } catch (t: Throwable) {
            AppLogger.exception(tag, fn, t, "Unexpected worker error")
            val reason = when (t) {
                is java.net.UnknownHostException -> WorkerExitReason.INGEST_UNREACHABLE
                else -> WorkerExitReason.FFMPEG_ERROR
            }
            _state.value = _state.value.copy(
                isStreaming = false,
                lastExitReason = reason,
                lastError = reason.userMessage
            )
            events.trySend(
                SupervisorEvent.WorkerExited(
                    lens = lens,
                    mode = WorkerMode.LIVE,
                    reason = reason,
                    startedAtWallMs = attemptStartWall,
                    endedAtWallMs = clock.nowMs(),
                    wasEverHealthy = everHealthy.get(),
                    lastHealthyWallMs = lastHealthyFrameWallMs,
                    detail = t.message.orEmpty()
                )
            )
            return AttemptOutcome(reason, everHealthy.get())
        } finally {
            watchdogJob?.cancel()
            session?.let { s ->
                currentSession.compareAndSet(s, null)
                try {
                    val sState = s.state
                    if (sState == SessionState.RUNNING || sState == SessionState.CREATED) {
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
            if (acquiredGuard) {
                targetGuard.release(target, owner)
            }
        }
    }

    private fun cancelActiveHandles(reason: String) {
        currentSession.get()?.let { s ->
            try {
                AppLogger.step(tag, "cancelActiveHandles", "Cancelling sessionId=${s.sessionId} ($reason)")
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
