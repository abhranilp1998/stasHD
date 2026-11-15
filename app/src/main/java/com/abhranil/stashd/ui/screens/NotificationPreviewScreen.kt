package com.abhranil.stashd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhranil.stashd.ui.components.GradientProgressBar
import com.abhranil.stashd.ui.components.PlaceholderNote

/**
 * Screen 6 · The progress notification itself is built by DownloadNotifier and shown by the worker.
 * This in-app preview mirrors its layout for design review.
 */
@Composable
fun NotificationPreviewScreen(onBack: () -> Unit) {
    Scaffold(topBar = { StashdTopBar("Notification", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Stashd · now", style = MaterialTheme.typography.labelMedium)
                Text("Downloading 6 items · 43%", style = MaterialTheme.typography.titleMedium)
                Text("Taylor Swift - 1989 (Taylor's Version) · 08 - Bad Blood.flac", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientProgressBar(0.43f)
                Text("4.10 / 9.50 GB · 18.6 MB/s · 4 min left", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {}) { Text("Pause") }
                    OutlinedButton(onClick = {}) { Text("Cancel") }
                }
            }
            PlaceholderNote("Run the demo download on the Downloads screen to see the real system notification.")
        }
    }
}
