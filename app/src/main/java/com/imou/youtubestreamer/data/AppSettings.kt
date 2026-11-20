package com.imou.youtubestreamer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages configuration and credentials with hardware-backed encryption (AES-256-GCM).
 * Falls back safely to standard private SharedPreferences if hardware keystore is unavailable.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "surveillance_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("AppSettings", "EncryptedSharedPreferences unavailable, falling back to private prefs: ${e.message}")
        context.getSharedPreferences("surveillance_prefs", Context.MODE_PRIVATE)
    }

    var cameraIp: String
        get() = prefs.getString(KEY_CAMERA_IP, "192.168.1.2") ?: "192.168.1.2"
        set(value) = prefs.edit().putString(KEY_CAMERA_IP, value.trim()).apply()

    var rtspPort: Int
        get() = prefs.getInt(KEY_RTSP_PORT, 554)
        set(value) = prefs.edit().putInt(KEY_RTSP_PORT, value).apply()

    var httpPort: Int
        get() = prefs.getInt(KEY_HTTP_PORT, 80)
        set(value) = prefs.edit().putInt(KEY_HTTP_PORT, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "admin") ?: "admin"
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var safetyCode: String
        get() = prefs.getString(KEY_SAFETY_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SAFETY_CODE, value.trim()).apply()

    /**
     * Builds an RTSP live-monitoring URL with percent-encoded userinfo so passwords
     * containing '@' (e.g. "767325@Nz") or special characters parse cleanly in FFmpeg.
     */
    fun buildLiveRtspUrl(channel: Int, subtype: Int = streamSubtype): String {
        val userEnc = encodeUrlComponent(username)
        val passEnc = encodeUrlComponent(safetyCode)
        return "rtsp://$userEnc:$passEnc@$cameraIp:$rtspPort/cam/realmonitor?channel=$channel&subtype=$subtype"
    }

    /**
     * Builds an RTSP MicroSD playback URL including the required subtype query parameter
     * and percent-encoded credentials.
     */
    fun buildPlaybackRtspUrl(
        channel: Int,
        startTimeStr: String,
        endTimeStr: String,
        subtype: Int = streamSubtype
    ): String {
        val userEnc = encodeUrlComponent(username)
        val passEnc = encodeUrlComponent(safetyCode)
        return "rtsp://$userEnc:$passEnc@$cameraIp:$rtspPort/cam/playback?channel=$channel&subtype=$subtype&starttime=$startTimeStr&endtime=$endTimeStr"
    }

    private fun encodeUrlComponent(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    var fixedChannel: Int
        get() = prefs.getInt(KEY_FIXED_CHANNEL, 2)
        set(value) = prefs.edit().putInt(KEY_FIXED_CHANNEL, value).apply()

    var rotatableChannel: Int
        get() = prefs.getInt(KEY_ROTATABLE_CHANNEL, 1)
        set(value) = prefs.edit().putInt(KEY_ROTATABLE_CHANNEL, value).apply()

    fun channelFor(lens: Lens): Int = when (lens) {
        Lens.FIXED -> fixedChannel
        Lens.ROTATABLE -> rotatableChannel
    }

    var streamSubtype: Int
        get() = prefs.getInt(KEY_STREAM_SUBTYPE, 0) // 0 = Main high quality, 1 = Sub stream
        set(value) = prefs.edit().putInt(KEY_STREAM_SUBTYPE, value).apply()

    var catchupSpeed: Float
        get() = prefs.getFloat(KEY_CATCHUP_SPEED, 3.0f)
        set(value) = prefs.edit().putFloat(KEY_CATCHUP_SPEED, value).apply()

    var silentAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_SILENT_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_AUDIO, value).apply()

    var privacyStatus: String
        get() = prefs.getString(KEY_PRIVACY_STATUS, "private") ?: "private"
        set(value) = prefs.edit().putString(KEY_PRIVACY_STATUS, value).apply()

    var useAutoBroadcast: Boolean
        get() = prefs.getBoolean(KEY_USE_AUTO_BROADCAST, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_AUTO_BROADCAST, value).apply()

    var manualRtmpFixed: String
        get() = prefs.getString(KEY_MANUAL_RTMP_FIXED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MANUAL_RTMP_FIXED, value.trim()).apply()

    var manualRtmpRotatable: String
        get() = prefs.getString(KEY_MANUAL_RTMP_ROTATABLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MANUAL_RTMP_ROTATABLE, value.trim()).apply()

    var backfillRtmpFixed: String
        get() = prefs.getString(KEY_BACKFILL_RTMP_FIXED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKFILL_RTMP_FIXED, value.trim()).apply()

    var backfillRtmpRotatable: String
        get() = prefs.getString(KEY_BACKFILL_RTMP_ROTATABLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKFILL_RTMP_ROTATABLE, value.trim()).apply()

    var backfillMode: BackfillMode
        get() {
            val raw = prefs.getString(KEY_BACKFILL_MODE, BackfillMode.SEPARATE_KEY.name)
            return try {
                BackfillMode.valueOf(raw ?: BackfillMode.SEPARATE_KEY.name)
            } catch (_: Exception) {
                BackfillMode.SEPARATE_KEY
            }
        }
        set(value) = prefs.edit().putString(KEY_BACKFILL_MODE, value.name).apply()

    var allowCellular: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_CELLULAR, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_CELLULAR, value).apply()

    var minGapToBackfillSec: Int
        get() = prefs.getInt(KEY_MIN_GAP_BACKFILL_SEC, 60).coerceIn(10, 3600)
        set(value) = prefs.edit().putInt(KEY_MIN_GAP_BACKFILL_SEC, value.coerceIn(10, 3600)).apply()

    var sdRetentionHours: Int
        get() = prefs.getInt(KEY_SD_RETENTION_HOURS, 72).coerceIn(1, 720)
        set(value) = prefs.edit().putInt(KEY_SD_RETENTION_HOURS, value.coerceIn(1, 720)).apply()

    var rotationPeriodMinutesOverride: Int
        get() = prefs.getInt(KEY_ROTATION_OVERRIDE_MIN, 0).coerceIn(0, 720)
        set(value) = prefs.edit().putInt(KEY_ROTATION_OVERRIDE_MIN, value.coerceIn(0, 720)).apply()

    fun getFormattedFixedRtmp(): String = SettingsValidator.normalizeRtmpTarget(manualRtmpFixed)

    fun getFormattedRotatableRtmp(): String = SettingsValidator.normalizeRtmpTarget(manualRtmpRotatable)

    fun getFormattedBackfillFixedRtmp(): String = SettingsValidator.normalizeRtmpTarget(backfillRtmpFixed)

    fun getFormattedBackfillRotatableRtmp(): String = SettingsValidator.normalizeRtmpTarget(backfillRtmpRotatable)

    fun liveRtmpFor(lens: Lens): String = when (lens) {
        Lens.FIXED -> getFormattedFixedRtmp()
        Lens.ROTATABLE -> getFormattedRotatableRtmp()
    }

    fun backfillRtmpFor(lens: Lens): String = when (lens) {
        Lens.FIXED -> getFormattedBackfillFixedRtmp()
        Lens.ROTATABLE -> getFormattedBackfillRotatableRtmp()
    }

    var homeWifiSsid: String
        get() = prefs.getString(KEY_HOME_WIFI_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_WIFI_SSID, value.trim()).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply()

    var lastOutageTimestamp: Long
        get() = prefs.getLong(KEY_LAST_OUTAGE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OUTAGE, value).apply()

    var signedInEmail: String
        get() = prefs.getString(KEY_SIGNED_IN_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIGNED_IN_EMAIL, value).apply()

    companion object {
        private const val KEY_CAMERA_IP = "pref_camera_ip"
        private const val KEY_RTSP_PORT = "pref_rtsp_port"
        private const val KEY_HTTP_PORT = "pref_http_port"
        private const val KEY_USERNAME = "pref_username"
        private const val KEY_SAFETY_CODE = "pref_safety_code"
        private const val KEY_FIXED_CHANNEL = "pref_fixed_channel"
        private const val KEY_ROTATABLE_CHANNEL = "pref_rotatable_channel"
        private const val KEY_STREAM_SUBTYPE = "pref_stream_subtype"
        private const val KEY_CATCHUP_SPEED = "pref_catchup_speed"
        private const val KEY_SILENT_AUDIO = "pref_silent_audio"
        private const val KEY_PRIVACY_STATUS = "pref_privacy_status"
        private const val KEY_USE_AUTO_BROADCAST = "pref_use_auto_broadcast"
        private const val KEY_MANUAL_RTMP_FIXED = "pref_manual_rtmp_fixed"
        private const val KEY_MANUAL_RTMP_ROTATABLE = "pref_manual_rtmp_rotatable"
        private const val KEY_BACKFILL_RTMP_FIXED = "pref_backfill_rtmp_fixed"
        private const val KEY_BACKFILL_RTMP_ROTATABLE = "pref_backfill_rtmp_rotatable"
        private const val KEY_BACKFILL_MODE = "pref_backfill_mode"
        private const val KEY_ALLOW_CELLULAR = "pref_allow_cellular"
        private const val KEY_MIN_GAP_BACKFILL_SEC = "pref_min_gap_backfill_sec"
        private const val KEY_SD_RETENTION_HOURS = "pref_sd_retention_hours"
        private const val KEY_ROTATION_OVERRIDE_MIN = "pref_rotation_override_min"
        private const val KEY_HOME_WIFI_SSID = "pref_home_wifi_ssid"
        private const val KEY_AUTO_START_BOOT = "pref_auto_start_boot"
        private const val KEY_AUTO_CHECK_UPDATES = "pref_auto_check_updates"
        private const val KEY_LAST_OUTAGE = "pref_last_outage"
        private const val KEY_SIGNED_IN_EMAIL = "pref_signed_in_email"
    }
}
