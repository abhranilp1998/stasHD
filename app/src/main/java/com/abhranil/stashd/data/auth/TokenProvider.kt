package com.abhranil.stashd.data.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.abhranil.stashd.data.drive.DriveMode
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** The signed-in account changed: drop (and clear) any token issued to another account. */
    suspend fun onAccountChanged(newEmail: String?)
    /** Sign-out: drop and clear the cached token. */
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
 * Drive authorization (drive.readonly only) via [AuthorizationBackend].
 * The access token lives in memory only: never persisted, never logged. Play services caches the grant
 * itself, so after a process restart [token] silently gets a fresh token without UI.
 */
@Singleton
class GoogleTokenProvider(
    private val backend: AuthorizationBackend,
    private val account: SelectedAccount,
    private val clock: () -> Long,
) : TokenProvider, DriveAuthorizer {
    @Inject constructor(backend: AuthorizationBackend, account: SelectedAccount) : this(backend, account, System::currentTimeMillis)

    private val mutex = Mutex()

    /** A token is only ever valid for the account it was issued to. */
    private class Cached(val account: String?, val token: String, val at: Long)
    @Volatile private var cached: Cached? = null
    /** Account the last interactive consent was started for; a consent result is cached under it. */
    @Volatile private var consentAccount: String? = null

    override suspend fun token(): String = mutex.withLock {
        // Read the selected account FIRST; the cache is only consulted for that same account.
        val email = account.email()
        cached?.let { c ->
            if (c.account == email && clock() - c.at < TOKEN_REUSE_MS) return c.token
            if (c.account != email) cached = null // account switched under us: never reuse
        }
        val grant = backend.authorize(DRIVE_READONLY_SCOPE, email)
        if (grant.pendingIntent != null) {
            consentAccount = email
            throw NeedsConsentException(grant.pendingIntent)
        }
        accept(email, grant) ?: throw NeedsConsentException(null)
    }

    override suspend fun invalidate(token: String) {
        mutex.withLock { if (cached?.token == token) cached = null }
        runCatching { backend.clearToken(token) }
    }

    override suspend fun authorize(): AuthorizationOutcome {
        // TODO(cloud-setup): demo mode grants without Google; the real consent flow below runs once a client ID is set.
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
        var grant: AuthGrant? = null
        var error: String? = null
        if (resultCode == Activity.RESULT_OK || data != null) {
            try {
                grant = backend.grantFromIntent(data)
            } catch (e: ApiException) {
                // Cancel can arrive as RESULT_CANCELED with an error status in the intent; treat that as Cancelled.
                if (resultCode != Activity.RESULT_OK && e.statusCode == CANCELED_STATUS) return ConsentResult.Cancelled
                error = apiMessage(e)
            }
        }
        val decision = ConsentDecision.decide(resultCode, grant?.grantedScopes, grant?.accessToken, error)
        if (decision is ConsentResult.Granted) grant?.let { accept(consentAccount, it) }
        return decision
    }

    override suspend fun onAccountChanged(newEmail: String?) {
        val stale = mutex.withLock { cached?.takeIf { it.account != newEmail }?.also { cached = null } }
        if (stale != null) runCatching { backend.clearToken(stale.token) }
    }

    override suspend fun forget() {
        val token = mutex.withLock { cached?.token.also { cached = null } }
        if (token != null) runCatching { backend.clearToken(token) }
    }

    private fun accept(email: String?, grant: AuthGrant): String? {
        if (DRIVE_READONLY_SCOPE !in grant.grantedScopes) return null
        val token = grant.accessToken ?: return null
        cached = Cached(email, token, clock())
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
