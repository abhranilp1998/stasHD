package com.abhranil.stashd.testing

import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.data.auth.TokenProvider

/** Hands out tokens in order; records invalidations. */
class FakeTokenProvider(vararg tokens: String, private val needsConsent: Boolean = false) : TokenProvider {
    private val queue = ArrayDeque(tokens.toList())
    private var current: String? = null
    val invalidated = mutableListOf<String>()
    var calls = 0

    override suspend fun token(): String {
        calls++
        if (needsConsent) throw NeedsConsentException(null)
        return current ?: queue.removeFirst().also { current = it }
    }

    override suspend fun invalidate(token: String) {
        invalidated += token
        if (current == token) current = null
    }
}
