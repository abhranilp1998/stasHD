package com.abhranil.stashd.download.core

import android.util.Log
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.DownloadItemEntity
import com.abhranil.stashd.data.db.FailureReason
import com.abhranil.stashd.data.db.FileTaskEntity
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.data.db.TaskState
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.ShortcutTarget
import com.abhranil.stashd.download.storage.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Opens the destination a batch writes to (SAF tree in the app, a plain directory in tests). */
fun interface DestinationFactory {
    fun open(treeUri: String): Destination
}

/** Live progress for the notification / Downloads screen. */
data class BatchProgress(
    val batchId: Long,
    val phase: Phase,
    val bytesDone: Long,
    val totalBytes: Long,
    val filesDone: Int,
    val totalFiles: Int,
    val currentFile: String?,
    val foldersScanned: Int = 0,
) {
    enum class Phase { EXPANDING, DOWNLOADING }
}

/** How a run ended; the scheduler adapters map this to jobFinished / Result. */
sealed interface RunResult {
    data class Finished(val state: BatchState) : RunResult
    data class Paused(val reason: PauseReason) : RunResult
    /** Network trouble: keep the batch QUEUED and let the scheduler retry when connectivity returns. */
    data object RetryLater : RunResult
    /** Nothing to do (unknown batch, already finished, user-paused). */
    data object NotRunnable : RunResult
}

/**
 * Runs one batch to completion, pause or "retry later". Scheduler-agnostic: the UIDT JobService and the
 * WorkManager worker both call [run]; only [run] touches the DB for a batch while it is active.
 *
 * Phases: EXPANDING (picks -> file tasks, atomically saved) then sequential file tasks. Each task is resumable
 * via its temp name + bytesDone; bytesDone is persisted about once a second and on stop.
 * Stopping is coroutine cancellation: the caller sets PAUSED/CANCELLED first (user), or nothing (system stop:
 * the batch goes back to QUEUED for the reschedule).
 */
