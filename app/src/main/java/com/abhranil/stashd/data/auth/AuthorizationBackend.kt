package com.abhranil.stashd.data.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** What AuthorizationClient returned, minus Play-services types, so token caching is unit-testable. */
data class AuthGrant(
    val accessToken: String?,
    val grantedScopes: List<String>,
    /** Non-null when Google needs the user to see a consent screen. */
    val pendingIntent: PendingIntent? = null,
)

/** Thin seam over Play services AuthorizationClient. */
interface AuthorizationBackend {
    /** Silent authorization for [scope], bound to [accountEmail] when known. */
    suspend fun authorize(scope: String, accountEmail: String?): AuthGrant
    /** Parses the consent screen's result intent. Throws ApiException on error/denial. */
    fun grantFromIntent(data: Intent?): AuthGrant
    suspend fun clearToken(token: String)
}

/** The Google account the user signed in with (persisted email), read fresh on every token request. */
interface SelectedAccount {
    suspend fun email(): String?
}

/**
 * TODO(cloud-setup): live path, untested until the Cloud project exists: Drive API enabled, consent screen in
 *   Testing with scope drive.readonly and you as a test user, Android OAuth client (package + SHA-1).
 *   Not used in demo mode (GoogleTokenProvider.authorize short-circuits).
 */
@Singleton
class PlayAuthorizationBackend @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AuthorizationBackend {
    private val client get() = Identity.getAuthorizationClient(context)

    override suspend fun authorize(scope: String, accountEmail: String?): AuthGrant {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(scope)))
            .apply { accountEmail?.let { setAccount(Account(it, "com.google")) } }
            .build()
        return client.authorize(request).await().toGrant()
    }

    override fun grantFromIntent(data: Intent?): AuthGrant = client.getAuthorizationResultFromIntent(data).toGrant()

    override suspend fun clearToken(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    private fun AuthorizationResult.toGrant() = AuthGrant(
        accessToken = accessToken,
        grantedScopes = grantedScopes,
        pendingIntent = if (hasResolution()) pendingIntent else null,
    )
}
