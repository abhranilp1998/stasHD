package com.abhranil.stashd.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.abhranil.stashd.data.auth.SignInClient
import com.abhranil.stashd.data.settings.SettingsStore
import com.abhranil.stashd.ui.components.GradientProgressBar
import com.abhranil.stashd.ui.components.PlaceholderNote
import com.abhranil.stashd.ui.theme.StashdThemeExt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    val signInClient: SignInClient,
    private val settings: SettingsStore,
) : ViewModel() {
    suspend fun signIn(activity: Activity): Result<String> = runCatching {
        val account = signInClient.signIn(activity)
        settings.setAccount(account.email)
        account.email
    }
}

/** Screen 1 · Welcome and Google sign-in. */
@Composable
fun WelcomeScreen(onSignedIn: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(StashdThemeExt.colors.progressGradient)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Download, null, tint = Color(0xFF05261F), modifier = Modifier.size(40.dp)) }

        Spacer(Modifier.height(28.dp))
        Text("Your Drive,", style = MaterialTheme.typography.displayMedium)
        Text("on your phone.", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(
            "Paste a Google Drive folder link, pick what you need and pull it down. Every byte shows on the progress bar.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("4.10 / 9.50 GB", style = MaterialTheme.typography.titleLarge)
            GradientProgressBar(0.43f, height = 8.dp)
            Text("18.6 MB/s · 4 min 45 s left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.weight(1f))
        if (!vm.signInClient.isConfigured) {
            PlaceholderNote("M0: WEB_CLIENT_ID not set (SETUP.md). The button skips sign-in for now.")
            Spacer(Modifier.height(12.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(8.dp)) }
        Button(
            onClick = {
                if (!vm.signInClient.isConfigured) { onSignedIn(); return@Button }
                val activity = context as? Activity ?: return@Button
                scope.launch { vm.signIn(activity).onSuccess { onSignedIn() }.onFailure { error = it.message } }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F4F8), contentColor = Color(0xFF111111)),
        ) { Text("Continue with Google", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = StashdThemeExt.colors.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Read-only access. Your Drive files are never changed.", style = MaterialTheme.typography.labelMedium, color = StashdThemeExt.colors.muted)
        }
    }
}
