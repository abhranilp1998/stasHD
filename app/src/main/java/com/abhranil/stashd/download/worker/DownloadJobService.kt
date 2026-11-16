package com.abhranil.stashd.download.worker

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.abhranil.stashd.di.ApplicationScope
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.core.RunResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Android 14+ main path: user-initiated data transfer job. Must post its notification (setNotification)
 * right away; we do it before any network I/O. onStopJob:
 *  - STOP_REASON_USER, if delivered → treated as Pause (state kept, no reschedule);
 *  - anything else (network lost, quota, system pressure) → cancel the run and ask to be rescheduled;
 *    the runner puts the batch back to QUEUED and resumes from bytesDone next time.
 * Task Manager Stop kills the process without this callback. Startup reconciliation makes an orphaned
 * batch resumable without automatically restarting a download the user stopped.
 */
// Only ever scheduled by UidtScheduler on API 34+; the SDK guards below are for lint and safety.
@AndroidEntryPoint
class DownloadJobService : JobService() {
    @Inject lateinit var execution: DownloadExecution
    @Inject lateinit var controller: DownloadController
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = ConcurrentHashMap<Int, Job>()
    private val systemStopped = ConcurrentHashMap.newKeySet<Int>()

    override fun onStartJob(params: JobParameters): Boolean {
        val batchId = params.extras.getLong(EXTRA_BATCH_ID, -1L)
        if (batchId < 0) return false
        runs[params.jobId] = scope.launch {
            try {
                val result = execution.execute(batchId) { id, n ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) postJobNotification(params, id, n)
                }
                jobFinished(params, result == RunResult.RetryLater)
            } catch (e: CancellationException) {
                // Our own pause/cancel stopped the run → finish without reschedule. System stop → onStopJob answered.
                if (!systemStopped.remove(params.jobId)) jobFinished(params, false)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "batch $batchId crashed: ${e.javaClass.simpleName}")
                jobFinished(params, false)
            } finally {
                runs.remove(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val batchId = params.extras.getLong(EXTRA_BATCH_ID, -1L)
        val byUser = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.stopReason == JobParameters.STOP_REASON_USER
        Log.i(TAG, "stop job for batch $batchId (user=$byUser)")
        systemStopped.add(params.jobId)
        if (byUser) {
            appScope.launch { controller.pause(batchId) }
            return false
        }
        runs[params.jobId]?.cancel()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun postJobNotification(params: JobParameters, id: Int, n: android.app.Notification) =
        setNotification(params, id, n, JOB_END_NOTIFICATION_POLICY_REMOVE)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BATCH_ID = "batchId"
        private const val TAG = "Stashd/UIDT"
    }
}
