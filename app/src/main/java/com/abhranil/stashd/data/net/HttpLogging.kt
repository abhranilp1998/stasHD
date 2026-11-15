package com.abhranil.stashd.data.net

import okhttp3.logging.HttpLoggingInterceptor

/**
 * The only way the app builds an HTTP logging interceptor. Credentials never reach logcat:
 *  - sensitive headers are redacted by OkHttp itself (value printed as ██),
 *  - every log line is also scrubbed for bearer tokens and token-like query params, in case a
 *    future call puts one somewhere unexpected.
 */
object HttpLogging {
    val REDACTED_HEADERS = listOf(
        "Authorization",
        "Proxy-Authorization",
        "Cookie",
        "Set-Cookie",
        // Resource keys are link secrets for older shared items; keep them out of logs too.
        "X-Goog-Drive-Resource-Keys",
    )

    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val tokenParam = Regex("(?i)\\b(access_token|id_token|refresh_token|resourcekey)=[^&\\s]+")

    fun scrub(line: String): String =
        tokenParam.replace(bearer.replace(line, "$1██"), "$1=██")

    fun interceptor(
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
        level: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.HEADERS,
    ): HttpLoggingInterceptor =
        HttpLoggingInterceptor { line -> logger.log(scrub(line)) }.apply {
            this.level = level
            REDACTED_HEADERS.forEach(::redactHeader)
        }
}
