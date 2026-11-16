package com.abhranil.stashd.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhranil.stashd.data.auth.AccountSession
import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.drive.DriveMode
import com.abhranil.stashd.data.settings.AccountPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val session: AccountSession,
    prefs: AccountPrefs,
    downloads: DownloadDao,
) : ViewModel() {
    val email: StateFlow<String?> = prefs.accountEmail.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** A download is active: signing out pauses it, so say so in the confirmation. */
    val downloadActive: StateFlow<Boolean> = downloads.activeBatch().map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val demoMode: Boolean get() = DriveMode.useFake

    fun signOut(then: () -> Unit) = viewModelScope.launch { session.signOut(); then() }
}

/** Account button for the Home top bar: shows who's signed in, sign out, switch account. */
@Composable
fun AccountMenu(onSignedOut: (switchAccount: Boolean) -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val email by vm.email.collectAsState()
    val active by vm.downloadActive.collectAsState()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Boolean?>(null) } // null = no dialog; true = switch; false = sign out

    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.AccountCircle, "Account") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = {
                Text(
                    when {
                        vm.demoMode -> "Demo mode (not signed in)"
                        email != null -> email!!
                        else -> "Signed in"
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = {}, enabled = false,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Switch account") },
            leadingIcon = { Icon(Icons.Rounded.SwitchAccount, null) },
            onClick = { open = false; confirm = true },
        )
        DropdownMenuItem(
            text = { Text(if (vm.demoMode) "Back to welcome" else "Sign out") },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            onClick = { open = false; confirm = false },
        )
    }

    confirm?.let { switch ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (switch) "Switch Google account?" else "Sign out?") },
            text = {
                Text(
                    buildString {
                        append("Stashd forgets this account's Drive access and its recent folders. Downloaded files and history stay on the phone.")
                        if (active) append("\n\nThe current download will be paused. You can resume it after signing in again.")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; vm.signOut { onSignedOut(switch) } }) { Text(if (switch) "Switch" else "Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
