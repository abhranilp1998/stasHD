package com.abhranil.stashd.data.auth

import com.abhranil.stashd.data.db.DownloadDao
import com.abhranil.stashd.data.db.DriveNodeDao
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.data.settings.AccountPrefs
import com.abhranil.stashd.download.core.DownloadController
import javax.inject.Inject
import javax.inject.Singleton

/** Clears Credential Manager's remembered sign-in (implemented by [SignInClient]). */
interface CredentialStateClearer {
    suspend fun clearCredentialState()
}

/**
 * Sign-out and switch-account. Order matters:
 *  1. pause the active download (reason AUTH) so it can't keep using the old account's token; partial files
 *     are kept, so it can be resumed after signing in again (with an account that can see those files);
 *  2. drop + revoke the cached Drive token;
 *  3. clear Credential Manager state so the next sign-in shows the account picker;
 *  4. forget the stored email and the cached Drive listings / recent folders (they're the old account's).
 * Download history and downloaded files stay: they're on this phone, not in the account.
 */
@Singleton
class AccountSession @Inject constructor(
    private val authorizer: DriveAuthorizer,
    private val credentials: CredentialStateClearer,
    private val prefs: AccountPrefs,
    private val downloads: DownloadDao,
    private val controller: DownloadController,
    private val cache: DriveNodeDao,
) {
    suspend fun signOut() {
        downloads.activeBatchId()?.let { controller.pause(it, PauseReason.AUTH) }
        authorizer.forget()
        credentials.clearCredentialState()
        prefs.storeAccount(null)
        runCatching { cache.clearDriveCache() }
    }
}
