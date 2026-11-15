package com.abhranil.stashd.data.drive

import kotlinx.coroutines.flow.Flow

/**
 * Drive REST v3 access. [RealDriveRepository] (OkHttp) is used when a Web client ID is configured;
 * [FakeDriveRepository] serves sample data otherwise. Every call carries the item's resource key, if any.
 */
interface DriveRepository {
    /** files.get on the pasted link: name, mimeType, resourceKey, canDownload. */
    suspend fun resolve(link: DriveLink): DriveItem

    /** One files.list page of a folder's direct children; pass the previous page's token. */
    suspend fun listPage(folderId: String, resourceKey: String?, pageToken: String? = null): DrivePage

    /** All direct children of a folder (loops [listPage] until there is no nextPageToken). */
    suspend fun listChildren(folderId: String, resourceKey: String?): List<DriveItem> {
        val all = ArrayList<DriveItem>()
        var token: String? = null
        var pages = 0
        do {
            val page = listPage(folderId, resourceKey, token)
            all += page.items
            token = page.nextPageToken
        } while (token != null && ++pages < MAX_PAGES)
        return all
    }

    /** Recursive BFS walk of a folder; caches the tree in Room and emits progress. */
    fun walk(rootFolderId: String, resourceKey: String?): Flow<WalkProgress>

    /** GET files/{id}?alt=media, with `Range: bytes=fromByte-` when fromByte > 0. */
    suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload

    companion object {
        /** 1000 items per page → 200k children before we stop; a guard against a looping token. */
        const val MAX_PAGES = 200
    }
}
