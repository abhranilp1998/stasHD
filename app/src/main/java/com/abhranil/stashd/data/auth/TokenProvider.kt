package com.abhranil.stashd.data.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.abhranil.stashd.data.drive.DriveMode
import com.abhranil.stashd.data.settings.SettingsStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/** Google needs the user to (re)consent: first run, scope removed, or the 7-day Testing-mode expiry. */
class NeedsConsentException(val pendingIntent: PendingIntent?) : Exception("Drive access needs your consent")

/** Used by the Drive client. Never shows UI. */
interface TokenProvider {
    /** A valid access token (about 1 h lifetime). Throws [NeedsConsentException] when UI is required. */
    suspend fun token(): String
    /** Call after a 401 so the next [token] goes back to Google. */
    suspend fun invalidate(token: String)
}

sealed interface AuthorizationOutcome {
    data object Granted : AuthorizationOutcome
    /** Launch [pendingIntent] with StartIntentSenderForResult, then pass the result to [DriveAuthorizer.completeConsent]. */
    class NeedsConsent(val pendingIntent: PendingIntent) : AuthorizationOutcome
    class Failed(val message: String) : AuthorizationOutcome
}

sealed interface ConsentResult {
    data object Granted : ConsentResult
    /** User backed out of the consent screen. */
    data object Cancelled : ConsentResult
    /** User declined, unticked the Drive scope, or Google returned an error. */
    class Denied(val message: String) : ConsentResult
}

/** Used by the UI to drive the interactive part of the flow. */
interface DriveAuthorizer {
    suspend fun authorize(): AuthorizationOutcome
    fun completeConsent(resultCode: Int, data: Intent?): ConsentResult
    suspend fun forget()
}

/** Pure mapping of a consent-screen result, unit-tested. */
object ConsentDecision {
    const val DENIED_SCOPE = "Stashd needs read-only Drive access to list folders. Tap Continue and allow \"See and download your Google Drive files\"."

    fun decide(resultCode: Int, grantedScopes: List<String>?, accessToken: String?, error: String?): ConsentResult = when {
        resultCode != Activity.RESULT_OK && error == null -> ConsentResult.Cancelled
        error != null -> ConsentResult.Denied(error)
        grantedScopes != null && DRIVE_READONLY_SCOPE !in grantedScopes -> ConsentResult.Denied(DENIED_SCOPE)
        accessToken.isNullOrBlank() -> ConsentResult.Denied("Google returned no access token. Try again.")
        else -> ConsentResult.Granted
    }
}

/**
 * Drive authorization through Play services [Identity.getAuthorizationClient] (drive.readonly only).
 * The access token lives in memory only: never persisted, never logged. Play services caches the grant
 * itself, so after a process restart [token] silently gets a fresh token without UI.
 */
@Singleton
class GoogleTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) : TokenProvider, DriveAuthorizer {
    private val mutex = Mutex()
    private val client get() = Identity.getAuthorizationClient(context)

    private class Cached(val token: String, val at: Long)
    @Volatile private var cached: Cached? = null

    private suspend fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
        .apply { settings.settings.first().accountEmail?.let { setAccount(Account(it, "com.google")) } }
        .build()

    override suspend fun token(): String = mutex.withLock {
        cached?.takeIf { System.currentTimeMillis() - it.at < TOKEN_REUSE_MS }?.let { return it.token }
        val result = client.authorize(request()).await()
        if (result.hasResolution()) throw NeedsConsentException(result.pendingIntent)
        accept(result) ?: throw NeedsConsentException(null)
    }

    override suspend fun invalidate(token: String) {
        mutex.withLock { if (cached?.token == token) cached = null }
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    override suspend fun authorize(): AuthorizationOutcome {
        if (DriveMode.useFake) return AuthorizationOutcome.Granted
        return try {
            token()
            AuthorizationOutcome.Granted
        } catch (e: NeedsConsentException) {
            e.pendingIntent?.let { AuthorizationOutcome.NeedsConsent(it) }
                ?: AuthorizationOutcome.Failed("Google didn't grant Drive access. Check the account and try again.")
        } catch (e: ApiException) {
            AuthorizationOutcome.Failed(apiMessage(e))
        }
    }

    override fun completeConsent(resultCode: Int, data: Intent?): ConsentResult {
        var result: AuthorizationResult? = null
        var error: String? = null
        if (resultCode == Activity.RESULT_OK || data != null) {
            try {
                result = client.getAuthorizationResultFromIntent(data)
            } catch (e: ApiException) {
                // Cancel can arrive as RESULT_CANCELED with an error status in the intent; treat that as Cancelled.
                if (resultCode != Activity.RESULT_OK && e.statusCode == CANCELED_STATUS) return ConsentResult.Cancelled
                error = apiMessage(e)
            }
        }
        val decision = ConsentDecision.decide(resultCode, result?.grantedScopes, result?.accessToken, error)
        if (decision is ConsentResult.Granted) result?.let(::accept)
        return decision
    }

    override suspend fun forget() {
        val token = cached?.token
        cached = null
        if (token != null) runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private fun accept(result: AuthorizationResult): String? {
        if (DRIVE_READONLY_SCOPE !in result.grantedScopes) return null
        val token = result.accessToken ?: return null
        cached = Cached(token, System.currentTimeMillis())
        return token
    }

    private fun apiMessage(e: ApiException) = when (e.statusCode) {
        DEVELOPER_ERROR -> "OAuth client misconfigured (package name / SHA-1 / Drive API). See SETUP.md."
        NETWORK_ERROR -> "No connection to Google. Check the network and try again."
        else -> e.message ?: "Google authorization failed (${e.statusCode})."
    }

    companion object {
        /** Tokens last ~60 min; reuse ours for 45 and let Play services hand out the next. */
        private const val TOKEN_REUSE_MS = 45 * 60 * 1000L
        private const val CANCELED_STATUS = 16 // CommonStatusCodes.CANCELED
        private const val DEVELOPER_ERROR = 10 // CommonStatusCodes.DEVELOPER_ERROR
        private const val NETWORK_ERROR = 7 // CommonStatusCodes.NETWORK_ERROR
    }
}
