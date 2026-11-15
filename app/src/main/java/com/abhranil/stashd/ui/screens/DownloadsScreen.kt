package com.abhranil.stashd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.db.ActiveBatchExists
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.BatchTotals
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.FileTaskEntity
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.progress.ProgressBus
import com.abhranil.stashd.download.progress.ProgressSnapshot
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatBytes
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatEta
import com.abhranil.stashd.ui.components.GradientProgressBar
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.theme.StashdRadii
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Screen 5 · the current (or most recent) batch, from Room, with live bytes/speed from [ProgressBus]. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    dao: DownloadDao,
    bus: ProgressBus,
    private val controller: DownloadController,
) : ViewModel() {
    val batch: StateFlow<DownloadBatchEntity?> = dao.history().map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val totals: StateFlow<BatchTotals?> = batch.flatMapLatest { b -> b?.let { dao.totals(it.id) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val failed: StateFlow<List<FileTaskEntity>> = batch.flatMapLatest { b -> b?.let { dao.failed(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val live: StateFlow<ProgressSnapshot?> = bus.snapshot

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun pause() = act { controller.pause(it) }
    fun resume() = act { controller.resume(it) }
    fun cancel() = act { controller.cancel(it) }
    fun retryFailed() = act { controller.retryFailed(it) }

    private fun act(block: suspend (Long) -> Unit) {
        val id = batch.value?.id ?: return
        viewModelScope.launch {
            try { block(id); _error.value = null } catch (e: ActiveBatchExists) { _error.value = e.message }
        }
    }
}

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onNotificationPreview: () -> Unit,
    onHistory: () -> Unit,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val batch by vm.batch.collectAsState()
    val totals by vm.totals.collectAsState()
    val failed by vm.failed.collectAsState()
    val liveAll by vm.live.collectAsState()
    val error by vm.error.collectAsState()
    val live = liveAll?.takeIf { it.batchId == batch?.id }

    Scaffold(
        topBar = {
            StashdTopBar("Downloads", onBack = onBack, actions = {
                IconButton(onClick = onNotificationPreview) { Icon(Icons.Rounded.Notifications, "Notification preview") }
                IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, "History") }
            })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val b = batch
        if (b == null) {
            CenterState { Text("No downloads yet. Pick files or folders in a Drive link and tap Download.", style = MaterialTheme.typography.bodyMedium) }
            return@Scaffold
        }
        val done = live?.bytesDone ?: totals?.doneBytes ?: 0L
        val total = live?.totalBytes?.takeIf { it > 0 } ?: totals?.totalBytes ?: b.totalBytes
        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val speed = live?.bytesPerSecond ?: 0.0

        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, StashdRadii.heroCard).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Overline(stateLabel(b, live), color = MaterialTheme.colorScheme.primary)
                    Text(b.rootName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (b.state == BatchState.EXPANDING) {
                        Text("Scanning folders\u2026 ${live?.foldersScanned ?: 0} scanned, ${live?.totalFiles ?: 0} files found", style = MaterialTheme.typography.bodyMedium)
                        GradientProgressBar(0f, height = 12.dp)
                    } else {
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displayLarge)
                        Text("${formatBytes(done)} of ${formatBytes(total)}", style = MaterialTheme.typography.bodyMedium)
                        GradientProgressBar(fraction, height = 12.dp)
                        val t = totals
                        if (t != null) {
                            val counted = t.totalFiles - t.skippedFiles
                            Text(
                                buildString {
                                    append("${t.doneFiles}/$counted files")
                                    if (t.failedFiles > 0) append(" \u00b7 ${t.failedFiles} failed")
                                    if (t.skippedFiles > 0) append(" \u00b7 ${t.skippedFiles} skipped")
                                    if (b.duplicatesRemoved > 0) append(" \u00b7 ${b.duplicatesRemoved} duplicates merged")
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (live != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatBytes(speed.toLong())}/s", style = MaterialTheme.typography.titleMedium)
                            Text(live.etaSeconds?.let { formatEta(it) + " left" } ?: "\u2013", style = MaterialTheme.typography.titleMedium)
                        }
                        live?.currentFile?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    b.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (b.state) {
                        BatchState.EXPANDING, BatchState.QUEUED, BatchState.RUNNING -> {
                            Button(onClick = vm::pause, modifier = Modifier.weight(1f)) { Text("Pause") }
                            OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                        }
                        BatchState.PAUSED -> {
                            Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Resume") }
                            OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                        }
                        BatchState.COMPLETED_WITH_ERRORS -> Button(onClick = vm::retryFailed, modifier = Modifier.weight(1f)) { Text("Retry failed files") }
                        else -> Unit
                    }
                }
            }
            if (b.state == BatchState.PAUSED || b.state.isActive) item {
                Text(
                    "Pause keeps partial files and resumes where it stopped. Cancel deletes unfinished partial files; files that finished stay.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (failed.isNotEmpty()) {
                item { Overline("Failed files") }
                items(failed, key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, StashdRadii.listCard).padding(12.dp)) {
                        Text((t.dirSegments + (t.finalName ?: t.plannedName)).joinToString("/"), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${t.errorReason?.name?.lowercase()?.replace('_', ' ') ?: "error"}: ${t.errorMessage.orEmpty()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun stateLabel(b: DownloadBatchEntity, live: ProgressSnapshot?): String = when (b.state) {
    BatchState.EXPANDING -> "preparing"
    BatchState.QUEUED -> b.message?.let { "waiting" } ?: "starting"
    BatchState.RUNNING -> if (live == null) "running" else "downloading"
    BatchState.PAUSED -> when (b.pauseReason) {
        PauseReason.STORAGE_FULL -> "paused \u00b7 storage full"
        PauseReason.DESTINATION_REVOKED -> "paused \u00b7 folder access lost"
        PauseReason.NEEDS_CONSENT, PauseReason.AUTH -> "paused \u00b7 sign in again"
        PauseReason.SYSTEM -> "paused by system"
        PauseReason.USER, null -> "paused"
    }
    BatchState.COMPLETED -> "done"
    BatchState.COMPLETED_WITH_ERRORS -> "done with errors"
    BatchState.CANCELLED -> "cancelled"
    BatchState.FAILED -> "failed"
}
