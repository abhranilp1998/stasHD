package com.abhranil.stashd.data.drive

import com.abhranil.stashd.data.auth.TokenProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Base URL is injectable so tests can point at MockWebServer. */
class DriveApiConfig(val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl()) {
    companion object { const val DEFAULT_BASE_URL = "https://www.googleapis.com/drive/v3/" }
}

/**
 * Drive REST v3 over OkHttp.
 * - Token from [TokenProvider] on every call, never stored here; on 401 the token is cleared and the call retried once.
 * - Resource keys go in [ResourceKeys.HEADER] for the folder being listed / item being fetched.
 * - Shared drives: supportsAllDrives + includeItemsFromAllDrives on every call.
 */
@Singleton
class RealDriveRepository @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val tokens: TokenProvider,
    private val config: DriveApiConfig,
) : DriveRepository {

    override suspend fun resolve(link: DriveLink): DriveItem {
        requireValidId(link.id)
        val url = config.baseUrl.newBuilder().addPathSegment("files").addPathSegment(link.id)
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        val item = get(url, ResourceKeys.header(link.id to link.resourceKey), link.id) {
            json.decodeFromString<FileJson>(it).toItem()
        }
        // The link's own key wins if the API omitted it (it only returns keys the caller may see).
        val withKey = if (item.resourceKey == null && item.id == link.id) item.copy(resourceKey = link.resourceKey) else item
        // A pasted link to a shortcut: browse the target.
        val target = withKey.shortcut ?: return withKey
        return resolve(DriveLink(target.id, target.resourceKey, target.mimeType == DriveItem.MIME_FOLDER))
    }

    override suspend fun listPage(folderId: String, resourceKey: String?, pageToken: String?): DrivePage {
        requireValidId(folderId)
        val url = config.baseUrl.newBuilder().addPathSegment("files")
            .addQueryParameter("q", "'$folderId' in parents and trashed = false")
            .addQueryParameter("fields", "nextPageToken,incompleteSearch,files($FILE_FIELDS)")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("orderBy", "folder,name_natural")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
            .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
            .build()
        return get(url, ResourceKeys.header(folderId to resourceKey), folderId) { body ->
            val page = json.decodeFromString<FileListJson>(body)
            DrivePage(page.files.map { it.toItem() }, page.nextPageToken?.takeIf { it.isNotBlank() }, page.incompleteSearch)
        }
    }

    override fun walk(rootFolderId: String, resourceKey: String?): Flow<WalkProgress> =
        throw NotImplementedError("Recursive expansion is M2.")

    override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload =
        throw NotImplementedError("Downloader is M2.")

    private suspend fun <T> get(url: HttpUrl, resourceKeyHeader: String?, id: String, parse: (String) -> T): T {
        var token = tokens.token()
        for (attempt in 0..1) {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .apply { if (resourceKeyHeader != null) header(ResourceKeys.HEADER, resourceKeyHeader) }
                .build()
            client.newCall(request).await().use { response ->
                if (response.code == 401 && attempt == 0) {
                    tokens.invalidate(token)
                    token = tokens.token()
                } else if (!response.isSuccessful) {
                    throw errorFor(response, id)
                } else {
                    return parse(response.body?.string().orEmpty())
                }
            }
        }
        throw DriveError.Unauthorized()
    }

    private fun errorFor(response: Response, id: String): DriveError {
        val body = runCatching { json.decodeFromString<ErrorEnvelopeJson>(response.body?.string().orEmpty()).error }.getOrNull()
        val reason = body?.errors?.firstOrNull()?.reason.orEmpty()
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
        return when {
            response.code == 401 -> DriveError.Unauthorized()
            response.code == 404 -> DriveError.NotFound(id)
            response.code == 429 -> DriveError.RateLimited(retryAfter)
            response.code == 403 && reason in RATE_REASONS -> DriveError.RateLimited(retryAfter)
            response.code == 403 -> DriveError.Forbidden(reason, body?.message.orEmpty())
            response.code == 400 -> DriveError.BadRequest(body?.message.orEmpty())
            else -> DriveError.Server(response.code)
        }
    }

    private fun requireValidId(id: String) {
        // Ids are interpolated into the q expression; refuse anything that isn't a Drive id.
        if (!ID.matches(id)) throw DriveError.BadRequest("Not a Drive id")
    }

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,200}$")
        private val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
    })
}
