package com.imou.youtubestreamer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.LogEntry
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.service.CctvStreamingService
import com.imou.youtubestreamer.sync.DahuaCgiClient
import com.imou.youtubestreamer.sync.DahuaSdCardSyncManager
import com.imou.youtubestreamer.sync.OnvifCameraClient
import com.imou.youtubestreamer.update.AppUpdateManager
import com.imou.youtubestreamer.update.UpdateStatus
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class CameraTestState {
    object Idle : CameraTestState()
    object Testing : CameraTestState()
    data class Success(val message: String) : CameraTestState()
    data class Error(val error: String) : CameraTestState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)
    private val cgiClient = DahuaCgiClient(settings)
    private val onvifClient = OnvifCameraClient(settings)
    private val syncManager = DahuaSdCardSyncManager(application, settings)

    val systemState: StateFlow<SystemStreamState> = CctvStreamingService.systemState
    val logs: StateFlow<List<LogEntry>> = AppLogger.logs

    private val _cameraTestState = MutableStateFlow<CameraTestState>(CameraTestState.Idle)
    val cameraTestState: StateFlow<CameraTestState> = _cameraTestState.asStateFlow()

    val updateStatus: StateFlow<UpdateStatus> = AppUpdateManager.status

    private val _manualCheckMessage = MutableStateFlow<String?>(null)
    val manualCheckMessage: StateFlow<String?> = _manualCheckMessage.asStateFlow()

    fun checkForUpdates(context: Context, manual: Boolean = false) {
        viewModelScope.launch {
            val result = AppUpdateManager.checkForUpdates(context)
            if (manual) {
                when (result) {
                    is UpdateStatus.UpToDate -> {
                        _manualCheckMessage.value = "You are on the latest version (v${result.currentVersion})."
                    }
                    is UpdateStatus.Error -> {
                        _manualCheckMessage.value = "Check failed: ${result.message}"
                    }
                    is UpdateStatus.UpdateAvailable -> {
                        _manualCheckMessage.value = null
                    }
                    else -> {}
                }
            }
        }
    }

    fun downloadAndInstallUpdate(context: Context, downloadUrl: String) {
        viewModelScope.launch {
            val result = AppUpdateManager.downloadUpdate(context, downloadUrl)
            if (result.isSuccess) {
                val apkFile = result.getOrThrow()
                if (AppUpdateManager.canInstallPackages(context)) {
                    AppUpdateManager.installApk(context, apkFile)
                }
            }
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        if (AppUpdateManager.canInstallPackages(context)) {
            AppUpdateManager.installApk(context, apkFile)
        } else {
            AppUpdateManager.openInstallPermissionSettings(context)
        }
    }

    fun dismissUpdateDialog() {
        AppUpdateManager.resetStatus()
    }

    fun clearManualCheckMessage() {
        _manualCheckMessage.value = null
    }

    fun saveStreamKeys(fixedKey: String, rotatableKey: String = "") {
        settings.manualRtmpFixed = fixedKey.trim()
        settings.manualRtmpRotatable = rotatableKey.trim()
        AppLogger.i("Settings", "YouTube Stream Key updated (Fixed: ${if (fixedKey.isNotBlank()) "Set" else "Empty"}, Rotatable: ${if (rotatableKey.isNotBlank()) "Set" else "Empty"})")
    }

    fun toggleStreaming() {
        val app = getApplication<Application>()
        if (CctvStreamingService.isRunning) {
            CctvStreamingService.stop(app)
        } else {
            CctvStreamingService.start(app)
        }
    }

    fun testCameraConnection() {
        viewModelScope.launch {
            _cameraTestState.value = CameraTestState.Testing
            val result = cgiClient.testConnection()
            if (result.isSuccess) {
                _cameraTestState.value = CameraTestState.Success(result.getOrNull() ?: "Connected successfully!")
            } else {
                _cameraTestState.value = CameraTestState.Error(result.exceptionOrNull()?.message ?: "Connection failed")
            }
        }
    }

    fun switchCameraToH264() {
        viewModelScope.launch {
            _cameraTestState.value = CameraTestState.Testing
            val result = onvifClient.switchMainStreamsToH264()
            if (result.isSuccess) {
                _cameraTestState.value = CameraTestState.Success(result.getOrNull() ?: "Camera switched to H.264!")
            } else {
                _cameraTestState.value = CameraTestState.Error(result.exceptionOrNull()?.message ?: "Failed to switch codec")
            }
        }
    }

    fun resetCameraTestState() {
        _cameraTestState.value = CameraTestState.Idle
    }

    fun triggerManualSdCatchup(hoursBack: Int = 1) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val start = now - (hoursBack * 3600 * 1000L)
            AppLogger.i("ManualSync", "Triggering manual SD card catchup for past $hoursBack hour(s)...")

            syncManager.syncMissingFootageTillNow(
                startTimeMs = start,
                endTimeMs = now,
                rtmpTargetFixed = settings.getFormattedFixedRtmp(),
                rtmpTargetRotatable = settings.getFormattedRotatableRtmp()
            )
        }
    }

    fun clearLogs() {
        AppLogger.clear()
    }
}
