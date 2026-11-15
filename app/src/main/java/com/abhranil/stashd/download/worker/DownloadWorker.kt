package com.abhranil.stashd.download.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.abhranil.stashd.download.core.RunResult
import com.abhranil.stashd.download.progress.ProgressSnapshot
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager fallback (Android < 14, or when UIDT is refused): one long-running worker per batch
 * (unique work "batch-{id}") in SystemForegroundService with type dataSync. Same [DownloadExecution] as UIDT.
 * A system stop cancels the coroutine; the runner re-queues and WorkManager re-runs the work.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val execution: DownloadExecution,
    private val notifier: DownloadNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getLong(KEY_BATCH_ID, -1L)
        if (batchId < 0) return Result.failure()
        return when (execution.execute(batchId) { id, n -> setForeground(info(id, n)) }) {
            RunResult.RetryLater -> Result.retry()
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        info(DownloadNotifier.NOTIFICATION_ID, notifier.progress(ProgressSnapshot(batchId = inputData.getLong(KEY_BATCH_ID, 0L), expanding = true), "download"))

    private fun info(id: Int, n: Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, n)

    companion object {
        const val KEY_BATCH_ID = "batchId"
        fun uniqueName(batchId: Long) = "batch-$batchId"
    }
}
