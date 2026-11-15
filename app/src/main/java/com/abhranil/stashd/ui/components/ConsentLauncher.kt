package com.abhranil.stashd.ui.components

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Launches the Google consent PendingIntent from AuthorizationClient and reports (resultCode, data). */
@Composable
fun rememberConsentLauncher(onResult: (resultCode: Int, data: Intent?) -> Unit): (PendingIntent) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        onResult(it.resultCode, it.data)
    }
    return remember(launcher) { { pi -> launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) } }
}
