package com.abhranil.stashd.download.core

import com.abhranil.stashd.data.drive.DriveDownload
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.storage.NameSanitizer
import com.abhranil.stashd.download.storage.DestDir
import com.abhranil.stashd.download.storage.Destination
import com.abhranil.stashd.download.storage.StorageError
import com.abhranil.stashd.download.storage.isNoSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Remote facts we expect the file to have; a resume is only valid while they still hold. */
data class RemoteFingerprint(val size: Long?, val md5: String?, val modifiedTime: Long) {
    fun differsFrom(other: RemoteFingerprint): Boolean =
        (size != null && other.size != null && size != other.size) ||
            (md5 != null && other.md5 != null && !md5.equals(other.md5, ignoreCase = true)) ||
            (modifiedTime > 0 && other.modifiedTime > 0 && modifiedTime != other.modifiedTime)

    companion object {
        fun of(item: DriveItem) = RemoteFingerprint(item.size, item.md5, item.modifiedTime)
    }
}

/** One file to fetch. [finalName]/[tempName] are null until the first start claims them (then stable across resumes). */
data class FileJob(
    val taskId: Long,
    val fileId: String,
    val resourceKey: String?,
    val dirSegments: List<String>,
    val plannedName: String,
    val expected: RemoteFingerprint,
    val finalName: String? = null,
    val tempName: String? = null,
    val skipExisting: Boolean = false,
)

sealed interface FileOutcome {
    data class Done(val finalName: String, val uri: String, val bytes: Long, val md5Verified: Boolean) : FileOutcome
    data class SkippedExisting(val name: String) : FileOutcome
}

/** Persisted by the runner; the downloader itself is stateless. */
interface FileJobListener {
    /** Names chosen on first start (collision-resolved); must be saved before bytes are written. */
    suspend fun namesClaimed(finalName: String, tempName: String)
    /** The remote file changed since planning; the partial was discarded and these are the new expectations. */
    suspend fun remoteChanged(now: RemoteFingerprint)
    /** Hot path (per 256 KB chunk): bytes in the temp file so far. The runner throttles persistence. */
    suspend fun progress(bytesDone: Long)
}

class ChecksumMismatch(val expected: String, val actual: String) : IOException("Checksum mismatch (expected $expected, got $actual)")

/**
 * Downloads ONE Drive file into a [Destination], resumably. Knows nothing about batches or schedulers.
 *  - temp file `<final>.stashdpart`, renamed to the final name only after size + md5 checks pass;
 *  - resumes with Range from the temp file's length, after re-checking the remote fingerprint
 *    (size/md5/modifiedTime) — a changed file restarts from 0;
 *  - a 200 to a Range request, a wrong Content-Range or a 416 short of the end restarts from 0;
 *  - free space is checked before each file (remaining bytes + margin); ENOSPC mid-write is reported as such.
 * Cancellation (pause) leaves the temp file in place for the next resume.
 */
