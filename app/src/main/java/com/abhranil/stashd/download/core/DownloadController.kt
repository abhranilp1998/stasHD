package com.abhranil.stashd.download.core

import android.util.Log
import com.abhranil.stashd.data.db.ActiveBatchExists
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.PauseReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Hands a batch to the OS. UIDT on Android 14+, WorkManager otherwise; both end up in [BatchRunner.run]. */
interface DownloadScheduler {
    suspend fun schedule(batch: DownloadBatchEntity)
    fun unschedule(batchId: Long)
    suspend fun isScheduled(batchId: Long): Boolean = false
}

/**
 * The in-process run of a batch, so pause/cancel can stop it *and wait* before touching its files.
 * The mutex also guarantees one runner at a time even if two schedulers fire (e.g. UIDT + fallback).
 */
class RunRegistry {
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val mutex = Mutex()

    suspend fun <T> run(batchId: Long, block: suspend () -> T): T = mutex.withLock {
        coroutineScope {
            val job = coroutineContext[Job]!!
            jobs[batchId] = job
            try { block() } finally { jobs.remove(batchId, job) }
        }
    }

    suspend fun stop(batchId: Long) { jobs[batchId]?.cancelAndJoin() }
    fun isRunning(batchId: Long) = jobs.containsKey(batchId)
}

data class NewBatch(
    val rootFolderId: String,
    val rootName: String,
    val picks: List<PickedItem>,
    val destTreeUri: String,
    val keepStructure: Boolean,
    val wifiOnly: Boolean,
    val skipExisting: Boolean,
)

/**
 * User intents on batches. Policy (also in SETUP.md):
 *  - ONE active batch (EXPANDING/QUEUED/RUNNING/PAUSED); creating a second throws [ActiveBatchExists].
 *  - Pause != cancel: pause stops the run and keeps `.stashdpart` files + bytesDone so resume continues
 *    with a Range request. Cancel deletes the partial files of unfinished tasks; files that completed stay.
 */
