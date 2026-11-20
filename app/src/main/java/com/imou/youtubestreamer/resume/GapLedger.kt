package com.imou.youtubestreamer.resume

import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.Lens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class GapStatus {
    OPEN,
    PENDING,
    IN_PROGRESS,
    DONE,
    ABANDONED
}

data class Gap(
    val id: String = UUID.randomUUID().toString(),
    val lens: Lens,
    val startWallMs: Long,
    val endWallMs: Long? = null,
    val doneUpToWallMs: Long = startWallMs,
    val attempts: Int = 0,
    val status: GapStatus = GapStatus.OPEN
) {
    fun remainingMs(nowWallMs: Long): Long {
        val end = endWallMs ?: nowWallMs
        return (end - doneUpToWallMs).coerceAtLeast(0L)
    }
}

/**
 * Crash-safe persistent ledger of unstreamed surveillance outages per lens.
 *
 * Backed by atomic `.tmp -> fsync -> rename` writes to `filesDir/gap_ledger.json`
 * so mid-outage process kills or reboots never corrupt or lose gap boundaries.
 */
class GapLedger(
    private val ledgerFile: File,
    private val clock: Clock
) {
    companion object {
        private const val TAG = "GapLedger"
        const val MERGE_PROXIMITY_MS = 10_000L
        private const val RETENTION_CLEANUP_FINISHED_AGE_MS = 7L * 24 * 3600 * 1000L
    }

    private val gaps = mutableListOf<Gap>()
    private val _pendingGaps = MutableStateFlow<List<Gap>>(emptyList())
    val pendingGaps: StateFlow<List<Gap>> = _pendingGaps.asStateFlow()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun openGap(lens: Lens, startWallMs: Long) {
        val existingOpen = gaps.find { it.lens == lens && it.status == GapStatus.OPEN }
        if (existingOpen != null) {
            return
        }
        val safeStart = startWallMs.coerceAtMost(clock.nowMs())
        gaps.add(
            Gap(
                lens = lens,
                startWallMs = safeStart,
                endWallMs = null,
                doneUpToWallMs = safeStart,
                attempts = 0,
                status = GapStatus.OPEN
            )
        )
        AppLogger.step(TAG, "openGap", "Opened gap for ${lens.code} at startWallMs=$safeStart")
        persistAndPublish()
    }

    @Synchronized
    fun closeGap(lens: Lens, endWallMs: Long, minGapSec: Int) {
        val idx = gaps.indexOfFirst { it.lens == lens && it.status == GapStatus.OPEN }
        if (idx < 0) return

        val open = gaps[idx]
        val safeEnd = endWallMs.coerceAtLeast(open.startWallMs)
        val durationMs = safeEnd - open.startWallMs
        val minGapMs = minGapSec.coerceAtLeast(1) * 1000L

        if (durationMs < minGapMs) {
            AppLogger.step(
                TAG,
                "closeGap",
                "Discarding short ${lens.code} gap (${durationMs / 1000}s < minGap ${minGapSec}s)"
            )
            gaps.removeAt(idx)
            persistAndPublish()
            return
        }

        gaps[idx] = open.copy(endWallMs = safeEnd, status = GapStatus.PENDING)
        mergePendingGapsLocked(lens)
        AppLogger.step(TAG, "closeGap", "Closed ${lens.code} gap: ${(safeEnd - open.startWallMs) / 1000}s queued as PENDING")
        persistAndPublish()
    }

    @Synchronized
    fun recordOutage(lens: Lens, startWallMs: Long, endWallMs: Long, minGapSec: Int) {
        if (endWallMs <= startWallMs) return
        val durationMs = endWallMs - startWallMs
        val minGapMs = minGapSec.coerceAtLeast(1) * 1000L
        if (durationMs < minGapMs) return

        gaps.add(
            Gap(
                lens = lens,
                startWallMs = startWallMs,
                endWallMs = endWallMs,
                doneUpToWallMs = startWallMs,
                attempts = 0,
                status = GapStatus.PENDING
            )
        )
        mergePendingGapsLocked(lens)
        AppLogger.step(
            TAG,
            "recordOutage",
            "Recorded ${lens.code} outage gap (${durationMs / 1000}s)"
        )
        persistAndPublish()
    }

    @Synchronized
    fun markInProgress(gapId: String) {
        val idx = gaps.indexOfFirst { it.id == gapId }
        if (idx < 0) return
        val g = gaps[idx]
        if (g.status == GapStatus.PENDING || g.status == GapStatus.IN_PROGRESS) {
            gaps[idx] = g.copy(status = GapStatus.IN_PROGRESS)
            persistAndPublish()
        }
    }

    @Synchronized
    fun markPaused(gapId: String) {
        val idx = gaps.indexOfFirst { it.id == gapId }
        if (idx < 0) return
        val g = gaps[idx]
        if (g.status == GapStatus.IN_PROGRESS) {
            val end = g.endWallMs ?: g.doneUpToWallMs
            val nextStatus = if (g.doneUpToWallMs >= end) GapStatus.DONE else GapStatus.PENDING
            gaps[idx] = g.copy(status = nextStatus)
            persistAndPublish()
        }
    }

    @Synchronized
    fun advanceProgress(gapId: String, newDoneUpToWallMs: Long) {
        val idx = gaps.indexOfFirst { it.id == gapId }
        if (idx < 0) return
        val g = gaps[idx]
        val end = g.endWallMs ?: return
        val clampedDone = newDoneUpToWallMs.coerceIn(g.doneUpToWallMs, end)
        val finished = clampedDone >= end
        gaps[idx] = g.copy(
            doneUpToWallMs = clampedDone,
            status = if (finished) GapStatus.DONE else GapStatus.IN_PROGRESS
        )
        persistAndPublish()
    }

    @Synchronized
    fun incrementAttempts(gapId: String): Int {
        val idx = gaps.indexOfFirst { it.id == gapId }
        if (idx < 0) return 0
        val g = gaps[idx]
        val newAttempts = g.attempts + 1
        gaps[idx] = g.copy(attempts = newAttempts)
        persistAndPublish()
        return newAttempts
    }

    @Synchronized
    fun markAbandoned(gapId: String) {
        val idx = gaps.indexOfFirst { it.id == gapId }
        if (idx < 0) return
        gaps[idx] = gaps[idx].copy(status = GapStatus.ABANDONED)
        persistAndPublish()
    }

    @Synchronized
    fun pruneExpired(nowWallMs: Long, sdRetentionHours: Int) {
        val retentionCutoff = nowWallMs - (sdRetentionHours.coerceAtLeast(1) * 3600L * 1000L)
        var changed = false

        val iterator = gaps.listIterator()
        while (iterator.hasNext()) {
            val g = iterator.next()
            when (g.status) {
                GapStatus.PENDING, GapStatus.IN_PROGRESS -> {
                    val end = g.endWallMs ?: continue
                    if (end <= retentionCutoff) {
                        iterator.set(g.copy(status = GapStatus.ABANDONED))
                        changed = true
                    } else if (g.doneUpToWallMs < retentionCutoff) {
                        iterator.set(g.copy(doneUpToWallMs = retentionCutoff))
                        changed = true
                    }
                }
                GapStatus.DONE, GapStatus.ABANDONED -> {
                    val refEnd = g.endWallMs ?: g.startWallMs
                    if (refEnd < nowWallMs - RETENTION_CLEANUP_FINISHED_AGE_MS) {
                        iterator.remove()
                        changed = true
                    }
                }
                GapStatus.OPEN -> {
                    if (g.startWallMs < retentionCutoff) {
                        iterator.set(g.copy(startWallMs = retentionCutoff, doneUpToWallMs = retentionCutoff))
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            persistAndPublish()
        }
    }

    @Synchronized
    fun nextPendingGap(lens: Lens): Gap? {
        return gaps
            .filter { it.lens == lens && (it.status == GapStatus.IN_PROGRESS || it.status == GapStatus.PENDING) }
            .sortedBy { it.startWallMs }
            .firstOrNull()
    }

    @Synchronized
    fun hasOpenGap(lens: Lens): Boolean =
        gaps.any { it.lens == lens && it.status == GapStatus.OPEN }

    @Synchronized
    fun pendingMinutesFor(lens: Lens, nowWallMs: Long): Long {
        val totalMs = gaps
            .filter { it.lens == lens && (it.status == GapStatus.PENDING || it.status == GapStatus.IN_PROGRESS || it.status == GapStatus.OPEN) }
            .sumOf { it.remainingMs(nowWallMs) }
        return totalMs / 60_000L
    }

    @Synchronized
    fun allGapsSnapshot(): List<Gap> = gaps.toList()

    private fun mergePendingGapsLocked(lens: Lens) {
        val sameLensPending = gaps
            .filter { it.lens == lens && (it.status == GapStatus.PENDING || it.status == GapStatus.IN_PROGRESS) }
            .sortedBy { it.startWallMs }
        if (sameLensPending.size <= 1) return

        val others = gaps.filterNot {
            it.lens == lens && (it.status == GapStatus.PENDING || it.status == GapStatus.IN_PROGRESS)
        }

        val merged = mutableListOf<Gap>()
        for (current in sameLensPending) {
            val prev = merged.lastOrNull()
            if (prev == null) {
                merged.add(current)
                continue
            }
            val prevEnd = prev.endWallMs ?: prev.startWallMs
            val curEnd = current.endWallMs ?: current.startWallMs
            if (current.startWallMs <= prevEnd + MERGE_PROXIMITY_MS) {
                val combinedEnd = maxOf(prevEnd, curEnd)
                val combinedDone = minOf(prev.doneUpToWallMs, current.doneUpToWallMs)
                val combinedStatus = if (prev.status == GapStatus.IN_PROGRESS || current.status == GapStatus.IN_PROGRESS) {
                    GapStatus.IN_PROGRESS
                } else {
                    GapStatus.PENDING
                }
                merged[merged.lastIndex] = prev.copy(
                    startWallMs = minOf(prev.startWallMs, current.startWallMs),
                    endWallMs = combinedEnd,
                    doneUpToWallMs = minOf(combinedDone, combinedEnd),
                    attempts = maxOf(prev.attempts, current.attempts),
                    status = combinedStatus
                )
            } else {
                merged.add(current)
            }
        }

        gaps.clear()
        gaps.addAll(others)
        gaps.addAll(merged)
    }

    private fun persistAndPublish() {
        publishSnapshotLocked()
        writeToDiskAtomicLocked()
    }

    private fun publishSnapshotLocked() {
        _pendingGaps.value = gaps
            .filter { it.status == GapStatus.OPEN || it.status == GapStatus.PENDING || it.status == GapStatus.IN_PROGRESS }
            .sortedBy { it.startWallMs }
    }

    private fun loadFromDisk() {
        synchronized(this) {
            try {
                if (!ledgerFile.exists() || ledgerFile.length() == 0L) {
                    publishSnapshotLocked()
                    return
                }
                val raw = ledgerFile.readText(Charsets.UTF_8)
                val arr = JSONArray(raw)
                val now = clock.nowMs()
                gaps.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val lens = try {
                        Lens.valueOf(obj.getString("lens"))
                    } catch (_: Exception) {
                        continue
                    }
                    val startWallMs = obj.getLong("startWallMs")
                    val endWallMs = if (obj.isNull("endWallMs")) null else obj.getLong("endWallMs")
                    val doneUpToWallMs = obj.optLong("doneUpToWallMs", startWallMs)
                    val attempts = obj.optInt("attempts", 0)
                    var status = try {
                        GapStatus.valueOf(obj.getString("status"))
                    } catch (_: Exception) {
                        GapStatus.PENDING
                    }

                    // Recover IN_PROGRESS back to PENDING across process death (resume from doneUpToWallMs),
                    // and recover stale OPEN gaps left behind by crashes into PENDING [startWallMs, now]
                    var normalizedEnd = endWallMs
                    if (status == GapStatus.IN_PROGRESS) {
                        status = GapStatus.PENDING
                    } else if (status == GapStatus.OPEN) {
                        normalizedEnd = now.coerceAtLeast(startWallMs)
                        status = GapStatus.PENDING
                    }

                    gaps.add(
                        Gap(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            lens = lens,
                            startWallMs = startWallMs,
                            endWallMs = normalizedEnd,
                            doneUpToWallMs = doneUpToWallMs.coerceAtLeast(startWallMs),
                            attempts = attempts,
                            status = status
                        )
                    )
                }
                Lens.entries.forEach { mergePendingGapsLocked(it) }
                publishSnapshotLocked()
            } catch (t: Throwable) {
                AppLogger.exception(TAG, "loadFromDisk", t, "file=${ledgerFile.absolutePath}")
            }
        }
    }

    private fun writeToDiskAtomicLocked() {
        try {
            ledgerFile.parentFile?.mkdirs()
            val arr = JSONArray()
            for (g in gaps) {
                val obj = JSONObject()
                obj.put("id", g.id)
                obj.put("lens", g.lens.name)
                obj.put("startWallMs", g.startWallMs)
                if (g.endWallMs != null) {
                    obj.put("endWallMs", g.endWallMs)
                } else {
                    obj.put("endWallMs", JSONObject.NULL)
                }
                obj.put("doneUpToWallMs", g.doneUpToWallMs)
                obj.put("attempts", g.attempts)
                obj.put("status", g.status.name)
                arr.put(obj)
            }

            val tmp = File(ledgerFile.parentFile, "${ledgerFile.name}.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(arr.toString(2).toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }
            try {
                Files.move(
                    tmp.toPath(),
                    ledgerFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(),
                    ledgerFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "writeToDiskAtomicLocked", t, "file=${ledgerFile.absolutePath}")
        }
    }
}
