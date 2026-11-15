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
import com.abhranil.stashd.download.progress.ProgressSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_downloads_desc) }
        nm.createNotificationChannel(channel)
    }

    /** Screen 6: title with %, current file, determinate bar, bytes · speed · ETA, Pause / Cancel. */
    fun progress(snapshot: ProgressSnapshot, itemCount: Int, cancelIntent: PendingIntent, batchId: Long): Notification {
        val pct = (snapshot.fraction * 100).toInt()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getBroadcast(
            context, batchId.toInt(),
            Intent(context, DownloadActionReceiver::class.java)
                .setAction(DownloadActionReceiver.ACTION_PAUSE)
                .putExtra(DownloadActionReceiver.EXTRA_BATCH_ID, batchId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detail = buildString {
            append(formatBytes(snapshot.bytesDone)).append(" / ").append(formatBytes(snapshot.totalBytes))
            if (snapshot.bytesPerSecond > 0) append(" · ").append(formatBytes(snapshot.bytesPerSecond.toLong())).append("/s")
            snapshot.etaSeconds?.let { append(" · ").append(formatEta(it)).append(" left") }
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading $itemCount items · $pct%")
            .setContentText(snapshot.currentFile ?: detail)
            .setSubText(detail)
            .setProgress(100, pct, snapshot.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_pause), pause)
            .addAction(0, context.getString(R.string.action_cancel), cancelIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 4101

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
