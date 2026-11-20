package com.imou.youtubestreamer.supervisor

import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.BackfillMode
import com.imou.youtubestreamer.data.CatchupStatus
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.Lens
import com.imou.youtubestreamer.data.LensMode
import com.imou.youtubestreamer.data.PipelineStatus
import com.imou.youtubestreamer.data.SettingsValidator
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.data.WorkerExitReason
import com.imou.youtubestreamer.engine.ActiveTargetGuard
import com.imou.youtubestreamer.engine.DualStreamOrchestrator
import com.imou.youtubestreamer.engine.LensWorker
import com.imou.youtubestreamer.net.ConnectivityMonitor
import com.imou.youtubestreamer.net.LinkState
import com.imou.youtubestreamer.resume.BackfillWorker
import com.imou.youtubestreamer.resume.GapLedger
import com.imou.youtubestreamer.resume.Heartbeat
import com.imou.youtubestreamer.scheduler.RotationClock
import com.imou.youtubestreamer.scheduler.RotationSlot
import com.imou.youtubestreamer.sync.OnvifCameraClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth for the entire CCTV-to-YouTube surveillance pipeline.
 *
 * Uses a single-threaded actor loop over [eventChannel] so network changes,
 * rotation boundaries, worker exits, stall watchdogs, thermal pressure, and manual backfill
 * never race each other and all decisions are idempotent.
 */
