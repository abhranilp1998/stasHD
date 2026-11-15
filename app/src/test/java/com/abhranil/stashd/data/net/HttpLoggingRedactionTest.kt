package com.abhranil.stashd.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpLoggingRedactionTest {
    private val server = MockWebServer()
    private val lines = mutableListOf<String>()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun client(level: HttpLoggingInterceptor.Level) = OkHttpClient.Builder()
        .addInterceptor(HttpLogging.interceptor({ lines += it }, level))
        .build()

    private fun call(level: HttpLoggingInterceptor.Level) {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("Set-Cookie", "SID=cookie-secret"))
        val req = Request.Builder()
            .url(server.url("/drive/v3/files?q=x&access_token=query-secret"))
            .header("Authorization", "Bearer ya29.header-secret")
            .header("X-Goog-Drive-Resource-Keys", "folder123/0-rk-secret")
            .build()
        client(level).newCall(req).execute().use { it.body!!.string() }
    }

    @Test fun headersLevelRedactsAuthorizationAndOtherSecrets() {
        call(HttpLoggingInterceptor.Level.HEADERS)
        val log = lines.joinToString("\n")
        listOf("header-secret", "query-secret", "rk-secret", "cookie-secret").forEach {
            assertFalse("leaked $it in:\n$log", log.contains(it))
        }
        assertTrue(log, lines.any { it == "Authorization: ██" })
        // Redaction only affects logs: the server still received the real header.
        assertEquals("Bearer ya29.header-secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun bodyLevelAlsoRedacts() {
        call(HttpLoggingInterceptor.Level.BODY)
        assertFalse(lines.joinToString("\n").contains("header-secret"))
        assertTrue(lines.any { it.contains("\"ok\":true") })
    }

    @Test fun scrubCatchesBearerAnywhere() {
        assertEquals("token Bearer ██ end", HttpLogging.scrub("token Bearer ya29.abc-DEF_123 end"))
        assertEquals("GET /x?a=1&access_token=██&b=2", HttpLogging.scrub("GET /x?a=1&access_token=zzz&b=2"))
    }
}
