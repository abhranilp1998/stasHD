package com.abhranil.stashd.download.progress

import com.abhranil.stashd.download.core.BatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Live snapshot the Downloads screen and notification read. Room holds the durable numbers (≈1 s behind). */
data class ProgressSnapshot(
    val batchId: Long = 0,
    val expanding: Boolean = false,
    val foldersScanned: Int = 0,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val filesDone: Int = 0,
    val totalFiles: Int = 0,
    val bytesPerSecond: Double = 0.0,
    val currentFile: String? = null,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val etaSeconds: Long? get() = if (bytesPerSecond < 1.0) null else ((totalBytes - bytesDone) / bytesPerSecond).toLong()
}

/** In-process hot channel from the running batch to the UI. Called per chunk; cheap. */
@Singleton
class ProgressBus @Inject constructor() {
    private val speed = SpeedMeter()
    private val _snapshot = MutableStateFlow<ProgressSnapshot?>(null)
    /** Null when no batch is running in this process. */
    val snapshot: StateFlow<ProgressSnapshot?> = _snapshot.asStateFlow()

    @Synchronized
    fun publish(p: BatchProgress, nowMs: Long = System.currentTimeMillis()): ProgressSnapshot {
        val expanding = p.phase == BatchProgress.Phase.EXPANDING
        val next = ProgressSnapshot(
            batchId = p.batchId, expanding = expanding, foldersScanned = p.foldersScanned,
            bytesDone = p.bytesDone, totalBytes = p.totalBytes, filesDone = p.filesDone, totalFiles = p.totalFiles,
            bytesPerSecond = if (expanding) 0.0 else speed.update(p.bytesDone, nowMs), currentFile = p.currentFile,
        )
        _snapshot.value = next
        return next
    }

    @Synchronized
    fun clear() {
        speed.reset()
        _snapshot.value = null
    }
}

/** Exponential moving average of bytes/second over roughly a 3 s window. */
class SpeedMeter(private val windowMs: Double = 3_000.0) {
    private var lastBytes = -1L
    private var lastAt = 0L
    private var ema = 0.0

    fun update(totalBytes: Long, nowMs: Long): Double {
        if (lastBytes < 0 || totalBytes < lastBytes) {
            lastBytes = totalBytes; lastAt = nowMs; return ema
        }
        val dt = nowMs - lastAt
        if (dt < 200) return ema // per-chunk calls: sample at most 5×/s
        val instant = (totalBytes - lastBytes) * 1000.0 / dt
        val alpha = 1 - kotlin.math.exp(-dt / windowMs)
        ema = if (ema == 0.0) instant else ema + alpha * (instant - ema)
        lastBytes = totalBytes; lastAt = nowMs
        return ema
    }

    fun reset() { lastBytes = -1L; lastAt = 0L; ema = 0.0 }
}