class FileDownloader(
    private val drive: DriveRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val spaceMarginBytes: Long = SPACE_MARGIN,
) {
    suspend fun download(job: FileJob, dest: Destination, listener: FileJobListener): FileOutcome = withContext(io) {
        dest.checkAccess()
        val dir = dest.dir(job.dirSegments)

        var finalName = job.finalName
        var tempName = job.tempName
        if (finalName == null || tempName == null) {
            val existing = dir.list()
            if (job.skipExisting && job.expected.size != null && existing[job.plannedName] == job.expected.size) {
                return@withContext FileOutcome.SkippedExisting(job.plannedName)
            }
            finalName = NameSanitizer.dedupe(job.plannedName, existing.keys + existing.keys.mapNotNull { it.removeSuffix(TEMP_SUFFIX).takeIf { s -> s != it } })
            tempName = finalName + TEMP_SUFFIX
            listener.namesClaimed(finalName, tempName)
        }

        var expected = job.expected
        var offset = dir.size(tempName) ?: 0L
        if (offset > 0) {
            val now = RemoteFingerprint.of(fresh(job))
            if (now.differsFrom(expected)) {
                dir.delete(tempName); offset = 0
                expected = now
                listener.remoteChanged(now)
            }
        }
        if (expected.size != null && offset > expected.size!!) { dir.delete(tempName); offset = 0 }
        listener.progress(offset)

        dest.availableBytes()?.let { free ->
            val need = (expected.size ?: 0L) - offset
            if (free < need + spaceMarginBytes) throw StorageError.InsufficientSpace(need, free)
        }

        val md5 = MessageDigest.getInstance("MD5")
        if (offset > 0) digestPrefix(dir, tempName, offset, md5)

        var restarts = 0
        while (true) {
            coroutineContext.ensureActive()
            val download: DriveDownload? = try {
                drive.openDownload(job.fileId, job.resourceKey, offset)
            } catch (e: DriveError.RangeNotSatisfiable) {
                if (expected.size != null && offset == expected.size) null // temp already complete
                else { if (restarts++ > 0) throw e; dir.delete(tempName); offset = 0; md5.reset(); continue }
            } catch (e: DriveError.RangeMismatch) {
                if (restarts++ > 0) throw e
                dir.delete(tempName); offset = 0; md5.reset(); continue
            }
            if (download != null) download.use { d ->
                if (d.offset == 0L && offset > 0) { offset = 0; md5.reset() } // server ignored Range: start over
                offset = stream(d, dir, tempName, offset, md5, listener)
            }
            break
        }

        if (expected.size != null && offset != expected.size) {
            dir.delete(tempName)
            throw ChecksumMismatch("${expected.size} bytes", "$offset bytes")
        }
        val actual = md5.digest().toHex()
        val verified = expected.md5 != null
        if (verified && !actual.equals(expected.md5, ignoreCase = true)) {
            dir.delete(tempName)
            throw ChecksumMismatch(expected.md5!!, actual)
        }

        if (dir.size(finalName) != null) { // something appeared under our name meanwhile
            finalName = NameSanitizer.dedupe(finalName, dir.list().keys)
        }
        val uri = dir.rename(tempName, finalName)
        FileOutcome.Done(finalName, uri, offset, verified)
    }

    private suspend fun fresh(job: FileJob): DriveItem {
        val meta = drive.fileMetadata(job.fileId, job.resourceKey)
        if (meta.isGoogleDoc) throw DriveError.NotDownloadable(job.fileId)
        if (!meta.canDownload) throw DriveError.CannotDownload("canDownload=false")
        return meta
    }

    private suspend fun stream(d: DriveDownload, dir: DestDir, temp: String, start: Long, md5: MessageDigest, listener: FileJobListener): Long {
        var offset = start
        val buf = ByteArray(BUFFER)
        val out = try { dir.openWrite(temp, append = start > 0) } catch (e: IOException) { throw storageOr(e) }
        out.use {
            while (true) {
                coroutineContext.ensureActive()
                val n = d.source.read(buf) // network IOExceptions propagate as retryable
                if (n == -1) break
                try { out.write(buf, 0, n) } catch (e: IOException) { throw storageOr(e) }
                md5.update(buf, 0, n)
                offset += n
                listener.progress(offset)
            }
            try { out.flush() } catch (e: IOException) { throw storageOr(e) }
        }
        return offset
    }

    private fun storageOr(e: IOException): IOException =
        if (e !is StorageError && e.isNoSpace()) StorageError.InsufficientSpace(0, null, e) else e

    private fun digestPrefix(dir: DestDir, temp: String, length: Long, md5: MessageDigest) {
        dir.openRead(temp).use { input ->
            val buf = ByteArray(BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n == -1) break
                md5.update(buf, 0, n); left -= n
            }
        }
    }

    companion object {
        const val TEMP_SUFFIX = ".stashdpart"
        const val BUFFER = 256 * 1024
        /** Keep this much free beyond the file so the phone doesn't hit zero. */
        const val SPACE_MARGIN = 200L * 1024 * 1024
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
