package com.imou.youtubestreamer.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imou.youtubestreamer.auth.YouTubeAuthManager
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.LogEntry
import com.imou.youtubestreamer.data.SystemStreamState
import com.imou.youtubestreamer.service.CctvStreamingService
import com.imou.youtubestreamer.sync.DahuaCgiClient
import com.imou.youtubestreamer.sync.DahuaSdCardSyncManager
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
    val authManager = YouTubeAuthManager(application, settings)
    private val cgiClient = DahuaCgiClient(settings)
    private val syncManager = DahuaSdCardSyncManager(application, settings)

    val systemState: StateFlow<SystemStreamState> = CctvStreamingService.systemState
    val logs: StateFlow<List<LogEntry>> = AppLogger.logs

    private val _cameraTestState = MutableStateFlow<CameraTestState>(CameraTestState.Idle)
    val cameraTestState: StateFlow<CameraTestState> = _cameraTestState.asStateFlow()

    private val _isSignedIn = MutableStateFlow(authManager.isUserSignedIn())
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _userEmail = MutableStateFlow(settings.signedInEmail)
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    fun toggleStreaming() {
        val app = getApplication<Application>()
        if (CctvStreamingService.isRunning) {
            CctvStreamingService.stop(app)
        } else {
            CctvStreamingService.start(app)
        }
    }

    fun handleSignInResult(data: Intent?) {
        val result = authManager.handleSignInResult(data)
        if (result.isSuccess) {
            _isSignedIn.value = true
            _userEmail.value = settings.signedInEmail
        }
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            authManager.signOut()
            _isSignedIn.value = false
            _userEmail.value = ""
        }
    }

    fun testCameraConnection() {
        viewModelScope.launch {
            _cameraTestState.value = CameraTestState.Testing
            val result = cgiClient.testConnection()
            if (result.isSuccess) {
                _cameraTestState.value = CameraTestState.Success("Connected! Dahua CGI responded successfully.")
            } else {
                _cameraTestState.value = CameraTestState.Error(result.exceptionOrNull()?.message ?: "Connection failed")
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
                rtmpTargetFixed = settings.manualRtmpFixed,
                rtmpTargetRotatable = settings.manualRtmpRotatable
            )
        }
    }

    fun clearLogs() {
        AppLogger.clear()
    }
}
