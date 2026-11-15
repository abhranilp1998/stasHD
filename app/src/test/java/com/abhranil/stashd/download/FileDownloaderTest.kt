package com.abhranil.stashd.download

import com.abhranil.stashd.data.drive.DriveApiConfig
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.data.drive.RealDriveRepository
import com.abhranil.stashd.download.core.ChecksumMismatch
import com.abhranil.stashd.download.core.FileDownloader
import com.abhranil.stashd.download.core.FileDownloader.Companion.toHex
import com.abhranil.stashd.download.core.FileJob
import com.abhranil.stashd.download.core.FileJobListener
import com.abhranil.stashd.download.core.FileOutcome
import com.abhranil.stashd.download.core.RemoteFingerprint
import com.abhranil.stashd.download.storage.FileTreeDestination
import com.abhranil.stashd.download.storage.StorageError
import com.abhranil.stashd.testing.FakeTokenProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class FileDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var downloader: FileDownloader
    private lateinit var dest: FileTreeDestination
    private lateinit var root: File
    private var free: Long? = null

    private val content = ByteArray(300_000) { ((it * 7) % 256).toByte() }
    private val md5 = MessageDigest.getInstance("MD5").digest(content).toHex()
    private val fp = RemoteFingerprint(content.size.toLong(), md5, 1_000L)

    private class Recorder : FileJobListener {
        var names: Pair<String, String>? = null
        var changed: RemoteFingerprint? = null
        var last = -1L
        override suspend fun namesClaimed(finalName: String, tempName: String) { names = finalName to tempName }
        override suspend fun remoteChanged(now: RemoteFingerprint) { changed = now }
        override suspend fun progress(bytesDone: Long) { last = bytesDone }
    }

    @Before fun setUp() {
        server.start()
        root = tmp.newFolder("dest")
        dest = FileTreeDestination(root) { free }
        val repo = RealDriveRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, FakeTokenProvider("t"), DriveApiConfig(server.url("/drive/v3/")))
        downloader = FileDownloader(repo, spaceMarginBytes = 1_000)
    }

    @After fun tearDown() = server.shutdown()

    private fun job(name: String = "01 Song.flac", dirs: List<String> = listOf("Album"), expected: RemoteFingerprint = fp, final: String? = null, temp: String? = null, skip: Boolean = false) =
        FileJob(1, "file1", "0-rk", dirs, name, expected, final, temp, skip)

    private fun body(from: Int = 0) = Buffer().write(content, from, content.size - from)
    private fun meta(size: Long = content.size.toLong(), md5: String = this.md5, modified: String = "1970-01-01T00:00:01Z") =
        MockResponse().setBody("""{"id":"file1","name":"x","mimeType":"audio/flac","size":"$size","md5Checksum":"$md5","modifiedTime":"$modified"}""")

    private fun partial(n: Int, dir: String = "Album", temp: String = "01 Song.flac.stashdpart") {
        File(root, dir).mkdirs(); File(File(root, dir), temp).writeBytes(content.copyOf(n))
    }

    @Test fun downloadsIntoTempThenRenamesAndVerifiesMd5() = runTest {
        server.enqueue(MockResponse().setBody(body()))
        val rec = Recorder()
        val out = downloader.download(job(), dest, rec) as FileOutcome.Done
        assertTrue(out.md5Verified)
        assertEquals("01 Song.flac" to "01 Song.flac.stashdpart", rec.names)
        assertArrayEquals(content, File(root, "Album/01 Song.flac").readBytes())
        assertFalse(File(root, "Album/01 Song.flac.stashdpart").exists())
        assertEquals(content.size.toLong(), rec.last)
    }

    @Test fun interruptedDownloadResumesWithRangeFromTempLength() = runTest {
        // First attempt: connection drops mid-body.
        server.enqueue(MockResponse().setBody(body()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val rec = Recorder()
        try { downloader.download(job(), dest, rec); fail("expected network failure") } catch (_: IOException) {}
        val temp = File(root, "Album/01 Song.flac.stashdpart")
        val have = temp.length().toInt()
        assertTrue("some bytes kept ($have)", have in 1 until content.size)
        assertArrayEquals(content.copyOf(have), temp.readBytes())
        server.takeRequest()

        // Resume with the claimed names: metadata re-check, then Range from the partial's length.
        server.enqueue(meta())
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $have-${content.size - 1}/${content.size}").setBody(body(have)))
        val out = downloader.download(job(final = rec.names!!.first, temp = rec.names!!.second), dest, rec) as FileOutcome.Done
        server.takeRequest()
        assertEquals("bytes=$have-", server.takeRequest().getHeader("Range"))
        assertTrue(out.md5Verified)
        assertArrayEquals(content, File(root, "Album/01 Song.flac").readBytes())
    }

    @Test fun remoteChangedSinceStartRestartsFromZero() = runTest {
        partial(100_000)
        val newContent = content.reversedArray()
        val newMd5 = MessageDigest.getInstance("MD5").digest(newContent).toHex()
        server.enqueue(meta(md5 = newMd5, modified = "2025-01-01T00:00:00Z"))
        server.enqueue(MockResponse().setBody(Buffer().write(newContent)))
        val rec = Recorder()
        downloader.download(job(final = "01 Song.flac", temp = "01 Song.flac.stashdpart"), dest, rec)
        server.takeRequest()
        assertNull("restart must not send Range", server.takeRequest().getHeader("Range"))
        assertEquals(newMd5, rec.changed?.md5)
        assertArrayEquals(newContent, File(root, "Album/01 Song.flac").readBytes())
    }

    @Test fun serverIgnoringRangeRestartsCleanly() = runTest {
        partial(100_000)
        server.enqueue(meta())
        server.enqueue(MockResponse().setBody(body())) // 200 with the whole file
        downloader.download(job(final = "01 Song.flac", temp = "01 Song.flac.stashdpart"), dest, Recorder())
        assertArrayEquals(content, File(root, "Album/01 Song.flac").readBytes())
    }

    @Test fun wrongContentRangeRestartsFromZeroOnce() = runTest {
        partial(100_000)
        server.enqueue(meta())
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-${content.size - 1}/${content.size}").setBody(body()))
        server.enqueue(MockResponse().setBody(body()))
        downloader.download(job(final = "01 Song.flac", temp = "01 Song.flac.stashdpart"), dest, Recorder())
        assertArrayEquals(content, File(root, "Album/01 Song.flac").readBytes())
    }

    @Test fun completeTempWith416JustFinalizes() = runTest {
        partial(content.size)
        server.enqueue(meta())
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}"))
        val out = downloader.download(job(final = "01 Song.flac", temp = "01 Song.flac.stashdpart"), dest, Recorder()) as FileOutcome.Done
        assertTrue(out.md5Verified)
        assertArrayEquals(content, File(root, "Album/01 Song.flac").readBytes())
    }

    @Test fun md5MismatchDeletesTempAndNeverCreatesFinal() = runTest {
        val corrupt = content.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(corrupt)))
        try { downloader.download(job(), dest, Recorder()); fail() } catch (e: ChecksumMismatch) { assertEquals(md5, e.expected) }
        assertFalse(File(root, "Album/01 Song.flac").exists())
        assertFalse(File(root, "Album/01 Song.flac.stashdpart").exists())
    }

    @Test fun truncatedBodyIsASizeMismatch() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(content, 0, 1000)))
        try { downloader.download(job(), dest, Recorder()); fail() } catch (_: ChecksumMismatch) {}
    }

    @Test fun sanitizedDirsAndCollisionsGetUniqueNames() = runTest {
        File(root, "Green Day - 39_Smooth (1990)").mkdirs()
        File(root, "Green Day - 39_Smooth (1990)/01 Song.flac").writeText("someone else's file")
        server.enqueue(MockResponse().setBody(body()))
        val rec = Recorder()
        val out = downloader.download(job(dirs = listOf("Green Day - 39/Smooth (1990)")), dest, rec) as FileOutcome.Done
        assertEquals("01 Song (2).flac", out.finalName)
        assertEquals("someone else's file", File(root, "Green Day - 39_Smooth (1990)/01 Song.flac").readText())
        assertArrayEquals(content, File(root, "Green Day - 39_Smooth (1990)/01 Song (2).flac").readBytes())
    }

    @Test fun skipExistingWhenSameSize() = runTest {
        File(root, "Album").mkdirs(); File(root, "Album/01 Song.flac").writeBytes(content)
        val out = downloader.download(job(skip = true), dest, Recorder())
        assertEquals(FileOutcome.SkippedExisting("01 Song.flac"), out)
        assertEquals(0, server.requestCount)
    }

    @Test fun lowStorageFailsBeforeDownloading() = runTest {
        free = 50_000
        try { downloader.download(job(), dest, Recorder()); fail() } catch (e: StorageError.InsufficientSpace) { assertEquals(content.size.toLong(), e.neededBytes) }
        assertEquals(0, server.requestCount)
    }

    @Test fun diskFullMidWriteIsReportedAsStorageNotNetwork() = runTest {
        val full = object : com.abhranil.stashd.download.storage.Destination by dest {
            override fun dir(segments: List<String>) = object : com.abhranil.stashd.download.storage.DestDir by dest.dir(segments) {
                override fun openWrite(name: String, append: Boolean) = object : java.io.OutputStream() {
                    var n = 0
                    override fun write(b: Int) { if (++n > 10) throw IOException("write failed: ENOSPC (No space left on device)") }
                }
            }
        }
        server.enqueue(MockResponse().setBody(body()))
        try { downloader.download(job(), full, Recorder()); fail() } catch (_: StorageError.InsufficientSpace) {}
    }

    @Test fun revokedDestinationFailsFast() = runTest {
        root.setWritable(false)
        try { downloader.download(job(), dest, Recorder()); fail() } catch (_: StorageError.AccessRevoked) {} finally { root.setWritable(true) }
        assertEquals(0, server.requestCount)
    }

    @Test fun googleDocDiscoveredAtResumeIsNotDownloadable() = runTest {
        partial(10)
        server.enqueue(MockResponse().setBody("""{"id":"file1","name":"x","mimeType":"application/vnd.google-apps.document"}"""))
        try { downloader.download(job(final = "01 Song.flac", temp = "01 Song.flac.stashdpart"), dest, Recorder()); fail() } catch (_: DriveError.NotDownloadable) {}
    }
}
