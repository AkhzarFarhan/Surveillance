package com.imou.youtubestreamer.data

enum class PipelineStatus(val label: String) {
    IDLE("Stopped"),
    INITIALIZING("Initializing"),
    STREAMING("Streaming Live"),
    OUTAGE_RECORDING("Wi-Fi Lost (SD Recording)"),
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
    val lastError: String? = null
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
    val serviceUptimeSeconds: Long = 0L
)
