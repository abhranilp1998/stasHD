package com.abhranil.stashd.data.drive

import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.testing.FakeTokenProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RealDriveRepositoryTest {
    private val server = MockWebServer()
    private lateinit var tokens: FakeTokenProvider
    private lateinit var repo: RealDriveRepository

    private val folderId = "1FolderAAAAAAAAAAAA"
    private val childId = "1ChildBBBBBBBBBBBBB"

    @Before fun setUp() {
        server.start()
        tokens = FakeTokenProvider("t1", "t2")
        repo = repo(tokens)
    }

    @After fun tearDown() = server.shutdown()

    private fun repo(t: FakeTokenProvider) = RealDriveRepository(
        OkHttpClient(), Json { ignoreUnknownKeys = true; explicitNulls = false }, t,
        DriveApiConfig(server.url("/drive/v3/")),
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val RecordedRequest.rk get() = getHeader(ResourceKeys.HEADER)
    private fun RecordedRequest.q(name: String) = requestUrl!!.queryParameter(name)

    @Test fun paginatesUntilNoNextPageTokenAndSendsResourceKeyOnEveryPage() = runTest {
        server.enqueue(json("""{"nextPageToken":"PAGE2","files":[
            {"id":"$childId","name":"Disc 1","mimeType":"application/vnd.google-apps.folder","resourceKey":"0-childKey"},
            {"id":"f2","name":"01 Intro.flac","mimeType":"audio/flac","size":"41234567","md5Checksum":"abc123","modifiedTime":"2024-10-04T10:00:00.000Z"}]}"""))
        server.enqueue(json("""{"files":[{"id":"f3","name":"02 Song.flac","mimeType":"audio/flac","size":"5"}]}"""))

        val items = repo.listChildren(folderId, "0-rootKey")

        assertEquals(listOf(childId, "f2", "f3"), items.map { it.id })
        assertEquals(41_234_567L, items[1].size)
        assertEquals("abc123", items[1].md5)
        assertEquals(1728036000000L, items[1].modifiedTime)
        assertEquals("0-childKey", items[0].resourceKey)

        val p1 = server.takeRequest()
        val p2 = server.takeRequest()
        assertEquals(2, server.requestCount)
        assertNull(p1.q("pageToken"))
        assertEquals("PAGE2", p2.q("pageToken"))
        for (r in listOf(p1, p2)) {
            assertEquals("/drive/v3/files", r.requestUrl!!.encodedPath)
            assertEquals("'$folderId' in parents and trashed = false", r.q("q"))
            assertEquals("$folderId/0-rootKey", r.rk)
            assertEquals("true", r.q("supportsAllDrives"))
            assertEquals("true", r.q("includeItemsFromAllDrives"))
            assertEquals("1000", r.q("pageSize"))
            assertEquals("Bearer t1", r.getHeader("Authorization"))
            val fields = r.q("fields")!!
            listOf("nextPageToken", "resourceKey", "mimeType", "size", "md5Checksum", "modifiedTime", "capabilities/canDownload")
                .forEach { assertTrue("fields missing $it: $fields", fields.contains(it)) }
        }
    }

    @Test fun noResourceKeyHeaderWhenItemHasNoKey() = runTest {
        server.enqueue(json("""{"files":[]}"""))
        assertEquals(emptyList<DriveItem>(), repo.listChildren(folderId, null))
        assertNull(server.takeRequest().rk)
    }

    @Test fun childFolderIsListedWithItsOwnResourceKey() = runTest {
        server.enqueue(json("""{"files":[{"id":"$childId","name":"Disc 1","mimeType":"application/vnd.google-apps.folder","resourceKey":"0-childKey"}]}"""))
        server.enqueue(json("""{"files":[]}"""))
        val child = repo.listChildren(folderId, "0-rootKey").single()
        repo.listChildren(child.id, child.resourceKey)
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("'$childId' in parents and trashed = false", second.q("q"))
        assertEquals("$childId/0-childKey", second.rk)
    }

    @Test fun resolveSendsLinkResourceKeyAndKeepsItWhenApiOmitsIt() = runTest {
        server.enqueue(json("""{"id":"$folderId","name":"Music","mimeType":"application/vnd.google-apps.folder"}"""))
        val item = repo.resolve(DriveLink(folderId, "0-linkKey", isFolder = true))
        val r = server.takeRequest()
        assertEquals("/drive/v3/files/$folderId", r.requestUrl!!.encodedPath)
        assertEquals("$folderId/0-linkKey", r.rk)
        assertEquals("true", r.q("supportsAllDrives"))
        assertTrue(item.isFolder)
        assertEquals("0-linkKey", item.resourceKey)
    }

    @Test fun shortcutIsFollowedWithTargetResourceKey() = runTest {
        server.enqueue(json("""{"files":[{"id":"sc1","name":"Shortcut to Album","mimeType":"application/vnd.google-apps.shortcut",
            "shortcutDetails":{"targetId":"$childId","targetMimeType":"application/vnd.google-apps.folder","targetResourceKey":"0-targetKey"}}]}"""))
        val item = repo.listChildren(folderId, null).single()
        assertEquals(childId, item.id)
        assertTrue(item.isFolder)
        assertEquals("0-targetKey", item.resourceKey)
        assertEquals("Shortcut to Album", item.name)
    }

    @Test fun expiredTokenIsClearedAndRequestRetriedOnce() = runTest {
        server.enqueue(json("""{"error":{"code":401,"message":"Invalid Credentials"}}""", 401))
        server.enqueue(json("""{"files":[]}"""))
        repo.listChildren(folderId, null)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
        assertEquals(listOf("t1"), tokens.invalidated)
    }

    @Test fun secondUnauthorizedGivesUp() = runTest {
        repeat(2) { server.enqueue(json("{}", 401)) }
        try { repo.listChildren(folderId, null); fail() } catch (_: DriveError.Unauthorized) {}
        assertEquals(2, server.requestCount)
    }

    @Test fun errorsAreMapped() = runTest {
        server.enqueue(json("""{"error":{"code":404,"message":"File not found"}}""", 404))
        try { repo.resolve(DriveLink(folderId, null, true)); fail() } catch (e: DriveError.NotFound) { assertEquals(folderId, e.id) }

        server.enqueue(json("""{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}]}}""", 403).setHeader("Retry-After", "3"))
        try { repo.listChildren(folderId, null); fail() } catch (e: DriveError.RateLimited) { assertEquals(3000L, e.retryAfterMs) }

        server.enqueue(json("""{"error":{"code":403,"message":"The user does not have sufficient permissions","errors":[{"reason":"insufficientFilePermissions"}]}}""", 403))
        try { repo.listChildren(folderId, null); fail() } catch (e: DriveError.Forbidden) { assertEquals("insufficientFilePermissions", e.reason) }

        server.enqueue(json("{}", 503))
        try { repo.listChildren(folderId, null); fail() } catch (e: DriveError.Server) { assertEquals(503, e.code) }
    }

    @Test fun consentNeededSurfacesBeforeAnyHttpCall() = runTest {
        val r = repo(FakeTokenProvider(needsConsent = true))
        try { r.listChildren(folderId, null); fail() } catch (_: NeedsConsentException) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsIdsThatCouldInjectIntoQuery() = runTest {
        try { repo.listChildren("x' or name contains '", null); fail() } catch (_: DriveError.BadRequest) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun resourceKeyHeaderFormat() {
        assertEquals("a/k1,b/k2", ResourceKeys.header("a" to "k1", "b" to "k2", "c" to null, "a" to "dup"))
        assertNull(ResourceKeys.header("a" to null, "b" to ""))
    }
}
