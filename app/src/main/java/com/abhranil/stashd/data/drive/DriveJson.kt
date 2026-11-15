package com.abhranil.stashd.data.drive

import kotlinx.serialization.Serializable
import java.time.Instant

/** Wire models for the subset of Drive v3 fields Stashd requests. */
@Serializable
internal data class FileListJson(
    val nextPageToken: String? = null,
    val incompleteSearch: Boolean = false,
    val files: List<FileJson> = emptyList(),
)

@Serializable
internal data class FileJson(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    /** int64 is sent as a JSON string. Absent for folders and Google Docs. */
    val size: String? = null,
    val md5Checksum: String? = null,
    val modifiedTime: String? = null,
    val resourceKey: String? = null,
    val capabilities: CapabilitiesJson? = null,
    val shortcutDetails: ShortcutJson? = null,
)

@Serializable internal data class CapabilitiesJson(val canDownload: Boolean = true)

@Serializable
internal data class ShortcutJson(
    val targetId: String? = null,
    val targetMimeType: String? = null,
    val targetResourceKey: String? = null,
)

@Serializable internal data class ErrorEnvelopeJson(val error: ErrorBodyJson? = null)
@Serializable internal data class ErrorBodyJson(val code: Int = 0, val message: String = "", val errors: List<ErrorItemJson> = emptyList())
@Serializable internal data class ErrorItemJson(val reason: String = "", val message: String = "")

internal const val FILE_FIELDS =
    "id,name,mimeType,size,md5Checksum,modifiedTime,resourceKey,capabilities/canDownload," +
        "shortcutDetails/targetId,shortcutDetails/targetMimeType,shortcutDetails/targetResourceKey"

/**
 * Shortcuts keep their OWN id, name and resource key (identity, list key, cache row); the target's id,
 * mime type and resource key go into [DriveItem.shortcut] for navigation and downloads.
 */
internal fun FileJson.toItem(): DriveItem = DriveItem(
    id = id,
    name = name,
    mimeType = mimeType,
    size = size?.toLongOrNull(),
    md5 = md5Checksum,
    resourceKey = resourceKey,
    modifiedTime = parseTime(modifiedTime),
    canDownload = capabilities?.canDownload ?: true,
    shortcut = shortcutDetails?.targetId
        ?.takeIf { mimeType == DriveItem.MIME_SHORTCUT }
        ?.let { ShortcutTarget(it, shortcutDetails.targetMimeType, shortcutDetails.targetResourceKey) },
)

private fun parseTime(s: String?): Long = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
