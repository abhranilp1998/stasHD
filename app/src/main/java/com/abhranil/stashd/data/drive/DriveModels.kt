package com.abhranil.stashd.data.drive

/** A parsed Drive link: folder or file id plus the optional resource key from `?resourcekey=`. */
data class DriveLink(val id: String, val resourceKey: String?, val isFolder: Boolean)

/** Where a Drive shortcut points. Navigation and downloads use this; identity and name stay the shortcut's. */
data class ShortcutTarget(val id: String, val mimeType: String?, val resourceKey: String?)

/**
 * A Drive file, folder or shortcut. [id]/[name]/[resourceKey] are always the item's OWN (a shortcut keeps its
 * shortcut id), so a folder holding both X and a shortcut to X lists two distinct entries.
 */
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val md5: String?,
    val resourceKey: String?,
    val modifiedTime: Long,
    val canDownload: Boolean,
    val childCount: Int = 0,
    /** For folders: total bytes of everything under it (filled after the walk). */
    val totalBytes: Long = size ?: 0L,
    /** Non-null for shortcuts (mimeType [MIME_SHORTCUT]). */
    val shortcut: ShortcutTarget? = null,
) {
    val isShortcut: Boolean get() = shortcut != null
    /** Id to list / download: the target for shortcuts, else [id]. */
    val targetId: String get() = shortcut?.id ?: id
    val targetResourceKey: String? get() = if (shortcut != null) shortcut.resourceKey else resourceKey
    val targetMimeType: String get() = shortcut?.mimeType ?: mimeType
    /** Navigable as a folder (a real folder or a shortcut to one). */
    val isFolder: Boolean get() = targetMimeType == MIME_FOLDER
    val isGoogleDoc: Boolean get() = targetMimeType.startsWith("application/vnd.google-apps.") && !isFolder && targetMimeType != MIME_SHORTCUT

    companion object {
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_SHORTCUT = "application/vnd.google-apps.shortcut"
    }
}

/**
 * An open media stream for one file. [offset] is where the body starts: equal to the requested byte for a
 * validated 206, or 0 when the server ignored Range (200) and the caller must restart the file.
 */
interface DriveDownload : AutoCloseable {
    val offset: Long
    /** Full file size from Content-Range / Content-Length, when the server said. */
    val totalSize: Long?
    val source: okio.BufferedSource
    /** Interrupt an in-flight body read, including a stalled socket. */
    fun cancel() = close()
}

/** One files.list page. [incompleteSearch] is Drive's own "this result may be missing items" flag. */
data class DrivePage(val items: List<DriveItem>, val nextPageToken: String?, val incompleteSearch: Boolean = false)

/**
 * A folder's direct children gathered over one or more pages. Complete only when no page token remains
 * AND Drive never reported incompleteSearch. Incomplete listings must not be cached or shown as complete.
 */
data class FolderListing(val items: List<DriveItem>, val nextPageToken: String?, val incompleteSearch: Boolean) {
    val isComplete: Boolean get() = nextPageToken == null && !incompleteSearch
}

sealed class DriveError(message: String) : Exception(message) {
    class NotFound(val id: String) : DriveError("Not found, or this account has no access to it")
    class Unauthorized : DriveError("Google rejected the access token")
    class Forbidden(val reason: String, detail: String) : DriveError(detail.ifBlank { "Access denied ($reason)" })
    class BadRequest(detail: String) : DriveError(detail.ifBlank { "Drive rejected the request" })
    class RateLimited(val retryAfterMs: Long?) : DriveError("Drive is rate limiting requests. Try again shortly.")
    class NotDownloadable(id: String) : DriveError("Google Docs file needs export: $id")
    class Server(val code: Int) : DriveError("Drive server error $code")
    class NotAFolder(val item: DriveItem) : DriveError("That link is a file, not a folder")
    /** Owner disabled downloads, or Drive flagged the file (cannotDownloadFile / abuse). */
    class CannotDownload(val reason: String) : DriveError("Drive won't let this file be downloaded ($reason)")
    /** 416: the requested start is past the end. [totalSize] from `Content-Range: bytes * /N`. */
    class RangeNotSatisfiable(val totalSize: Long?) : DriveError("Requested range not satisfiable")
    /** 206 whose Content-Range doesn't start where we asked. */
    class RangeMismatch(val requested: Long, val got: String?) : DriveError("Server resumed at the wrong offset ($got, wanted $requested)")
}

/**
 * Builds the `X-Goog-Drive-Resource-Keys` header: comma-separated `fileId/resourceKey` pairs.
 * Required for items shared by link before the 2021 security update; harmless otherwise.
 */
object ResourceKeys {
    const val HEADER = "X-Goog-Drive-Resource-Keys"

    fun header(vararg pairs: Pair<String, String?>): String? =
        pairs.filter { !it.second.isNullOrBlank() }.distinctBy { it.first }
            .joinToString(",") { "${it.first}/${it.second}" }
            .ifEmpty { null }
}
