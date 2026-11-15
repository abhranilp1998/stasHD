package com.abhranil.stashd.data.drive

/**
 * Drive REST v3 access. [RealDriveRepository] (OkHttp) is used when a Web client ID is configured;
 * [FakeDriveRepository] serves sample data otherwise. Every call carries the item's resource key, if any.
 */
interface DriveRepository {
    /** files.get on the pasted link: name, mimeType, resourceKey, canDownload. */
    suspend fun resolve(link: DriveLink): DriveItem

    /** One files.list page of a folder's direct children; pass the previous page's token. */
    suspend fun listPage(folderId: String, resourceKey: String?, pageToken: String? = null): DrivePage

    /**
     * Direct children, following page tokens for up to [maxPages] pages starting at [pageToken].
     * Never silently truncates: if a token remains or Drive flags incompleteSearch, the result says so.
     */
    suspend fun listChildren(folderId: String, resourceKey: String?, pageToken: String? = null, maxPages: Int = MAX_PAGES): FolderListing {
        val all = ArrayList<DriveItem>()
        var token = pageToken
        var incomplete = false
        var pages = 0
        do {
            val page = listPage(folderId, resourceKey, token)
            all += page.items
            incomplete = incomplete || page.incompleteSearch
            token = page.nextPageToken
        } while (token != null && ++pages < maxPages)
        return FolderListing(all, token, incomplete)
    }

    /** Fresh metadata for one file (size, md5Checksum, modifiedTime, canDownload); does not follow shortcuts. */
    suspend fun fileMetadata(fileId: String, resourceKey: String?): DriveItem

    /**
     * GET files/{id}?alt=media, with `Range: bytes=fromByte-` when fromByte > 0. A 206 is validated against
     * Content-Range ([DriveError.RangeMismatch]); a 200 comes back with offset 0; 416 is [DriveError.RangeNotSatisfiable].
     */
    suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload

    companion object {
        /** Pages fetched per run (1000 items each). Hitting it yields an explicit incomplete listing the user can continue. */
        const val MAX_PAGES = 200
    }
}
