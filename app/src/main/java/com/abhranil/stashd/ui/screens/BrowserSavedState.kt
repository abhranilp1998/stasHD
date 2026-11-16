package com.abhranil.stashd.ui.screens

import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.ShortcutTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the browser must survive process death with: the folder path and the picks. Stored as one JSON string
 * in the SavedStateHandle (small: ids and names). Listings are NOT saved; they're re-fetched (or come from cache).
 */
internal object BrowserSavedState {
    const val KEY = "browser.state.v1"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(stack: List<Crumb>, selected: Map<String, Selection>): String =
        json.encodeToString(Saved.serializer(), Saved(stack.map { it.toSaved() }, selected.values.map { SavedSelection(it.item.toSaved(), it.path.map { c -> c.toSaved() }) }))

    /** Null when absent or unreadable (e.g. written by an older build): the browser then starts at the root. */
    fun decode(s: String?, rootId: String): Pair<List<Crumb>, Map<String, Selection>>? {
        if (s == null) return null
        val saved = runCatching { json.decodeFromString(Saved.serializer(), s) }.getOrNull() ?: return null
        val stack = saved.stack.map { it.toCrumb() }
        if (stack.isEmpty() || stack.first().id != rootId) return null
        val selected = saved.selected.associate { it.item.id to Selection(it.item.toItem(), it.path.map { c -> c.toCrumb() }) }
        return stack to selected
    }

    @Serializable private data class Saved(val stack: List<SavedCrumb>, val selected: List<SavedSelection>)
    @Serializable private data class SavedSelection(val item: SavedItem, val path: List<SavedCrumb>)
    @Serializable private data class SavedCrumb(val id: String, val name: String, val resourceKey: String?, val selectionKey: String, val source: SavedItem?)
    @Serializable private data class SavedItem(
        val id: String, val name: String, val mimeType: String, val size: Long?, val md5: String?, val resourceKey: String?,
        val modifiedTime: Long, val canDownload: Boolean, val childCount: Int, val totalBytes: Long,
        val shortcutId: String? = null, val shortcutMime: String? = null, val shortcutKey: String? = null,
    )

    private fun Crumb.toSaved() = SavedCrumb(id, name, resourceKey, selectionKey, source?.toSaved())
    private fun SavedCrumb.toCrumb() = Crumb(id, name, resourceKey, selectionKey, source?.toItem())
    private fun DriveItem.toSaved() = SavedItem(id, name, mimeType, size, md5, resourceKey, modifiedTime, canDownload, childCount, totalBytes, shortcut?.id, shortcut?.mimeType, shortcut?.resourceKey)
    private fun SavedItem.toItem() = DriveItem(
        id, name, mimeType, size, md5, resourceKey, modifiedTime, canDownload, childCount, totalBytes,
        shortcutId?.let { ShortcutTarget(it, shortcutMime, shortcutKey) },
    )
}
