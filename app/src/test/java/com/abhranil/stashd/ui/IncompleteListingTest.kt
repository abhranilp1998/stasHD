package com.abhranil.stashd.ui

import androidx.lifecycle.SavedStateHandle
import com.abhranil.stashd.testing.FakeAuthorizer
import com.abhranil.stashd.testing.MemoryFolderCache
import com.abhranil.stashd.testing.ScriptedDrive
import com.abhranil.stashd.testing.file
import com.abhranil.stashd.ui.screens.BrowserViewModel
import com.abhranil.stashd.ui.screens.FolderContent
import com.abhranil.stashd.ui.screens.Incomplete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncompleteListingTest {
    private val drive = ScriptedDrive()
    private val cache = MemoryFolderCache()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = BrowserViewModel(SavedStateHandle(mapOf("folderId" to "ROOT", "name" to "Big")), drive, cache, FakeAuthorizer(), null)

    /** The 201-page probe: 200 pages are fetched, a page token remains -> must not be cached as complete. */
    @Test fun twoHundredOnePagesIsNotCachedAsComplete() {
        drive.pages["ROOT"] = (0 until 201).map { listOf(file("f$it")) }
        val vm = vm()
        assertEquals(200, drive.calls.size)
        assertEquals(200, vm.state.value.items.size)
        assertNull("partial listing must not be cached as complete", cache.stored["ROOT"])
        val content = vm.state.value.content as FolderContent.Loaded
        assertEquals(Incomplete(Incomplete.Reason.PAGE_LIMIT, "p200"), content.incomplete)
    }

    @Test fun continueResumesFromTokenAndThenCachesComplete() {
        drive.pages["ROOT"] = (0 until 201).map { listOf(file("f$it")) }
        val vm = vm()
        vm.continueListing()
        assertEquals(ScriptedDrive.Call("ROOT", null, "p200"), drive.calls.last())
        assertEquals(201, drive.calls.size)
        val content = vm.state.value.content as FolderContent.Loaded
        assertNull(content.incomplete)
        assertEquals(201, content.items.size)
        assertEquals(201, cache.stored["ROOT"]!!.second.size)
    }

    @Test fun incompleteSearchIsShownAndNotCached() {
        drive.pages["ROOT"] = listOf(listOf(file("a")), listOf(file("b")))
        drive.incompletePages["ROOT"] = setOf(1)
        val vm = vm()
        val content = vm.state.value.content as FolderContent.Loaded
        assertEquals(Incomplete(Incomplete.Reason.DRIVE_INCOMPLETE_SEARCH, null), content.incomplete)
        assertEquals(2, content.items.size)
        assertNull(cache.stored["ROOT"])
        // Retry lists from scratch; when Drive no longer flags it, it's complete and cached.
        drive.incompletePages.clear()
        vm.retry()
        assertNull((vm.state.value.content as FolderContent.Loaded).incomplete)
        assertEquals(2, cache.stored["ROOT"]!!.second.size)
    }

    @Test fun incompleteListingIsNotReusedOnBackNavigation() {
        drive.pages["ROOT"] = listOf(listOf(com.abhranil.stashd.testing.folder("A")))
        drive.pages["A"] = listOf(listOf(file("a")))
        drive.incompletePages["ROOT"] = setOf(0)
        val vm = vm()
        vm.open(com.abhranil.stashd.testing.folder("A"))
        val before = drive.calls.size
        vm.back()
        assertEquals("incomplete root is re-listed, not served as complete", before + 1, drive.calls.size)
    }
}
