package com.abhranil.stashd.ui

import androidx.lifecycle.SavedStateHandle
import com.abhranil.stashd.data.auth.ConsentResult
import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.ShortcutTarget
import com.abhranil.stashd.testing.FakeAuthorizer
import com.abhranil.stashd.testing.MemoryFolderCache
import com.abhranil.stashd.testing.ScriptedDrive
import com.abhranil.stashd.testing.file
import com.abhranil.stashd.testing.folder
import com.abhranil.stashd.ui.screens.BrowserViewModel
import com.abhranil.stashd.ui.screens.FolderContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val drive = ScriptedDrive()
    private val cache = MemoryFolderCache()
    private val auth = FakeAuthorizer()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        drive.pages["ROOT"] = listOf(listOf(folder("A", rk = "0-keyA"), folder("B")), listOf(file("r1"), file("r2", 50)))
        drive.pages["A"] = listOf(listOf(folder("A1", rk = "0-keyA1"), file("a1")))
        drive.pages["A1"] = listOf(listOf(file("deep")))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(rk: String? = "0-rootKey") = BrowserViewModel(
        SavedStateHandle(mapOf("folderId" to "ROOT", "resourceKey" to rk, "name" to "Music")),
        drive, cache, auth, null,
    )

    @Test fun routeResourceKeyIsUsedForRootListingOnEveryPage() {
        val vm = vm()
        assertEquals(listOf(ScriptedDrive.Call("ROOT", "0-rootKey", null), ScriptedDrive.Call("ROOT", "0-rootKey", "p1")), drive.calls)
        assertEquals(listOf("A", "B", "r1", "r2"), vm.state.value.items.map { it.id })
        assertEquals("Music", vm.state.value.current.name)
        assertEquals("0-rootKey" to listOf("A", "B", "r1", "r2"), cache.stored["ROOT"]!!.let { it.first to it.second.map { i -> i.id } })
    }

    @Test fun enteringFoldersCarriesEachFoldersOwnResourceKey() {
        val vm = vm()
        vm.open(vm.state.value.items.first { it.id == "A" })
        vm.open(vm.state.value.items.first { it.id == "A1" })
        assertEquals(ScriptedDrive.Call("A", "0-keyA", null), drive.calls[2])
        assertEquals(ScriptedDrive.Call("A1", "0-keyA1", null), drive.calls[3])
        assertEquals(listOf("ROOT", "A", "A1"), vm.state.value.stack.map { it.id })
        assertEquals(listOf("0-rootKey", "0-keyA", "0-keyA1"), vm.state.value.stack.map { it.resourceKey })
    }

    @Test fun backAndBreadcrumbReuseListingsWithoutRefetching() {
        val vm = vm()
        vm.open(folder("A", rk = "0-keyA"))
        vm.open(folder("A1", rk = "0-keyA1"))
        val callsBefore = drive.calls.size
        assertTrue(vm.back())
        assertEquals(listOf("A1", "a1"), vm.state.value.items.map { it.id })
        vm.goTo(0)
        assertEquals(listOf("ROOT"), vm.state.value.stack.map { it.id })
        assertEquals(callsBefore, drive.calls.size)
        assertFalse("at root, back pops the screen", vm.back())
    }

    @Test fun selectionIsPreservedAcrossNavigation() {
        val vm = vm()
        vm.toggle(vm.state.value.items.first { it.id == "r2" })
        vm.open(folder("A", rk = "0-keyA"))
        vm.toggle(vm.state.value.items.first { it.id == "a1" })
        vm.back()
        assertEquals(setOf("r2", "a1"), vm.state.value.selected.keys)
        assertEquals(listOf("ROOT", "A"), vm.state.value.selected.getValue("a1").path.map { it.id })
        vm.open(folder("A", rk = "0-keyA"))
        assertTrue("a1" in vm.state.value.selected)
        assertEquals(2, vm.state.value.selectedFiles)
        assertEquals(150L, vm.state.value.selectedFileBytes)
    }

    @Test fun childrenOfASelectedFolderShowAsIncludedAndCannotBeToggled() {
        val vm = vm()
        vm.toggle(vm.state.value.items.first { it.id == "A" })
        vm.open(folder("A", rk = "0-keyA"))
        assertEquals("A", vm.state.value.coveringFolder?.id)
        vm.toggle(vm.state.value.items.first { it.id == "a1" })
        assertEquals(setOf("A"), vm.state.value.selected.keys)
        assertEquals(1, vm.state.value.selectedFolders)
    }

    @Test fun tappingAFileTogglesInsteadOfNavigating() {
        val vm = vm()
        vm.open(vm.state.value.items.first { it.id == "r1" })
        assertEquals(listOf("ROOT"), vm.state.value.stack.map { it.id })
        assertTrue("r1" in vm.state.value.selected)
    }

    @Test fun emptyFolderIsLoadedWithNoItems() {
        val vm = vm()
        vm.open(folder("B"))
        assertEquals(FolderContent.Loaded(emptyList()), vm.state.value.content)
    }

    @Test fun errorThenRetry() {
        drive.failures["ROOT"] = DriveError.NotFound("ROOT")
        val vm = vm()
        assertTrue(vm.state.value.content is FolderContent.Error)
        drive.failures.clear()
        vm.retry()
        assertEquals(4, vm.state.value.items.size)
    }

    @Test fun networkErrorFallsBackToCachedListing() {
        cache.stored["ROOT"] = null to listOf(file("cached"))
        drive.failures["ROOT"] = IOException("offline")
        val vm = vm()
        assertEquals(FolderContent.Loaded(listOf(file("cached")), fromCache = true), vm.state.value.content)
    }

    @Test fun consentNeededShowsGrantAndRetriesAfterGrant() {
        drive.failures["ROOT"] = NeedsConsentException(null)
        val vm = vm()
        val err = vm.state.value.content as FolderContent.Error
        assertTrue(err.needsConsent)
        drive.failures.clear()
        auth.consent = ConsentResult.Granted
        vm.onConsentResult(-1, null)
        assertEquals(4, vm.state.value.items.size)
    }

    @Test fun consentCancelledKeepsGrantPrompt() {
        drive.failures["ROOT"] = NeedsConsentException(null)
        val vm = vm()
        auth.consent = ConsentResult.Cancelled
        vm.onConsentResult(0, null)
        assertTrue((vm.state.value.content as FolderContent.Error).needsConsent)
    }

    @Test fun noResourceKeyMeansNullAllTheWay() {
        vm(rk = null)
        assertEquals(null, drive.calls.first().resourceKey)
    }

    @Test fun shortcutToFolderNavigatesWithTargetIdAndKeyButSelectsByShortcutId() {
        val sc = DriveItem("sc", "Shortcut to A", DriveItem.MIME_SHORTCUT, null, null, null, 0L, true, shortcut = ShortcutTarget("A", DriveItem.MIME_FOLDER, "0-keyA"))
        drive.pages["ROOT"] = listOf(listOf(folder("A", rk = "0-keyA"), sc))
        val vm = vm()
        assertEquals(2, vm.state.value.items.map { it.id }.toSet().size)
        vm.toggle(sc)
        assertEquals(setOf("sc"), vm.state.value.selected.keys)
        vm.open(sc)
        assertEquals(ScriptedDrive.Call("A", "0-keyA", null), drive.calls.last())
        assertEquals("Shortcut to A", vm.state.value.current.name)
        assertEquals("sc", vm.state.value.coveringFolder?.selectionKey) // selected shortcut covers its contents
    }
}
