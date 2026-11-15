package com.abhranil.stashd.data.drive

import kotlinx.coroutines.flow.Flow

/**
 * Drive REST v3 access used by the app. M0 ships [FakeDriveRepository]; the OkHttp implementation
 * (files.get / files.list BFS / alt=media with Range + X-Goog-Drive-Resource-Keys) lands in M1–M3.
 */
interface DriveRepository {
    /** files.get on the pasted link: name, mimeType, resourceKey, canDownload. */
    suspend fun resolve(link: DriveLink): DriveItem

    /** Direct children of a folder (one files.list page loop). */
    suspend fun listChildren(folderId: String, resourceKey: String?): List<DriveItem>

    /** Recursive BFS walk of a folder; caches the tree in Room and emits progress. */
    fun walk(rootFolderId: String, resourceKey: String?): Flow<WalkProgress>

    /** GET files/{id}?alt=media, with `Range: bytes=fromByte-` when fromByte > 0. */
    suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload
}
