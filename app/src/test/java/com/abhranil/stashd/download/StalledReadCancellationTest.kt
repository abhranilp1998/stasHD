package com.abhranil.stashd.download

import com.abhranil.stashd.data.drive.DriveDownload
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.download.core.*
import com.abhranil.stashd.download.storage.FileTreeDestination
import com.abhranil.stashd.testing.BytesDrive
import kotlinx.coroutines.*
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StalledReadCancellationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cancellationClosesAStreamBlockedInsideRead() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val base = BytesDrive()
        base.folder(null, "root")
        val file = base.file("root", "file", "track.flac", byteArrayOf(1))
        val drive = object : DriveRepository by base {
            override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload = object : DriveDownload {
                override val offset = 0L
                override val totalSize = 1L
                override val source = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        entered.countDown()
                        released.await(10, TimeUnit.SECONDS)
                        throw IOException("closed")
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() { released.countDown() }
                }.buffer()
                override fun cancel() { released.countDown() }
                override fun close() { released.countDown() }
            }
        }
        val listener = object : FileJobListener {
            override suspend fun namesClaimed(finalName: String, tempName: String) {}
            override suspend fun remoteChanged(now: RemoteFingerprint) {}
            override suspend fun progress(bytesDone: Long) {}
        }
        val job = launch(Dispatchers.IO) {
            FileDownloader(drive, spaceMarginBytes = 0).download(
                FileJob(1, file.id, null, emptyList(), file.name, RemoteFingerprint.of(file)),
                FileTreeDestination(tmp.newFolder()), listener,
            )
        }
        try {
            assertTrue("read never started", entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue("cancel didn't interrupt the blocked read", released.count == 0L)
        } finally {
            released.countDown()
            job.cancelAndJoin()
        }
    }
}
