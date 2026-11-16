package com.abhranil.stashd

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.worker.DownloadActionReceiver
import com.abhranil.stashd.download.worker.DownloadNotifier
import com.abhranil.stashd.ui.nav.StashdNavHost
import com.abhranil.stashd.ui.theme.StashdTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: DownloadController
    @Inject lateinit var notifier: DownloadNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not on recreation (rotation): the intent would be re-delivered and resume twice.
        if (savedInstanceState == null) handleResume(intent)
        setContent {
            // Dark is the primary look; a light/system option comes with settings (DataStore) later.
            StashdTheme(darkTheme = true) {
                StashdNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResume(intent)
    }

    /** "Resume" from the paused notification: done here because Stashd is now visible (UIDT rule). */
    private fun handleResume(intent: Intent?) {
        if (intent?.action != DownloadNotifier.ACTION_RESUME_IN_APP) return
        val batchId = intent.getLongExtra(DownloadActionReceiver.EXTRA_BATCH_ID, -1L)
        intent.action = null
        if (batchId < 0) return
        lifecycleScope.launch {
            notifier.clearResult()
            controller.resume(batchId)
            Toast.makeText(this@MainActivity, "Resuming download", Toast.LENGTH_SHORT).show()
        }
    }
}
