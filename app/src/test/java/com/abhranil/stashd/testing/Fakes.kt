package com.abhranil.stashd.testing

import android.content.Intent
import com.abhranil.stashd.data.auth.AuthorizationOutcome
import com.abhranil.stashd.data.auth.ConsentResult
import com.abhranil.stashd.data.auth.DriveAuthorizer
import com.abhranil.stashd.data.drive.DriveDownload
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveLink
import com.abhranil.stashd.data.drive.DrivePage
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.FolderCache

fun folder(id: String, rk: String? = null) = DriveItem(id, "Folder $id", DriveItem.MIME_FOLDER, null, null, rk, 0L, true)
fun file(id: String, size: Long = 100) = DriveItem(id, "$id.flac", "audio/flac", size, null, null, 0L, true)

/** Scripted Drive: folderId -> pages (or an exception). Records every listPage call. */
class ScriptedDrive : DriveRepository {
    data class Call(val folderId: String, val resourceKey: String?, val pageToken: String?)
    val calls = mutableListOf<Call>()
    val pages = HashMap<String, List<List<DriveItem>>>()
    val failures = HashMap<String, Exception>()
    /** Thrown once each, in order, before the folder lists normally (429/5xx bursts). */
    val transient = HashMap<String, MutableList<Exception>>()
    /** folderId -> page indexes that come back with incompleteSearch=true. */
    val incompletePages = HashMap<String, Set<Int>>()
    var resolved: suspend (DriveLink) -> DriveItem = { folder(it.id, it.resourceKey) }

    override suspend fun resolve(link: DriveLink) = resolved(link)

    override suspend fun listPage(folderId: String, resourceKey: String?, pageToken: String?): DrivePage {
        calls += Call(folderId, resourceKey, pageToken)
        failures[folderId]?.let { throw it }
        transient[folderId]?.removeFirstOrNull()?.let { throw it }
        val p = pages[folderId] ?: listOf(emptyList())
        val i = pageToken?.removePrefix("p")?.toInt() ?: 0
        return DrivePage(p[i], if (i + 1 < p.size) "p${i + 1}" else null, i in incompletePages[folderId].orEmpty())
    }

    val metadata = HashMap<String, DriveItem>()
    val metadataCalls = mutableListOf<Pair<String, String?>>()
    override suspend fun fileMetadata(fileId: String, resourceKey: String?): DriveItem {
        metadataCalls += fileId to resourceKey
        return metadata[fileId] ?: throw com.abhranil.stashd.data.drive.DriveError.NotFound(fileId)
    }
    override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload = throw NotImplementedError()
}

class MemoryFolderCache : FolderCache {
    val stored = HashMap<String, Pair<String?, List<DriveItem>>>()
    override suspend fun storeListing(folder: DriveItem?, folderId: String, folderResourceKey: String?, children: List<DriveItem>, now: Long) {
        stored[folderId] = folderResourceKey to children
    }
    override suspend fun cachedChildren(folderId: String) = stored[folderId]?.second
}

class FakeAuthorizer(var outcome: AuthorizationOutcome = AuthorizationOutcome.Granted, var consent: ConsentResult = ConsentResult.Granted) : DriveAuthorizer {
    override suspend fun authorize() = outcome
    override fun completeConsent(resultCode: Int, data: Intent?) = consent
    override suspend fun onAccountChanged(newEmail: String?) = Unit
    override suspend fun forget() = Unit
}
