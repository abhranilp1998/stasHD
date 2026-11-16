package com.abhranil.stashd.ui.screens

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.db.ActiveBatchExists
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.BatchSummary
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.FailureReason
import com.abhranil.stashd.data.db.FileTaskEntity
import com.abhranil.stashd.data.db.TaskState
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatBytes
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.theme.StashdRadii
import com.abhranil.stashd.ui.theme.StashdThemeExt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val dao: DownloadDao,
    private val controller: DownloadController,
) : ViewModel() {
    val batches: StateFlow<List<BatchSummary>?> = dao.historySummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun problems(batchId: Long): Flow<List<FileTaskEntity>> = dao.problems(batchId)

    /** All retry actions start a download, so they go through the same one-active-batch rule. */
    private fun act(onStarted: (Long) -> Unit, block: suspend () -> Long?) = viewModelScope.launch {
        try { block()?.let(onStarted) } catch (e: ActiveBatchExists) {
            _message.value = "Another download is still active. Finish or cancel it first."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _message.value = e.message ?: "Couldn't start this download." }
    }

    fun retryFailed(batchId: Long, onStarted: (Long) -> Unit) = act(onStarted) { controller.retryFailed(batchId); batchId }
    fun retryFile(taskId: Long, onStarted: (Long) -> Unit) = act(onStarted) {
        val task = dao.task(taskId) ?: return@act null
        controller.retryFile(taskId); task.batchId
    }
    fun downloadAgain(batchId: Long, onStarted: (Long) -> Unit) = act(onStarted) { controller.restart(batchId) }
    fun forget(batchId: Long) = viewModelScope.launch { controller.forget(batchId) }
}

/** Screen 7 · History: every batch with its outcome; failed files can be retried one by one or all at once. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenDownloads: (Long) -> Unit = {}, vm: HistoryViewModel = hiltViewModel()) {
    val batches by vm.batches.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = { StashdTopBar("History", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val list = batches
        when {
            list == null -> Unit
            list.isEmpty() -> Column(Modifier.padding(padding)) {
                CenterState {
                    Icon(Icons.Rounded.History, null, tint = StashdThemeExt.colors.muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No downloads yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var lastDay: String? = null
                list.forEach { s ->
                    val day = dayLabel(s.batch.createdAt)
                    if (day != lastDay) { lastDay = day; item(key = "day-$day-${s.batch.id}") { Overline(day) } }
                    item(key = s.batch.id) { BatchCard(s, vm, onOpenDownloads) }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun BatchCard(s: BatchSummary, vm: HistoryViewModel, onOpenDownloads: (Long) -> Unit) {
    val b = s.batch
    var expanded by rememberSaveable(b.id) { mutableStateOf(false) }
    val hasProblems = s.failedFiles + s.skippedTasks > 0
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, StashdRadii.heroCard)
            .clickable(enabled = hasProblems) { expanded = !expanded }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateIcon(b.state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(b.rootName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${stateLabel(b.state)} \u00b7 ${timeLabel(b.createdAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasProblems) Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide files" else "Show files")
        }
        Text(summaryLine(s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        b.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (b.state) {
                BatchState.COMPLETED_WITH_ERRORS -> if (s.failedFiles > 0) Button(
                    onClick = { vm.retryFailed(b.id, onOpenDownloads) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry ${s.failedFiles} failed") }
                BatchState.CANCELLED, BatchState.FAILED -> OutlinedButton(onClick = { vm.downloadAgain(b.id, onOpenDownloads) }) {
                    Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Download again")
                }
                else -> if (b.state.isActive) OutlinedButton(onClick = { onOpenDownloads(b.id) }) { Text("Open Downloads") }
            }
            Spacer(Modifier.weight(1f))
            if (!b.state.isActive) TextButton(onClick = { vm.forget(b.id) }) { Text("Remove") }
        }

        if (expanded) {
            val problems by remember(b.id) { vm.problems(b.id) }.collectAsState(initial = emptyList())
            problems.forEach { t -> ProblemRow(t, canRetry = b.state == BatchState.COMPLETED_WITH_ERRORS, onRetry = { vm.retryFile(t.id, onOpenDownloads) }) }
        }
    }
}

@Composable
private fun ProblemRow(t: FileTaskEntity, canRetry: Boolean, onRetry: () -> Unit) {
    val failed = t.state == TaskState.FAILED
    Row(
        Modifier.fillMaxWidth()
            .background(if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh, StashdRadii.listCard)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (t.dirSegments + (t.finalName ?: t.plannedName)).joinToString("/"),
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                reasonText(t),
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (failed && canRetry && isRetryable(t.errorReason)) IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry this file") }
    }
}

@Composable
private fun StateIcon(state: BatchState) = when (state) {
    BatchState.COMPLETED -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    BatchState.COMPLETED_WITH_ERRORS, BatchState.FAILED -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
    BatchState.CANCELLED -> Icon(Icons.Rounded.RemoveCircle, null, tint = StashdThemeExt.colors.muted)
    else -> Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.secondary)
}

internal fun stateLabel(s: BatchState) = when (s) {
    BatchState.EXPANDING -> "Preparing"
    BatchState.QUEUED -> "Waiting"
    BatchState.RUNNING -> "Downloading"
    BatchState.PAUSED -> "Paused"
    BatchState.COMPLETED -> "Done"
    BatchState.COMPLETED_WITH_ERRORS -> "Done with errors"
    BatchState.CANCELLED -> "Cancelled"
    BatchState.FAILED -> "Failed"
}

internal fun summaryLine(s: BatchSummary): String = buildList {
    val total = if (s.batch.totalFiles > 0) s.batch.totalFiles else s.taskCount
    add("${s.doneFiles} of $total files")
    add(formatBytes(s.doneBytes))
    if (s.failedFiles > 0) add("${s.failedFiles} failed")
    if (s.skippedTasks > 0) add("${s.skippedTasks} skipped")
}.joinToString(" \u00b7 ")

/** Retrying won't help for these (the file itself can't be downloaded), so no retry button. */
internal fun isRetryable(r: FailureReason?) = r != FailureReason.GOOGLE_DOC && r != FailureReason.CANNOT_DOWNLOAD && r != FailureReason.EXISTS

internal fun reasonText(t: FileTaskEntity): String {
    val why = when (t.errorReason) {
        FailureReason.NOT_FOUND -> "Not found, or this account can't open it any more"
        FailureReason.CANNOT_DOWNLOAD -> "The owner turned off downloads (or Drive flagged the file)"
        FailureReason.GOOGLE_DOC -> "Google Docs/Sheets/Slides need export (not supported yet)"
        FailureReason.AUTH -> "Drive access was lost; sign in again"
        FailureReason.CHECKSUM -> "Checksum didn't match twice, so the file was discarded"
        FailureReason.RETRIES_EXHAUSTED -> "Drive kept failing (rate limit or server errors)"
        FailureReason.FORBIDDEN -> "Access denied"
        FailureReason.EXISTS -> "Already on the phone (skipped)"
        FailureReason.OTHER, null -> "Something went wrong"
    }
    val progress = if (t.state == TaskState.FAILED && t.bytesDone > 0 && (t.size ?: 0) > 0)
        " \u00b7 ${formatBytes(t.bytesDone)} of ${formatBytes(t.size!!)} kept; retry resumes" else ""
    return why + progress
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun timeLabel(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
private fun dayLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(dateFmt) }
}
