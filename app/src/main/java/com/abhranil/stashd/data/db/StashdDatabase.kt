package com.abhranil.stashd.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter fun taskStateToString(s: TaskState): String = s.name
    @TypeConverter fun stringToTaskState(s: String): TaskState = TaskState.valueOf(s)
    @TypeConverter fun batchStateToString(s: BatchState): String = s.name
    @TypeConverter fun stringToBatchState(s: String): BatchState = BatchState.valueOf(s)
    @TypeConverter fun pauseToString(s: PauseReason?): String? = s?.name
    @TypeConverter fun stringToPause(s: String?): PauseReason? = s?.let(PauseReason::valueOf)
    @TypeConverter fun failureToString(s: FailureReason?): String? = s?.name
    @TypeConverter fun stringToFailure(s: String?): FailureReason? = s?.let(FailureReason::valueOf)
    @TypeConverter fun listToJson(l: List<String>): String = Json.encodeToString(ListSerializer(String.serializer()), l)
    @TypeConverter fun jsonToList(s: String): List<String> = Json.decodeFromString(ListSerializer(String.serializer()), s)
}

@Database(
    entities = [
        DriveRootEntity::class,
        DriveNodeEntity::class,
        DriveEdgeEntity::class,
        DriveListingEntity::class,
        DownloadBatchEntity::class,
        DownloadItemEntity::class,
        FileTaskEntity::class,
    ],
    // v2 (M1): node identity by Drive file id + edge table. Pre-release, so destructive migration (see AppModule).
    // v3 (M1b): shortcut target columns. v4 (M2): batch/item/task schema for the downloader.
    // v5 (M3): file_task.md5State. From v4 on, upgrades use real migrations (Migrations.kt); never destructive.
    // v6: durable file-finalization evidence and Drive parent IDs. Legacy md5State is no longer trusted.
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StashdDatabase : RoomDatabase() {
    abstract fun driveRootDao(): DriveRootDao
    abstract fun driveNodeDao(): DriveNodeDao
    abstract fun downloadDao(): DownloadDao
}
