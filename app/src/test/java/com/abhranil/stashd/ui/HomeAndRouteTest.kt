package com.abhranil.stashd.ui

import com.abhranil.stashd.data.db.DriveRootDao
import com.abhranil.stashd.data.db.DriveRootEntity
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.testing.FakeAuthorizer
import com.abhranil.stashd.testing.ScriptedDrive
import com.abhranil.stashd.testing.file
import com.abhranil.stashd.testing.folder
import com.abhranil.stashd.ui.nav.BrowserRoute
import com.abhranil.stashd.ui.screens.HomeViewModel
import com.abhranil.stashd.ui.screens.OpenFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAndRouteTest {
    private class MemRoots : DriveRootDao {
        val rows = HashMap<String, DriveRootEntity>()
        override fun recent(limit: Int): Flow<List<DriveRootEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun byFolderId(folderId: String) = rows[folderId]
        override suspend fun upsert(root: DriveRootEntity) { rows[root.folderId] = root }
    }

    private val drive = ScriptedDrive()
    private val roots = MemRoots()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** BrowserViewModel reads these SavedStateHandle keys; type-safe navigation stores args under the property names. */
    @Test fun browserRouteArgNamesMatchViewModelKeys() {
        val d = BrowserRoute.serializer().descriptor
        assertEquals(listOf("folderId", "resourceKey", "name"), (0 until d.elementsCount).map(d::getElementName))
    }

    @Test fun validLinkResolvesAndNavigatesWithResourceKey() {
        drive.resolved = { folder(it.id, it.resourceKey).copy(name = "Music") }
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange("https://drive.google.com/drive/folders/1wa95llBR886wVZ-cps7XbhhDfuOtGCdU?resourcekey=0-abc")
        vm.openLink()
        assertEquals(OpenFolder("1wa95llBR886wVZ-cps7XbhhDfuOtGCdU", "0-abc", "Music"), vm.state.value.navigateTo)
        assertEquals("0-abc", roots.rows.getValue("1wa95llBR886wVZ-cps7XbhhDfuOtGCdU").resourceKey)
    }

    @Test fun fileLinkIsRejectedWithClearMessage() {
        drive.resolved = { file(it.id) }
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view")
        vm.openLink()
        assertNull(vm.state.value.navigateTo)
        assertEquals(HomeViewModel.linkError(DriveError.NotAFolder(file("x"))), vm.state.value.error)
    }

    @Test fun notFoundExplainsSharingAndResourceKey() {
        drive.resolved = { throw DriveError.NotFound(it.id) }
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange("https://drive.google.com/drive/folders/1wa95llBR886wVZ-cps7XbhhDfuOtGCdU")
        vm.openLink()
        assertEquals(true, vm.state.value.error?.contains("resourcekey"))
    }

    @Test fun garbageLinkShowsInlineError() {
        val vm = HomeViewModel(roots, drive, FakeAuthorizer())
        vm.onLinkChange("hello")
        assertEquals("That doesn't look like a Google Drive link", vm.state.value.error)
    }
}