class DownloadController(
    private val dao: DownloadDao,
    private val scheduler: DownloadScheduler,
    private val registry: RunRegistry,
    private val destinations: DestinationFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A force-stop/Task Manager Stop can kill us without any callback. Never silently restart it. */
    suspend fun reconcileInterrupted(userStoppedAt: Long? = null) {
        val id = dao.activeBatchId() ?: return
        val batch = dao.batchOnce(id) ?: return
        if (batch.state == BatchState.PAUSED || registry.isRunning(id)) return
        val stoppedByUser = userStoppedAt != null && batch.createdAt <= userStoppedAt
        if (!stoppedByUser && scheduler.isScheduled(id)) return
        dao.requeueRunning(id)
        dao.pauseInterrupted(id)
        if (stoppedByUser) scheduler.unschedule(id)
    }

    suspend fun create(req: NewBatch): Long {
        require(req.picks.isNotEmpty()) { "Nothing selected" }
        val id = dao.createBatch(
            DownloadBatchEntity(
                rootFolderId = req.rootFolderId, rootName = req.rootName, destTreeUri = req.destTreeUri,
                keepStructure = req.keepStructure, wifiOnly = req.wifiOnly, skipExisting = req.skipExisting,
                state = BatchState.EXPANDING, createdAt = clock(),
            ),
            req.picks.mapIndexed { i, p -> p.toEntity(i) },
        )
        scheduler.schedule(dao.batchOnce(id)!!)
        return id
    }

    suspend fun pause(batchId: Long, reason: PauseReason = PauseReason.USER) {
        val b = dao.batchOnce(batchId) ?: return
        if (!b.state.isActive || b.state == BatchState.PAUSED) return
        dao.setBatchState(batchId, BatchState.PAUSED, reason, null)
        registry.stop(batchId)
        scheduler.unschedule(batchId)
    }

    suspend fun resume(batchId: Long) {
        val b = dao.batchOnce(batchId) ?: return
        if (b.state != BatchState.PAUSED) return
        dao.setBatchState(batchId, BatchState.QUEUED, null, null)
        scheduler.schedule(dao.batchOnce(batchId)!!)
    }

    /** Completed files keep their URIs; only unfinished files restart when the destination changes. */
    suspend fun restoreDestination(batchId: Long, treeUri: String) {
        val batch = dao.batchOnce(batchId) ?: return
        require(batch.state == BatchState.PAUSED) { "Pause the download before restoring folder access." }
        destinations.open(treeUri).checkAccess()
        if (batch.destTreeUri != treeUri) {
            registry.stop(batchId)
            deletePartials(batch)
            dao.replaceDestination(batchId, treeUri)
        }
    }

    suspend fun cancel(batchId: Long) {
        val b = dao.batchOnce(batchId) ?: return
        if (!b.state.isActive) return
        dao.finishBatch(batchId, BatchState.CANCELLED, null, clock())
        registry.stop(batchId)
        scheduler.unschedule(batchId)
        deletePartials(b)
        dao.cancelOpenTasks(batchId)
    }

    /** Re-queues FAILED files of a finished batch (keeps their temp files, so they resume). */
    suspend fun retryFailed(batchId: Long) {
        val b = dao.batchOnce(batchId) ?: return
        if (b.state != BatchState.COMPLETED_WITH_ERRORS) return
        dao.activeBatchId()?.let { throw ActiveBatchExists(it) }
        if (dao.requeueFailed(batchId) == 0) return
        dao.setBatchState(batchId, BatchState.QUEUED, null, null)
        scheduler.schedule(dao.batchOnce(batchId)!!)
    }

    /** History: retry ONE failed file of a finished batch (its partial is kept, so it resumes). */
    suspend fun retryFile(taskId: Long) {
        val t = dao.task(taskId) ?: return
        val b = dao.batchOnce(t.batchId) ?: return
        if (b.state != BatchState.COMPLETED_WITH_ERRORS) return
        dao.activeBatchId()?.let { throw ActiveBatchExists(it) }
        if (dao.requeueOneFailed(taskId) == 0) return
        dao.setBatchState(b.id, BatchState.QUEUED, null, null)
        scheduler.schedule(dao.batchOnce(b.id)!!)
    }

    /**
     * History: "Download again" for a cancelled or failed batch. Same picks, same destination, as a NEW batch
     * with skip-existing on, so files that already finished aren't downloaded twice. Returns the new batch id.
     */
    suspend fun restart(batchId: Long): Long? {
        val b = dao.batchOnce(batchId) ?: return null
        if (b.state != BatchState.CANCELLED && b.state != BatchState.FAILED) return null
        val items = dao.items(batchId)
        if (items.isEmpty()) return null
        val id = dao.createBatch(
            b.copy(id = 0, state = BatchState.EXPANDING, pauseReason = null, message = null, skipExisting = true,
                totalBytes = 0, totalFiles = 0, skippedFiles = 0, duplicatesRemoved = 0, createdAt = clock(), startedAt = null, finishedAt = null),
            items.map { it.copy(id = 0, batchId = 0) },
        )
        scheduler.schedule(dao.batchOnce(id)!!)
        return id
    }

    /** History: remove a finished batch's record (files on the phone are not touched). */
    suspend fun forget(batchId: Long) { dao.deleteFinishedBatch(batchId) }

    private suspend fun deletePartials(b: DownloadBatchEntity) {
        val dest = runCatching { destinations.open(b.destTreeUri) }.getOrNull() ?: return
        for (t in dao.tasksWithPartials(b.id)) {
            runCatching { dest.dir(t.dirSegments).delete(t.tempName!!) }
                .onFailure { Log.w(TAG, "couldn't delete partial for task ${t.id}: ${it.javaClass.simpleName}") }
        }
    }

    companion object { private const val TAG = "Stashd/Ctl" }
}
