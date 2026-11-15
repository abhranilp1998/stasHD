package com.abhranil.stashd.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.settings.Settings
import com.abhranil.stashd.data.settings.SettingsStore
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatBytes
import com.abhranil.stashd.ui.components.IconTile
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.components.PlaceholderNote
import com.abhranil.stashd.ui.theme.StashdRadii
import com.abhranil.stashd.ui.theme.StashdThemeExt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserUiState(val items: List<DriveItem> = emptyList(), val selected: Set<String> = emptySet()) {
    val selectedBytes: Long get() = items.filter { it.id in selected }.sumOf { it.totalBytes }
    val selectedFiles: Int get() = items.filter { it.id in selected }.sumOf { if (it.isFolder) it.childCount else 1 }
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val drive: DriveRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()
    val settings: StateFlow<Settings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    fun load(folderId: String) = viewModelScope.launch {
        val items = drive.listChildren(folderId, null)
        _state.value = BrowserUiState(items, items.map { it.id }.toSet() - setOf("f5", "f7"))
    }

    fun toggle(id: String) {
        val s = _state.value
        _state.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun setDestination(uri: String) = viewModelScope.launch { settingsStore.setDestination(uri) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { settingsStore.setWifiOnly(v) }
    fun setKeepStructure(v: Boolean) = viewModelScope.launch { settingsStore.setKeepStructure(v) }
    fun setSkipExisting(v: Boolean) = viewModelScope.launch { settingsStore.setSkipExisting(v) }
}

/** Screen 3 · Folder browser, with screen 4 (destination and options) as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(folderId: String, onBack: () -> Unit, onStarted: () -> Unit, vm: BrowserViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    LaunchedEffect(folderId) { vm.load(folderId) }

    Scaffold(
        topBar = { StashdTopBar("Music · Lossless", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Row(
                Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.large)
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${state.selected.size} of ${state.items.size} selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${formatBytes(state.selectedBytes)} · ${state.selectedFiles} files", style = MaterialTheme.typography.titleLarge)
                }
                Button(onClick = { showSheet = true }, enabled = state.selected.isNotEmpty(), modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Download")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp)) {
            items(state.items, key = DriveItem::id) { item ->
                val on = item.id in state.selected
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.background, MaterialTheme.shapes.medium)
                        .clickable { vm.toggle(item.id) }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = { vm.toggle(item.id) })
                    if (item.isFolder) IconTile(Icons.Rounded.Folder, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer, size = 40.dp)
                    else IconTile(Icons.Rounded.FolderZip, StashdThemeExt.colors.warning, StashdThemeExt.colors.warningContainer, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row {
                            Text(if (item.isFolder) "${item.childCount} files" else "1 file", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            Text(formatBytes(item.totalBytes), style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onSurface else StashdThemeExt.colors.muted)
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = StashdRadii.sheetTop,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DestinationSheet(
                vm = vm,
                summary = "${state.selectedFiles} files · ${formatBytes(state.selectedBytes)}",
                count = state.selected.size,
                onCancel = { showSheet = false },
                onStart = { showSheet = false; onStarted() },
            )
        }
    }
}

/** Screen 4 · Destination and options. */
@Composable
private fun DestinationSheet(vm: BrowserViewModel, summary: String, count: Int, onCancel: () -> Unit, onStart: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.setDestination(uri.toString())
        }
    }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Download $count items", style = MaterialTheme.typography.titleLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Overline("Save to")
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, StashdRadii.listCard).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (settings.lastDestTreeUri == null) "No folder chosen" else "Chosen folder", style = MaterialTheme.typography.titleSmall)
                Text(settings.lastDestTreeUri?.let { Uri.decode(it).substringAfterLast(':') } ?: "Pick e.g. Music/Drive", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { picker.launch(null) }) { Text("Change") }
        }
        Overline("Options")
        OptionRow("Keep folder structure", "Each album lands in its own folder", settings.keepStructure, vm::setKeepStructure)
        OptionRow("Wi-Fi only", "Pause on mobile data, resume on Wi-Fi", settings.wifiOnly, vm::setWifiOnly)
        OptionRow("Skip files already on phone", "Matched by name and size", settings.skipExisting, vm::setSkipExisting)
        PlaceholderNote("M0: starting a real batch lands in M3. Start opens the Downloads screen.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start download") }
        }
    }
}

@Composable
private fun OptionRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
