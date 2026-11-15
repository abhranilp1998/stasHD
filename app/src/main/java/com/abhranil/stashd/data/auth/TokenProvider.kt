package com.abhranil.stashd.data.auth

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/** Thrown when Google needs the user to (re)consent, e.g. the 7-day Testing-mode expiry. */
class NeedsConsentException(val pendingIntent: PendingIntent?) : Exception("User consent required for Drive access")

interface TokenProvider {
    /** A valid access token (about 1 h lifetime). Never shows UI; throws [NeedsConsentException]. */
    suspend fun token(): String
    /** Call after a 401 so the next [token] goes to the OAuth server. */
    suspend fun invalidate(token: String)
}

@Singleton
class GoogleTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TokenProvider {
    private val mutex = Mutex()
    private val client get() = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
        .build()

    override suspend fun token(): String = mutex.withLock {
        val result = client.authorize(request).await()
        if (result.hasResolution()) throw NeedsConsentException(result.pendingIntent)
        result.accessToken ?: throw NeedsConsentException(null)
    }

    override suspend fun invalidate(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }
}
