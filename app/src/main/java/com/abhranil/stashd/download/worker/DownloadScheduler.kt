package com.abhranil.stashd.download.worker

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadScheduler @Inject constructor(private val workManager: WorkManager) {

    fun start(batchId: Long, wifiOnly: Boolean, demo: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_BATCH_ID to batchId, DownloadWorker.KEY_DEMO to demo))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(DownloadWorker.uniqueName(batchId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(batchId: Long) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueName(batchId))
    }

    fun observe(batchId: Long): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.uniqueName(batchId)).map { it.firstOrNull() }

    companion object { const val TAG = "stashd-download" }
}
