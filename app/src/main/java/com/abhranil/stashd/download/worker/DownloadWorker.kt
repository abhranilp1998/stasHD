package com.abhranil.stashd.download.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.download.progress.ProgressBus
import com.abhranil.stashd.download.progress.ProgressSnapshot
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * One long-running worker per batch (unique work "batch-{id}"), running in WorkManager's
 * SystemForegroundService with type dataSync.
 *
 * M0: [KEY_DEMO] = true simulates bytes so the foreground service, notification and setProgress
 * pipeline can be tested on a device before OAuth exists. The real per-file loop (Range resume via
 * [DriveRepository.openDownload], SAF writes, md5 verify, backoff) is M3.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notifier: DownloadNotifier,
    private val progressBus: ProgressBus,
    @Suppress("unused") private val downloadDao: DownloadDao,
    @Suppress("unused") private val drive: DriveRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getLong(KEY_BATCH_ID, -1L)
        val demo = inputData.getBoolean(KEY_DEMO, false)
        if (batchId < 0) return Result.failure()

        progressBus.reset()
        return if (demo) runDemo(batchId) else runBatch(batchId)
    }

    private suspend fun runDemo(batchId: Long): Result {
        val files = listOf(
            "01 - Welcome To New York (Taylor's Version).flac" to 28_400_000L,
            "08 - Bad Blood (Taylor's Version).flac" to 30_300_000L,
            "09 - Wildest Dreams (Taylor's Version).flac" to 33_100_000L,
            "muzik.rar (demo slice)" to 120_000_000L,
        )
        val total = files.sumOf { it.second }
        var done = 0L
        var snapshot = ProgressSnapshot(batchId = batchId, totalBytes = total, totalFiles = files.size)
        setForeground(foregroundInfo(snapshot, files.size, batchId))

        var lastUi = 0L
        files.forEachIndexed { index, (name, size) ->
            var fileDone = 0L
            while (fileDone < size) {
                val chunk = minOf(256 * 1024L * 8, size - fileDone) // simulate ~18 MB/s at 100 ms ticks
                fileDone += chunk; done += chunk
                progressBus.tick(taskId = index.toLong(), bytesDone = fileDone)
                delay(100)
                val now = System.currentTimeMillis()
                if (now - lastUi >= 1_000) {
                    lastUi = now
                    snapshot = progressBus.sample(snapshot.copy(bytesDone = done, filesDone = index, currentFile = name), now)
                    reportProgress(snapshot)
                    setForeground(foregroundInfo(snapshot, files.size, batchId))
                }
            }
        }
        snapshot = snapshot.copy(bytesDone = total, filesDone = files.size, currentFile = null)
        reportProgress(snapshot)
        return Result.success(workDataOf(KEY_BYTES_DONE to total, KEY_TOTAL_BYTES to total))
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun runBatch(@Suppress("UNUSED_PARAMETER") batchId: Long): Result {
        // TODO(M3): load QUEUED/PAUSED file_task rows, Semaphore(parallelism), FileDownloader per task,
        //  flush progressBus.taskBytes() to downloadDao.flushBytes() every second, handle stopReason.
        return Result.failure(workDataOf(KEY_ERROR to "Real downloads are not implemented in M0"))
    }

    private suspend fun reportProgress(s: ProgressSnapshot) {
        setProgress(
            workDataOf(
                KEY_BYTES_DONE to s.bytesDone,
                KEY_TOTAL_BYTES to s.totalBytes,
                KEY_FILES_DONE to s.filesDone,
                KEY_TOTAL_FILES to s.totalFiles,
                KEY_SPEED to s.bytesPerSecond,
                KEY_CURRENT_FILE to s.currentFile,
            )
        )
    }

    private fun foregroundInfo(s: ProgressSnapshot, items: Int, batchId: Long): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = notifier.progress(s, items, cancel, batchId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(DownloadNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(DownloadNotifier.NOTIFICATION_ID, notification)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(ProgressSnapshot(), 0, inputData.getLong(KEY_BATCH_ID, 0L))

    companion object {
        const val KEY_BATCH_ID = "batchId"
        const val KEY_DEMO = "demo"
        const val KEY_BYTES_DONE = "bytesDone"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_FILES_DONE = "filesDone"
        const val KEY_TOTAL_FILES = "totalFiles"
        const val KEY_SPEED = "bytesPerSecond"
        const val KEY_CURRENT_FILE = "currentFile"
        const val KEY_ERROR = "error"

        fun uniqueName(batchId: Long) = "batch-$batchId"
    }
}
