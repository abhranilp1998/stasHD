package com.abhranil.stashd.data.db

import androidx.room.Dao
import androidx.room.Insert
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
    suspend fun upsert(root: DriveRootEntity)
}

@Dao
interface DriveNodeDao {
    @Query(
        """
        SELECT n.* FROM drive_node n JOIN drive_edge e ON e.childId = n.fileId
        WHERE e.parentId = :parentId ORDER BY n.isFolder DESC, e.position, n.name COLLATE NOCASE
        """
    )
    fun children(parentId: String): Flow<List<DriveNodeEntity>>

    @Query(
        """
        SELECT n.* FROM drive_node n JOIN drive_edge e ON e.childId = n.fileId
        WHERE e.parentId = :parentId ORDER BY n.isFolder DESC, e.position, n.name COLLATE NOCASE
        """
    )
    suspend fun childrenOnce(parentId: String): List<DriveNodeEntity>

    @Query("SELECT * FROM drive_node WHERE fileId = :fileId")
    suspend fun node(fileId: String): DriveNodeEntity?

    @Query("SELECT parentId FROM drive_edge WHERE childId = :childId")
    suspend fun parentsOf(childId: String): List<String>

    @Query("SELECT * FROM drive_listing WHERE folderId = :folderId")
    suspend fun listing(folderId: String): DriveListingEntity?

    /** Insert-or-update in place. Never REPLACE: a REPLACE deletes the row first and the edge FK cascade would drop other parents' edges. */
    @Upsert suspend fun upsertNodes(nodes: List<DriveNodeEntity>)
    @Upsert suspend fun upsertListing(listing: DriveListingEntity)
    @Upsert suspend fun upsertEdges(edges: List<DriveEdgeEntity>)

    @Query("DELETE FROM drive_edge WHERE parentId = :parentId")
    suspend fun clearEdgesOf(parentId: String)

    /**
     * Replace the cached direct children of ONE folder. Only edges whose parent is [parentId] are touched;
     * nodes are upserted, never deleted, so other folders' caches that reference them stay intact.
     */
    @Transaction
    suspend fun replaceChildren(parentId: String, parentResourceKey: String?, self: DriveNodeEntity?, children: List<DriveNodeEntity>, now: Long) {
        val all = if (self != null) children + self else children
        upsertNodes(all)
        clearEdgesOf(parentId)
        upsertEdges(children.mapIndexed { i, c -> DriveEdgeEntity(parentId, c.fileId, i) })
        upsertListing(DriveListingEntity(parentId, parentResourceKey, children.size, now))
    }

    /** Housekeeping: nodes no folder lists and that are not a pasted root. */
    @Query("DELETE FROM drive_node WHERE fileId NOT IN (SELECT childId FROM drive_edge) AND fileId NOT IN (SELECT folderId FROM drive_root) AND fileId NOT IN (SELECT folderId FROM drive_listing)")
    suspend fun pruneOrphans(): Int
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
