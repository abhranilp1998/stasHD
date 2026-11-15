package com.abhranil.stashd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhranil.stashd.ui.components.GradientProgressBar
import com.abhranil.stashd.ui.components.Overline
import com.abhranil.stashd.ui.components.PlaceholderNote
import com.abhranil.stashd.ui.theme.StashdRadii

/** Screen 7 · History with an error + retry state. M0 shows static sample data; Room-backed in M4. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    Scaffold(topBar = { StashdTopBar("History", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Overline("Today")
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, StashdRadii.heroCard).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Music · Lossless", style = MaterialTheme.typography.titleMedium)
                Text("81 of 82 files · 9.40 GB · 1 issue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientProgressBar(0.99f, color = MaterialTheme.colorScheme.primary)
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, StashdRadii.listCard).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text("04 - Disappearing Boy.flac", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Text("Connection lost at 71% · 68.2 of 96.0 MB. Retry resumes from 68.2 MB.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                GradientProgressBar(0.71f, height = 4.dp, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.align(Alignment.End),
                ) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry · resume") }
            }
            PlaceholderNote("M0: sample data. Reads download_batch / file_task from Room in M4.")
        }
    }
}