class BatchRunner(
    private val dao: DownloadDao,
    private val drive: DriveRepository,
    private val destinations: DestinationFactory,
    private val downloader: FileDownloader,
    private val policy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val persistEveryMs: Long = 1_000,
) {
    suspend fun run(batchId: Long, onProgress: (BatchProgress) -> Unit = {}): RunResult {
        val batch = dao.batchOnce(batchId) ?: return RunResult.NotRunnable
        if (!batch.state.isActive || batch.state == BatchState.PAUSED) return RunResult.NotRunnable
        try {
            // EXPANDING, or resumed before the plan was ever saved (pause/kill during expansion).
            if (batch.state == BatchState.EXPANDING || dao.tasks(batchId).isEmpty()) {
                expand(batch, onProgress)?.let { return it }
            }
            dao.requeueRunning(batchId) // a previous run was killed mid-file
            dao.setBatchState(batchId, BatchState.RUNNING)
            dao.markStarted(batchId, clock())
            return downloadAll(dao.batchOnce(batchId)!!, onProgress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.requeueRunning(batchId)
                // User pause/cancel already wrote PAUSED/CANCELLED; a system stop leaves RUNNING -> QUEUED
                // (EXPANDING stays EXPANDING: the plan is saved atomically, so expansion simply reruns).
                if (dao.batchOnce(batchId)?.state == BatchState.RUNNING) dao.setBatchState(batchId, BatchState.QUEUED)
            }
            throw e
        }
    }

    // ---- Expansion -------------------------------------------------------------------------------------------

    private suspend fun expand(batch: DownloadBatchEntity, onProgress: (BatchProgress) -> Unit): RunResult? {
        val picks = dao.items(batch.id).map { it.toPick() }
        var failures = 0
        while (true) {
            try {
                val result = SelectionExpander(drive).expand(picks, batch.keepStructure) { folders, files ->
                    onProgress(BatchProgress(batch.id, BatchProgress.Phase.EXPANDING, 0, 0, 0, files, null, folders))
                }
                val tasks = result.files.map { f ->
                    FileTaskEntity(
                        batchId = batch.id, sourceKey = f.sourceKey, fileId = f.fileId, resourceKey = f.resourceKey,
                        dirSegments = f.dirSegments, plannedName = f.name, size = f.expected.size, md5 = f.expected.md5,
                        modifiedTime = f.expected.modifiedTime, viaShortcut = f.viaShortcut,
                    )
                } + result.skipped.map { s ->
                    FileTaskEntity(
                        batchId = batch.id, sourceKey = s.sourceKey, fileId = s.fileId, resourceKey = null,
                        dirSegments = s.dirSegments, plannedName = s.name, size = null, md5 = null, modifiedTime = 0,
                        state = TaskState.SKIPPED,
                        errorReason = if (s.reason == SkipReason.GOOGLE_DOC) FailureReason.GOOGLE_DOC else FailureReason.CANNOT_DOWNLOAD,
                        errorMessage = if (s.reason == SkipReason.GOOGLE_DOC) "Google Docs/Sheets/Slides need export (not supported yet)" else "The owner disabled downloads",
                        finishedAt = clock(),
                    )
                }
                dao.savePlan(batch.id, tasks, result.totalBytes, result.files.size, result.skipped.size, result.duplicatesRemoved)
                Log.i(TAG, "batch ${batch.id}: ${result.files.size} files, ${result.skipped.size} skipped, ${result.duplicatesRemoved} dups, ${result.foldersScanned} folders")
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExpansionIncomplete) {
                if (++failures >= policy.maxAttempts) return failBatch(batch.id, e.message)
                sleep(policy.delayMs(failures))
            } catch (e: Exception) {
                when (val d = policy.classify(e)) {
                    is FailureDecision.PauseBatch -> return pause(batch.id, d.reason, e.message)
                    is FailureDecision.Terminal -> return failBatch(batch.id, e.message)
                    is FailureDecision.Retry -> {
                        if (++failures >= policy.maxAttempts) {
                            return if (d.kind == FailureDecision.Kind.NETWORK) RunResult.RetryLater else failBatch(batch.id, e.message)
                        }
                        sleep(policy.delayMs(failures, d.retryAfterMs))
                    }
                }
            }
        }
    }

    // ---- Files -----------------------------------------------------------------------------------------------

    private suspend fun downloadAll(batch: DownloadBatchEntity, onProgress: (BatchProgress) -> Unit): RunResult {
        val dest = destinations.open(batch.destTreeUri)
        val all = dao.tasks(batch.id)
        val counted = all.filter { it.state != TaskState.SKIPPED }
        val totalBytes = counted.sumOf { it.size ?: 0L }
        var doneBytes = counted.filter { it.state == TaskState.DONE }.sumOf { it.size ?: it.bytesDone }
        var filesDone = counted.count { it.state == TaskState.DONE }
        var networkStreak = 0

        fun report(current: String?, currentBytes: Long) = onProgress(
            BatchProgress(batch.id, BatchProgress.Phase.DOWNLOADING, doneBytes + currentBytes, totalBytes, filesDone, counted.size, current),
        )

        for (start in dao.runnableTasks(batch.id)) {
            var task = start
            dao.markRunning(task.id, clock())
            while (true) {
                val listener = Listener(task.id) { b -> report(task.finalName ?: task.plannedName, b) }
                try {
                    val outcome = downloader.download(task.toJob(batch.skipExisting), dest, listener)
                    networkStreak = 0
                    when (outcome) {
                        is FileOutcome.Done -> {
                            dao.markDone(task.id, outcome.finalName, outcome.uri, outcome.bytes, clock())
                            doneBytes += outcome.bytes; filesDone++
                        }
                        is FileOutcome.SkippedExisting -> {
                            dao.markSkipped(task.id, FailureReason.EXISTS, "Already in the folder", clock())
                            doneBytes += task.size ?: 0L; filesDone++
                        }
                    }
                    report(null, 0)
                    break
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { dao.setBytes(task.id, listener.last) }
                    throw e
                } catch (e: Exception) {
                    dao.setBytes(task.id, listener.last)
                    task = dao.task(task.id) ?: break
                    val msg = e.message
                    when (val d = policy.classify(e)) {
                        is FailureDecision.Terminal -> {
                            dao.markFailed(task.id, d.reason, msg, clock()); break
                        }
                        is FailureDecision.PauseBatch -> {
                            dao.requeue(task.id)
                            return pause(batch.id, d.reason, msg)
                        }
                        is FailureDecision.Retry -> {
                            dao.recordAttempt(task.id, msg)
                            val attempts = task.attempts + 1
                            when (d.kind) {
                                FailureDecision.Kind.CHECKSUM -> if (attempts >= policy.maxChecksumAttempts) {
                                    dao.markFailed(task.id, FailureReason.CHECKSUM, msg, clock()); break
                                }
                                FailureDecision.Kind.SERVER -> if (attempts >= policy.maxAttempts) {
                                    dao.markFailed(task.id, FailureReason.RETRIES_EXHAUSTED, msg, clock()); break
                                }
                                FailureDecision.Kind.NETWORK -> {
                                    if (attempts >= policy.maxNetworkAttemptsTotal) {
                                        dao.markFailed(task.id, FailureReason.RETRIES_EXHAUSTED, msg, clock()); break
                                    }
                                    if (++networkStreak >= policy.maxAttempts) {
                                        dao.requeue(task.id)
                                        dao.setBatchState(batch.id, BatchState.QUEUED, null, "Waiting for network")
                                        return RunResult.RetryLater
                                    }
                                }
                            }
                            Log.w(TAG, "task ${task.id} attempt $attempts failed (${e.javaClass.simpleName}); retrying")
                            sleep(policy.delayMs(attempts, d.retryAfterMs))
                            task = dao.task(task.id) ?: break
                        }
                    }
                }
            }
        }

        val tasks = dao.tasks(batch.id)
        val state = if (tasks.any { it.state == TaskState.FAILED }) BatchState.COMPLETED_WITH_ERRORS else BatchState.COMPLETED
        dao.finishBatch(batch.id, state, null, clock())
        return RunResult.Finished(state)
    }

    private inner class Listener(private val taskId: Long, private val onBytes: (Long) -> Unit) : FileJobListener {
        @Volatile var last = 0L
        private var persistedAt = 0L
        override suspend fun namesClaimed(finalName: String, tempName: String) = dao.claimNames(taskId, finalName, tempName)
        override suspend fun remoteChanged(now: RemoteFingerprint) = dao.remoteChanged(taskId, now.size, now.md5, now.modifiedTime)
        override suspend fun progress(bytesDone: Long) {
            last = bytesDone
            onBytes(bytesDone)
            val t = clock()
            if (t - persistedAt >= persistEveryMs) { // resume offset survives a process kill (±1 s of bytes)
                persistedAt = t
                dao.setBytes(taskId, bytesDone)
            }
        }
    }

    private suspend fun pause(batchId: Long, reason: PauseReason, message: String?): RunResult {
        dao.setBatchState(batchId, BatchState.PAUSED, reason, message)
        Log.w(TAG, "batch $batchId paused: $reason")
        return RunResult.Paused(reason)
    }

    private suspend fun failBatch(batchId: Long, message: String?): RunResult {
        dao.finishBatch(batchId, BatchState.FAILED, message, clock())
        return RunResult.Finished(BatchState.FAILED)
    }

    companion object { private const val TAG = "Stashd/Batch" }
}

fun FileTaskEntity.toJob(skipExisting: Boolean) = FileJob(
    taskId = id, fileId = fileId, resourceKey = resourceKey, dirSegments = dirSegments, plannedName = plannedName,
    expected = RemoteFingerprint(size, md5, modifiedTime), finalName = finalName, tempName = tempName, skipExisting = skipExisting,
)

fun DownloadItemEntity.toPick() = PickedItem(
    DriveItem(
        id = nodeId, name = name, mimeType = mimeType, size = size, md5 = md5, resourceKey = resourceKey,
        modifiedTime = modifiedTime, canDownload = canDownload,
        shortcut = targetId?.let { ShortcutTarget(it, targetMimeType, targetResourceKey) },
    ),
    parentNames,
)

fun PickedItem.toEntity(order: Int) = DownloadItemEntity(
    batchId = 0, nodeId = item.id, name = item.name, mimeType = item.mimeType, resourceKey = item.resourceKey,
    targetId = item.shortcut?.id, targetMimeType = item.shortcut?.mimeType, targetResourceKey = item.shortcut?.resourceKey,
    size = item.size, md5 = item.md5, modifiedTime = item.modifiedTime, canDownload = item.canDownload,
    parentNames = parentNames, sortOrder = order,
)

