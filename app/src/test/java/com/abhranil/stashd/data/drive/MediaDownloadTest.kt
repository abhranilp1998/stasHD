package com.abhranil.stashd.data.drive

import com.abhranil.stashd.testing.FakeTokenProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MediaDownloadTest {
    private val server = MockWebServer()
    private lateinit var repo: RealDriveRepository
    private val body = ByteArray(1000) { (it % 251).toByte() }

    @Before fun setUp() {
        server.start()
        repo = RealDriveRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, FakeTokenProvider("t1", "t2"), DriveApiConfig(server.url("/drive/v3/")))
    }

    @After fun tearDown() = server.shutdown()

    private fun slice(from: Int) = Buffer().write(body, from, body.size - from)

    @Test fun fullDownloadIsAltMediaWithoutRangeAndIdentityEncoding() = runTest {
        server.enqueue(MockResponse().setBody(slice(0)))
        repo.openDownload("file123", "0-rk", 0).use { d ->
            assertEquals(0L, d.offset)
            assertEquals(1000L, d.totalSize)
            assertEquals(body.toList(), d.source.readByteArray().toList())
        }
        val r = server.takeRequest()
        assertEquals("/drive/v3/files/file123", r.requestUrl!!.encodedPath)
        assertEquals("media", r.requestUrl!!.queryParameter("alt"))
        assertEquals("true", r.requestUrl!!.queryParameter("supportsAllDrives"))
        assertNull(r.getHeader("Range"))
        assertEquals("identity", r.getHeader("Accept-Encoding"))
        assertEquals("file123/0-rk", r.getHeader(ResourceKeys.HEADER))
    }

    @Test fun rangeResumeValidates206ContentRange() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 400-999/1000").setBody(slice(400)))
        repo.openDownload("file123", null, 400).use { d ->
            assertEquals(400L, d.offset)
            assertEquals(1000L, d.totalSize)
            assertEquals(600, d.source.readByteArray().size)
        }
        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
    }

    @Test fun wrongContentRangeStartIsRejected() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-999/1000").setBody(slice(0)))
        try { repo.openDownload("file123", null, 400); fail() } catch (e: DriveError.RangeMismatch) { assertEquals(400L, e.requested) }
    }

    @Test fun missingContentRangeOn206IsRejected() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody(slice(400)))
        try { repo.openDownload("file123", null, 400); fail() } catch (_: DriveError.RangeMismatch) {}
    }

    @Test fun serverIgnoringRangeReturnsOffsetZero() = runTest {
        server.enqueue(MockResponse().setBody(slice(0)))
        repo.openDownload("file123", null, 400).use { assertEquals(0L, it.offset) }
    }

    @Test fun rangeNotSatisfiableCarriesTotal() = runTest {
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */1000"))
        try { repo.openDownload("file123", null, 1000); fail() } catch (e: DriveError.RangeNotSatisfiable) { assertEquals(1000L, e.totalSize) }
    }

    @Test fun cannotDownloadAndDocsAreMapped() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"errors":[{"reason":"cannotDownloadFile"}]}}"""))
        try { repo.openDownload("file123", null, 0); fail() } catch (e: DriveError.CannotDownload) { assertEquals("cannotDownloadFile", e.reason) }
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"errors":[{"reason":"fileNotDownloadable"}]}}"""))
        try { repo.openDownload("file123", null, 0); fail() } catch (_: DriveError.NotDownloadable) {}
    }

    @Test fun mediaRequestRenewsTokenOn401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(slice(0)))
        repo.openDownload("file123", null, 0).use { it.source.readByteArray() }
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun metadataRequestsChecksumFields() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"file123","name":"a.flac","mimeType":"audio/flac","size":"1000","md5Checksum":"abc","modifiedTime":"2024-01-01T00:00:00Z"}"""))
        val m = repo.fileMetadata("file123", null)
        assertEquals(1000L to "abc", m.size to m.md5)
        val fields = server.takeRequest().requestUrl!!.queryParameter("fields")!!
        listOf("size", "md5Checksum", "modifiedTime", "capabilities/canDownload").forEach { assert(fields.contains(it)) }
    }
}
