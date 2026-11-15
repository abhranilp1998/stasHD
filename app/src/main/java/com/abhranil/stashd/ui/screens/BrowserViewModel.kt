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
import java.io.IOException
import javax.inject.Inject

/** A level of the folder path. The resource key travels with it so every listing call can send it. */
data class Crumb(val id: String, val name: String, val resourceKey: String?)

sealed interface FolderContent {
    data class Loading(val itemsSoFar: Int = 0) : FolderContent
    data class Loaded(val items: List<DriveItem>, val fromCache: Boolean = false) : FolderContent
    data class Error(val message: String, val needsConsent: Boolean = false, val consentIntent: PendingIntent? = null) : FolderContent
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
    val coveringFolder: Crumb? get() = stack.firstOrNull { it.id in selected }

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
        val crumb = Crumb(item.id, item.name, item.resourceKey)
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

    private fun load(crumb: Crumb) {
        loadJob?.cancel()
        listed[crumb.id]?.let { setContent(crumb, FolderContent.Loaded(it)); return }
        setContent(crumb, FolderContent.Loading())
        loadJob = viewModelScope.launch {
            try {
                val items = ArrayList<DriveItem>()
                var token: String? = null
                var pages = 0
                do {
                    val page = drive.listPage(crumb.id, crumb.resourceKey, token)
                    items += page.items
                    token = page.nextPageToken
                    if (token != null) setContent(crumb, FolderContent.Loading(items.size))
                } while (token != null && ++pages < DriveRepository.MAX_PAGES)
                listed[crumb.id] = items
                setContent(crumb, FolderContent.Loaded(items))
                runCatching { cache.storeListing(null, crumb.id, crumb.resourceKey, items) }
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
}
