package com.abhranil.stashd.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun taskStateToString(s: TaskState): String = s.name
    @TypeConverter fun stringToTaskState(s: String): TaskState = TaskState.valueOf(s)
    @TypeConverter fun batchStateToString(s: BatchState): String = s.name
    @TypeConverter fun stringToBatchState(s: String): BatchState = BatchState.valueOf(s)
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
    // v3 (M1b): shortcut target columns on drive_node.
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StashdDatabase : RoomDatabase() {
    abstract fun driveRootDao(): DriveRootDao
    abstract fun driveNodeDao(): DriveNodeDao
    abstract fun downloadDao(): DownloadDao
}
