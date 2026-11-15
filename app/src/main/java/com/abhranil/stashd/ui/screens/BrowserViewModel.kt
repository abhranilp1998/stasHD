package com.abhranil.stashd.ui.screens

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.auth.AuthorizationOutcome
import com.abhranil.stashd.data.auth.ConsentResult
import com.abhranil.stashd.data.auth.DriveAuthorizer
import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.FolderCache
import com.abhranil.stashd.data.drive.FolderListing
import com.abhranil.stashd.data.settings.Settings
import com.abhranil.stashd.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.abhranil.stashd.data.db.ActiveBatchExists
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.core.NewBatch
import com.abhranil.stashd.download.core.PickedItem
import java.io.IOException
import javax.inject.Inject

/**
 * A level of the folder path. [id]/[resourceKey] are what gets listed (a shortcut's TARGET);
 * [selectionKey] is the item's own id (the shortcut's id), used for selection.
 */
data class Crumb(
    val id: String,
    val name: String,
    val resourceKey: String?,
    val selectionKey: String = id,
    /** The list item this level was opened from (null for the pasted root). */
    val source: DriveItem? = null,
) {
    /** This folder as a selectable item: the item it was opened from, or the linked root itself. */
    fun asItem(): DriveItem = source ?: DriveItem(
        id = id, name = name, mimeType = DriveItem.MIME_FOLDER, size = null, md5 = null,
        resourceKey = resourceKey, modifiedTime = 0L, canDownload = true,
    )
}

sealed interface FolderContent {
    data class Loading(val itemsSoFar: Int = 0) : FolderContent
    data class Loaded(val items: List<DriveItem>, val fromCache: Boolean = false, val incomplete: Incomplete? = null) : FolderContent
    data class Error(val message: String, val needsConsent: Boolean = false, val consentIntent: PendingIntent? = null) : FolderContent
}

/** Why a listing is not complete. Shown to the user; never cached as complete. */
data class Incomplete(val reason: Reason, val nextPageToken: String?) {
    enum class Reason {
        /** Stopped after [DriveRepository.MAX_PAGES] pages with a page token left; "Continue" resumes from it. */
        PAGE_LIMIT,
        /** Drive set incompleteSearch=true; "Retry" lists again. */
        DRIVE_INCOMPLETE_SEARCH,
    }
}

/** A picked file or folder, remembered with where it was picked so it survives navigating elsewhere. */
data class Selection(val item: DriveItem, val path: List<Crumb>)

