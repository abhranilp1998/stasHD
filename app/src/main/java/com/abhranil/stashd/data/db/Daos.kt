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
    @Query("DELETE FROM drive_edge") suspend fun deleteAllEdges()
    @Query("DELETE FROM drive_listing") suspend fun deleteAllListings()
    @Query("DELETE FROM drive_node") suspend fun deleteAllNodes()
    @Query("DELETE FROM drive_root") suspend fun deleteAllRoots()

    /** Sign-out: forget every cached Drive listing and recent folder (they belong to the old account). */
    @Transaction
    suspend fun clearDriveCache() { deleteAllEdges(); deleteAllListings(); deleteAllNodes(); deleteAllRoots() }

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

class ActiveBatchExists(val batchId: Long) : IllegalStateException("A download batch is already active (#$batchId). Finish, pause-and-cancel, or resume it first.")

@Dao
interface DownloadDao {
    @Insert suspend fun insertBatchRow(batch: DownloadBatchEntity): Long
    @Insert suspend fun insertItems(items: List<DownloadItemEntity>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun insertTasks(tasks: List<FileTaskEntity>): List<Long>

    @Query("SELECT id FROM download_batch WHERE state IN ('EXPANDING','QUEUED','RUNNING','PAUSED') ORDER BY id LIMIT 1")
    suspend fun activeBatchId(): Long?

    /** Creates a batch + its picks. Enforces ONE active batch atomically (check and insert in one transaction). */
    @Transaction
    suspend fun createBatch(batch: DownloadBatchEntity, items: List<DownloadItemEntity>): Long {
        activeBatchId()?.let { throw ActiveBatchExists(it) }
        val id = insertBatchRow(batch)
        insertItems(items.map { it.copy(batchId = id) })
        return id
    }

    @Query("SELECT * FROM download_batch WHERE id = :id") suspend fun batchOnce(id: Long): DownloadBatchEntity?
    @Query("SELECT * FROM download_batch WHERE id = :id") fun batch(id: Long): Flow<DownloadBatchEntity?>
    @Query("SELECT * FROM download_batch WHERE state IN ('EXPANDING','QUEUED','RUNNING','PAUSED') ORDER BY id LIMIT 1")
    fun activeBatch(): Flow<DownloadBatchEntity?>
    @Query("SELECT * FROM download_batch ORDER BY createdAt DESC") fun history(): Flow<List<DownloadBatchEntity>>
    @Query("SELECT * FROM download_item WHERE batchId = :batchId ORDER BY sortOrder") suspend fun items(batchId: Long): List<DownloadItemEntity>

    @Query("SELECT * FROM file_task WHERE batchId = :batchId AND state IN ('QUEUED','RUNNING') ORDER BY id")
    suspend fun runnableTasks(batchId: Long): List<FileTaskEntity>
    @Query("SELECT * FROM file_task WHERE batchId = :batchId ORDER BY id") suspend fun tasks(batchId: Long): List<FileTaskEntity>
    @Query("SELECT * FROM file_task WHERE id = :id") suspend fun task(id: Long): FileTaskEntity?
    @Query("SELECT * FROM file_task WHERE batchId = :batchId AND state = 'FAILED'") fun failed(batchId: Long): Flow<List<FileTaskEntity>>
    @Query("SELECT * FROM file_task WHERE batchId = :batchId AND tempName IS NOT NULL AND state NOT IN ('DONE','SKIPPED')")
    suspend fun tasksWithPartials(batchId: Long): List<FileTaskEntity>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN state != 'SKIPPED' THEN COALESCE(size, 0) ELSE 0 END), 0) AS totalBytes,
               COALESCE(SUM(CASE WHEN state = 'DONE' THEN COALESCE(size, bytesDone) WHEN state IN ('QUEUED','RUNNING') THEN bytesDone ELSE 0 END), 0) AS doneBytes,
               COUNT(*) AS totalFiles,
               COALESCE(SUM(CASE WHEN state = 'DONE' THEN 1 ELSE 0 END), 0) AS doneFiles,
               COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedFiles,
               COALESCE(SUM(CASE WHEN state = 'SKIPPED' THEN 1 ELSE 0 END), 0) AS skippedFiles
        FROM file_task WHERE batchId = :batchId
        """
    )
    fun totals(batchId: Long): Flow<BatchTotals>

    @Query("UPDATE file_task SET bytesDone = :bytes WHERE id = :taskId") suspend fun setBytes(taskId: Long, bytes: Long)
    @Query("UPDATE file_task SET finalName = :finalName, tempName = :tempName WHERE id = :taskId")
    suspend fun claimNames(taskId: Long, finalName: String, tempName: String)
    @Query("UPDATE file_task SET md5State = :state WHERE id = :taskId") suspend fun saveMd5State(taskId: Long, state: String?)
    @Query("UPDATE file_task SET size = :size, md5 = :md5, modifiedTime = :modified, bytesDone = 0, md5State = NULL WHERE id = :taskId")
    suspend fun remoteChanged(taskId: Long, size: Long?, md5: String?, modified: Long)
    @Query("UPDATE file_task SET state = 'RUNNING', startedAt = COALESCE(startedAt, :now) WHERE id = :taskId")
    suspend fun markRunning(taskId: Long, now: Long)
    /** A failed attempt (retryable); the message is kept so the UI can show "retrying: ...". */
    @Query("UPDATE file_task SET attempts = attempts + 1, errorMessage = :message WHERE id = :taskId")
    suspend fun recordAttempt(taskId: Long, message: String?)
    @Query("UPDATE file_task SET state = 'QUEUED' WHERE id = :taskId") suspend fun requeue(taskId: Long)
    @Query("UPDATE file_task SET state = 'QUEUED' WHERE batchId = :batchId AND state = 'RUNNING'") suspend fun requeueRunning(batchId: Long)
    @Query("UPDATE file_task SET state = 'DONE', finalName = :finalName, finalUri = :uri, bytesDone = :bytes, finishedAt = :now, errorReason = NULL, errorMessage = NULL WHERE id = :taskId")
    suspend fun markDone(taskId: Long, finalName: String, uri: String, bytes: Long, now: Long)
    @Query("UPDATE file_task SET state = 'SKIPPED', errorReason = :reason, errorMessage = :message, finishedAt = :now WHERE id = :taskId")
    suspend fun markSkipped(taskId: Long, reason: FailureReason, message: String?, now: Long)
    @Query("UPDATE file_task SET state = 'FAILED', errorReason = :reason, errorMessage = :message, finishedAt = :now WHERE id = :taskId")
    suspend fun markFailed(taskId: Long, reason: FailureReason, message: String?, now: Long)
    @Query("UPDATE file_task SET state = 'CANCELLED', tempName = NULL, bytesDone = 0, md5State = NULL WHERE batchId = :batchId AND state NOT IN ('DONE','SKIPPED','FAILED')")
    suspend fun cancelOpenTasks(batchId: Long)
    @Query("UPDATE file_task SET state = 'QUEUED', attempts = 0, errorReason = NULL, errorMessage = NULL, finishedAt = NULL WHERE batchId = :batchId AND state = 'FAILED'")
    suspend fun requeueFailed(batchId: Long): Int

    @Query("UPDATE download_batch SET state = :state, pauseReason = :reason, message = :message WHERE id = :batchId")
    suspend fun setBatchState(batchId: Long, state: BatchState, reason: PauseReason? = null, message: String? = null)
    @Query("UPDATE download_batch SET state = :state, pauseReason = NULL, message = :message, finishedAt = :at WHERE id = :batchId")
    suspend fun finishBatch(batchId: Long, state: BatchState, message: String?, at: Long)
    @Query("UPDATE download_batch SET startedAt = COALESCE(startedAt, :now) WHERE id = :batchId") suspend fun markStarted(batchId: Long, now: Long)
    @Query("UPDATE download_batch SET totalBytes = :bytes, totalFiles = :files, skippedFiles = :skipped, duplicatesRemoved = :dups WHERE id = :batchId")
    suspend fun setPlanTotals(batchId: Long, bytes: Long, files: Int, skipped: Int, dups: Int)

    /** Expansion result lands atomically: tasks inserted and batch moved EXPANDING -> QUEUED. */
    @Transaction
    suspend fun savePlan(batchId: Long, tasks: List<FileTaskEntity>, bytes: Long, files: Int, skipped: Int, dups: Int) {
        insertTasks(tasks)
        setPlanTotals(batchId, bytes, files, skipped, dups)
        setBatchState(batchId, BatchState.QUEUED)
    }
}
