package com.abhranil.stashd.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.abhranil.stashd.BuildConfig
import com.abhranil.stashd.data.auth.GoogleTokenProvider
import com.abhranil.stashd.data.auth.TokenProvider
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.DriveNodeDao
import com.abhranil.stashd.data.db.DriveRootDao
import com.abhranil.stashd.data.db.StashdDatabase
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.FakeDriveRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
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
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS))
        }
        .build()

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /** Swap for the OkHttp-backed implementation in M1/M2. */
    @Binds abstract fun driveRepository(impl: FakeDriveRepository): DriveRepository
    @Binds abstract fun tokenProvider(impl: GoogleTokenProvider): TokenProvider
}
