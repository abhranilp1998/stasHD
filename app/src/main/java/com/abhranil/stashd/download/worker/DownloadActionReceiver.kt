package com.abhranil.stashd.download.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.abhranil.stashd.di.ApplicationScope
import com.abhranil.stashd.download.core.DownloadController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Notification actions. All go through [DownloadController] so pause/cancel behave like in the app. */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: DownloadController
    @Inject lateinit var notifier: DownloadNotifier
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val batchId = intent.getLongExtra(EXTRA_BATCH_ID, -1L)
        if (batchId < 0) return
        val pending = goAsync()
        appScope.launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> controller.pause(batchId)
                    ACTION_RESUME -> { notifier.clearResult(); controller.resume(batchId) }
                    ACTION_CANCEL -> { controller.cancel(batchId); notifier.clearResult() }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.abhranil.stashd.action.PAUSE"
        const val ACTION_RESUME = "com.abhranil.stashd.action.RESUME"
        const val ACTION_CANCEL = "com.abhranil.stashd.action.CANCEL"
        const val EXTRA_BATCH_ID = "batchId"
    }
}
