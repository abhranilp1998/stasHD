package com.abhranil.stashd.ui

import com.abhranil.stashd.data.db.DriveRootDao
import com.abhranil.stashd.data.db.DriveRootEntity
import com.abhranil.stashd.testing.FakeAuthorizer
import com.abhranil.stashd.testing.ScriptedDrive
import com.abhranil.stashd.testing.folder
import com.abhranil.stashd.ui.screens.HomeViewModel
import com.abhranil.stashd.ui.screens.OpenFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** P2: a validation started for an old input must never navigate after the field was edited. */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleLinkValidationTest {
    private val roots = object : DriveRootDao {
        val rows = HashMap<String, DriveRootEntity>()
        override fun recent(limit: Int): Flow<List<DriveRootEntity>> = emptyFlow()
        override suspend fun byFolderId(folderId: String) = rows[folderId]
        override suspend fun upsert(root: DriveRootEntity) { rows[root.folderId] = root }
    }
    private val drive = ScriptedDrive()
    private val gates = HashMap<String, CompletableDeferred<Unit>>()

    private val linkOld = "https://drive.google.com/drive/folders/1OldFolderAAAAAAAAAA"
    private val linkNew = "https://drive.google.com/drive/folders/1NewFolderBBBBBBBBBB"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Delayed fake: each resolve waits until the test opens its gate.
        drive.resolved = { link ->
            gates.getOrPut(link.id) { CompletableDeferred() }.await()
            folder(link.id).copy(name = "Folder ${link.id}")
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun editingTheLinkDropsTheInFlightValidation() {
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange(linkOld)
        vm.openLink()
        vm.onLinkChange(linkNew)                       // user edits while the old check is in flight
        gates.getValue("1OldFolderAAAAAAAAAA").complete(Unit) // old response arrives late
        assertNull("must not navigate to the old folder", vm.state.value.navigateTo)
        assertFalse(vm.state.value.checking)
        assertNull(roots.rows["1OldFolderAAAAAAAAAA"])
        assertEquals(linkNew, vm.state.value.link)
    }

    @Test fun onlyTheLatestInputNavigates() {
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange(linkOld); vm.openLink()
        vm.onLinkChange(linkNew); vm.openLink()
        gates.getOrPut("1NewFolderBBBBBBBBBB") { CompletableDeferred() }.complete(Unit)
        gates.getValue("1OldFolderAAAAAAAAAA").complete(Unit)
        assertEquals(OpenFolder("1NewFolderBBBBBBBBBB", null, "Folder 1NewFolderBBBBBBBBBB"), vm.state.value.navigateTo)
    }

    @Test fun reopeningTheSameInputWhileCheckingDoesNotDoubleNavigate() {
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange(linkNew); vm.openLink(); vm.openLink()
        gates.getOrPut("1NewFolderBBBBBBBBBB") { CompletableDeferred() }.complete(Unit)
        assertEquals("1NewFolderBBBBBBBBBB", vm.state.value.navigateTo?.id)
    }
}
