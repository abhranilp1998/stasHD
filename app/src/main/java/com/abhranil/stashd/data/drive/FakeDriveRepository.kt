package com.abhranil.stashd.data.drive

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Mock data matching the mockups so UI work can start before the Google Cloud project exists. */
@Singleton
class FakeDriveRepository @Inject constructor() : DriveRepository {

    private fun folder(id: String, name: String, files: Int, bytes: Long) = DriveItem(
        id = id, name = name, mimeType = DriveItem.MIME_FOLDER, size = null, md5 = null, resourceKey = null,
        modifiedTime = 0L, canDownload = true, childCount = files, totalBytes = bytes,
    )

    private val root = listOf(
        folder("f1", "Coldplay - Moon Music (2024)", 10, 412_000_000),
        folder("f2", "Linkin Park - From Zero (2024) 24-48", 11, 638_000_000),
        folder("f3", "Taylor Swift - 1989 (Taylor's Version)", 22, 1_020_000_000),
        folder("f4", "Gorillaz - The Mountain 2xCD (2026)", 28, 1_210_000_000),
        folder("f5", "Bryan Adams - How's It Goin' (2026)", 12, 702_000_000),
        folder("f6", "Green Day - 39/Smooth (1990) 24-96", 10, 1_180_000_000),
        folder("f7", "Michael Jackson Greatest Hits", 18, 624_000_000),
        DriveItem("r1", "muzik.rar", "application/vnd.rar", 5_040_000_000, null, null, 0L, true),
    )

    override suspend fun resolve(link: DriveLink): DriveItem =
        folder(link.id, "Music · Lossless", root.size, root.sumOf { it.totalBytes })

    override suspend fun listChildren(folderId: String, resourceKey: String?): List<DriveItem> = root

    override fun walk(rootFolderId: String, resourceKey: String?): Flow<WalkProgress> = flow {
        var files = 0
        var bytes = 0L
        root.forEachIndexed { i, item ->
            delay(80)
            files += if (item.isFolder) item.childCount else 1
            bytes += item.totalBytes
            emit(WalkProgress(i + 1, files, bytes, done = false))
        }
        emit(WalkProgress(root.size, files, bytes, done = true))
    }

    override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload =
        throw NotImplementedError("Real downloads arrive in M3 (OkHttp DriveApi).")
}
