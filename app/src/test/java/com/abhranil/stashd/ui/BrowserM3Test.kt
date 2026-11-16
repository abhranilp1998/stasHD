package com.abhranil.stashd.ui

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M3 browser behaviour: process-death restore, 429/5xx backoff, pull-to-refresh, shortcut/target dedup. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserM3Test {
    private val drive = ScriptedDrive()
    private val args = mapOf("folderId" to "ROOT", "resourceKey" to "0-rootKey", "name" to "Music")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        drive.pages["ROOT"] = listOf(listOf(folder("A", rk = "0-keyA"), file("r1")))
        drive.pages["A"] = listOf(listOf(file("a1"), file("a2")))
        drive.pages["B"] = listOf(listOf(file("b1")))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(handle: SavedStateHandle = SavedStateHandle(args)) =
        BrowserViewModel(handle, drive, MemoryFolderCache(), FakeAuthorizer(), null).also { it.sleep = { waits += it } }
    private val waits = mutableListOf<Long>()

    private fun shortcutTo(target: DriveItem, id: String) = target.copy(
        id = id, name = "${target.name} (shortcut)", mimeType = DriveItem.MIME_SHORTCUT,
        shortcut = ShortcutTarget(target.id, target.mimeType, target.resourceKey),
    )

    @Test fun pathAndPicksSurviveProcessDeath() {
        val handle = SavedStateHandle(args)
        val first = vm(handle)
        first.toggle(file("r1"))
        first.open(folder("A", rk = "0-keyA"))
        first.toggle(file("a2"))

        // New process: Android rebuilds the handle from the saved Bundle; only it survives.
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        val again = vm(restoredHandle)
        val s = again.state.value
        assertEquals(listOf("ROOT", "A"), s.stack.map { it.id })
        assertEquals("0-keyA", s.current.resourceKey)
        assertEquals(setOf("r1", "a2"), s.selected.keys)
        assertEquals(listOf("ROOT", "A"), s.selected.getValue("a2").path.map { it.id })
        assertTrue(s.content is FolderContent.Loaded) // the current folder was listed again
        assertEquals(1, again.picks().first { it.item.id == "a2" }.parentNames.size)
    }

    @Test fun unreadableSavedStateStartsAtTheRoot() {
        val vm = vm(SavedStateHandle(args + ("browser.state.v1" to "{not json")))
        assertEquals(listOf("ROOT"), vm.state.value.stack.map { it.id })
    }

    @Test fun rateLimitAndServerErrorsAreRetriedWithBackoff() {
        drive.transient["A"] = mutableListOf(DriveError.RateLimited(retryAfterMs = 1_500), DriveError.Server(503))
        val vm = vm()
        vm.open(folder("A", rk = "0-keyA"))
        val c = vm.state.value.content as FolderContent.Loaded
        assertEquals(listOf("a1", "a2"), c.items.map { it.id })
        assertEquals(2, waits.size)
        assertEquals(1_500L, waits[0]) // Retry-After honoured
        assertEquals(3, drive.calls.count { it.folderId == "A" })
    }

    @Test fun persistentServerErrorGivesUpAfterThreeRetries() {
        drive.transient["A"] = MutableList(10) { DriveError.Server(500) }
        val vm = vm()
        vm.open(folder("A", rk = "0-keyA"))
        assertTrue(vm.state.value.content is FolderContent.Error)
        assertEquals(BrowserViewModel.MAX_LIST_RETRIES, waits.size)
        assertEquals(1 + BrowserViewModel.MAX_LIST_RETRIES, drive.calls.count { it.folderId == "A" })
    }

    @Test fun nonTransientErrorsAreNotRetried() {
        drive.failures["A"] = DriveError.Forbidden("forbidden", "nope")
        val vm = vm()
        vm.open(folder("A", rk = "0-keyA"))
        assertTrue(vm.state.value.content is FolderContent.Error)
        assertTrue(waits.isEmpty())
    }

    @Test fun pullToRefreshRelistsAndKeepsContentOnFailure() {
        val vm = vm()
        drive.pages["ROOT"] = listOf(listOf(folder("A", rk = "0-keyA"), file("r1"), file("new")))
        vm.refresh()
        assertEquals(listOf("A", "r1", "new"), (vm.state.value.content as FolderContent.Loaded).items.map { it.id })
        assertFalse(vm.state.value.refreshing)

        drive.failures["ROOT"] = java.io.IOException("offline")
        vm.refresh()
        val c = vm.state.value.content as FolderContent.Loaded
        assertEquals(3, c.items.size) // still showing the last good list
        assertNotNull(vm.state.value.notice)
        vm.clearNotice()
        assertEquals(null, vm.state.value.notice)
    }

    @Test fun shortcutAndItsTargetCanOnlyBePickedOnce() {
        val target = file("r1")
        val sc = shortcutTo(target, "sc1")
        val vm = vm()
        vm.toggle(target)
        vm.toggle(sc)
        assertEquals(setOf("r1"), vm.state.value.selected.keys)
        assertNotNull(vm.state.value.notice)
        assertTrue(vm.state.value.isChecked(sc)) // shown as included

        // Two shortcuts to the same folder: the second is a no-op too.
        val b = folder("B")
        vm.toggle(shortcutTo(b, "scB1"))
        vm.toggle(shortcutTo(b, "scB2"))
        assertEquals(setOf("r1", "scB1"), vm.state.value.selected.keys)

        // Unpicking the original frees the target again.
        vm.toggle(target)
        vm.toggle(sc)
        assertEquals(setOf("scB1", "sc1"), vm.state.value.selected.keys)
    }
}
