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

sealed class DriveError(message: String) : Exception(message) {
    class NotFound(id: String) : DriveError("Not found or no access: $id")
    class Unauthorized : DriveError("Access token rejected")
    class RateLimited(val retryAfterMs: Long?) : DriveError("Rate limited")
    class NotDownloadable(id: String) : DriveError("Google Docs file needs export: $id")
    class Server(val code: Int) : DriveError("Drive server error $code")
}
