package com.abhranil.stashd.data.drive

import kotlinx.coroutines.delay
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sample data used when no Web client ID is configured (demo mode / pre-Cloud).
 * Has nested folders, a resource-keyed folder, an empty folder, a no-access folder, a shortcut, a Google Doc
 * and 2-page listings. Files are downloadable: deterministic bytes, Range-aware, throttled to ~4 MB/s, with
 * small real sizes (a few MB) so a demo batch doesn't fill the phone.
 */
@Singleton
class FakeDriveRepository @Inject constructor() : DriveRepository {

    private fun folder(id: String, name: String, files: Int, bytes: Long, rk: String? = null) = DriveItem(
        id = id, name = name, mimeType = DriveItem.MIME_FOLDER, size = null, md5 = null, resourceKey = rk,
        modifiedTime = 0L, canDownload = true, childCount = files, totalBytes = bytes,
    )

    private fun track(parent: String, n: Int, bytes: Long) = DriveItem(
        id = "$parent-t$n", name = "%02d - Track %d.flac".format(n, n), mimeType = "audio/flac",
        size = bytes, md5 = null, resourceKey = null, modifiedTime = 1_700_000_000_000L, canDownload = true,
    )

    private val rootItems = listOf(
        folder("f1", "Coldplay - Moon Music (2024)", 10, 40_000_000),
        folder("f2", "Linkin Park - From Zero (2024) 24-48", 11, 44_000_000),
        folder("f3", "Taylor Swift - 1989 (Taylor's Version)", 22, 88_000_000),
        folder("f4", "Gorillaz - The Mountain 2xCD (2026)", 28, 112_000_000),
        folder("f5", "Bryan Adams - How's It Goin' (2026)", 12, 48_000_000),
        folder("f6", "Green Day - 39/Smooth (1990) 24-96", 10, 50_000_000, rk = "0-demoResourceKey"),
        folder("f7", "Michael Jackson Greatest Hits", 18, 72_000_000),
        folder("f8", "Empty folder", 0, 0),
        folder("f9", "No access (demo error)", 0, 0),
        DriveItem("r1", "muzik.rar", "application/vnd.rar", 24_000_000, null, null, 1_700_000_000_000L, true),
        DriveItem("d1", "Tracklist notes", "application/vnd.google-apps.document", null, null, null, 0L, true),
        DriveItem(
            "s1", "Shortcut to Coldplay", DriveItem.MIME_SHORTCUT, null, null, null, 0L, true,
            shortcut = ShortcutTarget("f1", DriveItem.MIME_FOLDER, null),
        ),
    )

    private fun children(folderId: String): List<DriveItem> = when {
        folderId == "f4" -> listOf(folder("f4-cd1", "CD1", 14, 56_000_000), folder("f4-cd2", "CD2", 14, 56_000_000))
        folderId == "f8" -> emptyList()
        folderId == "f9" -> throw DriveError.NotFound(folderId)
        folderId == "f6" -> (1..10).map { track(folderId, it, 5_000_000) }
        folderId.matches(Regex("f\\d(-cd\\d)?")) -> (1..12).map { track(folderId, it, 3_000_000L + it * 100_000L) }
        else -> rootItems
    }

    private fun find(fileId: String): DriveItem? =
        rootItems.firstOrNull { it.id == fileId } ?: Regex("(f\\d(?:-cd\\d)?)-t\\d+").matchEntire(fileId)
            ?.let { m -> runCatching { children(m.groupValues[1]) }.getOrNull()?.firstOrNull { it.id == fileId } }

    override suspend fun resolve(link: DriveLink): DriveItem {
        delay(250)
        return folder(link.id, "Music · Lossless", rootItems.size, rootItems.sumOf { it.totalBytes }, link.resourceKey)
    }

    /** Lists split into 2 pages so the paging loop runs. */
    override suspend fun listPage(folderId: String, resourceKey: String?, pageToken: String?): DrivePage {
        delay(350)
        val all = children(folderId)
        val half = (all.size + 1) / 2
        return if (pageToken == null && all.size > 1) DrivePage(all.take(half), "page2")
        else DrivePage(if (pageToken == null) all else all.drop(half), null)
    }

    override suspend fun fileMetadata(fileId: String, resourceKey: String?): DriveItem =
        find(fileId) ?: throw DriveError.NotFound(fileId)

    override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload {
        val item = find(fileId) ?: throw DriveError.NotFound(fileId)
        if (item.isGoogleDoc) throw DriveError.NotDownloadable(fileId)
        val size = item.size ?: 0L
        if (fromByte > size) throw DriveError.RangeNotSatisfiable(size)
        val src = PatternSource(fileId.hashCode(), fromByte, size).buffer()
        return object : DriveDownload {
            override val offset = fromByte
            override val totalSize = size
            override val source: BufferedSource = src
            override fun close() = src.close()
        }
    }

    /** byte(i) = (i * 31 + seed) & 0xFF, throttled. Same content on every request, so resume is byte-exact. */
    private class PatternSource(private val seed: Int, private var pos: Long, private val end: Long) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pos >= end) return -1
            val n = minOf(byteCount, 64 * 1024L, end - pos).toInt()
            val chunk = ByteArray(n) { i -> (((pos + i) * 31 + seed) and 0xFF).toByte() }
            Thread.sleep(16) // ~4 MB/s
            sink.write(chunk)
            pos += n
            return n.toLong()
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }
}
