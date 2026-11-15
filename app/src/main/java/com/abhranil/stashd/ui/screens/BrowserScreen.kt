package com.abhranil.stashd.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatBytes
import com.abhranil.stashd.ui.components.IconTile
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.components.PlaceholderNote
import com.abhranil.stashd.ui.components.rememberConsentLauncher
import com.abhranil.stashd.ui.theme.StashdRadii
import com.abhranil.stashd.ui.theme.StashdThemeExt

/**
 * Screen 3 · Folder browser, with screen 4 (destination and options) as a bottom sheet.
 * Tap a folder row to enter it; the checkbox selects. Back / breadcrumb climbs. Selection is kept across folders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack: () -> Unit, onStarted: () -> Unit, vm: BrowserViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    val launchConsent = rememberConsentLauncher(vm::onConsentResult)

    BackHandler(enabled = state.stack.size > 1) { vm.back() }

    Scaffold(
        topBar = { StashdTopBar(state.current.name, onBack = { if (!vm.back()) onBack() }) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (state.selected.isNotEmpty()) SelectionBar(state, onClear = vm::clearSelection, onDownload = { showSheet = true })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Breadcrumbs(state.stack, onCrumb = vm::goTo)
            state.coveringFolder?.let {
                Text(
                    "Everything here is included via \u201c${it.name}\u201d.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            when (val c = state.content) {
                is FolderContent.Loading -> CenterState {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(if (c.itemsSoFar > 0) "Loading\u2026 ${c.itemsSoFar} items so far" else "Loading folder\u2026", style = MaterialTheme.typography.bodyMedium)
                }
                is FolderContent.Error -> CenterState {
                    Icon(if (c.needsConsent) Icons.Rounded.Lock else Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(c.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (c.needsConsent) Button(onClick = { c.consentIntent?.let(launchConsent) ?: vm.requestAccess(launchConsent) }) { Text("Grant Drive access") }
                    else Button(onClick = vm::retry) { Text("Retry") }
                }
                is FolderContent.Loaded -> if (c.items.isEmpty()) CenterState {
                    Icon(Icons.Rounded.FolderOff, null, tint = StashdThemeExt.colors.muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("This folder is empty.", style = MaterialTheme.typography.bodyMedium)
                } else FolderList(c, state, vm)
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
                summary = selectionSummary(state),
                count = state.selected.size,
                onCancel = { showSheet = false },
                onStart = { showSheet = false; onStarted() },
            )
        }
    }
}

private fun selectionSummary(s: BrowserUiState): String = buildList {
    if (s.selectedFolders > 0) add("${s.selectedFolders} folder${if (s.selectedFolders == 1) "" else "s"}")
    if (s.selectedFiles > 0) add("${s.selectedFiles} file${if (s.selectedFiles == 1) "" else "s"} \u00b7 ${formatBytes(s.selectedFileBytes)}")
}.joinToString(" + ")

@Composable
private fun Breadcrumbs(stack: List<Crumb>, onCrumb: (Int) -> Unit) {
    if (stack.size < 2) return
    val listState = rememberLazyListState()
    LaunchedEffect(stack.size) { listState.animateScrollToItem(stack.lastIndex) }
    LazyRow(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(stack, key = { i, c -> "$i-${c.id}" }) { i, crumb ->
            if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = StashdThemeExt.colors.muted, modifier = Modifier.size(18.dp))
            val last = i == stack.lastIndex
            TextButton(onClick = { onCrumb(i) }, enabled = !last) {
                Text(
                    crumb.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(IntrinsicCrumbWidth),
                )
            }
        }
    }
}

private val IntrinsicCrumbWidth = 140.dp

@Composable
private fun CenterState(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun FolderList(c: FolderContent.Loaded, state: BrowserUiState, vm: BrowserViewModel) {
    val covered = state.coveringFolder != null
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (c.fromCache) item { PlaceholderNote("Offline: showing the last saved list for this folder.", Modifier.padding(8.dp)) }
        items(c.items, key = DriveItem::id) { item ->
            val on = covered || item.id in state.selected
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.background, MaterialTheme.shapes.medium)
                    .clickable { if (item.isFolder) vm.open(item) else vm.toggle(item) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, enabled = !covered, onCheckedChange = { vm.toggle(item) })
                when {
                    item.isFolder -> IconTile(Icons.Rounded.Folder, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer, size = 40.dp)
                    item.mimeType.startsWith("audio/") -> IconTile(Icons.Rounded.AudioFile, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 40.dp)
                    item.name.endsWith(".rar") || item.name.endsWith(".zip") || item.name.endsWith(".7z") ->
                        IconTile(Icons.Rounded.FolderZip, StashdThemeExt.colors.warning, StashdThemeExt.colors.warningContainer, size = 40.dp)
                    else -> IconTile(Icons.AutoMirrored.Rounded.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, size = 40.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            item.isShortcut && item.isFolder -> "Shortcut to folder"
                            item.isShortcut -> "Shortcut"
                            item.isFolder -> "Folder"
                            item.isGoogleDoc -> "Google Docs file (export in a later version)"
                            !item.canDownload -> "Owner disabled downloads"
                            else -> formatBytes(item.size ?: 0L)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else StashdThemeExt.colors.muted,
                    )
                }
                if (item.isFolder) Icon(Icons.Rounded.ChevronRight, "Open", tint = StashdThemeExt.colors.muted)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SelectionBar(state: BrowserUiState, onClear: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.large)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${state.selected.size} selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(selectionSummary(state), style = MaterialTheme.typography.titleSmall, maxLines = 2)
        }
        TextButton(onClick = onClear) { Text("Clear") }
        Button(onClick = onDownload, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Download")
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
        PlaceholderNote("Downloading the selection lands in M2. Start opens the Downloads screen (demo).")
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
