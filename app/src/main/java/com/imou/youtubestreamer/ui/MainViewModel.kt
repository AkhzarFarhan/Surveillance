package com.imou.youtubestreamer.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.LogEntry
import com.imou.youtubestreamer.data.PipelineStatus
import com.imou.youtubestreamer.data.SettingsValidator
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.data.ValidationResult
import com.imou.youtubestreamer.service.CctvStreamingService
import com.imou.youtubestreamer.supervisor.SupervisorStore
import com.imou.youtubestreamer.sync.DahuaCgiClient
import com.imou.youtubestreamer.sync.DahuaSdCardSyncManager
import com.imou.youtubestreamer.sync.OnvifCameraClient
import com.imou.youtubestreamer.update.AppUpdateManager
import com.imou.youtubestreamer.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class CameraTestState {
    object Idle : CameraTestState()
    object Testing : CameraTestState()
    data class Success(val message: String) : CameraTestState()
    data class Error(val error: String) : CameraTestState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

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

    private val _cgiCheckMessage = MutableStateFlow<String?>(null)
    val cgiCheckMessage: StateFlow<String?> = _cgiCheckMessage.asStateFlow()

    private val _cgiChecking = MutableStateFlow(false)
    val cgiChecking: StateFlow<Boolean> = _cgiChecking.asStateFlow()

    fun checkForUpdates(context: Context = getApplication(), manual: Boolean = false) {
        AppLogger.enter(TAG, "checkForUpdates", "manual=$manual")
        val appCtx = context.applicationContext
        viewModelScope.launch {
            try {
                val result = AppUpdateManager.checkForUpdates(appCtx)
                AppLogger.step(TAG, "checkForUpdates", "Result=$result")
                if (manual) {
                    when (result) {
                        is UpdateStatus.UpToDate -> {
                            _manualCheckMessage.value = "You are on the latest version (v${result.currentVersion})."
                        }
                        is UpdateStatus.Error -> {
                            _manualCheckMessage.value = "Check failed: ${result.message}"
                        }
                        is UpdateStatus.UpdateAvailable, is UpdateStatus.ReadyToInstall -> {
                            _manualCheckMessage.value = null
                        }
                        else -> {}
                    }
                }
                AppLogger.exit(TAG, "checkForUpdates", result::class.java.simpleName)
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "checkForUpdates", t)
            }
        }
    }

    fun downloadAndInstallUpdate(
        context: Context,
        downloadUrl: String,
        targetVersion: String = "latest"
    ) {
        AppLogger.enter(TAG, "downloadAndInstallUpdate", "url='$downloadUrl', targetVersion='$targetVersion'")
        val appCtx = context.applicationContext
        viewModelScope.launch {
            try {
                val result = AppUpdateManager.downloadUpdate(appCtx, downloadUrl, targetVersion)
                if (result.isSuccess) {
                    val apkFile = result.getOrThrow()
                    AppLogger.step(TAG, "downloadAndInstallUpdate", "Downloaded APK to ${apkFile.absolutePath} (${apkFile.length()} bytes)")
                    if (AppUpdateManager.canInstallPackages(appCtx)) {
                        AppUpdateManager.installApk(context, apkFile, downloadUrl)
                    }
                } else {
                    AppLogger.unexpected(TAG, "downloadAndInstallUpdate", "Download failed", result.exceptionOrNull()?.message)
                }
                AppLogger.exit(TAG, "downloadAndInstallUpdate", "isSuccess=${result.isSuccess}")
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "downloadAndInstallUpdate", t)
            }
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File, fallbackUrl: String = "") {
        AppLogger.enter(TAG, "installDownloadedApk", "apkFile=${apkFile.absolutePath}, exists=${apkFile.exists()}")
        try {
            if (AppUpdateManager.canInstallPackages(context)) {
                AppUpdateManager.installApk(context, apkFile, fallbackUrl)
            } else {
                AppUpdateManager.openInstallPermissionSettings(context)
            }
            AppLogger.exit(TAG, "installDownloadedApk", "Triggered")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "installDownloadedApk", t)
        }
    }

    fun dismissUpdateDialog() {
        AppUpdateManager.resetStatus()
    }

    fun clearManualCheckMessage() {
        _manualCheckMessage.value = null
    }

    fun saveStreamKeys(
        fixedKey: String,
        rotatableKey: String = "",
        fixedBackfillKey: String = settings.backfillRtmpFixed,
        rotatableBackfillKey: String = settings.backfillRtmpRotatable
    ): ValidationResult {
        AppLogger.enter(
            TAG,
            "saveStreamKeys",
            "fixedKey=${AppLogger.maskSecret(fixedKey)}, rotatableKey=${AppLogger.maskSecret(rotatableKey)}"
        )
        val validation = SettingsValidator.validateStreamKeys(
            fixedLive = fixedKey,
            rotatableLive = rotatableKey,
            fixedBackfill = fixedBackfillKey,
            rotatableBackfill = rotatableBackfillKey,
            requireAtLeastOneLive = false
        )
        settings.manualRtmpFixed = fixedKey.trim()
        settings.manualRtmpRotatable = rotatableKey.trim()
        settings.backfillRtmpFixed = fixedBackfillKey.trim()
        settings.backfillRtmpRotatable = rotatableBackfillKey.trim()
        AppLogger.i(
            "Settings",
            "YouTube Stream Keys updated (Fixed: ${if (fixedKey.isNotBlank()) "Set" else "Empty"}, Rotatable: ${if (rotatableKey.isNotBlank()) "Set" else "Empty"}, valid=${validation.isValid})"
        )
        AppLogger.exit(TAG, "saveStreamKeys", "valid=${validation.isValid}")
        return validation
    }

    fun toggleStreaming() {
        val app = getApplication<Application>()
        val status = systemState.value.status
        val activePipeline = CctvStreamingService.isRunning &&
            status != PipelineStatus.IDLE &&
            status != PipelineStatus.ERROR

        AppLogger.enter(
            TAG,
            "toggleStreaming",
            "serviceIsRunning=${CctvStreamingService.isRunning}, pipelineStatus=$status, activePipeline=$activePipeline"
        )

        try {
            if (activePipeline) {
                CctvStreamingService.stop(app)
                AppLogger.exit(TAG, "toggleStreaming", "Requested STOP")
            } else {
                SupervisorStore(app).clearCrashHistory()
                CctvStreamingService.start(app, explicit = true)
                AppLogger.exit(TAG, "toggleStreaming", "Requested START")
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "toggleStreaming", t)
        }
    }

    fun restartPipeline() {
        AppLogger.enter(TAG, "restartPipeline")
        try {
            val app = getApplication<Application>()
            SupervisorStore(app).clearCrashHistory()
            CctvStreamingService.restart(app)
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "restartPipeline", t)
        }
    }

    fun clearCrashLoopWarning() {
        SupervisorStore(getApplication()).clearCrashHistory()
        CctvStreamingService.restart(getApplication())
    }

    fun testCameraConnection() {
        AppLogger.enter(TAG, "testCameraConnection")
        viewModelScope.launch {
            try {
                _cameraTestState.value = CameraTestState.Testing
                val result = cgiClient.testConnection()
                if (result.isSuccess) {
                    val msg = result.getOrNull() ?: "Connected successfully!"
                    _cameraTestState.value = CameraTestState.Success(msg)
                    AppLogger.exit(TAG, "testCameraConnection", "Success")
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Connection failed"
                    _cameraTestState.value = CameraTestState.Error(err)
                    AppLogger.exit(TAG, "testCameraConnection", "Error($err)")
                }
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "testCameraConnection", t)
                _cameraTestState.value = CameraTestState.Error(t.localizedMessage ?: "Unexpected error")
            }
        }
    }

    fun switchCameraToH264() {
        AppLogger.enter(TAG, "switchCameraToH264")
        viewModelScope.launch {
            try {
                _cameraTestState.value = CameraTestState.Testing
                val result = onvifClient.switchMainStreamsToH264()
                if (result.isSuccess) {
                    _cameraTestState.value = CameraTestState.Success(result.getOrNull() ?: "Camera switched to H.264!")
                    AppLogger.exit(TAG, "switchCameraToH264", "Success")
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Failed to switch codec"
                    _cameraTestState.value = CameraTestState.Error(err)
                    AppLogger.exit(TAG, "switchCameraToH264", "Error($err)")
                }
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "switchCameraToH264", t)
                _cameraTestState.value = CameraTestState.Error(t.localizedMessage ?: "Unexpected error")
            }
        }
    }

    fun resetCameraTestState() {
        _cameraTestState.value = CameraTestState.Idle
    }

    fun triggerManualSdCatchup(hoursBack: Int = 1) {
        AppLogger.enter(TAG, "triggerManualSdCatchup", "hoursBack=$hoursBack")
        try {
            CctvStreamingService.enqueueManualBackfill(getApplication(), hoursBack)
            AppLogger.exit(TAG, "triggerManualSdCatchup", "Enqueued into GapLedger & StreamSupervisor")
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "triggerManualSdCatchup", t)
        }
    }

    fun runCgiBacklogDiagnostic(hoursBack: Int = 1) {
        AppLogger.enter(TAG, "runCgiBacklogDiagnostic", "hoursBack=$hoursBack")
        viewModelScope.launch {
            _cgiChecking.value = true
            try {
                val now = System.currentTimeMillis()
                val start = now - (hoursBack * 3600_000L)
                val res = syncManager.runCgiBacklogCheck(start, now)
                _cgiCheckMessage.value = res.getOrNull() ?: "Error: ${res.exceptionOrNull()?.message}"
            } catch (t: Throwable) {
                _cgiCheckMessage.value = "Error: ${t.localizedMessage}"
            } finally {
                _cgiChecking.value = false
            }
        }
    }

    fun clearLogs() {
        AppLogger.clear()
    }
}
