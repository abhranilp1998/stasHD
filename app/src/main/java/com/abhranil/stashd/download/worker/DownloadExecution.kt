package com.abhranil.stashd.download.worker

import android.app.Notification
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.TaskState
import com.abhranil.stashd.download.core.BatchRunner
import com.abhranil.stashd.download.core.RunRegistry
import com.abhranil.stashd.download.core.RunResult
import com.abhranil.stashd.download.progress.ProgressBus
import com.abhranil.stashd.download.progress.ProgressSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What both schedulers run: [BatchRunner] + notification + live progress. The scheduler supplies how to
 * show the first (foreground) notification: JobService.setNotification for UIDT, setForeground for WorkManager.
 */
@Singleton
class DownloadExecution @Inject constructor(
    private val runner: BatchRunner,
    private val registry: RunRegistry,
    private val bus: ProgressBus,
    private val notifier: DownloadNotifier,
    private val dao: DownloadDao,
) {
    suspend fun execute(batchId: Long, showForeground: suspend (id: Int, n: Notification) -> Unit): RunResult {
        val batch = dao.batchOnce(batchId) ?: return RunResult.NotRunnable
        val title = batch.rootName
        notifier.clearResult()
        showForeground(DownloadNotifier.NOTIFICATION_ID, notifier.progress(ProgressSnapshot(batchId = batchId, expanding = true), title))
        var lastPost = 0L
        try {
            val result = registry.run(batchId) {
                runner.run(batchId) { p ->
                    val s = bus.publish(p)
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= 1_000) { lastPost = now; notifier.update(s, title) }
                }
            }
            val after = dao.batchOnce(batchId)
            when (result) {
                is RunResult.Paused -> notifier.paused(batchId, title, result.reason, after?.message)
                is RunResult.Finished -> {
                    val tasks = dao.tasks(batchId)
                    notifier.finished(title, result.state, tasks.count { it.state != TaskState.SKIPPED }, tasks.count { it.state == TaskState.FAILED })
                }
                else -> Unit
            }
            return result
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val after = dao.batchOnce(batchId)
                if (after?.state == BatchState.PAUSED) notifier.paused(batchId, title, after.pauseReason, after.message)
            }
            throw e
        } finally {
            bus.clear()
        }
    }
}
