package com.abhranil.stashd.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A pasted Drive folder link (screen 2, recent folders). */
@Entity(tableName = "drive_root", indices = [Index(value = ["folderId"], unique = true)])
data class DriveRootEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: String,
    val resourceKey: String?,
    val name: String,
    val totalBytes: Long,
    val folderCount: Int,
    val fileCount: Int,
    val lastOpenedAt: Long,
)

/** Cached Drive tree for the browser (screen 3). */
@Entity(
    tableName = "drive_node",
    foreignKeys = [ForeignKey(entity = DriveRootEntity::class, parentColumns = ["id"], childColumns = ["rootId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["rootId", "parentId"])],
)
data class DriveNodeEntity(
    @PrimaryKey val fileId: String,
    val rootId: Long,
    val parentId: String?,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val md5: String?,
    val resourceKey: String?,
    val isFolder: Boolean,
    val isGoogleDoc: Boolean,
    val canDownload: Boolean,
    val modifiedTime: Long,
    val relPath: String,
)

enum class BatchState { RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED }

enum class TaskState { QUEUED, RUNNING, PAUSED, WAITING_NETWORK, DONE, SKIPPED, FAILED, CANCELLED }

/** One per "Start download" tap (screens 4, 5, 7). */
@Entity(tableName = "download_batch", indices = [Index(value = ["rootId"])])
data class DownloadBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rootId: Long,
    val destTreeUri: String,
    val keepStructure: Boolean,
    val wifiOnly: Boolean,
    val skipExisting: Boolean,
    val parallelism: Int,
    val totalBytes: Long,
    val totalFiles: Int,
    val state: BatchState,
    val createdAt: Long,
    val finishedAt: Long?,
)

/** A top-level pick inside a batch (an album folder or a single file). */
@Entity(
    tableName = "download_item",
    foreignKeys = [ForeignKey(entity = DownloadBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["batchId"])],
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val nodeId: String,
    val name: String,
    val isFolder: Boolean,
    val totalBytes: Long,
    val fileCount: Int,
    val sortOrder: Int,
)

/** One row per file: the resume point and retry target. */
@Entity(
    tableName = "file_task",
    foreignKeys = [
        ForeignKey(entity = DownloadBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DownloadItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["batchId", "state"]), Index(value = ["itemId"])],
)
data class FileTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val itemId: Long,
    val fileId: String,
    val resourceKey: String?,
    val relPath: String,
    val size: Long,
    val md5: String?,
    val modifiedTime: Long,
    val bytesDone: Long = 0,
    val state: TaskState = TaskState.QUEUED,
    val attempts: Int = 0,
    val errorCode: Int? = null,
    val errorReason: String? = null,
    val errorMessage: String? = null,
    val partUri: String? = null,
    val finalUri: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/** Aggregate row for per-item progress (screen 5). */
data class ItemProgress(
    val itemId: Long,
    val totalBytes: Long,
    val doneBytes: Long,
    val fileCount: Int,
    val doneFiles: Int,
    val failedFiles: Int,
)
