package com.abhranil.stashd.data.auth

import android.content.Intent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** P1: switching Google accounts in the same process must never hand out the previous account's token. */
class TokenAccountIsolationTest {
    private class MutableAccount(var current: String?) : SelectedAccount {
        override suspend fun email() = current
    }

    /** Issues a distinct token per account, like AuthorizationClient bound to setAccount(). */
    private class PerAccountBackend : AuthorizationBackend {
        val authorizeCalls = mutableListOf<String?>()
        val cleared = mutableListOf<String>()
        private val serial = HashMap<String?, Int>()
        override suspend fun authorize(scope: String, accountEmail: String?): AuthGrant {
            authorizeCalls += accountEmail
            val n = (serial[accountEmail] ?: 0) + 1
            serial[accountEmail] = n
            return AuthGrant("token-$accountEmail-$n", listOf(DRIVE_READONLY_SCOPE))
        }
        override fun grantFromIntent(data: Intent?) = AuthGrant("consent-token", listOf(DRIVE_READONLY_SCOPE))
        override suspend fun clearToken(token: String) { cleared += token }
    }

    private val account = MutableAccount("a@example.com")
    private val backend = PerAccountBackend()
    private var now = 0L
    private val provider = GoogleTokenProvider(backend, account) { now }

    @Test fun switchingAccountDoesNotReturnPreviousAccountsToken() = runTest {
        assertEquals("token-a@example.com-1", provider.token())
        assertEquals("token-a@example.com-1", provider.token()) // cached for A
        account.current = "b@example.com"                       // user signs in as B, same process
        val b = provider.token()
        assertNotEquals("token-a@example.com-1", b)
        assertEquals("token-b@example.com-1", b)
        assertEquals(listOf("a@example.com", "b@example.com"), backend.authorizeCalls)
    }

    @Test fun switchingBackToAIsAFreshRequestNotBsToken() = runTest {
        provider.token()
        account.current = "b@example.com"; provider.token()
        account.current = "a@example.com"
        assertEquals("token-a@example.com-2", provider.token())
    }

    @Test fun onAccountChangedDropsAndClearsCachedToken() = runTest {
        val a = provider.token()
        provider.onAccountChanged("b@example.com")
        assertEquals(listOf(a), backend.cleared)
        account.current = "b@example.com"
        assertEquals("token-b@example.com-1", provider.token())
    }

    @Test fun sameAccountReusesTokenUntilExpiry() = runTest {
        provider.token(); provider.token()
        assertEquals(1, backend.authorizeCalls.size)
        now += 46 * 60 * 1000L
        provider.token()
        assertEquals(2, backend.authorizeCalls.size)
    }

    @Test fun signOutForgetsToken() = runTest {
        val a = provider.token()
        provider.forget()
        account.current = null
        assertEquals("token-null-1", provider.token())
        assertEquals(listOf(a), backend.cleared)
    }
}
