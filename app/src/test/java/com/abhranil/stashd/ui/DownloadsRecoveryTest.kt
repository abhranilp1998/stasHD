package com.abhranil.stashd.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhranil.stashd.data.auth.*
import com.abhranil.stashd.data.db.*
import com.abhranil.stashd.download.core.*
import com.abhranil.stashd.download.progress.ProgressBus
import com.abhranil.stashd.download.storage.FileTreeDestination
import com.abhranil.stashd.ui.screens.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadsRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: StashdDatabase
    private lateinit var vm: DownloadsViewModel
    private val models = ViewModelStore()
    private val scheduled = mutableListOf<Long>()
    private val scheduledSignal = CompletableDeferred<Long>()
    private var authorization: AuthorizationOutcome = AuthorizationOutcome.Granted
    private var consent: ConsentResult = ConsentResult.Granted
    private var authorizationCalls = 0
    private val dao get() = db.downloadDao()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StashdDatabase::class.java).allowMainThreadQueries().build()
        val scheduler = object : DownloadScheduler {
            override suspend fun schedule(batch: DownloadBatchEntity) { scheduled += batch.id; scheduledSignal.complete(batch.id) }
            override fun unschedule(batchId: Long) {}
        }
        val controller = DownloadController(dao, scheduler, RunRegistry(), DestinationFactory { FileTreeDestination(tmp.root) })
        val authorizer = object : DriveAuthorizer {
            override suspend fun authorize(): AuthorizationOutcome { authorizationCalls++; return authorization }
            override fun completeConsent(resultCode: Int, data: Intent?) = consent
            override suspend fun onAccountChanged(newEmail: String?) {}
            override suspend fun forget() {}
        }
        vm = DownloadsViewModel(dao, ProgressBus(), controller, authorizer, object : SelectedAccount {
            override suspend fun email() = "owner@example.test"
        })
        models.put("downloads", vm)
    }

    @After fun tearDown() { models.clear(); db.close(); Dispatchers.resetMain() }

    private suspend fun paused(reason: PauseReason): Long = dao.createBatch(
        DownloadBatchEntity(rootFolderId = "root", rootName = "Music", destTreeUri = "dest", keepStructure = true,
            wifiOnly = false, skipExisting = false, state = BatchState.PAUSED, pauseReason = reason, createdAt = 1),
        emptyList(),
    )

    @Test fun resumeWaitsForInteractiveConsentBeforeScheduling() = runTest {
        val id = paused(PauseReason.NEEDS_CONSENT)
        authorization = AuthorizationOutcome.NeedsConsent(PendingIntent.getActivity(
            ApplicationProvider.getApplicationContext(), 1, Intent("test.consent"), PendingIntent.FLAG_IMMUTABLE,
        ))
        vm.resumeBatch(id).join()
        assertNotNull(vm.consentIntent.value)
        assertTrue(scheduled.isEmpty())
        assertEquals(BatchState.PAUSED, dao.batchOnce(id)!!.state)
        vm.onConsentResult(Activity.RESULT_OK, null)
        assertEquals(id, scheduledSignal.await())
        assertEquals(listOf(id), scheduled)
        assertEquals(BatchState.QUEUED, dao.batchOnce(id)!!.state)
    }

    @Test fun cancelledConsentKeepsTheBatchPausedAndExplainsHowToRetry() = runTest {
        val id = paused(PauseReason.AUTH)
        authorization = AuthorizationOutcome.NeedsConsent(PendingIntent.getActivity(
            ApplicationProvider.getApplicationContext(), 2, Intent("test.consent"), PendingIntent.FLAG_IMMUTABLE,
        ))
        vm.resumeBatch(id).join()
        consent = ConsentResult.Cancelled
        vm.onConsentResult(Activity.RESULT_CANCELED, null)
        assertTrue(scheduled.isEmpty())
        assertEquals(BatchState.PAUSED, dao.batchOnce(id)!!.state)
        assertTrue(vm.error.value!!.contains("Resume"))
    }

    @Test fun silentRenewalSchedulesOnlyAfterAccessHasBeenGranted() = runTest {
        val id = paused(PauseReason.AUTH)
        vm.resumeBatch(id).join()
        assertEquals(1, authorizationCalls)
        assertEquals(listOf(id), scheduled)
    }

    @Test fun revokedFolderCannotBeRescheduledWithoutRepair() = runTest {
        val id = paused(PauseReason.DESTINATION_REVOKED)
        vm.resumeBatch(id).join()
        assertTrue(scheduled.isEmpty())
        assertEquals(BatchState.PAUSED, dao.batchOnce(id)!!.state)
    }

    @Test fun retryingAnOlderBatchShowsTheActiveBatchInsteadOfTheNewestHistoryEntry() = runTest {
        val old = paused(PauseReason.USER)
        dao.finishBatch(old, BatchState.COMPLETED, null, 2)
        val newer = dao.createBatch(dao.batchOnce(old)!!.copy(id = 0, createdAt = 10, state = BatchState.COMPLETED), emptyList())
        dao.setBatchState(old, BatchState.PAUSED, PauseReason.USER)
        assertEquals(newer, dao.history().first().first().id)
        assertEquals(old, vm.batch.first { it != null }!!.id)
    }

    @Test fun downloadAgainReportsTheNewBatchForNavigation() = runTest {
        val old = paused(PauseReason.USER)
        dao.insertItems(listOf(DownloadItemEntity(
            batchId = old, nodeId = "file", name = "sample.bin", mimeType = "application/octet-stream",
            resourceKey = null, targetId = null, targetMimeType = null, targetResourceKey = null,
            size = 1, md5 = null, modifiedTime = 1, canDownload = true, parentNames = emptyList(), sortOrder = 0,
        )))
        dao.finishBatch(old, BatchState.CANCELLED, null, 2)
        assertEquals(old, vm.batch.first { it != null }!!.id)
        val opened = CompletableDeferred<Long>()
        vm.restart { opened.complete(it) }
        val newId = opened.await()
        assertNotEquals(old, newId)
        assertEquals(listOf(newId), scheduled)
        assertEquals(BatchState.CANCELLED, dao.batchOnce(old)!!.state)
        assertEquals(BatchState.EXPANDING, dao.batchOnce(newId)!!.state)
    }
}
