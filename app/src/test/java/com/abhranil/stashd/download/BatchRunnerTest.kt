package com.abhranil.stashd.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhranil.stashd.data.db.ActiveBatchExists
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.data.db.FailureReason
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.data.db.StashdDatabase
import com.abhranil.stashd.data.db.TaskState
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.download.core.BatchProgress
import com.abhranil.stashd.download.core.BatchRunner
import com.abhranil.stashd.download.core.DestinationFactory
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.core.DownloadScheduler
import com.abhranil.stashd.download.core.FileDownloader
import com.abhranil.stashd.download.core.NewBatch
import com.abhranil.stashd.download.core.PickedItem
import com.abhranil.stashd.download.core.RetryPolicy
import com.abhranil.stashd.download.core.RunRegistry
import com.abhranil.stashd.download.core.RunResult
import com.abhranil.stashd.download.storage.FileTreeDestination
import com.abhranil.stashd.download.storage.StorageError
import com.abhranil.stashd.testing.BytesDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** Batch runner + controller against Room (in-memory), an in-memory Drive with real bytes, and a temp directory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatchRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: StashdDatabase
    private lateinit var root: File
    private lateinit var drive: BytesDrive
    private var free: Long? = null
    private var accessOk = true
    private val delays = mutableListOf<Long>()
    private val scheduled = mutableListOf<Long>()
    private val unscheduled = mutableListOf<Long>()

    private val a1 = BytesDrive.bytes(700_000, 1)
    private val a2 = BytesDrive.bytes(400_000, 2)
    private val b1 = BytesDrive.bytes(250_000, 3)

    private val dao get() = db.downloadDao()
    private val destinations = DestinationFactory {
        object : com.abhranil.stashd.download.storage.Destination by FileTreeDestination(root, { free }) {
            override fun checkAccess() { if (!accessOk) throw StorageError.AccessRevoked("test") }
        }
    }
    private val scheduler = object : DownloadScheduler {
        override suspend fun schedule(batch: DownloadBatchEntity) { scheduled += batch.id }
        override fun unschedule(batchId: Long) { unscheduled += batchId }
    }
    private val registry = RunRegistry()

    private fun runner(policy: RetryPolicy = RetryPolicy(random = Random(1))) = BatchRunner(
        dao, drive, destinations, FileDownloader(drive, Dispatchers.Unconfined, spaceMarginBytes = 0), policy,
        clock = { 1_000L }, sleep = { delays += it }, persistEveryMs = 0,
    )
    private val controller get() = DownloadController(dao, scheduler, registry, destinations) { 1_000L }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StashdDatabase::class.java)
            .allowMainThreadQueries().build()
        root = tmp.newFolder("Music")
        drive = BytesDrive()
        // Linked root "1989 TV" / album "Disc 1" (a1, a2, a Doc) / "Disc 2" (b1, shortcut to a1).
        drive.folder(null, "R", "1989 TV")
        drive.folder("R", "D1", "Disc 1")
        drive.folder("R", "D2", "Disc 2")
        val fa1 = drive.file("D1", "a1", "01 Welcome.flac", a1)
        drive.file("D1", "a2", "02 Blank Space.flac", a2)
        drive.doc("D1", "doc", "Lyrics")
        drive.file("D2", "b1", "39/Smooth.flac", b1)
        drive.shortcut("D2", "s1", "Welcome (shortcut).flac", fa1)
    }

    @After fun tearDown() = db.close()

    private suspend fun createWholeRoot(keep: Boolean = true): Long =
        controller.create(NewBatch("R", "1989 TV", listOf(PickedItem(drive.items["R"]!!, emptyList())), "dest", keep, false, false))

    private fun file(vararg path: String) = File(root, path.joinToString("/"))

    @Test fun downloadsWholeFolderPreservingPathsDedupingAndSkippingDocs() = runTest {
        val id = createWholeRoot()
        assertEquals(listOf(id), scheduled)
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))

        assertArrayEquals(a1, file("1989 TV", "Disc 1", "01 Welcome.flac").readBytes())
        assertArrayEquals(a2, file("1989 TV", "Disc 1", "02 Blank Space.flac").readBytes())
        assertArrayEquals(b1, file("1989 TV", "Disc 2", "39_Smooth.flac").readBytes()) // sanitized
        assertFalse("shortcut duplicate not downloaded twice", file("1989 TV", "Disc 2", "Welcome (shortcut).flac").exists())
        assertTrue(root.walk().none { it.name.endsWith(".stashdpart") })

        val b = dao.batchOnce(id)!!
        assertEquals(BatchState.COMPLETED, b.state)
        assertEquals(1, b.duplicatesRemoved)
        assertEquals(3, b.totalFiles)
        assertEquals((a1.size + a2.size + b1.size).toLong(), b.totalBytes)
        val tasks = dao.tasks(id)
        assertEquals(TaskState.SKIPPED, tasks.single { it.fileId == "doc" }.state)
        assertEquals(FailureReason.GOOGLE_DOC, tasks.single { it.fileId == "doc" }.errorReason)
        assertEquals(3, tasks.count { it.state == TaskState.DONE })
    }

    @Test fun oneActiveBatchAtATime() = runTest {
        val first = createWholeRoot()
        try { createWholeRoot(); fail("second active batch allowed") } catch (e: ActiveBatchExists) { assertEquals(first, e.batchId) }
        controller.pause(first)
        try { createWholeRoot(); fail("a paused batch is still active") } catch (_: ActiveBatchExists) {}
        controller.cancel(first)
        createWholeRoot() // cancelled is not active
    }

    @Test fun interruptedFileResumesFromPersistedOffsetWithRange() = runTest {
        val id = createWholeRoot()
        lateinit var job: Job
        var cancelled = false
        job = async {
            runner().run(id) { p ->
                if (!cancelled && p.phase == BatchProgress.Phase.DOWNLOADING && p.bytesDone > 300_000) { cancelled = true; job.cancel() }
            }
        }
        try { (job as kotlinx.coroutines.Deferred<*>).await() } catch (_: CancellationException) {}

        // System stop: batch back to QUEUED, first file's offset persisted, temp file kept.
        assertEquals(BatchState.QUEUED, dao.batchOnce(id)!!.state)
        val t = dao.tasks(id).single { it.fileId == "a1" }
        assertEquals(TaskState.QUEUED, t.state)
        val temp = file("1989 TV", "Disc 1", t.tempName!!)
        assertTrue(temp.exists())
        assertEquals(temp.length(), t.bytesDone)
        assertTrue(t.bytesDone in 300_001 until a1.size)

        drive.opens.clear()
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
        assertEquals("a1" to t.bytesDone, drive.opens.first()) // Range from the saved offset
        assertArrayEquals(a1, file("1989 TV", "Disc 1", "01 Welcome.flac").readBytes())
    }

    @Test fun userPauseKeepsPartialsAndResumeContinues() = runTest {
        val id = createWholeRoot()
        val paused = CompletableDeferred<Unit>()
        val run = async {
            registry.run(id) {
                runner().run(id) { p -> if (p.bytesDone > 200_000 && !paused.isCompleted) paused.complete(Unit) }
            }
        }
        paused.await()
        controller.pause(id)
        try { run.await() } catch (_: CancellationException) {}

        val b = dao.batchOnce(id)!!
        assertEquals(BatchState.PAUSED, b.state)
        assertEquals(PauseReason.USER, b.pauseReason)
        assertEquals(listOf(id), unscheduled)
        val partial = dao.tasksWithPartials(id).single()
        assertTrue(file("1989 TV", "Disc 1", partial.tempName!!).length() > 0)
        assertEquals(RunResult.NotRunnable, runner().run(id)) // paused batches don't run until resumed

        controller.resume(id)
        assertEquals(BatchState.QUEUED, dao.batchOnce(id)!!.state)
        assertEquals(listOf(id, id), scheduled)
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
        assertArrayEquals(a1, file("1989 TV", "Disc 1", "01 Welcome.flac").readBytes())
    }

    @Test fun cancelDeletesPartialsButKeepsFinishedFiles() = runTest {
        val id = createWholeRoot()
        val inSecond = CompletableDeferred<Unit>()
        val run = async {
            registry.run(id) {
                runner().run(id) { p -> if (p.filesDone == 1 && p.bytesDone > a1.size + 100_000 && !inSecond.isCompleted) inSecond.complete(Unit) }
            }
        }
        inSecond.await()
        controller.cancel(id)
        try { run.await() } catch (_: CancellationException) {}

        assertEquals(BatchState.CANCELLED, dao.batchOnce(id)!!.state)
        assertTrue(file("1989 TV", "Disc 1", "01 Welcome.flac").exists())
        assertTrue("partial deleted", root.walk().none { it.name.endsWith(".stashdpart") })
        val tasks = dao.tasks(id)
        assertEquals(TaskState.DONE, tasks.single { it.fileId == "a1" }.state)
        assertTrue(tasks.filter { it.fileId != "a1" && it.fileId != "doc" }.all { it.state == TaskState.CANCELLED && it.tempName == null })
    }

    @Test fun serverErrorsRetryWithBackoffAndHonourRetryAfter() = runTest {
        drive.openFailures["a2"] = ArrayDeque(listOf(DriveError.Server(503), DriveError.RateLimited(7_000), DriveError.Server(500)))
        val id = createWholeRoot()
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
        val t = dao.tasks(id).single { it.fileId == "a2" }
        assertEquals(TaskState.DONE, t.state)
        assertEquals(3, t.attempts)
        assertEquals(3, delays.size)
        assertTrue(delays[0] in 1_000L..2_000L) // 2 s base with jitter
        assertEquals(7_000L, delays[1]) // Retry-After
        assertTrue(delays[2] in 4_000L..8_000L)
    }

    @Test fun retriesAreBoundedThenFileFailsAndBatchContinues() = runTest {
        drive.openFailures["a2"] = ArrayDeque(List(10) { DriveError.Server(503) })
        val id = createWholeRoot()
        assertEquals(RunResult.Finished(BatchState.COMPLETED_WITH_ERRORS), runner().run(id))
        val t = dao.tasks(id).single { it.fileId == "a2" }
        assertEquals(TaskState.FAILED, t.state)
        assertEquals(FailureReason.RETRIES_EXHAUSTED, t.errorReason)
        assertEquals(5, drive.opens.count { it.first == "a2" })
        assertTrue(file("1989 TV", "Disc 2", "39_Smooth.flac").exists()) // next files still downloaded

        // "Retry failed files" requeues just that one (Drive has recovered).
        drive.openFailures.remove("a2")
        drive.opens.clear()
        controller.retryFailed(id)
        assertEquals(BatchState.QUEUED, dao.batchOnce(id)!!.state)
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
        assertEquals(listOf("a2" to 0L), drive.opens)
    }

    @Test fun terminalErrorsFailImmediatelyWithSavedReason() = runTest {
        drive.openFailures["a1"] = ArrayDeque(listOf(DriveError.NotFound("a1")))
        drive.openFailures["a2"] = ArrayDeque(listOf(DriveError.CannotDownload("cannotDownloadFile")))
        val id = createWholeRoot()
        assertEquals(RunResult.Finished(BatchState.COMPLETED_WITH_ERRORS), runner().run(id))
        val tasks = dao.tasks(id)
        assertEquals(FailureReason.NOT_FOUND, tasks.single { it.fileId == "a1" }.errorReason)
        assertEquals(FailureReason.CANNOT_DOWNLOAD, tasks.single { it.fileId == "a2" }.errorReason)
        assertTrue(tasks.single { it.fileId == "a2" }.errorMessage!!.contains("cannotDownloadFile"))
        assertEquals(1, drive.opens.count { it.first == "a1" })
        assertTrue(delays.isEmpty())
    }

    @Test fun authFailurePausesBatchInsteadOfFailingEveryFile() = runTest {
        drive.openFailures["a1"] = ArrayDeque(listOf(DriveError.Unauthorized()))
        val id = createWholeRoot()
        assertEquals(RunResult.Paused(PauseReason.AUTH), runner().run(id))
        assertEquals(PauseReason.AUTH, dao.batchOnce(id)!!.pauseReason)
        assertTrue(dao.tasks(id).none { it.state == TaskState.FAILED })
        controller.resume(id)
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
    }

    @Test fun lowStoragePausesAndResumesAfterSpaceIsFreed() = runTest {
        free = 500_000 // a1 (700 KB) doesn't fit
        val id = createWholeRoot()
        assertEquals(RunResult.Paused(PauseReason.STORAGE_FULL), runner().run(id))
        assertEquals(TaskState.QUEUED, dao.tasks(id).single { it.fileId == "a1" }.state)
        assertTrue(drive.opens.isEmpty()) // checked before any download
        free = null
        controller.resume(id)
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
    }

    @Test fun revokedFolderAccessPausesTheBatch() = runTest {
        accessOk = false
        val id = createWholeRoot()
        assertEquals(RunResult.Paused(PauseReason.DESTINATION_REVOKED), runner().run(id))
        assertEquals(BatchState.PAUSED, dao.batchOnce(id)!!.state)
    }

    @Test fun md5MismatchRestartsOnceThenFails() = runTest {
        drive.contents["b1"] = BytesDrive.bytes(250_000, 99) // served bytes don't match the listed md5
        val id = createWholeRoot()
        assertEquals(RunResult.Finished(BatchState.COMPLETED_WITH_ERRORS), runner().run(id))
        val t = dao.tasks(id).single { it.fileId == "b1" }
        assertEquals(FailureReason.CHECKSUM, t.errorReason)
        assertEquals(2, drive.opens.count { it.first == "b1" }) // one full restart
        assertTrue(root.walk().none { it.name.startsWith("39_Smooth") })
    }

    @Test fun midStreamNetworkDropRetriesWithRangeFromTempLength() = runTest {
        drive.cutAfter["a1"] = 123_456
        val id = createWholeRoot()
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
        assertEquals(listOf("a1" to 0L, "a1" to 123_456L), drive.opens.filter { it.first == "a1" })
        assertArrayEquals(a1, file("1989 TV", "Disc 1", "01 Welcome.flac").readBytes())
    }

    @Test fun persistentNetworkLossHandsBackToSchedulerKeepingTheFileQueued() = runTest {
        drive.openFailures["a1"] = ArrayDeque(List(5) { IOException("unreachable") })
        val id = createWholeRoot()
        assertEquals(RunResult.RetryLater, runner().run(id))
        val b = dao.batchOnce(id)!!
        assertEquals(BatchState.QUEUED, b.state)
        assertEquals(TaskState.QUEUED, dao.tasks(id).single { it.fileId == "a1" }.state)
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id)) // network back
    }

    @Test fun remoteChangeWhilePausedRestartsFromZero() = runTest {
        val id = createWholeRoot()
        // The connection drops at 200 KB; with a 1-attempt policy the run hands back with a partial on disk.
        drive.cutAfter["a1"] = 200_000
        val policy = RetryPolicy(maxAttempts = 1, random = Random(1))
        assertEquals(RunResult.RetryLater, runner(policy).run(id))
        assertEquals(200_000L, dao.tasks(id).single { it.fileId == "a1" }.bytesDone)

        val changed = BytesDrive.bytes(650_000, 42)
        drive.contents["a1"] = changed
        drive.items["a1"] = drive.items["a1"]!!.copy(size = changed.size.toLong(), md5 = BytesDrive.md5(changed), modifiedTime = 2_000)
        drive.opens.clear()
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(id))
        assertEquals("a1" to 0L, drive.opens.first())
        assertArrayEquals(changed, file("1989 TV", "Disc 1", "01 Welcome.flac").readBytes())
        assertEquals(changed.size.toLong(), dao.tasks(id).single { it.fileId == "a1" }.size)
    }

    @Test fun flattenedPicksAndSubfolderPick() = runTest {
        val id = controller.create(
            NewBatch(
                "R", "1989 TV",
                listOf(PickedItem(drive.items["D2"]!!, emptyList()), PickedItem(drive.items["a2"]!!, listOf("Disc 1"))),
                "dest", keepStructure = true, wifiOnly = false, skipExisting = false,
            ),
        )
        runner().run(id)
        assertTrue(file("Disc 2", "39_Smooth.flac").exists())
        assertTrue(file("Disc 2", "Welcome (shortcut).flac").exists()) // only reachable via shortcut here → kept
        assertTrue(file("Disc 1", "02 Blank Space.flac").exists())
        controller.cancel(id) // no-op on finished
        assertNull(dao.activeBatchId())
    }

    // --- History (M3) ---

    @Test fun historyRetriesOneFailedFileAndCountsOutcomes() = runTest {
        drive.openFailures["a1"] = ArrayDeque(List(10) { DriveError.Server(503) })
        drive.openFailures["a2"] = ArrayDeque(List(10) { DriveError.Server(503) })
        val id = createWholeRoot()
        assertEquals(RunResult.Finished(BatchState.COMPLETED_WITH_ERRORS), runner().run(id))
        var sum = dao.historySummaries().first().single()
        assertEquals(2, sum.failedFiles)
        assertEquals(1, sum.doneFiles) // b1
        assertEquals(b1.size.toLong(), sum.doneBytes)
        assertEquals(2, dao.problems(id).first().count { it.state == TaskState.FAILED })

        drive.openFailures.clear()
        drive.opens.clear()
        controller.retryFile(dao.tasks(id).single { it.fileId == "a2" }.id)
        assertEquals(BatchState.QUEUED, dao.batchOnce(id)!!.state)
        assertEquals(RunResult.Finished(BatchState.COMPLETED_WITH_ERRORS), runner().run(id)) // a1 still failed
        assertEquals(listOf("a2" to 0L), drive.opens)
        sum = dao.historySummaries().first().single()
        assertEquals(1, sum.failedFiles)
        assertEquals(2, sum.doneFiles)
    }

    @Test fun retryFileRespectsTheOneActiveBatchRule() = runTest {
        drive.openFailures["a2"] = ArrayDeque(List(10) { DriveError.Server(503) })
        val id = createWholeRoot()
        runner().run(id)
        val other = controller.create(NewBatch("R", "1989 TV", listOf(PickedItem(drive.items["b1"]!!, emptyList())), "dest", true, false, false))
        try { controller.retryFile(dao.tasks(id).single { it.fileId == "a2" }.id); fail("allowed while another batch is active") }
        catch (e: ActiveBatchExists) { assertEquals(other, e.batchId) }
        assertEquals(TaskState.FAILED, dao.tasks(id).single { it.fileId == "a2" }.state)
    }

    @Test fun downloadAgainCopiesPicksIntoANewSkipExistingBatch() = runTest {
        val id = createWholeRoot()
        controller.cancel(id)
        assertEquals(BatchState.CANCELLED, dao.batchOnce(id)!!.state)
        val again = controller.restart(id)!!
        val nb = dao.batchOnce(again)!!
        assertTrue(nb.skipExisting)
        assertEquals("dest", nb.destTreeUri)
        assertEquals(dao.items(id).map { it.nodeId }, dao.items(again).map { it.nodeId })
        assertEquals(RunResult.Finished(BatchState.COMPLETED), runner().run(again))
        assertArrayEquals(a1, file("1989 TV", "Disc 1", "01 Welcome.flac").readBytes())
        assertNull(controller.restart(again)) // only for cancelled/failed batches

        controller.forget(id)
        assertNull(dao.batchOnce(id))
        assertEquals(listOf(again), dao.historySummaries().first().map { it.batch.id })
    }
}
