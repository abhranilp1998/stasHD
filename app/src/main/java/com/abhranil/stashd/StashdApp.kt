package com.abhranil.stashd

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.abhranil.stashd.download.worker.DownloadNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StashdApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: DownloadNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // WorkManager's own JobScheduler ids; UIDT download jobs use 7000-7999 (UidtScheduler.jobId).
            .setJobSchedulerJobIdRange(1_000, 5_000)
            .build()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannel()
    }
}
