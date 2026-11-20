package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Structurally enforces the invariant: at most ONE active FFmpeg publisher
 * per normalized RTMP(S) stream key target at any moment across the entire app.
 */
class ActiveTargetGuard {

    private val activeOwners = ConcurrentHashMap<String, String>()

    private fun canonicalKey(rawTarget: String): String {
        val trimmed = rawTarget.trim().lowercase()
        // Treat plain rtmp://a.rtmp.youtube.com and rtmps://a.rtmps.youtube.com primary targets as the same key lock
        return trimmed
            .removePrefix("rtmps://")
            .removePrefix("rtmp://")
            .replace("a.rtmp.youtube.com/", "a.rtmps.youtube.com/")
    }

    fun tryAcquire(rawTarget: String, owner: String): Boolean {
        val key = canonicalKey(rawTarget)
        if (key.isEmpty()) return false
        val existing = activeOwners.putIfAbsent(key, owner)
        if (existing != null && existing != owner) {
            AppLogger.unexpected(
                "TargetGuard",
                "tryAcquire",
                "Rejected concurrent publisher '$owner': target already held by '$existing'"
            )
            return false
        }
        return true
    }

    fun release(rawTarget: String, owner: String) {
        val key = canonicalKey(rawTarget)
        if (key.isEmpty()) return
        activeOwners.remove(key, owner)
    }

    fun currentOwner(rawTarget: String): String? = activeOwners[canonicalKey(rawTarget)]

    fun activeCount(): Int = activeOwners.size
}
