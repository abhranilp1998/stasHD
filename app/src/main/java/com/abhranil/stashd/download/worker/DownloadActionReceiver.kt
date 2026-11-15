package com.abhranil.stashd.download.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/** Notification "Pause". M0 cancels the unique work; M3 also persists bytesDone and marks tasks PAUSED. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val batchId = intent.getLongExtra(EXTRA_BATCH_ID, -1L)
        if (batchId >= 0) WorkManager.getInstance(context).cancelUniqueWork(DownloadWorker.uniqueName(batchId))
    }

    companion object {
        const val ACTION_PAUSE = "com.abhranil.stashd.action.PAUSE"
        const val EXTRA_BATCH_ID = "batchId"
    }
}
