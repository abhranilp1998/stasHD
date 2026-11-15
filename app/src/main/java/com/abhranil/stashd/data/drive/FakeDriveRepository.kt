package com.abhranil.stashd.data.drive

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sample data matching the mockups, used when no Web client ID is configured (debug / pre-Cloud).
 * Has nested folders, a resource-keyed folder, an empty folder, a no-access folder and 2-page listings,
 * so the browser's navigation, states and paging can be exercised without credentials.
 */
@Singleton
class FakeDriveRepository @Inject constructor() : DriveRepository {

    private fun folder(id: String, name: String, files: Int, bytes: Long, rk: String? = null) = DriveItem(
        id = id, name = name, mimeType = DriveItem.MIME_FOLDER, size = null, md5 = null, resourceKey = rk,
        modifiedTime = 0L, canDownload = true, childCount = files, totalBytes = bytes,
    )

    private fun track(parent: String, n: Int, bytes: Long) = DriveItem(
        id = "$parent-t$n", name = "%02d - Track %d.flac".format(n, n), mimeType = "audio/flac",
        size = bytes, md5 = null, resourceKey = null, modifiedTime = 0L, canDownload = true,
    )

    private val rootItems = listOf(
        folder("f1", "Coldplay - Moon Music (2024)", 10, 412_000_000),
        folder("f2", "Linkin Park - From Zero (2024) 24-48", 11, 638_000_000),
        folder("f3", "Taylor Swift - 1989 (Taylor's Version)", 22, 1_020_000_000),
        folder("f4", "Gorillaz - The Mountain 2xCD (2026)", 28, 1_210_000_000),
        folder("f5", "Bryan Adams - How's It Goin' (2026)", 12, 702_000_000),
        folder("f6", "Green Day - 39/Smooth (1990) 24-96", 10, 1_180_000_000, rk = "0-demoResourceKey"),
        folder("f7", "Michael Jackson Greatest Hits", 18, 624_000_000),
        folder("f8", "Empty folder", 0, 0),
        folder("f9", "No access (demo error)", 0, 0),
        DriveItem("r1", "muzik.rar", "application/vnd.rar", 5_040_000_000, null, null, 0L, true),
        DriveItem(
            "s1", "Shortcut to Coldplay", DriveItem.MIME_SHORTCUT, null, null, null, 0L, true,
            shortcut = ShortcutTarget("f1", DriveItem.MIME_FOLDER, null),
        ),
    )

    private fun children(folderId: String): List<DriveItem> = when {
        folderId == "f4" -> listOf(folder("f4-cd1", "CD1", 14, 600_000_000), folder("f4-cd2", "CD2", 14, 610_000_000))
        folderId == "f8" -> emptyList()
        folderId == "f9" -> throw DriveError.NotFound(folderId)
        folderId == "f6" -> (1..10).map { track(folderId, it, 118_000_000) }
        folderId.matches(Regex("f\\d(-cd\\d)?")) -> (1..12).map { track(folderId, it, 40_000_000L + it * 1_000_000L) }
        else -> rootItems
    }

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

    override fun walk(rootFolderId: String, resourceKey: String?): Flow<WalkProgress> = flow {
        var files = 0
        var bytes = 0L
        rootItems.forEachIndexed { i, item ->
            delay(80)
            files += if (item.isFolder) item.childCount else 1
            bytes += item.totalBytes
            emit(WalkProgress(i + 1, files, bytes, done = false))
        }
        emit(WalkProgress(rootItems.size, files, bytes, done = true))
    }

    override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload =
        throw NotImplementedError("Downloader is M2.")
}
