package com.abhranil.stashd.download.progress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Live snapshot the UI and notification read. Room gets the same numbers about once a second. */
data class ProgressSnapshot(
    val batchId: Long = 0,
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

/**
 * Hot path: [tick] is called per 256 KB chunk from download coroutines. It only touches an AtomicLong.
 * [sample] is called on a timer (100 ms for UI, 1 s for Room + notification) and builds a snapshot.
 */
@Singleton
class ProgressBus @Inject constructor() {
    private val perTask = ConcurrentHashMap<Long, AtomicLong>()
    private val speed = SpeedMeter()
    private val _snapshot = MutableStateFlow(ProgressSnapshot())
    val snapshot: StateFlow<ProgressSnapshot> = _snapshot.asStateFlow()

    fun tick(taskId: Long, bytesDone: Long) {
        perTask.getOrPut(taskId) { AtomicLong() }.set(bytesDone)
    }

    fun taskBytes(): Map<Long, Long> = perTask.mapValues { it.value.get() }

    fun sample(base: ProgressSnapshot, nowMs: Long = System.currentTimeMillis()): ProgressSnapshot {
        val bytes = base.bytesDone
        val next = base.copy(bytesPerSecond = speed.update(bytes, nowMs))
        _snapshot.value = next
        return next
    }

    fun reset() {
        perTask.clear()
        speed.reset()
        _snapshot.value = ProgressSnapshot()
    }
}

/** Exponential moving average of bytes/second over roughly a 3 s window. */
class SpeedMeter(private val windowMs: Double = 3_000.0) {
    private var lastBytes = -1L
    private var lastAt = 0L
    private var ema = 0.0

    fun update(totalBytes: Long, nowMs: Long): Double {
        if (lastBytes < 0) {
            lastBytes = totalBytes; lastAt = nowMs; return 0.0
        }
        val dt = (nowMs - lastAt).coerceAtLeast(1)
        val instant = (totalBytes - lastBytes) * 1000.0 / dt
        val alpha = 1 - kotlin.math.exp(-dt / windowMs)
        ema = if (ema == 0.0) instant else ema + alpha * (instant - ema)
        lastBytes = totalBytes; lastAt = nowMs
        return ema
    }

    fun reset() { lastBytes = -1L; lastAt = 0L; ema = 0.0 }
}
