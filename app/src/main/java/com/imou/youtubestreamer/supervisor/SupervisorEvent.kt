package com.imou.youtubestreamer.supervisor

import com.imou.youtubestreamer.data.Lens
import com.imou.youtubestreamer.data.WorkerExitReason
import com.imou.youtubestreamer.net.LinkState
import com.imou.youtubestreamer.scheduler.RotationSlot

enum class WorkerMode {
    LIVE,
    BACKFILL
}

sealed interface SupervisorEvent {
    data class UserStart(val explicit: Boolean) : SupervisorEvent
    data class UserStop(val reason: String = "user") : SupervisorEvent
    data class RestartPipeline(val reason: String = "restart") : SupervisorEvent

    data class LinkChanged(
        val linkState: LinkState,
        val networkHandleChanged: Boolean = false
    ) : SupervisorEvent

    data class RotationBoundary(val slot: RotationSlot) : SupervisorEvent

    data class WorkerHealthyTick(
        val lens: Lens,
        val frameNumber: Long,
        val wallMs: Long
    ) : SupervisorEvent

    data class WorkerExited(
        val lens: Lens,
        val mode: WorkerMode,
        val reason: WorkerExitReason,
        val startedAtWallMs: Long,
        val endedAtWallMs: Long,
        val wasEverHealthy: Boolean,
        val lastHealthyWallMs: Long,
        val detail: String = ""
    ) : SupervisorEvent

    data class BackfillChunkDone(
        val lens: Lens,
        val gapId: String,
        val newDoneUpToWallMs: Long,
        val chunkEndWallMs: Long,
        val statusText: String
    ) : SupervisorEvent

    data class BackfillFinished(
        val lens: Lens,
        val gapId: String,
        val reason: WorkerExitReason
    ) : SupervisorEvent

    data class EnqueueManualBackfill(val hoursBack: Int) : SupervisorEvent

    data class MemoryOrThermalPressure(
        val forceSubstream: Boolean,
        val source: String
    ) : SupervisorEvent

    data class Tick(
        val nowWallMs: Long,
        val elapsedMs: Long
    ) : SupervisorEvent
}
