package com.abhranil.stashd.download.worker

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.download.core.DownloadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SchedulerSelector {
    enum class Kind { UIDT, WORK_MANAGER }
    /** User-initiated data transfer jobs exist from Android 14 (API 34). */
    fun preferred(sdkInt: Int): Kind = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Kind.UIDT else Kind.WORK_MANAGER
}

/**
 * Main path on Android 14+: a user-initiated data transfer job (no 6 h/10 min caps, survives the app being
 * swiped away, shows in Task Manager). Falls back to WorkManager if the system refuses the job (e.g. the
 * app wasn't in an allowed state when scheduling).
 */
@Singleton
class SelectingScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManagerScheduler,
) : DownloadScheduler {
    override suspend fun schedule(batch: DownloadBatchEntity) {
        if (SchedulerSelector.preferred(Build.VERSION.SDK_INT) == SchedulerSelector.Kind.UIDT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            if (UidtScheduler(context).schedule(batch)) return
            Log.w(TAG, "UIDT refused for batch ${batch.id}; using WorkManager")
        }
        workManager.schedule(batch)
    }

    override fun unschedule(batchId: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) UidtScheduler(context).cancel(batchId)
        workManager.unschedule(batchId)
    }

    override suspend fun isScheduled(batchId: Long): Boolean {
        val uidtPending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.getSystemService(JobScheduler::class.java).getPendingJob(UidtScheduler.jobId(batchId)) != null
        return uidtPending || workManager.isScheduled(batchId)
    }

    companion object { private const val TAG = "Stashd/Sched" }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UidtScheduler(private val context: Context) {
    private val js get() = context.getSystemService(JobScheduler::class.java)

    fun schedule(batch: DownloadBatchEntity): Boolean {
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { if (batch.wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()
        val info = JobInfo.Builder(jobId(batch.id), ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            .setEstimatedNetworkBytes(if (batch.totalBytes > 0) batch.totalBytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setExtras(PersistableBundle().apply { putLong(DownloadJobService.EXTRA_BATCH_ID, batch.id) })
            .build()
        return try {
            js.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (e: RuntimeException) { // SecurityException / IllegalStateException when not allowed right now
            Log.w("Stashd/Sched", "UIDT schedule failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun cancel(batchId: Long) = js.cancel(jobId(batchId))

    companion object { fun jobId(batchId: Long) = 7_000 + (batchId % 1_000).toInt() }
}

/** Fallback (Android 8–13): a long-running worker in WorkManager's dataSync foreground service. */
@Singleton
class WorkManagerScheduler @Inject constructor(private val workManager: WorkManager) {
    suspend fun isScheduled(batchId: Long): Boolean = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(DownloadWorker.uniqueName(batchId)).get().any { !it.state.isFinished }
    }
    fun schedule(batch: DownloadBatchEntity) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_BATCH_ID to batch.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (batch.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // REPLACE: schedule is only called on create/resume/retry, when no run should be alive.
        workManager.enqueueUniqueWork(DownloadWorker.uniqueName(batch.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun unschedule(batchId: Long) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueName(batchId))
    }

    companion object { const val TAG = "stashd-download" }
}
