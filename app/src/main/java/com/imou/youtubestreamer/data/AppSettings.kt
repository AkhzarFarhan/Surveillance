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

    var fixedChannel: Int
        get() = prefs.getInt(KEY_FIXED_CHANNEL, 2)
        set(value) = prefs.edit().putInt(KEY_FIXED_CHANNEL, value).apply()

    var rotatableChannel: Int
        get() = prefs.getInt(KEY_ROTATABLE_CHANNEL, 1)
        set(value) = prefs.edit().putInt(KEY_ROTATABLE_CHANNEL, value).apply()

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

    fun getFormattedFixedRtmp(): String {
        val target = manualRtmpFixed.trim()
        if (target.isBlank()) return ""
        return if (target.startsWith("rtmp://") || target.startsWith("rtmps://")) {
            target
        } else {
            "rtmps://a.rtmp.youtube.com/live2/$target"
        }
    }

    fun getFormattedRotatableRtmp(): String {
        val target = manualRtmpRotatable.trim()
        if (target.isBlank()) return ""
        return if (target.startsWith("rtmp://") || target.startsWith("rtmps://")) {
            target
        } else {
            "rtmps://a.rtmp.youtube.com/live2/$target"
        }
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
        private const val KEY_HOME_WIFI_SSID = "pref_home_wifi_ssid"
        private const val KEY_AUTO_START_BOOT = "pref_auto_start_boot"
        private const val KEY_AUTO_CHECK_UPDATES = "pref_auto_check_updates"
        private const val KEY_LAST_OUTAGE = "pref_last_outage"
        private const val KEY_SIGNED_IN_EMAIL = "pref_signed_in_email"
    }
}
