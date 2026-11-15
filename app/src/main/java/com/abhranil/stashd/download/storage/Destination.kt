package com.abhranil.stashd.download.storage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Destination failures the batch can't fix by retrying the same file. */
sealed class StorageError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** The SAF grant is gone (folder deleted, permission revoked, SD card removed). Pick the folder again. */
    class AccessRevoked(detail: String, cause: Throwable? = null) : StorageError("Can't write to the download folder anymore: $detail", cause)
    /** Not enough free space for the rest of this file (plus a safety margin), or the disk filled mid-write. */
    class InsufficientSpace(val neededBytes: Long, val availableBytes: Long?, cause: Throwable? = null) :
        StorageError("Not enough free space on the phone", cause)
}

/**
 * Where a batch writes. Scheduler- and Android-agnostic so the downloader is unit-testable:
 * [SafDestination] is the real one (Storage Access Framework tree), [FileTreeDestination] a plain directory.
 */
interface Destination {
    /** Throws [StorageError.AccessRevoked] when the destination is no longer writable. */
    fun checkAccess()
    /** Free bytes on the destination volume, or null when it can't be measured. */
    fun availableBytes(): Long?
    /** The directory for these (already sanitized) path segments, created as needed. */
    fun dir(segments: List<String>): DestDir
}

interface DestDir {
    /** Existing entries: name → size. */
    fun list(): Map<String, Long>
    fun size(name: String): Long?
    /** Opens [name] for writing (created if missing); [append] keeps existing bytes. */
    fun openWrite(name: String, append: Boolean): OutputStream
    fun openRead(name: String): InputStream
    fun delete(name: String)
    /** Renames [from] to [to] (which must not exist); returns a URI/path for the final file. */
    fun rename(from: String, to: String): String
}

/** ENOSPC from the kernel surfaces as an IOException message (or ErrnoException cause). */
fun Throwable.isNoSpace(): Boolean =
    generateSequence(this) { it.cause }.any { t ->
        val m = t.message.orEmpty()
        m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) || m.contains("EDQUOT")
    }
