package com.abhranil.stashd.ui.screens

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.auth.AuthorizationOutcome
import com.abhranil.stashd.data.auth.ConsentResult
import com.abhranil.stashd.data.auth.DriveAuthorizer
import com.abhranil.stashd.data.auth.SignInCancelledException
import com.abhranil.stashd.ui.components.rememberConsentLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

sealed interface WelcomeState {
    data object Idle : WelcomeState
    data object Working : WelcomeState
    class NeedsConsent(val pendingIntent: PendingIntent) : WelcomeState
    class Message(val text: String, val isError: Boolean = true) : WelcomeState
    data object Done : WelcomeState
}

/** Sign in (Credential Manager) → authorize drive.readonly (AuthorizationClient) → Home. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val handle: androidx.lifecycle.SavedStateHandle,
    val signInClient: SignInClient,
    private val authorizer: DriveAuthorizer,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow<WelcomeState>(WelcomeState.Idle)
    val state: StateFlow<WelcomeState> = _state.asStateFlow()

    /** True once when this Welcome was opened by "Switch account" (survives rotation without re-firing). */
    fun consumeAutoStart(): Boolean {
        if (handle.get<Boolean>("autoStart") != true || handle.get<Boolean>("autoStartDone") == true) return false
        handle["autoStartDone"] = true
        return signInClient.isConfigured
    }

    fun start(activity: Activity) {
        if (_state.value == WelcomeState.Working) return
        if (!signInClient.isConfigured) { _state.value = WelcomeState.Done; return }
        _state.value = WelcomeState.Working
        viewModelScope.launch {
            try {
                val account = signInClient.signIn(activity)
                settings.setAccount(account.email)
                authorizer.onAccountChanged(account.email)
            } catch (e: SignInCancelledException) {
                _state.value = WelcomeState.Message("Sign-in cancelled.", isError = false); return@launch
            } catch (e: Exception) {
                _state.value = WelcomeState.Message(e.message ?: "Sign-in failed."); return@launch
            }
            authorizeDrive()
        }
    }

    private suspend fun authorizeDrive() {
        _state.value = when (val outcome = authorizer.authorize()) {
            AuthorizationOutcome.Granted -> WelcomeState.Done
            is AuthorizationOutcome.NeedsConsent -> WelcomeState.NeedsConsent(outcome.pendingIntent)
            is AuthorizationOutcome.Failed -> WelcomeState.Message(outcome.message)
        }
    }

    /** The consent sheet is showing; avoid relaunching it on recomposition. */
    fun consentLaunched() { _state.value = WelcomeState.Working }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        _state.value = when (val r = authorizer.completeConsent(resultCode, data)) {
            ConsentResult.Granted -> WelcomeState.Done
            ConsentResult.Cancelled -> WelcomeState.Message("Drive access is needed to browse folders. Tap Continue to try again.", isError = false)
            is ConsentResult.Denied -> WelcomeState.Message(r.message)
        }
    }
}

/** Screen 1 · Welcome and Google sign-in. */
@Composable
fun WelcomeScreen(onSignedIn: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val launchConsent = rememberConsentLauncher(vm::onConsentResult)
    LaunchedEffect(Unit) { if (vm.consumeAutoStart()) (context as? Activity)?.let(vm::start) }
    LaunchedEffect(state) {
        when (val s = state) {
            WelcomeState.Done -> onSignedIn()
            is WelcomeState.NeedsConsent -> { vm.consentLaunched(); launchConsent(s.pendingIntent) }
            else -> Unit
        }
    }

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
            PlaceholderNote("Demo mode: no Web client ID (SETUP.md). Continue opens sample data without signing in.")
            Spacer(Modifier.height(12.dp))
        }
        (state as? WelcomeState.Message)?.let {
            Text(
                it.text, style = MaterialTheme.typography.labelMedium,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        val working = state == WelcomeState.Working || state is WelcomeState.NeedsConsent
        Button(
            onClick = { (context as? Activity)?.let(vm::start) },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F4F8), contentColor = Color(0xFF111111)),
        ) {
            if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF111111))
            else Text(if (vm.signInClient.isConfigured) "Continue with Google" else "Continue (demo)", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = StashdThemeExt.colors.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Read-only access. Your Drive files are never changed.", style = MaterialTheme.typography.labelMedium, color = StashdThemeExt.colors.muted)
        }
    }
}
