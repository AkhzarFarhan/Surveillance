package com.imou.youtubestreamer.data

enum class Lens(val code: String, val displayName: String) {
    FIXED("_F", "Fixed Overview"),
    ROTATABLE("_R", "Rotatable PTZ")
}

enum class LensMode(val label: String) {
    OFF("Off"),
    WAITING("Waiting for link"),
    LIVE("Live"),
    BACKFILL("Backfilling SD"),
    LIVE_AND_BACKFILL("Live + Backfill")
}

enum class WorkerExitReason(val userMessage: String) {
    SUCCESS("Completed cleanly"),
    CANCELLED("Stopped"),
    CAMERA_UNREACHABLE("Camera unreachable on LAN"),
    CAMERA_AUTH_FAILED("Camera auth failed (check username/password)"),
    INGEST_UNREACHABLE("YouTube ingest unreachable"),
    TLS_ERROR("RTMPS TLS handshake/connection error"),
    STALLED("Stream stalled (no frames/bytes moving)"),
    FFMPEG_ERROR("FFmpeg streaming error"),
    NO_SD_RECORDING("No SD recording for requested window")
}

enum class BackfillMode(val label: String) {
    SEPARATE_KEY("Separate Key (Concurrent)"),
    SAME_KEY("Same Key (Pause Live)")
}

enum class PipelineStatus(val label: String) {
    IDLE("Stopped"),
    INITIALIZING("Initializing"),
    STREAMING("Streaming Live"),
    OUTAGE_RECORDING("Offline – camera recording to SD"),
    CAMERA_OFFLINE("Camera Offline (LAN)"),
    CATCHING_UP("Catching Up from SD Card"),
    ROTATING("Rotating Session (15s Break)"),
    ERROR("Error")
}

data class LensStreamState(
    val lensName: String,
    val lensCode: String, // "_F" or "_R"
    val channel: Int,
    val isStreaming: Boolean = false,
    val fps: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val uptimeSeconds: Long = 0L,
    val activeTitle: String = "",
    val broadcastUrl: String = "",
    val lastError: String? = null,
    val mode: LensMode = LensMode.OFF,
    val lastExitReason: WorkerExitReason? = null,
    val pendingGapMinutes: Long = 0L,
    val isBackfilling: Boolean = false,
    val backfillStatus: String = ""
)

data class CatchupStatus(
    val isCatchingUp: Boolean = false,
    val gapMinutes: Long = 0L,
    val speedMultiplier: Float = 3.0f,
    val statusMessage: String = ""
)

data class SystemStreamState(
    val status: PipelineStatus = PipelineStatus.IDLE,
    val isWifiConnected: Boolean = false,
    val wifiSsid: String = "",
    val activePartIndex: Int = 1,
    val activeDateStr: String = "",
    val nextRotationRemainingMs: Long = 0L,
    val fixedLens: LensStreamState = LensStreamState("Fixed Overview", "_F", 2),
    val rotatableLens: LensStreamState = LensStreamState("Rotatable PTZ", "_R", 1),
    val catchup: CatchupStatus = CatchupStatus(),
    val serviceUptimeSeconds: Long = 0L,
    val lanUp: Boolean = false,
    val wanUp: Boolean = false,
    val isCellularOnly: Boolean = false,
    val lastHeartbeatWallMs: Long = 0L,
    val crashLoopDetected: Boolean = false,
    val crashCountRecent: Int = 0,
    val pendingFixedGapMinutes: Long = 0L,
    val pendingRotatableGapMinutes: Long = 0L,
    val lastSupervisorWarning: String? = null
)
