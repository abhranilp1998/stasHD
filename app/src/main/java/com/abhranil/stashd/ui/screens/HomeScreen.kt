package com.abhranil.stashd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.abhranil.stashd.data.db.DriveRootDao
import com.abhranil.stashd.data.db.DriveRootEntity
import com.abhranil.stashd.data.drive.DriveLink
import com.abhranil.stashd.data.drive.LinkParser
import com.abhranil.stashd.ui.components.IconTile
import com.abhranil.stashd.ui.components.Overline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class HomeUiState(val link: String = "", val parsed: DriveLink? = null, val error: String? = null)

@HiltViewModel
class HomeViewModel @Inject constructor(rootDao: DriveRootDao) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    val recents = rootDao.recent()

    fun onLinkChange(text: String) {
        val parsed = LinkParser.parse(text)
        _state.value = HomeUiState(text, parsed, if (text.isNotBlank() && parsed == null) "That doesn't look like a Drive folder link" else null)
    }
}

/** Screen 2 · Home: paste link + recent folders. */
@Composable
fun HomeScreen(
    onOpenFolder: (String, String?) -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val recents by vm.recents.collectAsState(initial = emptyList())
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

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
                    Text("Paste a shared folder link.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.link,
                        onValueChange = vm::onLinkChange,
                        label = { Text("Folder link") },
                        leadingIcon = { Icon(Icons.Rounded.Link, null) },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = { Text(state.error ?: state.parsed?.let { "Folder id ${it.id.take(10)}…" } ?: "") },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { clipboard.getText()?.text?.let(vm::onLinkChange) }) {
                            Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paste")
                        }
                        Button(
                            onClick = { state.parsed?.let { onOpenFolder(it.id, it.resourceKey) } ?: onOpenFolder("demo", null) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (state.parsed != null) "Open folder" else "Open demo folder") }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Overline("Recent folders")
                Spacer(Modifier.height(8.dp))
            }
            if (recents.isEmpty()) {
                item { Text("Folders you open appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(recents, key = DriveRootEntity::id) { root ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.LibraryMusic, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.name, style = MaterialTheme.typography.titleSmall)
                        Text("${root.folderCount} folders · ${root.fileCount} files", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
