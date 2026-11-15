package com.abhranil.stashd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveRootDao {
    @Query("SELECT * FROM drive_root ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun recent(limit: Int = 10): Flow<List<DriveRootEntity>>

    @Query("SELECT * FROM drive_root WHERE folderId = :folderId LIMIT 1")
    suspend fun byFolderId(folderId: String): DriveRootEntity?

    @Upsert
    suspend fun upsert(root: DriveRootEntity): Long
}

@Dao
interface DriveNodeDao {
    @Query("SELECT * FROM drive_node WHERE rootId = :rootId AND parentId = :parentId ORDER BY isFolder DESC, name COLLATE NOCASE")
    fun children(rootId: Long, parentId: String): Flow<List<DriveNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<DriveNodeEntity>)

    @Query("DELETE FROM drive_node WHERE rootId = :rootId")
    suspend fun clearRoot(rootId: Long)

    @Transaction
    suspend fun replaceTree(rootId: Long, nodes: List<DriveNodeEntity>) {
        clearRoot(rootId)
        insertAll(nodes)
    }
}

@Dao
interface DownloadDao {
    @Insert suspend fun insertBatch(batch: DownloadBatchEntity): Long
    @Insert suspend fun insertItem(item: DownloadItemEntity): Long
    @Insert suspend fun insertTasks(tasks: List<FileTaskEntity>)

    @Query("SELECT * FROM download_batch WHERE id = :id")
    fun batch(id: Long): Flow<DownloadBatchEntity?>

    @Query("SELECT * FROM download_batch ORDER BY createdAt DESC")
    fun history(): Flow<List<DownloadBatchEntity>>

    @Query("SELECT * FROM download_item WHERE batchId = :batchId ORDER BY sortOrder")
    fun items(batchId: Long): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM file_task WHERE batchId = :batchId AND state IN (:states) ORDER BY id")
    suspend fun tasksIn(batchId: Long, states: List<TaskState>): List<FileTaskEntity>

    @Query("SELECT * FROM file_task WHERE batchId = :batchId AND state = 'FAILED'")
    fun failed(batchId: Long): Flow<List<FileTaskEntity>>

    @Query(
        """
        SELECT itemId,
               SUM(size) AS totalBytes,
               SUM(bytesDone) AS doneBytes,
               COUNT(*) AS fileCount,
               SUM(CASE WHEN state = 'DONE' OR state = 'SKIPPED' THEN 1 ELSE 0 END) AS doneFiles,
               SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failedFiles
        FROM file_task WHERE batchId = :batchId GROUP BY itemId
        """
    )
    fun itemProgress(batchId: Long): Flow<List<ItemProgress>>

    /** Called by the ProgressBus flush, about once a second, inside one transaction. */
    @Query("UPDATE file_task SET bytesDone = :bytesDone WHERE id = :taskId")
    suspend fun updateBytes(taskId: Long, bytesDone: Long)

    @Transaction
    suspend fun flushBytes(progress: Map<Long, Long>) {
        progress.forEach { (taskId, bytes) -> updateBytes(taskId, bytes) }
    }

    @Query("UPDATE file_task SET state = :state, errorCode = :code, errorReason = :reason, errorMessage = :message, finishedAt = :at WHERE id = :taskId")
    suspend fun setTaskState(taskId: Long, state: TaskState, code: Int?, reason: String?, message: String?, at: Long?)

    @Query("UPDATE download_batch SET state = :state, finishedAt = :finishedAt WHERE id = :batchId")
    suspend fun setBatchState(batchId: Long, state: BatchState, finishedAt: Long?)
}
