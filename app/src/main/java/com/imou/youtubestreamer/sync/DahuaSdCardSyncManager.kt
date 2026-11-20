package com.imou.youtubestreamer.sync

import android.content.Context
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.CatchupStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic helper for Dahua HTTP CGI SD-card file index inspection.
 *
 * Note (v1.0.15): All automatic & manual SD-card backlog streaming is now handled exclusively
 * by `BackfillWorker` + `GapLedger` + `StreamSupervisor` using resumable 10-minute RTSP
 * `/cam/playback` chunks over `executeWithArgumentsAsync` and `RtmpsTlsBridge`.
 */
class DahuaSdCardSyncManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val settings: AppSettings
) {
    companion object {
        private const val TAG = "SdCardSync"
    }

    private val cgiClient = DahuaCgiClient(settings)
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _catchupState = MutableStateFlow(CatchupStatus())
    val catchupState: StateFlow<CatchupStatus> = _catchupState.asStateFlow()

    /**
     * Optional Settings diagnostic button: checks whether HTTP `/cgi-bin/` media search is
     * exposed on the camera (consumer Imou IPC-S7XE models use RTSP + ONVIF instead).
     */
    suspend fun runCgiBacklogCheck(startTimeMs: Long, endTimeMs: Long): Result<String> = withContext(Dispatchers.IO) {
        AppLogger.enter(TAG, "runCgiBacklogCheck")
        try {
            val deviceInfo = OnvifCameraClient(settings).getDeviceInfo().getOrNull()
            if (deviceInfo != null && deviceInfo.model.contains("IPC-S7XE", ignoreCase = true)) {
                val msg = "${deviceInfo.manufacturer} ${deviceInfo.model}: HTTP /cgi-bin/ is disabled on this model; SD backfill uses RTSP /cam/playback."
                AppLogger.step(TAG, "runCgiBacklogCheck", msg)
                AppLogger.exit(TAG, "runCgiBacklogCheck", "ImouModelUsesRtsp")
                return@withContext Result.success(msg)
            }

            val objectId = cgiClient.createSearchSession()
            if (objectId == null) {
                val msg = "Camera HTTP CGI search unavailable on ${settings.cameraIp}:${settings.httpPort}; SD backfill uses RTSP /cam/playback."
                AppLogger.exit(TAG, "runCgiBacklogCheck", msg)
                return@withContext Result.success(msg)
            }

            val startFormatted = synchronized(logTimeFormat) { logTimeFormat.format(Date(startTimeMs)) }
            val endFormatted = synchronized(logTimeFormat) { logTimeFormat.format(Date(endTimeMs)) }
            val foundFixed = cgiClient.findFiles(objectId, settings.fixedChannel - 1, startFormatted, endFormatted)
            val clips = if (foundFixed) cgiClient.fetchFoundFiles(objectId, 50) else emptyList()
            cgiClient.destroySearchSession(objectId)

            val msg = "CGI search OK: found ${clips.size} recorded clips on SD card between $startFormatted and $endFormatted."
            AppLogger.i(TAG, msg)
            AppLogger.exit(TAG, "runCgiBacklogCheck", msg)
            Result.success(msg)
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "runCgiBacklogCheck", t)
            Result.failure(t)
        }
    }
}
