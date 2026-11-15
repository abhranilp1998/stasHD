package com.abhranil.stashd.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.abhranil.stashd.BuildConfig
import com.abhranil.stashd.data.auth.DriveAuthorizer
import com.abhranil.stashd.data.auth.GoogleTokenProvider
import com.abhranil.stashd.data.auth.TokenProvider
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.DriveNodeDao
import com.abhranil.stashd.data.db.DriveRootDao
import com.abhranil.stashd.data.db.StashdDatabase
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.DriveApiConfig
import com.abhranil.stashd.data.drive.DriveCache
import com.abhranil.stashd.data.drive.FolderCache
import com.abhranil.stashd.data.drive.DriveMode
import com.abhranil.stashd.data.drive.FakeDriveRepository
import com.abhranil.stashd.data.drive.RealDriveRepository
import com.abhranil.stashd.data.net.HttpLogging
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): StashdDatabase =
        Room.databaseBuilder(context, StashdDatabase::class.java, "stashd.db")
            // Sideloaded hobby build with no shipped data yet; the cache is rebuildable. Add real Migrations before M3.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun driveRootDao(db: StashdDatabase): DriveRootDao = db.driveRootDao()
    @Provides fun driveNodeDao(db: StashdDatabase): DriveNodeDao = db.driveNodeDao()
    @Provides fun downloadDao(db: StashdDatabase): DownloadDao = db.downloadDao()

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            // Debug only, and always via HttpLogging so Authorization/resource keys are redacted.
            if (BuildConfig.DEBUG) addInterceptor(HttpLogging.interceptor())
        }
        .build()

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides @Singleton
    fun driveApiConfig(): DriveApiConfig = DriveApiConfig()

    /**
     * Real Drive when a Web client ID is configured; sample data otherwise (or when a debug build sets
     * stashd.fakeDrive=true). Both are lazy so the unused one is never constructed.
     */
    @Provides @Singleton
    fun driveRepository(real: Provider<RealDriveRepository>, fake: Provider<FakeDriveRepository>): DriveRepository =
        if (DriveMode.useFake) fake.get() else real.get()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun tokenProvider(impl: GoogleTokenProvider): TokenProvider
    @Binds abstract fun driveAuthorizer(impl: GoogleTokenProvider): DriveAuthorizer
    @Binds abstract fun folderCache(impl: DriveCache): FolderCache
}
