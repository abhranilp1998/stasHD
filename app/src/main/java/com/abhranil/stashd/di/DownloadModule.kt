package com.abhranil.stashd.di

import android.content.Context
import androidx.core.net.toUri
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.download.core.BatchRunner
import com.abhranil.stashd.download.core.DestinationFactory
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.core.DownloadScheduler
import com.abhranil.stashd.download.core.FileDownloader
import com.abhranil.stashd.download.core.RunRegistry
import com.abhranil.stashd.download.storage.SafDestination
import com.abhranil.stashd.download.worker.SelectingScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Lives as long as the process; for work that must outlive a receiver or service callback. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton fun runRegistry() = RunRegistry()

    /** The SAF tree the user picked. Works the same in demo mode (fake Drive bytes, real files). */
    @Provides @Singleton
    fun destinations(@ApplicationContext context: Context): DestinationFactory =
        DestinationFactory { SafDestination(context, it.toUri()) }

    @Provides @Singleton
    fun batchRunner(dao: DownloadDao, drive: DriveRepository, destinations: DestinationFactory): BatchRunner =
        BatchRunner(dao, drive, destinations, FileDownloader(drive))

    @Provides @Singleton fun scheduler(impl: SelectingScheduler): DownloadScheduler = impl

    @Provides @Singleton
    fun controller(dao: DownloadDao, scheduler: DownloadScheduler, registry: RunRegistry, destinations: DestinationFactory) =
        DownloadController(dao, scheduler, registry, destinations)
}
