package com.imou.youtubestreamer.data

import java.net.URI

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

object SettingsValidator {

    private val HOSTNAME_REGEX = Regex("^[a-zA-Z0-9.-]+$")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun normalizeRtmpTarget(raw: String): String {
        val target = raw.trim()
        if (target.isBlank()) return ""
        return when {
            // YouTube's TLS ingest certificate is issued for *.rtmps.youtube.com, not a.rtmp.youtube.com
            target.startsWith("rtmps://a.rtmp.youtube.com/", ignoreCase = true) ->
                "rtmps://a.rtmps.youtube.com/" + target.substringAfter("rtmps://a.rtmp.youtube.com/")
            target.startsWith("rtmp://", ignoreCase = true) || target.startsWith("rtmps://", ignoreCase = true) ->
                target
            else ->
                "rtmps://a.rtmps.youtube.com/live2/$target"
        }
    }

    fun isValidHostOrIp(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val ipv4Match = IPV4_REGEX.matchEntire(trimmed)
        if (ipv4Match != null) {
            return (1..4).all { idx ->
                val octet = ipv4Match.groupValues[idx].toIntOrNull() ?: -1
                octet in 0..255
            }
        }
        return HOSTNAME_REGEX.matches(trimmed) && !trimmed.startsWith(".") && !trimmed.endsWith(".")
    }

    fun isValidRtmpInput(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true // Blank is allowed individually
        if (trimmed.any { it.isWhitespace() }) return false
        if (trimmed.contains("://")) {
            if (!trimmed.startsWith("rtmp://", ignoreCase = true) &&
                !trimmed.startsWith("rtmps://", ignoreCase = true)
            ) {
                return false
            }
            return try {
                val uri = URI(trimmed)
                !uri.host.isNullOrBlank() && !uri.rawPath.isNullOrBlank()
            } catch (_: Throwable) {
                false
            }
        }
        // Raw YouTube stream key token
        return trimmed.length >= 4 && !trimmed.contains("/")
    }

    fun validateCameraSettings(
        cameraIp: String,
        rtspPort: Int,
        httpPort: Int,
        fixedChannel: Int,
        rotatableChannel: Int
    ): ValidationResult {
        val errors = mutableListOf<String>()
        if (!isValidHostOrIp(cameraIp)) {
            errors.add("Invalid Camera IP/Host address.")
        }
        if (rtspPort !in 1..65535) {
            errors.add("RTSP port must be between 1 and 65535.")
        }
        if (httpPort !in 1..65535) {
            errors.add("HTTP/ONVIF port must be between 1 and 65535.")
        }
        if (fixedChannel < 1 || rotatableChannel < 1) {
            errors.add("Camera channel numbers must be >= 1.")
        }
        if (fixedChannel == rotatableChannel) {
            errors.add("Fixed channel ($fixedChannel) and Rotatable channel ($rotatableChannel) cannot be the same.")
        }
        return ValidationResult(errors.isEmpty(), errors)
    }

    fun validateStreamKeys(
        fixedLive: String,
        rotatableLive: String,
        fixedBackfill: String = "",
        rotatableBackfill: String = "",
        requireAtLeastOneLive: Boolean = false
    ): ValidationResult {
        val errors = mutableListOf<String>()

        if (requireAtLeastOneLive && fixedLive.isBlank() && rotatableLive.isBlank()) {
            errors.add("At least one YouTube live stream key must be configured.")
        }
        if (!isValidRtmpInput(fixedLive)) {
            errors.add("Fixed Live stream key/URL is malformed (no spaces; valid key or rtmp(s):// URL).")
        }
        if (!isValidRtmpInput(rotatableLive)) {
            errors.add("Rotatable Live stream key/URL is malformed (no spaces; valid key or rtmp(s):// URL).")
        }
        if (!isValidRtmpInput(fixedBackfill)) {
            errors.add("Fixed Backfill stream key/URL is malformed.")
        }
        if (!isValidRtmpInput(rotatableBackfill)) {
            errors.add("Rotatable Backfill stream key/URL is malformed.")
        }

        val normFixedLive = normalizeRtmpTarget(fixedLive)
        val normRotLive = normalizeRtmpTarget(rotatableLive)
        val normFixedBf = normalizeRtmpTarget(fixedBackfill)
        val normRotBf = normalizeRtmpTarget(rotatableBackfill)

        if (normFixedLive.isNotEmpty() && normRotLive.isNotEmpty() &&
            normFixedLive.equals(normRotLive, ignoreCase = true)
        ) {
            errors.add("Fixed (_F) and Rotatable (_R) stream keys cannot be identical — two publishers on one key fail on YouTube.")
        }

        if (normFixedBf.isNotEmpty() && normRotBf.isNotEmpty() &&
            normFixedBf.equals(normRotBf, ignoreCase = true)
        ) {
            errors.add("Fixed and Rotatable backfill stream keys cannot be identical.")
        }

        if (normFixedBf.isNotEmpty() &&
            (normFixedBf.equals(normFixedLive, ignoreCase = true) ||
                normFixedBf.equals(normRotLive, ignoreCase = true))
        ) {
            errors.add("Fixed backfill key cannot match a live stream key (would cause dual-publisher conflict).")
        }

        if (normRotBf.isNotEmpty() &&
            (normRotBf.equals(normFixedLive, ignoreCase = true) ||
                normRotBf.equals(normRotLive, ignoreCase = true))
        ) {
            errors.add("Rotatable backfill key cannot match a live stream key (would cause dual-publisher conflict).")
        }

        return ValidationResult(errors.isEmpty(), errors)
    }
}
