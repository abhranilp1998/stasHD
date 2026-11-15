package com.abhranil.stashd.data.drive

import com.abhranil.stashd.data.db.DriveNodeDao
import com.abhranil.stashd.data.db.DriveNodeEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Folder-listing cache seen by the UI layer (interface so view models can be tested without Room). */
interface FolderCache {
    suspend fun storeListing(folder: DriveItem?, folderId: String, folderResourceKey: String?, children: List<DriveItem>, now: Long = System.currentTimeMillis())
    /** Cached direct children, or null when this folder was never listed completely. */
    suspend fun cachedChildren(folderId: String): List<DriveItem>?
}

/** Write-through cache of folder listings. Identity = Drive file id; structure = parent→child edges. */
@Singleton
class DriveCache @Inject constructor(private val dao: DriveNodeDao) : FolderCache {

    override suspend fun storeListing(folder: DriveItem?, folderId: String, folderResourceKey: String?, children: List<DriveItem>, now: Long) {
        dao.replaceChildren(folderId, folderResourceKey, folder?.toEntity(now), children.map { it.toEntity(now) }, now)
    }

    override suspend fun cachedChildren(folderId: String): List<DriveItem>? {
        dao.listing(folderId) ?: return null
        return dao.childrenOnce(folderId).map { it.toItem() }
    }

    companion object {
        fun DriveItem.toEntity(now: Long) = DriveNodeEntity(
            fileId = id, name = name, mimeType = mimeType, size = size, md5 = md5, resourceKey = resourceKey,
            isFolder = isFolder, isGoogleDoc = isGoogleDoc, canDownload = canDownload, modifiedTime = modifiedTime, cachedAt = now,
        )

        fun DriveNodeEntity.toItem() = DriveItem(
            id = fileId, name = name, mimeType = mimeType, size = size, md5 = md5, resourceKey = resourceKey,
            modifiedTime = modifiedTime, canDownload = canDownload,
        )
    }
}
