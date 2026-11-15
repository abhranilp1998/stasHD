package com.abhranil.stashd.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.abhranil.stashd.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import javax.inject.Inject
import javax.inject.Singleton

data class SignedInAccount(val email: String, val displayName: String?)

/** Authentication only (who the user is). Drive access is a separate step via [TokenProvider]. */
@Singleton
class SignInClient @Inject constructor() {

    val isConfigured: Boolean get() = BuildConfig.WEB_CLIENT_ID.isNotBlank()

    /** Must be called with an Activity context; shows the Sign in with Google sheet. */
    suspend fun signIn(activityContext: Context): SignedInAccount {
        check(isConfigured) { "WEB_CLIENT_ID is empty. See SETUP.md." }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: NoCredentialException) {
            throw IllegalStateException("No Google account available on this device. Add one in Settings.", e)
        }
        val google = GoogleIdTokenCredential.createFrom(response.credential.data)
        return SignedInAccount(email = google.id, displayName = google.displayName)
    }
}
