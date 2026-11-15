package com.abhranil.stashd.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.work.WorkInfo
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatBytes
import com.abhranil.stashd.download.worker.DownloadNotifier.Companion.formatEta
import com.abhranil.stashd.download.worker.DownloadScheduler
import com.abhranil.stashd.download.worker.DownloadWorker
import com.abhranil.stashd.ui.components.GradientProgressBar
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.components.PlaceholderNote
import com.abhranil.stashd.ui.theme.StashdRadii
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val scheduler: DownloadScheduler) : ViewModel() {
    val work = scheduler.observe(DEMO_BATCH)
    fun startDemo() = scheduler.start(DEMO_BATCH, wifiOnly = false, demo = true)
    fun cancel() = scheduler.cancel(DEMO_BATCH)

    companion object { const val DEMO_BATCH = 0L }
}

/** Screen 5 · Active downloads. M0 drives it from the demo worker's setProgress data. */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onNotificationPreview: () -> Unit,
    onHistory: () -> Unit,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val info by vm.work.collectAsState(initial = null)
    val p = info?.progress
    val outputs = info?.outputData
    val done = p?.getLong(DownloadWorker.KEY_BYTES_DONE, 0L)?.takeIf { it > 0 } ?: outputs?.getLong(DownloadWorker.KEY_BYTES_DONE, 0L) ?: 0L
    val total = p?.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L)?.takeIf { it > 0 } ?: outputs?.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L
    val speed = p?.getDouble(DownloadWorker.KEY_SPEED, 0.0) ?: 0.0
    val fraction = if (total > 0) done.toFloat() / total else 0f
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startDemo() }

    Scaffold(
        topBar = {
            StashdTopBar("Downloads", onBack = onBack, actions = {
                IconButton(onClick = onNotificationPreview) { Icon(Icons.Rounded.Notifications, "Notification preview") }
                IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, "History") }
            })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, StashdRadii.heroCard).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Overline(info?.state?.name?.lowercase() ?: "idle", color = MaterialTheme.colorScheme.primary)
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displayLarge)
                Text("${formatBytes(done)} of ${formatBytes(total)}", style = MaterialTheme.typography.bodyMedium)
                GradientProgressBar(fraction, height = 12.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatBytes(speed.toLong())}/s", style = MaterialTheme.typography.titleMedium)
                    val eta = if (speed > 1) ((total - done) / speed).toLong() else null
                    Text(eta?.let { formatEta(it) + " left" } ?: "–", style = MaterialTheme.typography.titleMedium)
                }
                p?.getString(DownloadWorker.KEY_CURRENT_FILE)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startDemo()
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Run demo download") }
                OutlinedButton(onClick = vm::cancel, enabled = running) { Text("Cancel") }
            }
            PlaceholderNote("M0: demo worker simulates bytes to exercise setForeground (dataSync) and setProgress. Per-item and per-file rows arrive in M4.")
            Spacer(Modifier.height(8.dp))
        }
    }
}
