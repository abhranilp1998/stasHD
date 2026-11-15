package com.abhranil.stashd.download.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.abhranil.stashd.MainActivity
import com.abhranil.stashd.R
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.download.progress.ProgressSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_downloads_desc) }
        nm.createNotificationChannel(channel)
    }

    /** Screen 6: title with %, current file, determinate bar, bytes · speed · ETA, Pause / Cancel. */
    fun progress(s: ProgressSnapshot, title: String): Notification {
        val pct = (s.fraction * 100).toInt()
        val (headline, detail) = if (s.expanding) {
            "Preparing $title" to "Scanning folders · ${s.foldersScanned} scanned · ${s.totalFiles} files found"
        } else {
            "Downloading $title · $pct%" to buildString {
                append(formatBytes(s.bytesDone)).append(" / ").append(formatBytes(s.totalBytes))
                if (s.bytesPerSecond > 0) append(" · ").append(formatBytes(s.bytesPerSecond.toLong())).append("/s")
                s.etaSeconds?.let { append(" · ").append(formatEta(it)).append(" left") }
            }
        }
        return builder()
            .setContentTitle(headline)
            .setContentText(s.currentFile ?: detail)
            .setSubText(if (s.expanding) null else "${s.filesDone}/${s.totalFiles} files")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(s.currentFile, detail).joinToString("\n")))
            .setProgress(100, pct, s.expanding || s.totalBytes <= 0)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_pause), action(DownloadActionReceiver.ACTION_PAUSE, s.batchId))
            .addAction(0, context.getString(R.string.action_cancel), action(DownloadActionReceiver.ACTION_CANCEL, s.batchId))
            .build()
    }

    fun update(s: ProgressSnapshot, title: String) = post(NOTIFICATION_ID, progress(s, title))

    /** Separate id so it survives the job's own notification being removed. */
    fun paused(batchId: Long, title: String, reason: PauseReason?, message: String?) {
        val why = when (reason) {
            PauseReason.USER, null -> "Paused"
            PauseReason.STORAGE_FULL -> "Paused: phone storage is full"
            PauseReason.DESTINATION_REVOKED -> "Paused: can't write to the download folder"
            PauseReason.NEEDS_CONSENT, PauseReason.AUTH -> "Paused: Google needs you to sign in again"
            PauseReason.SYSTEM -> "Paused by the system"
        }
        post(
            RESULT_ID,
            builder().setContentTitle(title).setContentText(why).setSubText(message?.take(80))
                .addAction(0, context.getString(R.string.action_resume), action(DownloadActionReceiver.ACTION_RESUME, batchId))
                .addAction(0, context.getString(R.string.action_cancel), action(DownloadActionReceiver.ACTION_CANCEL, batchId))
                .build(),
        )
    }

    fun finished(title: String, state: BatchState, files: Int, failed: Int) {
        val text = when (state) {
            BatchState.COMPLETED -> "Done · $files files"
            BatchState.COMPLETED_WITH_ERRORS -> "Done · $failed of $files files failed"
            BatchState.FAILED -> "Couldn't start the download"
            else -> state.name.lowercase()
        }
        post(RESULT_ID, builder().setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }

    fun clearResult() = nm.cancel(RESULT_ID)

    private fun builder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    private fun action(action: String, batchId: Long) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, DownloadActionReceiver::class.java).setAction(action).putExtra(DownloadActionReceiver.EXTRA_BATCH_ID, batchId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(id: Int, n: Notification) {
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 4101
        const val RESULT_ID = 4102

        fun formatBytes(b: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = b.toDouble(); var i = 0
            while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
            return if (i == 0) "$b B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.2f %s", v, units[i])
        }

        fun formatEta(s: Long): String = when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "$s s"
        }
    }
}
