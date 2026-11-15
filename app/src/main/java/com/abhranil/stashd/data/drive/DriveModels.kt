package com.abhranil.stashd.data.drive

/** A parsed Drive link: folder or file id plus the optional resource key from `?resourcekey=`. */
data class DriveLink(val id: String, val resourceKey: String?, val isFolder: Boolean)

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
) {
    val isFolder: Boolean get() = mimeType == MIME_FOLDER
    val isGoogleDoc: Boolean get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder && mimeType != MIME_SHORTCUT

    companion object {
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_SHORTCUT = "application/vnd.google-apps.shortcut"
    }
}

/** Progress of the recursive folder walk (screen 3 shows "Scanning… 412 files"). */
data class WalkProgress(val foldersScanned: Int, val filesFound: Int, val bytesFound: Long, val done: Boolean)

/** An open media stream for one file. [offset] is where the body starts (206 resumes, 200 restarts at 0). */
interface DriveDownload : AutoCloseable {
    val offset: Long
    val contentLength: Long
    val source: okio.BufferedSource
}

/** One files.list page. */
data class DrivePage(val items: List<DriveItem>, val nextPageToken: String?)

sealed class DriveError(message: String) : Exception(message) {
    class NotFound(val id: String) : DriveError("Not found, or this account has no access to it")
    class Unauthorized : DriveError("Google rejected the access token")
    class Forbidden(val reason: String, detail: String) : DriveError(detail.ifBlank { "Access denied ($reason)" })
    class BadRequest(detail: String) : DriveError(detail.ifBlank { "Drive rejected the request" })
    class RateLimited(val retryAfterMs: Long?) : DriveError("Drive is rate limiting requests. Try again shortly.")
    class NotDownloadable(id: String) : DriveError("Google Docs file needs export: $id")
    class Server(val code: Int) : DriveError("Drive server error $code")
    class NotAFolder(val item: DriveItem) : DriveError("That link is a file, not a folder")
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
