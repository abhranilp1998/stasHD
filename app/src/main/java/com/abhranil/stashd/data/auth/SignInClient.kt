package com.abhranil.stashd.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.abhranil.stashd.BuildConfig
import com.abhranil.stashd.data.drive.DriveMode
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SignedInAccount(val email: String, val displayName: String?)

class SignInCancelledException : Exception("Sign-in cancelled")

/**
 * Authentication only (which Google account). Drive access is the separate [DriveAuthorizer] step.
 * The ID token is used only to read the email and is not stored.
 */
@Singleton
class SignInClient @Inject constructor(@param:ApplicationContext private val appContext: Context) {

    val isConfigured: Boolean get() = !DriveMode.useFake

    /** Must be called with an Activity context; shows the Sign in with Google sheet. */
    suspend fun signIn(activityContext: Context): SignedInAccount {
        check(isConfigured) { "WEB_CLIENT_ID is empty. See SETUP.md." }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelledException()
        } catch (e: NoCredentialException) {
            throw IllegalStateException("No Google account available on this device. Add one in Settings.", e)
        }
        val google = GoogleIdTokenCredential.createFrom(response.credential.data)
        return SignedInAccount(email = google.id, displayName = google.displayName)
    }

    suspend fun signOut() {
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
