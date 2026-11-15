package com.abhranil.stashd.ui.screens

import android.app.PendingIntent
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.auth.ConsentResult
import com.abhranil.stashd.data.auth.DriveAuthorizer
import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.data.db.DriveRootDao
import com.abhranil.stashd.data.db.DriveRootEntity
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.data.drive.DriveLink
import com.abhranil.stashd.data.drive.DriveMode
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.LinkParser
import com.abhranil.stashd.ui.components.IconTile
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.components.rememberConsentLauncher
import com.abhranil.stashd.ui.theme.StashdThemeExt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Where to go after a link checks out: folder id, resource key and display name. */
data class OpenFolder(val id: String, val resourceKey: String?, val name: String)

data class HomeUiState(
    val link: String = "",
    val parsed: DriveLink? = null,
    val error: String? = null,
    val checking: Boolean = false,
    val consentIntent: PendingIntent? = null,
    val navigateTo: OpenFolder? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val rootDao: DriveRootDao,
    private val drive: DriveRepository,
    private val authorizer: DriveAuthorizer,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    val recents = rootDao.recent()
    val demoMode: Boolean get() = DriveMode.useFake

    fun onLinkChange(text: String) {
        val parsed = LinkParser.parse(text)
        _state.value = HomeUiState(text, parsed, if (text.isNotBlank() && parsed == null) "That doesn't look like a Google Drive link" else null)
    }

    /** Validates the link against Drive (files.get) before opening, so bad links fail here, not in the browser. */
    fun openLink() {
        val link = _state.value.parsed ?: if (demoMode) DriveLink("demo", null, true) else return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val item = drive.resolve(link)
                if (!item.isFolder) throw DriveError.NotAFolder(item)
                val key = item.resourceKey ?: link.resourceKey
                rootDao.upsert(
                    (rootDao.byFolderId(item.id) ?: DriveRootEntity(item.id, key, item.name, 0, 0, 0, 0))
                        .copy(resourceKey = key, name = item.name, lastOpenedAt = System.currentTimeMillis())
                )
                _state.update { it.copy(checking = false, navigateTo = OpenFolder(item.id, key, item.name)) }
            } catch (e: NeedsConsentException) {
                _state.update { it.copy(checking = false, consentIntent = e.pendingIntent, error = if (e.pendingIntent == null) "Drive access needed. Sign in again." else null) }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, error = linkError(e)) }
            }
        }
    }

    fun openRecent(root: DriveRootEntity) = viewModelScope.launch {
        rootDao.upsert(root.copy(lastOpenedAt = System.currentTimeMillis()))
        _state.update { it.copy(navigateTo = OpenFolder(root.folderId, root.resourceKey, root.name)) }
    }

    fun consumeNavigation() = _state.update { it.copy(navigateTo = null) }
    fun consentLaunched() = _state.update { it.copy(consentIntent = null) }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        when (val r = authorizer.completeConsent(resultCode, data)) {
            ConsentResult.Granted -> openLink()
            ConsentResult.Cancelled -> _state.update { it.copy(error = "Drive access is needed to open folders.") }
            is ConsentResult.Denied -> _state.update { it.copy(error = r.message) }
        }
    }

    companion object {
        fun linkError(e: Exception): String = when (e) {
            is DriveError.NotAFolder -> "That's a file link. Stashd opens folders; paste the folder's link."
            is DriveError.NotFound -> "Folder not found, or this account can't open it. Check sharing, and paste the whole link (incl. ?resourcekey=)."
            is DriveError.Forbidden -> "Access denied: ${e.message}"
            is DriveError -> e.message ?: "Drive error"
            is IOException -> "No connection. Check the network and try again."
            else -> e.message ?: "Couldn't open that link"
        }
    }
}

/** Screen 2 · Home: paste link + recent folders. */
@Composable
fun HomeScreen(
    onOpenFolder: (id: String, resourceKey: String?, name: String) -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val recents by vm.recents.collectAsState(initial = emptyList())
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val launchConsent = rememberConsentLauncher(vm::onConsentResult)

    LaunchedEffect(state.navigateTo) {
        state.navigateTo?.let { vm.consumeNavigation(); onOpenFolder(it.id, it.resourceKey, it.name) }
    }
    LaunchedEffect(state.consentIntent) {
        state.consentIntent?.let { vm.consentLaunched(); launchConsent(it) }
    }

    Scaffold(
        topBar = {
            StashdTopBar("Stashd", actions = {
                IconButton(onClick = onDownloads) { Icon(Icons.Rounded.Downloading, "Downloads") }
                IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, "History") }
            })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large).padding(18.dp),
                ) {
                    Text("Open a Drive folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.demoMode) "Demo mode: sample data, no Google sign-in." else "Paste a shared folder link.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.link,
                        onValueChange = vm::onLinkChange,
                        label = { Text("Folder link") },
                        leadingIcon = { Icon(Icons.Rounded.Link, null) },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = {
                            Text(state.error ?: state.parsed?.let { "Folder id ${it.id.take(10)}\u2026" + if (it.resourceKey != null) " \u00b7 resource key" else "" } ?: "")
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { clipboard.getText()?.text?.let(vm::onLinkChange) }) {
                            Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paste")
                        }
                        Button(
                            onClick = vm::openLink,
                            enabled = !state.checking && (state.parsed != null || vm.demoMode),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(if (state.parsed == null && vm.demoMode) "Open demo folder" else "Open folder")
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Overline("Recent folders")
                Spacer(Modifier.height(8.dp))
            }
            if (recents.isEmpty()) {
                item { Text("Folders you open appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(recents, key = DriveRootEntity::folderId) { root ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openRecent(root) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Rounded.LibraryMusic, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (root.resourceKey != null) "Shared link \u00b7 resource key" else "Shared folder", style = MaterialTheme.typography.labelMedium)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = StashdThemeExt.colors.muted)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
