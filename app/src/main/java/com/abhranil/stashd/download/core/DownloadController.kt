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

    private suspend fun deletePartials(b: DownloadBatchEntity) {
        val dest = runCatching { destinations.open(b.destTreeUri) }.getOrNull() ?: return
        for (t in dao.tasksWithPartials(b.id)) {
            runCatching { dest.dir(t.dirSegments).delete(t.tempName!!) }
                .onFailure { Log.w(TAG, "couldn't delete partial for task ${t.id}: ${it.javaClass.simpleName}") }
        }
    }

    companion object { private const val TAG = "Stashd/Ctl" }
}