data class BrowserUiState(
    val stack: List<Crumb>,
    val content: FolderContent = FolderContent.Loading(),
    val selected: Map<String, Selection> = emptyMap(),
) {
    val current: Crumb get() = stack.last()
    val items: List<DriveItem> get() = (content as? FolderContent.Loaded)?.items.orEmpty()

    /** The selected ancestor (or current folder) that already includes everything shown here, if any. */
    val coveringFolder: Crumb? get() = stack.firstOrNull { it.selectionKey in selected }

    /** "Download this folder" is on for the folder being shown (root = the whole linked folder). */
    val currentFolderSelected: Boolean get() = current.selectionKey in selected
    /** An ancestor is selected, so the current folder is already included and can't be toggled on its own. */
    val currentFolderIncludedViaAncestor: Crumb? get() = stack.dropLast(1).firstOrNull { it.selectionKey in selected }

    val selectedFolders: Int get() = selected.values.count { it.item.isFolder }
    val selectedFiles: Int get() = selected.values.count { !it.item.isFolder }
    /** Known bytes: files only. Folder totals are computed when the batch is expanded (M2). */
    val selectedFileBytes: Long get() = selected.values.filter { !it.item.isFolder }.sumOf { it.item.size ?: 0L }
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val drive: DriveRepository,
    private val cache: FolderCache,
    private val authorizer: DriveAuthorizer,
    private val settingsStore: SettingsStore?,
    private val controller: DownloadController? = null,
) : ViewModel() {

    private val root = Crumb(
        id = requireNotNull(savedStateHandle.get<String>("folderId")) { "BrowserRoute.folderId missing" },
        name = savedStateHandle.get<String>("name") ?: "Drive folder",
        resourceKey = savedStateHandle.get<String>("resourceKey"),
    )

    private val _state = MutableStateFlow(BrowserUiState(stack = listOf(root)))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = (settingsStore?.settings ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Listings fetched this session, so going back is instant and doesn't re-hit Drive. */
    private val listed = HashMap<String, List<DriveItem>>()
    private var loadJob: Job? = null

    init { load(root) }

    fun open(item: DriveItem) {
        if (!item.isFolder) { toggle(item); return }
        val crumb = Crumb(item.targetId, item.name, item.targetResourceKey, selectionKey = item.id, source = item)
        _state.update { it.copy(stack = it.stack + crumb) }
        load(crumb)
    }

    /** Returns false when already at the pasted root (the screen should then pop itself). */
    fun back(): Boolean {
        val s = _state.value
        if (s.stack.size <= 1) return false
        goTo(s.stack.lastIndex - 1)
        return true
    }

    fun goTo(index: Int) {
        val s = _state.value
        if (index !in s.stack.indices || index == s.stack.lastIndex) return
        val stack = s.stack.subList(0, index + 1).toList()
        _state.update { it.copy(stack = stack) }
        load(stack.last())
    }

    fun toggle(item: DriveItem) {
        val s = _state.value
        if (s.coveringFolder != null) return // already included via a selected parent folder
        _state.update {
            val sel = if (item.id in it.selected) it.selected - item.id else it.selected + (item.id to Selection(item, it.stack))
            it.copy(selected = sel)
        }
    }

    /**
     * "Download this folder" for the folder on screen (the linked root at the top level). Uses the same
     * selection model: a selected folder includes everything under it, so items picked inside it are folded in.
     */
    fun toggleCurrentFolder() {
        val s = _state.value
        if (s.currentFolderIncludedViaAncestor != null) return
        val cur = s.current
        _state.update {
            if (cur.selectionKey in it.selected) it.copy(selected = it.selected - cur.selectionKey)
            else {
                val outside = it.selected.filterValues { sel -> sel.path.none { c -> c.selectionKey == cur.selectionKey } }
                it.copy(selected = outside + (cur.selectionKey to Selection(cur.asItem(), it.stack.dropLast(1))))
            }
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptyMap()) }

    fun retry() { listed.remove(_state.value.current.id); load(_state.value.current) }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        when (val r = authorizer.completeConsent(resultCode, data)) {
            ConsentResult.Granted -> retry()
            ConsentResult.Cancelled -> setError("Drive access is needed to list this folder.", needsConsent = true)
            is ConsentResult.Denied -> setError(r.message, needsConsent = true)
        }
    }

    /** "Grant access" with no PendingIntent in hand (e.g. after a restart): ask again. */
    fun requestAccess(launch: (PendingIntent) -> Unit) = viewModelScope.launch {
        when (val o = authorizer.authorize()) {
            AuthorizationOutcome.Granted -> retry()
            is AuthorizationOutcome.NeedsConsent -> launch(o.pendingIntent)
            is AuthorizationOutcome.Failed -> setError(o.message, needsConsent = true)
        }
    }

    /** Resume an incomplete listing from its page token (keeps what was already fetched). */
    fun continueListing() {
        val s = _state.value
        val c = s.content as? FolderContent.Loaded ?: return
        val token = c.incomplete?.nextPageToken ?: return retry()
        load(s.current, resume = c.items to token)
    }

    private fun load(crumb: Crumb, resume: Pair<List<DriveItem>, String>? = null) {
        loadJob?.cancel()
        if (resume == null) listed[crumb.id]?.let { setContent(crumb, FolderContent.Loaded(it)); return }
        setContent(crumb, FolderContent.Loading(resume?.first?.size ?: 0))
        loadJob = viewModelScope.launch {
            try {
                val items = ArrayList<DriveItem>(resume?.first.orEmpty())
                var token: String? = resume?.second
                var incompleteSearch = false
                var pages = 0
                do {
                    val page = drive.listPage(crumb.id, crumb.resourceKey, token)
                    items += page.items
                    incompleteSearch = incompleteSearch || page.incompleteSearch
                    token = page.nextPageToken
                    if (token != null) setContent(crumb, FolderContent.Loading(items.size))
                } while (token != null && ++pages < DriveRepository.MAX_PAGES)
                val listing = FolderListing(items, token, incompleteSearch)
                if (listing.isComplete) {
                    listed[crumb.id] = items
                    setContent(crumb, FolderContent.Loaded(items))
                    runCatching { cache.storeListing(null, crumb.id, crumb.resourceKey, items) }
                } else {
                    // Visible, not cached, not remembered as complete.
                    val reason = if (token != null) Incomplete.Reason.PAGE_LIMIT else Incomplete.Reason.DRIVE_INCOMPLETE_SEARCH
                    setContent(crumb, FolderContent.Loaded(items, incomplete = Incomplete(reason, token)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConsentException) {
                setContent(crumb, FolderContent.Error("Stashd needs your OK to read Drive (it expires every 7 days while the app is in testing).", true, e.pendingIntent))
            } catch (e: Exception) {
                val cached = runCatching { cache.cachedChildren(crumb.id) }.getOrNull()
                if (cached != null && e is IOException) setContent(crumb, FolderContent.Loaded(cached, fromCache = true))
                else setContent(crumb, FolderContent.Error(messageFor(e)))
            }
        }
    }

    private fun setContent(crumb: Crumb, content: FolderContent) =
        _state.update { if (it.current.id == crumb.id) it.copy(content = content) else it }

    private fun setError(message: String, needsConsent: Boolean) =
        _state.update { it.copy(content = FolderContent.Error(message, needsConsent)) }

    private fun messageFor(e: Exception): String = when (e) {
        is DriveError.NotFound -> "This folder wasn't found, or this Google account can't open it. Check the link's sharing (and that the full link, incl. resourcekey, was pasted)."
        is DriveError.Forbidden -> "Access denied: ${e.message}"
        is DriveError -> e.message ?: "Drive error"
        is IOException -> "No connection. Check the network and retry."
        else -> e.message ?: "Something went wrong"
    }

    fun setDestination(uri: String) = viewModelScope.launch { settingsStore?.setDestination(uri) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { settingsStore?.setWifiOnly(v) }
    fun setKeepStructure(v: Boolean) = viewModelScope.launch { settingsStore?.setKeepStructure(v) }
    fun setSkipExisting(v: Boolean) = viewModelScope.launch { settingsStore?.setSkipExisting(v) }

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    /** Selections as batch picks: parent names from the linked root (excluded) down to where each was picked. */
    fun picks(): List<PickedItem> = _state.value.selected.values.map { sel -> PickedItem(sel.item, sel.path.drop(1).map { it.name }) }

    /** "Start download" (covers "Download this folder" too: it's just a selection). One active batch at a time. */
    fun startDownload(onStarted: () -> Unit) {
        val c = controller ?: return
        val s = settings.value
        val dest = s.lastDestTreeUri ?: run { _startError.value = "Choose a folder to save to first."; return }
        val picks = picks().ifEmpty { return }
        viewModelScope.launch {
            try {
                c.create(NewBatch(root.id, root.name, picks, dest, s.keepStructure, s.wifiOnly, s.skipExisting))
                _startError.value = null
                clearSelection()
                onStarted()
            } catch (e: ActiveBatchExists) {
                _startError.value = "Another download is still active. Finish or cancel it on the Downloads screen first."
            }
        }
    }

    fun clearStartError() { _startError.value = null }
}