class StreamSupervisor(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val settings: AppSettings,
    private val store: SupervisorStore,
    private val heartbeat: Heartbeat,
    private val ledger: GapLedger,
    private val probe: OnvifCameraClient,
    private val connectivity: ConnectivityMonitor,
    private val systemStateFlow: MutableStateFlow<SystemStreamState>,
    private val onNotificationUpdate: (title: String, text: String) -> Unit,
    private val onWakeLockRefreshNeeded: () -> Unit,
    private val onAllStopped: () -> Unit
) {
    companion object {
        private const val TAG = "StreamSupervisor"
        private const val TICK_INTERVAL_MS = 10_000L
        private const val GAP_CLOSE_HEALTHY_MS = 30_000L
        private const val SAME_KEY_MAX_CHASE_ROUNDS = 8
        private const val NOTIFICATION_THROTTLE_MS = 5_000L
    }

    val eventChannel = Channel<SupervisorEvent>(Channel.UNLIMITED)
    private val targetGuard = ActiveTargetGuard()

    val fixedWorker = LensWorker(Lens.FIXED, settings, probe, clock, targetGuard, eventChannel, scope)
    val rotatableWorker = LensWorker(Lens.ROTATABLE, settings, probe, clock, targetGuard, eventChannel, scope)
    val fixedBackfill = BackfillWorker(Lens.FIXED, settings, probe, clock, targetGuard, eventChannel, scope)
    val rotatableBackfill = BackfillWorker(Lens.ROTATABLE, settings, probe, clock, targetGuard, eventChannel, scope)

    private var actorJob: Job? = null
    private var tickerJob: Job? = null
    private var collectorJobs: List<Job> = emptyList()

    // Actor-confined state (accessed ONLY inside actorJob)
    private var currentSlot: RotationSlot = RotationClock.slotFor(clock.nowMs(), settings.rotationPeriodMinutesOverride)
    private var linkState: LinkState = connectivity.linkState.value
    private var rotationGapActive: Boolean = false
    private var rotationGapEndElapsedMs: Long = 0L
    private var stoppedByUser: Boolean = false
    private var staleRecoveryChecked: Boolean = false
    private var supervisorWarning: String? = null
    private val startedAtElapsedMs = clock.elapsedMs()
    private val healthySinceElapsed = mutableMapOf<Lens, Long>()
    private val healthySinceWall = mutableMapOf<Lens, Long>()
    private val desiredModes = mutableMapOf(Lens.FIXED to LensMode.OFF, Lens.ROTATABLE to LensMode.OFF)
    private val sameKeyChaseRounds = mutableMapOf<Lens, Int>()
    private val lensBackfillStatus = mutableMapOf(Lens.FIXED to "", Lens.ROTATABLE to "")
    private var lastNotificationSummary: String = ""
    private var lastNotificationElapsedMs: Long = 0L

    fun startActor() {
        if (actorJob?.isActive == true) return
        AppLogger.enter(TAG, "startActor")

        actorJob = scope.launch {
            AppLogger.step(TAG, "actorLoop", "Supervisor actor started")
            try {
                for (event in eventChannel) {
                    try {
                        handleEvent(event)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        AppLogger.exception(TAG, "handleEvent[${event::class.java.simpleName}]", t)
                    }
                }
            } catch (_: CancellationException) {
                AppLogger.step(TAG, "actorLoop", "Supervisor actor cancelled cleanly")
            }
        }

        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                eventChannel.trySend(SupervisorEvent.Tick(clock.nowMs(), clock.elapsedMs()))
            }
        }

        collectorJobs = listOf(
            scope.launch {
                fixedWorker.state.collect { publishSystemState() }
            },
            scope.launch {
                rotatableWorker.state.collect { publishSystemState() }
            },
            scope.launch {
                ledger.pendingGaps.collect { publishSystemState() }
            }
        )

        AppLogger.exit(TAG, "startActor", "Actor, 10s ticker & collectors running")
    }

    suspend fun shutdown(reason: String = "shutdown") {
        AppLogger.enter(TAG, "shutdown", "reason='$reason'")
        tickerJob?.cancel()
        collectorJobs.forEach { it.cancel() }
        stopAllWorkers(reason)
        actorJob?.cancel()
        actorJob = null
        DualStreamOrchestrator.logRunningFfmpegSessions("supervisor.shutdown")
        AppLogger.exit(TAG, "shutdown", "Stopped")
    }

    fun send(event: SupervisorEvent) {
        eventChannel.trySend(event)
    }

    private fun workerFor(lens: Lens): LensWorker = when (lens) {
        Lens.FIXED -> fixedWorker
        Lens.ROTATABLE -> rotatableWorker
    }

    private fun backfillFor(lens: Lens): BackfillWorker = when (lens) {
        Lens.FIXED -> fixedBackfill
        Lens.ROTATABLE -> rotatableBackfill
    }

    private suspend fun handleEvent(event: SupervisorEvent) {
        when (event) {
            is SupervisorEvent.UserStart -> {
                AppLogger.step(TAG, "UserStart", "explicit=${event.explicit}, storedDesired=${store.desiredRunning}")
                stoppedByUser = false
                if (event.explicit) {
                    store.desiredRunning = true
                    sameKeyChaseRounds.clear()
                }
                if (store.desiredRunning && !staleRecoveryChecked) {
                    staleRecoveryChecked = true
                    recoverStaleHeartbeatGapIfAny()
                }
                if (store.desiredRunning) {
                    heartbeat.beat()
                }
                currentSlot = RotationClock.slotFor(clock.nowMs(), settings.rotationPeriodMinutesOverride)
                reconcile("UserStart")
            }

            is SupervisorEvent.UserStop -> {
                AppLogger.i(TAG, "UserStop received (reason=${event.reason}). Halting all workers.")
                stoppedByUser = true
                store.desiredRunning = false
                rotationGapActive = false
                healthySinceElapsed.clear()
                healthySinceWall.clear()
                stopAllWorkers(event.reason)
                desiredModes[Lens.FIXED] = LensMode.OFF
                desiredModes[Lens.ROTATABLE] = LensMode.OFF
                publishSystemState()
                onAllStopped()
            }

            is SupervisorEvent.RestartPipeline -> {
                AppLogger.i(TAG, "RestartPipeline received (${event.reason}); recycling workers cleanly...")
                stoppedByUser = false
                rotationGapActive = false
                healthySinceElapsed.clear()
                healthySinceWall.clear()
                stopAllWorkers(event.reason)
                currentSlot = RotationClock.slotFor(clock.nowMs(), settings.rotationPeriodMinutesOverride)
                reconcile("RestartPipeline")
            }

            is SupervisorEvent.LinkChanged -> {
                val prev = linkState
                linkState = event.linkState
                val wasOnline = prev.lanUp && prev.wanUp
                val nowOnline = linkState.lanUp && linkState.wanUp

                if (wasOnline && !nowOnline && store.desiredRunning) {
                    val now = clock.nowMs()
                    for (lens in Lens.entries) {
                        if (settings.liveRtmpFor(lens).isNotBlank()) {
                            val lastOk = workerFor(lens).lastHealthyFrameWallMs
                            val start = if (lastOk > 0L && now - lastOk < 5 * 60_000L) lastOk else now
                            ledger.openGap(lens, start)
                            healthySinceElapsed.remove(lens)
                            healthySinceWall.remove(lens)
                        }
                    }
                }

                if (nowOnline && event.networkHandleChanged) {
                    AppLogger.w(TAG, "Default network handle changed while online; resetting bound sockets")
                    stopAllWorkers("network_changed")
                }

                reconcile("LinkChanged")
            }

            is SupervisorEvent.RotationBoundary -> {
                triggerRotationBoundary(event.slot)
            }

            is SupervisorEvent.WorkerHealthyTick -> {
                val nowEl = clock.elapsedMs()
                if (!healthySinceElapsed.containsKey(event.lens)) {
                    healthySinceElapsed[event.lens] = nowEl
                    healthySinceWall[event.lens] = event.wallMs
                }
                val startHealthyEl = healthySinceElapsed[event.lens] ?: nowEl
                if (ledger.hasOpenGap(event.lens) && (nowEl - startHealthyEl >= GAP_CLOSE_HEALTHY_MS)) {
                    val outageEndWall = healthySinceWall[event.lens] ?: event.wallMs
                    ledger.closeGap(event.lens, outageEndWall, settings.minGapToBackfillSec)
                    reconcile("GapClosedOnHealthy")
                }
            }

            is SupervisorEvent.WorkerExited -> {
                if (event.mode == WorkerMode.LIVE) {
                    healthySinceElapsed.remove(event.lens)
                    healthySinceWall.remove(event.lens)
                    if (store.desiredRunning && !rotationGapActive && !stoppedByUser &&
                        event.reason != WorkerExitReason.CANCELLED && event.reason != WorkerExitReason.SUCCESS
                    ) {
                        val gapStart = if (event.lastHealthyWallMs > 0L) event.lastHealthyWallMs else event.startedAtWallMs
                        ledger.openGap(event.lens, gapStart)
                    }
                } else if (event.mode == WorkerMode.BACKFILL) {
                    // SAME_KEY backfill run exited; queue chase gap if backfill took >= minGapToBackfillSec
                    if (event.reason == WorkerExitReason.SUCCESS &&
                        settings.backfillMode == BackfillMode.SAME_KEY &&
                        store.desiredRunning && !stoppedByUser && !rotationGapActive
                    ) {
                        val elapsedWall = event.endedAtWallMs - event.startedAtWallMs
                        val rounds = sameKeyChaseRounds[event.lens] ?: 0
                        if (elapsedWall >= settings.minGapToBackfillSec * 1000L && rounds < SAME_KEY_MAX_CHASE_ROUNDS) {
                            sameKeyChaseRounds[event.lens] = rounds + 1
                            AppLogger.step(
                                TAG,
                                "SameKeyChase[${event.lens.code}]",
                                "Backfill paused live for ${elapsedWall / 1000}s; recording chase gap (round ${rounds + 1})"
                            )
                            ledger.recordOutage(event.lens, event.startedAtWallMs, event.endedAtWallMs, settings.minGapToBackfillSec)
                        } else {
                            sameKeyChaseRounds[event.lens] = 0
                        }
                    }
                }

                if (event.reason == WorkerExitReason.INGEST_UNREACHABLE ||
                    event.reason == WorkerExitReason.STALLED ||
                    event.reason == WorkerExitReason.CAMERA_UNREACHABLE
                ) {
                    connectivity.triggerImmediateProbe()
                }
                publishSystemState()
            }

            is SupervisorEvent.BackfillChunkDone -> {
                ledger.advanceProgress(event.gapId, event.newDoneUpToWallMs)
                lensBackfillStatus[event.lens] = event.statusText
                publishSystemState()
            }

            is SupervisorEvent.BackfillFinished -> {
                if (event.reason == WorkerExitReason.SUCCESS) {
                    lensBackfillStatus[event.lens] = "Up to date"
                } else {
                    ledger.markPaused(event.gapId)
                    if (event.reason != WorkerExitReason.CANCELLED) {
                        val attempts = ledger.incrementAttempts(event.gapId)
                        lensBackfillStatus[event.lens] = event.reason.userMessage
                        if (attempts >= 12) {
                            AppLogger.w(TAG, "Abandoning gap ${event.gapId} after $attempts failed backfill attempts")
                            ledger.markAbandoned(event.gapId)
                        }
                    }
                }
                reconcile("BackfillFinished[${event.lens.code}]")
            }

            is SupervisorEvent.EnqueueManualBackfill -> {
                val now = clock.nowMs()
                val start = now - (event.hoursBack.coerceIn(1, 72) * 3600_000L)
                var enqueued = false
                for (lens in Lens.entries) {
                    if (settings.liveRtmpFor(lens).isNotBlank()) {
                        ledger.recordOutage(lens, start, now, minGapSec = 10)
                        enqueued = true
                    }
                }
                if (!enqueued) {
                    supervisorWarning = "Configure at least one YouTube Stream Key before syncing SD card."
                }
                reconcile("EnqueueManualBackfill")
            }

            is SupervisorEvent.MemoryOrThermalPressure -> {
                AppLogger.w(TAG, "Applying pressure mode=${event.forceSubstream} from ${event.source}")
                fixedWorker.setForceSubstream(event.forceSubstream)
                rotatableWorker.setForceSubstream(event.forceSubstream)
            }

            is SupervisorEvent.Tick -> {
                if (store.desiredRunning && !stoppedByUser) {
                    heartbeat.beat()
                }
                onWakeLockRefreshNeeded()
                ledger.pruneExpired(event.nowWallMs, settings.sdRetentionHours)

                if (rotationGapActive && event.elapsedMs >= rotationGapEndElapsedMs) {
                    AppLogger.i(TAG, "15s rotation archive break finished; resuming sessions for ${currentSlot.dateStr} Part ${currentSlot.partIndex}/${currentSlot.totalParts}")
                    rotationGapActive = false
                }

                // Detect boundary crossing or manual wall-clock jump within 10s
                val latestSlot = RotationClock.slotFor(event.nowWallMs, settings.rotationPeriodMinutesOverride)
                if (!rotationGapActive && store.desiredRunning && !stoppedByUser &&
                    (latestSlot.partIndex != currentSlot.partIndex ||
                        latestSlot.dateStr != currentSlot.dateStr ||
                        latestSlot.totalParts != currentSlot.totalParts)
                ) {
                    triggerRotationBoundary(latestSlot)
                    return
                }
                currentSlot = latestSlot

                // Ensure any open gap closes cleanly once healthy >= 30s even between worker stat ticks
                val nowEl = event.elapsedMs
                for (lens in Lens.entries) {
                    val startH = healthySinceElapsed[lens]
                    if (startH != null && ledger.hasOpenGap(lens) && (nowEl - startH >= GAP_CLOSE_HEALTHY_MS)) {
                        ledger.closeGap(lens, healthySinceWall[lens] ?: event.nowWallMs, settings.minGapToBackfillSec)
                    }
                }

                reconcile("Tick")
            }
        }
    }

    private suspend fun triggerRotationBoundary(newSlot: RotationSlot) {
        AppLogger.i(
            TAG,
            "Rotation boundary -> ${newSlot.dateStr} Part ${newSlot.partIndex} of ${newSlot.totalParts}. Stopping workers for 15s YouTube archive seal."
        )
        currentSlot = newSlot
        rotationGapActive = true
        rotationGapEndElapsedMs = clock.elapsedMs() + RotationClock.SEAL_GAP_MS
        healthySinceElapsed.clear()
        healthySinceWall.clear()
        stopAllWorkers("rotation_boundary")
        desiredModes[Lens.FIXED] = LensMode.OFF
        desiredModes[Lens.ROTATABLE] = LensMode.OFF
        reconcile("RotationBoundary")

        scope.launch {
            delay(RotationClock.SEAL_GAP_MS)
            eventChannel.trySend(SupervisorEvent.Tick(clock.nowMs(), clock.elapsedMs()))
        }
    }

    private fun recoverStaleHeartbeatGapIfAny() {
        val window = heartbeat.staleOutageWindow(gapThresholdMs = 30_000L) ?: return
        val (gapStart, gapEnd) = window
        AppLogger.i(
            TAG,
            "Process restart recovery: recording missed ${(gapEnd - gapStart) / 1000}s downtime window into GapLedger"
        )
        for (lens in Lens.entries) {
            if (settings.liveRtmpFor(lens).isNotBlank()) {
                ledger.recordOutage(lens, gapStart, gapEnd, settings.minGapToBackfillSec)
            }
        }
    }

    private suspend fun reconcile(trigger: String) {
        if (!store.desiredRunning || stoppedByUser) {
            stopAllWorkers("desiredRunning_false")
            desiredModes[Lens.FIXED] = LensMode.OFF
            desiredModes[Lens.ROTATABLE] = LensMode.OFF
            publishSystemState()
            return
        }

        // Phase 6.1 & 6.9: refuse duplicate / invalid keys rather than flapping
        val keyValidation = SettingsValidator.validateStreamKeys(
            fixedLive = settings.manualRtmpFixed,
            rotatableLive = settings.manualRtmpRotatable,
            fixedBackfill = settings.backfillRtmpFixed,
            rotatableBackfill = settings.backfillRtmpRotatable,
            requireAtLeastOneLive = true
        )
        val camValidation = SettingsValidator.validateCameraSettings(
            cameraIp = settings.cameraIp,
            rtspPort = settings.rtspPort,
            httpPort = settings.httpPort,
            fixedChannel = settings.fixedChannel,
            rotatableChannel = settings.rotatableChannel
        )
        val combinedErrors = keyValidation.errors + camValidation.errors
        if (combinedErrors.isNotEmpty()) {
            val errMsg = combinedErrors.first()
            if (supervisorWarning != errMsg) {
                AppLogger.unexpected(TAG, "reconcile[$trigger]", "Refusing pipeline start due to invalid configuration", errMsg)
            }
            supervisorWarning = errMsg
            stopAllWorkers("invalid_settings")
            desiredModes[Lens.FIXED] = LensMode.OFF
            desiredModes[Lens.ROTATABLE] = LensMode.OFF
            publishSystemState()
            return
        }
        supervisorWarning = null

        if (rotationGapActive) {
            stopAllWorkers("rotation_seal")
            desiredModes[Lens.FIXED] = LensMode.OFF
            desiredModes[Lens.ROTATABLE] = LensMode.OFF
            publishSystemState()
            return
        }

        if (!linkState.lanUp || !linkState.wanUp) {
            stopAllWorkers(if (!linkState.lanUp) "lan_down" else "wan_down")
            for (lens in Lens.entries) {
                desiredModes[lens] = if (settings.liveRtmpFor(lens).isNotBlank()) LensMode.WAITING else LensMode.OFF
            }
            publishSystemState()
            return
        }

        // Online: reconcile each lens independently
        val nowWall = clock.nowMs()
        val minGapMs = settings.minGapToBackfillSec * 1000L

        for (lens in Lens.entries) {
            val liveTarget = settings.liveRtmpFor(lens)
            val worker = workerFor(lens)
            val bfWorker = backfillFor(lens)

            if (liveTarget.isBlank()) {
                if (bfWorker.isActive()) bfWorker.stop("no_live_key")
                worker.stop("no_live_key")
                desiredModes[lens] = LensMode.OFF
                continue
            }

            val pendingGap = ledger.nextPendingGap(lens)
            val bfKey = settings.backfillRtmpFor(lens)
            val useSeparateKey = settings.backfillMode == BackfillMode.SEPARATE_KEY && bfKey.isNotBlank()
            val title = currentSlot.titleFor(lens)

            if (useSeparateKey) {
                // Concurrent mode: Live runs uninterrupted while BackfillWorker replays pending gaps on dedicated key
                if (!worker.isRunningLive(liveTarget, title)) {
                    worker.startLive(liveTarget, title)
                }
                val nearBoundary = (currentSlot.nextBoundaryWallMs - nowWall) <= 30_000L
                if (pendingGap != null && !nearBoundary) {
                    ledger.markInProgress(pendingGap.id)
                    if (!bfWorker.isRunningGap(pendingGap.id, bfKey)) {
                        bfWorker.startAsync(
                            gap = pendingGap,
                            targetRtmp = bfKey,
                            maxWallEndMs = currentSlot.nextBoundaryWallMs - 15_000L
                        )
                    }
                    desiredModes[lens] = LensMode.LIVE_AND_BACKFILL
                } else {
                    if (bfWorker.isActive()) {
                        bfWorker.stop(if (nearBoundary) "boundary_approaching" else "no_pending_gaps")
                    }
                    desiredModes[lens] = LensMode.LIVE
                }
            } else {
                // SAME_KEY mode: strictly Live XOR Backfill on this lens key
                if (bfWorker.isActive()) {
                    bfWorker.stop("same_key_mode")
                }

                val remGapMs = pendingGap?.remainingMs(nowWall) ?: 0L
                val canChaseInSameKey = settings.catchupSpeed > 1.2f &&
                    (nowWall + 90_000L < currentSlot.nextBoundaryWallMs)

                if (pendingGap != null && remGapMs >= minGapMs && canChaseInSameKey) {
                    ledger.markInProgress(pendingGap.id)
                    if (!worker.isRunningSameKeyBackfill(pendingGap.id, liveTarget)) {
                        worker.startBackfill(
                            gap = pendingGap,
                            target = liveTarget,
                            backfillWorker = bfWorker,
                            maxWallEndMs = currentSlot.nextBoundaryWallMs - 20_000L
                        )
                    }
                    desiredModes[lens] = LensMode.BACKFILL
                } else {
                    if (worker.isRunningSameKeyBackfill(pendingGap?.id ?: "", liveTarget)) {
                        worker.stop("switch_to_live")
                    }
                    if (!worker.isRunningLive(liveTarget, title)) {
                        worker.startLive(liveTarget, title)
                    }
                    desiredModes[lens] = LensMode.LIVE
                }
            }
        }

        publishSystemState()
    }

    private suspend fun stopAllWorkers(reason: String) {
        if (fixedBackfill.isActive()) fixedBackfill.stop(reason)
        if (rotatableBackfill.isActive()) rotatableBackfill.stop(reason)
        fixedWorker.stop(reason)
        rotatableWorker.stop(reason)
    }

    private fun publishSystemState() {
        val nowWall = clock.nowMs()
        val nowEl = clock.elapsedMs()
        val pendingFixedMin = ledger.pendingMinutesFor(Lens.FIXED, nowWall)
        val pendingRotMin = ledger.pendingMinutesFor(Lens.ROTATABLE, nowWall)

        fixedWorker.updateMeta(
            mode = desiredModes[Lens.FIXED] ?: LensMode.OFF,
            pendingGapMinutes = pendingFixedMin,
            isBackfilling = fixedBackfill.isActive() || fixedWorker.state.value.isBackfilling,
            backfillStatus = lensBackfillStatus[Lens.FIXED].orEmpty()
        )
        rotatableWorker.updateMeta(
            mode = desiredModes[Lens.ROTATABLE] ?: LensMode.OFF,
            pendingGapMinutes = pendingRotMin,
            isBackfilling = rotatableBackfill.isActive() || rotatableWorker.state.value.isBackfilling,
            backfillStatus = lensBackfillStatus[Lens.ROTATABLE].orEmpty()
        )

        val fState = fixedWorker.state.value
        val rState = rotatableWorker.state.value
        val anyBackfilling = fState.isBackfilling || rState.isBackfilling

        val overallStatus = when {
            !store.desiredRunning || stoppedByUser -> PipelineStatus.IDLE
            supervisorWarning != null -> PipelineStatus.ERROR
            rotationGapActive -> PipelineStatus.ROTATING
            !linkState.lanUp -> PipelineStatus.CAMERA_OFFLINE
            !linkState.wanUp -> PipelineStatus.OUTAGE_RECORDING
            anyBackfilling && !fState.isStreaming && !rState.isStreaming -> PipelineStatus.CATCHING_UP
            else -> PipelineStatus.STREAMING
        }

        val totalPendingMin = pendingFixedMin + pendingRotMin
        val catchup = CatchupStatus(
            isCatchingUp = anyBackfilling,
            gapMinutes = totalPendingMin,
            speedMultiplier = settings.catchupSpeed,
            statusMessage = when {
                anyBackfilling && (fState.isStreaming || rState.isStreaming) ->
                    "Concurrent live + SD backfill (${totalPendingMin}m queued at ${settings.catchupSpeed}x)"
                anyBackfilling ->
                    "Catching up ${totalPendingMin}m SD card backlog at ${settings.catchupSpeed}x..."
                else -> ""
            }
        )

        val recentCrashes = store.recentCrashCount(nowWall)
        val remRotationMs = RotationClock.remainingToBoundaryMs(nowWall, currentSlot)

        systemStateFlow.value = SystemStreamState(
            status = overallStatus,
            isWifiConnected = linkState.isWifi,
            wifiSsid = if (linkState.isCellularOnly) "Cellular Only" else if (linkState.isWifi) "Home Wi-Fi" else "",
            activePartIndex = currentSlot.partIndex,
            activeDateStr = currentSlot.dateStr,
            nextRotationRemainingMs = remRotationMs,
            fixedLens = fState,
            rotatableLens = rState,
            catchup = catchup,
            serviceUptimeSeconds = (nowEl - startedAtElapsedMs) / 1000L,
            lanUp = linkState.lanUp,
            wanUp = linkState.wanUp,
            isCellularOnly = linkState.isCellularOnly,
            lastHeartbeatWallMs = store.heartbeatWallMs,
            crashLoopDetected = store.isCrashLoopActive(nowWall),
            crashCountRecent = recentCrashes,
            pendingFixedGapMinutes = pendingFixedMin,
            pendingRotatableGapMinutes = pendingRotMin,
            lastSupervisorWarning = supervisorWarning
        )

        // Update ongoing service notification (throttled unless status text changes)
        val notifTitle = when (overallStatus) {
            PipelineStatus.IDLE -> "Surveillance Stopped"
            PipelineStatus.ERROR -> "Surveillance Config Warning"
            PipelineStatus.ROTATING -> "Rotating 8h Archive (15s Seal)"
            PipelineStatus.CAMERA_OFFLINE -> "Camera Unreachable (LAN Down)"
            PipelineStatus.OUTAGE_RECORDING -> "Offline – Camera Recording to SD"
            PipelineStatus.CATCHING_UP -> "Catching Up SD Backlog (${totalPendingMin}m left)"
            PipelineStatus.STREAMING -> "Part ${currentSlot.partIndex}/${currentSlot.totalParts} Live"
            PipelineStatus.INITIALIZING -> "Initializing Pipeline..."
        }
        val fTag = "_F:${fState.mode.label}"
        val rTag = if (settings.rotatableRtmpConfigured()) " • _R:${rState.mode.label}" else ""
        val gapTag = if (totalPendingMin > 0L) " • Gap:${totalPendingMin}m" else ""
        val notifText = supervisorWarning ?: "$fTag$rTag$gapTag"

        val combined = "$notifTitle|$notifText"
        if (combined != lastNotificationSummary || (nowEl - lastNotificationElapsedMs >= NOTIFICATION_THROTTLE_MS)) {
            lastNotificationSummary = combined
            lastNotificationElapsedMs = nowEl
            onNotificationUpdate(notifTitle, notifText)
        }
    }

    private fun AppSettings.rotatableRtmpConfigured(): Boolean = liveRtmpFor(Lens.ROTATABLE).isNotBlank()
}
