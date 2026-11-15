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
        DownloadBatchEntity::class,
        DownloadItemEntity::class,
        FileTaskEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StashdDatabase : RoomDatabase() {
    abstract fun driveRootDao(): DriveRootDao
    abstract fun driveNodeDao(): DriveNodeDao
    abstract fun downloadDao(): DownloadDao
}
