package com.abhranil.stashd.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.data.db.FailureReason
import com.abhranil.stashd.data.db.FileTaskEntity
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.data.db.StashdDatabase
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.download.core.ChecksumMismatch
import com.abhranil.stashd.download.core.FailureDecision
import com.abhranil.stashd.download.core.FailureDecision.Kind
import com.abhranil.stashd.download.core.RetryPolicy
import com.abhranil.stashd.download.storage.StorageError
import com.abhranil.stashd.download.worker.SchedulerSelector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadPolicyTest {
    private val policy = RetryPolicy(random = Random(7))

    @Test fun classifiesFailures() {
        assertEquals(FailureDecision.Retry(Kind.SERVER, 3_000), policy.classify(DriveError.RateLimited(3_000)))
        assertEquals(FailureDecision.Retry(Kind.SERVER), policy.classify(DriveError.Server(502)))
        assertEquals(FailureDecision.Retry(Kind.NETWORK), policy.classify(SocketTimeoutException()))
        assertEquals(FailureDecision.Retry(Kind.NETWORK), policy.classify(IOException("reset")))
        assertEquals(FailureDecision.Retry(Kind.CHECKSUM), policy.classify(ChecksumMismatch("a", "b")))
        assertEquals(FailureDecision.Terminal(FailureReason.NOT_FOUND), policy.classify(DriveError.NotFound("x")))
        assertEquals(FailureDecision.Terminal(FailureReason.CANNOT_DOWNLOAD), policy.classify(DriveError.CannotDownload("cannotDownloadFile")))
        assertEquals(FailureDecision.Terminal(FailureReason.GOOGLE_DOC), policy.classify(DriveError.NotDownloadable("d")))
        assertEquals(FailureDecision.Terminal(FailureReason.FORBIDDEN), policy.classify(DriveError.Forbidden("insufficientFilePermissions", "")))
        assertEquals(FailureDecision.PauseBatch(PauseReason.AUTH), policy.classify(DriveError.Unauthorized()))
        assertEquals(FailureDecision.PauseBatch(PauseReason.NEEDS_CONSENT), policy.classify(NeedsConsentException(null)))
        assertEquals(FailureDecision.PauseBatch(PauseReason.STORAGE_FULL), policy.classify(StorageError.InsufficientSpace(1, 0)))
        assertEquals(FailureDecision.PauseBatch(PauseReason.DESTINATION_REVOKED), policy.classify(StorageError.AccessRevoked("gone")))
    }

    @Test fun backoffIsExponentialJitteredAndCapped() {
        repeat(50) {
            assertTrue(policy.delayMs(1) in 1_000L..2_000L)
            assertTrue(policy.delayMs(3) in 4_000L..8_000L)
            assertTrue(policy.delayMs(30) in 30_000L..60_000L)
        }
        assertEquals(9_000L, policy.delayMs(1, retryAfterMs = 9_000))
        assertEquals(300_000L, policy.delayMs(1, retryAfterMs = 3_600_000)) // absurd Retry-After capped
    }

    @Test fun uidtOnAndroid14PlusWorkManagerBelow() {
        assertEquals(SchedulerSelector.Kind.WORK_MANAGER, SchedulerSelector.preferred(26))
        assertEquals(SchedulerSelector.Kind.WORK_MANAGER, SchedulerSelector.preferred(33))
        assertEquals(SchedulerSelector.Kind.UIDT, SchedulerSelector.preferred(34))
        assertEquals(SchedulerSelector.Kind.UIDT, SchedulerSelector.preferred(36))
    }

    @Test fun taskTableDedupsByFileAndPersistsResumeState() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StashdDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.downloadDao()
            val id = dao.createBatch(DownloadBatchEntity(rootFolderId = "R", rootName = "R", destTreeUri = "d", keepStructure = true, wifiOnly = false, skipExisting = false, state = BatchState.EXPANDING, createdAt = 0), emptyList())
            fun task(file: String) = FileTaskEntity(batchId = id, sourceKey = "R", fileId = file, resourceKey = null, dirSegments = listOf("A", "B/C"), plannedName = "$file.flac", size = 1_000, md5 = null, modifiedTime = 1)
            dao.savePlan(id, listOf(task("f1"), task("f2"), task("f1")), 2_000, 2, 0, 1)
            val tasks = dao.tasks(id)
            assertEquals(listOf("f1", "f2"), tasks.map { it.fileId })
            assertEquals(listOf("A", "B/C"), tasks[0].dirSegments) // list round-trips (names may contain '/')
            assertEquals(BatchState.QUEUED, dao.batchOnce(id)!!.state)

            dao.claimNames(tasks[0].id, "f1.flac", "f1.flac.stashdpart")
            dao.setBytes(tasks[0].id, 400)
            dao.setBatchState(id, BatchState.PAUSED, PauseReason.USER)
            val t = dao.task(tasks[0].id)!!
            assertEquals(400L, t.bytesDone)
            assertEquals("f1.flac.stashdpart", t.tempName)
            assertEquals(PauseReason.USER, dao.batchOnce(id)!!.pauseReason)
            val totals = dao.totals(id).first()
            assertEquals(2_000L, totals.totalBytes)
            assertEquals(400L, totals.doneBytes)
        } finally { db.close() }
    }
}
