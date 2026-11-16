package com.abhranil.stashd.download.worker

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.abhranil.stashd.download.core.DownloadController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs before either scheduler starts copying bytes, including when a retained job restarts the process. */
@Singleton
class DownloadStartupRecovery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val controller: Provider<DownloadController>,
) {
    private val mutex = Mutex()
    private var recovered = false

    // Persist the acknowledgement before allowing transfers; this blocking write runs on Dispatchers.IO.
    @SuppressLint("ApplySharedPref")
    suspend fun recover() = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (recovered) return@withLock
            val preferences = context.getSharedPreferences("download_recovery", Context.MODE_PRIVATE)
            val lastHandled = preferences.getLong("handled_exit", 0)
            val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
            } else null
            val stoppedAt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                exit?.reason == ApplicationExitInfo.REASON_USER_REQUESTED && exit.timestamp > lastHandled
            ) exit.timestamp else null
            controller.get().reconcileInterrupted(stoppedAt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && exit != null) {
                preferences.edit().putLong("handled_exit", exit.timestamp).commit()
            }
            recovered = true
        }
    }
}
