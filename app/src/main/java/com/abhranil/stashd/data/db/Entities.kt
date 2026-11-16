package com.abhranil.stashd.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A pasted Drive folder link (screen 2, recent folders). Keyed by the Drive folder id. */
@Entity(tableName = "drive_root")
data class DriveRootEntity(
    @PrimaryKey val folderId: String,
    val resourceKey: String?,
    val name: String,
    val totalBytes: Long,
    val folderCount: Int,
    val fileCount: Int,
    val lastOpenedAt: Long,
)

/**
 * One row per Drive file or folder, keyed ONLY by its Drive file id. It does not belong to any root:
 * the same folder can be a pasted root and a child of another pasted root at once.
 * Parent/child structure lives in [DriveEdgeEntity], so re-caching one folder can never delete or
 * re-parent rows that another cached folder points at (the M0 bug: rootId + REPLACE on fileId).
 */
@Entity(tableName = "drive_node")
data class DriveNodeEntity(
    @PrimaryKey val fileId: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val md5: String?,
    val resourceKey: String?,
    val isFolder: Boolean,
    val isGoogleDoc: Boolean,
    val canDownload: Boolean,
    val modifiedTime: Long,
    val cachedAt: Long,
    /** Shortcut target (null for non-shortcuts). The row's identity stays the shortcut's own fileId. */
    val targetId: String? = null,
    val targetMimeType: String? = null,
    val targetResourceKey: String? = null,
)

/** "childId is listed inside parentId". A node may have several parents (multi-parent items, shortcuts). */
@Entity(
    tableName = "drive_edge",
    primaryKeys = ["parentId", "childId"],
    foreignKeys = [ForeignKey(entity = DriveNodeEntity::class, parentColumns = ["fileId"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["childId"])],
)
data class DriveEdgeEntity(
    val parentId: String,
    val childId: String,
    val position: Int,
)

/** When a folder's direct children were last listed completely (all pages). */
@Entity(tableName = "drive_listing")
data class DriveListingEntity(
    @PrimaryKey val folderId: String,
    val resourceKey: String?,
    val childCount: Int,
    val listedAt: Long,
)

/** Batch lifecycle. EXPANDING/QUEUED/RUNNING/PAUSED are "active": at most one such batch exists (DownloadDao.createBatch). */
enum class BatchState {
    /** Picks recorded; folders are being listed into file tasks. */
    EXPANDING,
    /** Ready; waiting for the scheduler (or network/conditions). */
    QUEUED,
    RUNNING,
    /** Stopped with resume offsets kept. See [PauseReason]. Pause != cancel. */
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    /** User cancelled: partial (.stashdpart) files deleted, completed files kept. */
    CANCELLED,
    /** Couldn't even start (e.g. expansion failed permanently). */
    FAILED;

    val isActive: Boolean get() = this == EXPANDING || this == QUEUED || this == RUNNING || this == PAUSED

    companion object {
        val ACTIVE = listOf(EXPANDING, QUEUED, RUNNING, PAUSED)
    }
}

/** Why a batch is PAUSED; all are resumable once fixed. */
enum class PauseReason { USER, STORAGE_FULL, DESTINATION_REVOKED, NEEDS_CONSENT, AUTH, SYSTEM }

enum class TaskState { QUEUED, RUNNING, DONE, SKIPPED, FAILED, CANCELLED }

/** Saved with a FAILED/SKIPPED task so history can say why. */
enum class FailureReason { NOT_FOUND, CANNOT_DOWNLOAD, GOOGLE_DOC, AUTH, CHECKSUM, RETRIES_EXHAUSTED, FORBIDDEN, EXISTS, OTHER }

/** One per "Start download" (screens 4, 5, 7). */
@Entity(tableName = "download_batch", indices = [Index(value = ["state"])])
data class DownloadBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rootFolderId: String,
    val rootName: String,
    val destTreeUri: String,
    val keepStructure: Boolean,
    val wifiOnly: Boolean,
    val skipExisting: Boolean,
    val state: BatchState,
    val pauseReason: PauseReason? = null,
    val message: String? = null,
    val totalBytes: Long = 0,
    val totalFiles: Int = 0,
    val skippedFiles: Int = 0,
    val duplicatesRemoved: Int = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/** A top-level pick inside a batch (the linked root, an album folder, a single file or a shortcut). */
@Entity(
    tableName = "download_item",
    foreignKeys = [ForeignKey(entity = DownloadBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["batchId"])],
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    /** The picked item's own id (shortcut id for shortcuts) = selection key. */
    val nodeId: String,
    val name: String,
    val mimeType: String,
    val resourceKey: String?,
    val targetId: String?,
    val targetMimeType: String?,
    val targetResourceKey: String?,
    val size: Long?,
    val md5: String?,
    val modifiedTime: Long,
    val canDownload: Boolean,
    /** Folder names from the linked root to the pick's parent (root excluded). */
    val parentNames: List<String>,
    val sortOrder: Int,
)

/** One row per file: resume point (bytesDone + tempName) and retry target. (batchId, fileId) is unique = DB-level dedup. */
@Entity(
    tableName = "file_task",
    foreignKeys = [ForeignKey(entity = DownloadBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["batchId", "state"]), Index(value = ["batchId", "fileId"], unique = true)],
)
data class FileTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val sourceKey: String,
    val fileId: String,
    val resourceKey: String?,
    val dirSegments: List<String>,
    val plannedName: String,
    val finalName: String? = null,
    val tempName: String? = null,
    val size: Long?,
    val md5: String?,
    val modifiedTime: Long,
    val viaShortcut: Boolean = false,
    val bytesDone: Long = 0,
    val state: TaskState = TaskState.QUEUED,
    val attempts: Int = 0,
    val errorReason: FailureReason? = null,
    val errorMessage: String? = null,
    val finalUri: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** v5: ResumableMd5 checkpoint of the temp file's prefix, so a resume needn't re-hash it all. */
    val md5State: String? = null,
)

/** Totals for the progress UI / notification. */
data class BatchTotals(
    val totalBytes: Long,
    val doneBytes: Long,
    val totalFiles: Int,
    val doneFiles: Int,
    val failedFiles: Int,
    val skippedFiles: Int,
)
