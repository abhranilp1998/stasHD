package com.abhranil.stashd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.abhranil.stashd.download.worker.DownloadActionReceiver
import com.abhranil.stashd.download.worker.DownloadNotifier
import com.abhranil.stashd.ui.nav.StashdNavHost
import com.abhranil.stashd.ui.theme.StashdTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestedDownload = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        // Not on recreation (rotation): the intent would be re-delivered and resume twice.
        if (savedInstanceState == null) handleResume(intent)
        setContent {
            // Dark is the primary look; a light/system option comes with settings (DataStore) later.
            StashdTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    val requested by requestedDownload.collectAsState()
                    StashdNavHost(requestedDownload = requested, onDownloadRequestHandled = { requestedDownload.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResume(intent)
    }

    /** Route notification actions to Downloads; its resumed UI handles consent and scheduling. */
    private fun handleResume(intent: Intent?) {
        if (intent?.action != DownloadNotifier.ACTION_RESUME_IN_APP && intent?.action != DownloadNotifier.ACTION_OPEN_DOWNLOADS) return
        val batchId = if (intent.action == DownloadNotifier.ACTION_OPEN_DOWNLOADS) 0L else intent.getLongExtra(DownloadActionReceiver.EXTRA_BATCH_ID, -1L)
        intent.action = null
        if (batchId < 0) return
        requestedDownload.value = batchId
    }
}
