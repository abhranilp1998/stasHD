package com.abhranil.stashd.auth

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhranil.stashd.data.auth.AccountSession
import com.abhranil.stashd.data.auth.AuthorizationOutcome
import com.abhranil.stashd.data.auth.ConsentResult
import com.abhranil.stashd.data.auth.CredentialStateClearer
import com.abhranil.stashd.data.auth.DriveAuthorizer
import com.abhranil.stashd.data.db.BatchState
import com.abhranil.stashd.data.db.DownloadBatchEntity
import com.abhranil.stashd.data.db.DriveRootEntity
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.data.db.StashdDatabase
import com.abhranil.stashd.data.settings.AccountPrefs
import com.abhranil.stashd.download.core.DownloadController
import com.abhranil.stashd.download.core.DownloadScheduler
import com.abhranil.stashd.download.core.RunRegistry
import com.abhranil.stashd.download.storage.FileTreeDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountSessionTest {
    private lateinit var db: StashdDatabase
    private val events = mutableListOf<String>()

    private val authorizer = object : DriveAuthorizer {
        override suspend fun authorize() = AuthorizationOutcome.Granted
        override fun completeConsent(resultCode: Int, data: Intent?) = ConsentResult.Granted
        override suspend fun onAccountChanged(newEmail: String?) = Unit
        override suspend fun forget() { events += "forget-token" }
    }
    private val credentials = object : CredentialStateClearer {
        override suspend fun clearCredentialState() { events += "clear-credentials" }
    }
    private val prefs = object : AccountPrefs {
        val email = MutableStateFlow<String?>("me@example.com")
        override val accountEmail = email
        override suspend fun storeAccount(email: String?) { events += "store:$email"; this.email.value = email }
    }
    private val scheduler = object : DownloadScheduler {
        override suspend fun schedule(batch: DownloadBatchEntity) = Unit
        override fun unschedule(batchId: Long) { events += "unschedule:$batchId" }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StashdDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun session() = AccountSession(
        authorizer, credentials, prefs, db.downloadDao(),
        DownloadController(db.downloadDao(), scheduler, RunRegistry(), { FileTreeDestination(File("/tmp"), { null }) }) { 1L },
        db.driveNodeDao(),
    )

    @Test fun signOutPausesDownloadRevokesTokenAndForgetsAccountData() = runTest {
        val id = db.downloadDao().createBatch(
            DownloadBatchEntity(rootFolderId = "R", rootName = "Music", destTreeUri = "d", keepStructure = true, wifiOnly = false, skipExisting = false, state = BatchState.RUNNING, createdAt = 1),
            emptyList(),
        )
        db.driveRootDao().upsert(DriveRootEntity(folderId = "R", resourceKey = null, name = "Music", totalBytes = 0, folderCount = 0, fileCount = 0, lastOpenedAt = 1))

        session().signOut()

        val b = db.downloadDao().batchOnce(id)!!
        assertEquals(BatchState.PAUSED, b.state)          // kept (resumable), not cancelled
        assertEquals(PauseReason.AUTH, b.pauseReason)
        assertEquals(listOf("unschedule:$id", "forget-token", "clear-credentials", "store:null"), events)
        assertNull(prefs.email.value)
        assertTrue(db.driveRootDao().recent().first().isEmpty())
    }

    @Test fun signOutWithNothingRunningJustSignsOut() = runTest {
        session().signOut()
        assertEquals(listOf("forget-token", "clear-credentials", "store:null"), events)
    }
}
