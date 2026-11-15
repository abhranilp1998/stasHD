package com.abhranil.stashd.ui

import androidx.lifecycle.SavedStateHandle
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.ShortcutTarget
import com.abhranil.stashd.testing.FakeAuthorizer
import com.abhranil.stashd.testing.MemoryFolderCache
import com.abhranil.stashd.testing.ScriptedDrive
import com.abhranil.stashd.testing.file
import com.abhranil.stashd.testing.folder
import com.abhranil.stashd.ui.screens.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadThisFolderTest {
    private val drive = ScriptedDrive()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        drive.pages["ROOT"] = listOf(listOf(folder("A", rk = "0-keyA"), file("r1")))
        drive.pages["A"] = listOf(listOf(file("a1"), file("a2")))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = BrowserViewModel(
        SavedStateHandle(mapOf("folderId" to "ROOT", "resourceKey" to "0-rootKey", "name" to "Music")),
        drive, MemoryFolderCache(), FakeAuthorizer(), null,
    )

    @Test fun selectingTheLinkedRootRepresentsTheWholeFolder() {
        val vm = vm()
        vm.toggleCurrentFolder()
        val s = vm.state.value
        assertTrue(s.currentFolderSelected)
        assertEquals(setOf("ROOT"), s.selected.keys)
        val root = s.selected.getValue("ROOT")
        assertEquals("ROOT" to "0-rootKey", root.item.id to root.item.resourceKey)
        assertTrue(root.item.isFolder)
        assertEquals("Music", root.item.name)
        assertEquals(emptyList<Any>(), root.path)
        assertEquals("ROOT", s.coveringFolder?.id)               // everything included
        assertEquals(1, s.selectedFolders)
        assertEquals(0, s.selectedFiles)
    }

    @Test fun rootSelectedMeansChildrenAndSubfoldersAreIncludedNotSeparate() {
        val vm = vm()
        vm.toggleCurrentFolder()
        vm.toggle(file("r1"))                                     // no separate pick while covered
        vm.open(folder("A", rk = "0-keyA"))
        assertEquals("ROOT", vm.state.value.currentFolderIncludedViaAncestor?.id)
        vm.toggleCurrentFolder()                                  // can't toggle A on its own
        assertEquals(setOf("ROOT"), vm.state.value.selected.keys)
    }

    @Test fun selectingRootFoldsInEarlierPicksAndDeselectClears() {
        val vm = vm()
        vm.toggle(file("r1"))
        vm.open(folder("A", rk = "0-keyA"))
        vm.toggle(file("a1"))
        vm.back()
        vm.toggleCurrentFolder()
        assertEquals(setOf("ROOT"), vm.state.value.selected.keys)
        vm.toggleCurrentFolder()
        assertTrue(vm.state.value.selected.isEmpty())
        assertNull(vm.state.value.coveringFolder)
    }

    @Test fun downloadThisFolderInsideASubfolderMatchesPickingItFromTheParent() {
        val vm = vm()
        vm.toggle(file("r1"))
        vm.open(vm.state.value.items.first { it.id == "A" })
        vm.toggle(file("a1"))
        vm.toggleCurrentFolder()
        val s = vm.state.value
        assertEquals(setOf("r1", "A"), s.selected.keys)           // a1 folded into A; r1 (outside A) kept
        assertEquals(listOf("ROOT"), s.selected.getValue("A").path.map { it.id })
        assertEquals("0-keyA", s.selected.getValue("A").item.resourceKey)
        vm.back()
        assertFalse(vm.state.value.currentFolderSelected)          // root itself not selected
        vm.toggle(vm.state.value.items.first { it.id == "A" })     // same key from the parent list: deselects
        assertEquals(setOf("r1"), vm.state.value.selected.keys)
    }

    @Test fun shortcutEnteredFolderIsSelectedByShortcutIdWithTarget() {
        val sc = DriveItem("sc", "Shortcut to A", DriveItem.MIME_SHORTCUT, null, null, null, 0L, true, shortcut = ShortcutTarget("A", DriveItem.MIME_FOLDER, "0-keyA"))
        drive.pages["ROOT"] = listOf(listOf(sc))
        val vm = vm()
        vm.open(sc)
        vm.toggleCurrentFolder()
        val sel = vm.state.value.selected.getValue("sc")
        assertEquals("A" to "0-keyA", sel.item.targetId to sel.item.targetResourceKey)
    }
}
