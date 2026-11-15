package com.abhranil.stashd.download

import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.ShortcutTarget
import com.abhranil.stashd.download.core.ExpansionIncomplete
import com.abhranil.stashd.download.core.PickedItem
import com.abhranil.stashd.download.core.SelectionExpander
import com.abhranil.stashd.download.core.SkipReason
import com.abhranil.stashd.testing.ScriptedDrive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SelectionExpanderTest {
    private val drive = ScriptedDrive()
    private val expander = SelectionExpander(drive)

    private fun folder(id: String, name: String = id, rk: String? = null) = DriveItem(id, name, DriveItem.MIME_FOLDER, null, null, rk, 0L, true)
    private fun file(id: String, name: String = "$id.flac", size: Long = 10, md5: String? = "m$id") = DriveItem(id, name, "audio/flac", size, md5, null, 5L, true)
    private fun shortcut(id: String, name: String, target: String, mime: String, rk: String? = null) =
        DriveItem(id, name, DriveItem.MIME_SHORTCUT, null, null, null, 0L, true, shortcut = ShortcutTarget(target, mime, rk))

    @Before fun tree() {
        // ROOT/ Album (A)/ CD1 (C1)/ t1, t2 ; A/ cover.jpg ; ROOT/ loose.flac ; ROOT/ Notes (gdoc)
        drive.pages["ROOT"] = listOf(listOf(folder("A", "Album", rk = "0-kA"), file("loose"), DriveItem("doc", "Notes", "application/vnd.google-apps.document", null, null, null, 0L, true)))
        drive.pages["A"] = listOf(listOf(folder("C1", "CD1"), file("cover", "cover.jpg")))
        drive.pages["C1"] = listOf(listOf(file("t1", "01 One.flac")), listOf(file("t2", "02 Two.flac"))) // two pages
    }

    private val rootItem = folder("ROOT", "Music")

    @Test fun preservesRelativePathsRecursively() = runTest {
        val r = expander.expand(listOf(PickedItem(folder("A", "Album", rk = "0-kA"), emptyList())), keepStructure = true)
        assertEquals(
            listOf("Album/cover.jpg", "Album/CD1/01 One.flac", "Album/CD1/02 Two.flac"),
            r.files.map { (it.dirSegments + it.name).joinToString("/") },
        )
        assertEquals(30L, r.totalBytes)
        assertEquals(ScriptedDrive.Call("A", "0-kA", null), drive.calls.first())
    }

    @Test fun wholeLinkedRootLandsUnderItsName() = runTest {
        val r = expander.expand(listOf(PickedItem(rootItem, emptyList())), keepStructure = true)
        assertTrue(r.files.all { it.dirSegments.first() == "Music" })
        assertEquals(listOf("Music", "Album", "CD1"), r.files.first { it.fileId == "t1" }.dirSegments)
        assertEquals(listOf(SkipReason.GOOGLE_DOC), r.skipped.map { it.reason })
        assertEquals("Notes", r.skipped.single().name)
    }

    @Test fun fileCoveredBySelectedFolderIsDownloadedOnce() = runTest {
        val r = expander.expand(
            listOf(PickedItem(file("t1", "01 One.flac"), listOf("Album", "CD1")), PickedItem(folder("A", "Album"), emptyList())),
            keepStructure = true,
        )
        assertEquals(1, r.files.count { it.fileId == "t1" })
        assertEquals(1, r.duplicatesRemoved)
        assertEquals(listOf("Album", "CD1"), r.files.single { it.fileId == "t1" }.dirSegments)
    }

    @Test fun shortcutToAFileAlreadyInSelectionIsDeduped() = runTest {
        drive.pages["A"] = listOf(listOf(folder("C1", "CD1"), file("cover", "cover.jpg"), shortcut("sc", "Shortcut to One", "t1", "audio/flac")))
        drive.metadata["t1"] = file("t1", "01 One.flac")
        val r = expander.expand(listOf(PickedItem(folder("A", "Album"), emptyList())), keepStructure = true)
        val one = r.files.single { it.fileId == "t1" }
        assertEquals("01 One.flac", one.name)          // the real file wins over the shortcut
        assertEquals(false, one.viaShortcut)
        assertEquals(1, r.duplicatesRemoved)
    }

    @Test fun shortcutToFolderIsExpandedViaTargetWithTargetKeyButRealPathWins() = runTest {
        drive.pages["ROOT"] = listOf(listOf(shortcut("scA", "Shortcut to Album", "A", DriveItem.MIME_FOLDER, rk = "0-kA"), folder("Sub")))
        drive.pages["Sub"] = listOf(listOf(folder("A", "Album", rk = "0-kA")))
        val r = expander.expand(listOf(PickedItem(rootItem, emptyList())), keepStructure = true)
        // A is reachable at depth 1 via shortcut and depth 2 for real: the real path wins and A is listed once.
        assertEquals(listOf("Music", "Sub", "Album", "CD1"), r.files.first { it.fileId == "t1" }.dirSegments)
        assertEquals(1, drive.calls.count { it.folderId == "A" })
    }

    @Test fun shortcutOnlyFolderUsesTargetResourceKey() = runTest {
        drive.pages["ROOT"] = listOf(listOf(shortcut("scA", "Shortcut to Album", "A", DriveItem.MIME_FOLDER, rk = "0-kA")))
        val r = expander.expand(listOf(PickedItem(rootItem, emptyList())), keepStructure = true)
        assertTrue(ScriptedDrive.Call("A", "0-kA", null) in drive.calls)
        assertEquals(listOf("Music", "Shortcut to Album", "CD1"), r.files.first { it.fileId == "t1" }.dirSegments)
        assertTrue(r.files.all { it.viaShortcut })
    }

    @Test fun shortcutCycleTerminates() = runTest {
        drive.pages["A"] = listOf(listOf(shortcut("back", "Back to root", "ROOT", DriveItem.MIME_FOLDER), file("x")))
        val r = expander.expand(listOf(PickedItem(rootItem, emptyList())), keepStructure = true)
        assertEquals(1, drive.calls.count { it.folderId == "ROOT" && it.pageToken == null })
        assertTrue(r.files.any { it.fileId == "x" })
    }

    @Test fun duplicateAndUnsafeNamesBecomeUniqueSafeNames() = runTest {
        drive.pages["X"] = listOf(listOf(file("a1", "Song.flac"), file("a2", "Song.flac"), file("a3", "song.FLAC"), file("a4", "39/Smooth.flac")))
        val r = expander.expand(listOf(PickedItem(folder("X", "Green Day - 39/Smooth"), emptyList())), keepStructure = true)
        assertEquals(listOf("Song.flac", "Song (2).flac", "song (3).FLAC", "39_Smooth.flac"), r.files.map { it.name })
        assertTrue(r.files.all { it.dirSegments == listOf("Green Day - 39_Smooth") })
    }

    @Test fun flattenWhenKeepStructureOff() = runTest {
        drive.pages["C1"] = listOf(listOf(file("t1", "cover.jpg")))
        val r = expander.expand(listOf(PickedItem(folder("A", "Album"), emptyList())), keepStructure = false)
        assertTrue(r.files.all { it.dirSegments.isEmpty() })
        assertEquals(listOf("cover.jpg", "cover (2).jpg"), r.files.map { it.name })
    }

    @Test fun individuallyPickedFileKeepsItsParentPath() = runTest {
        val r = expander.expand(listOf(PickedItem(file("t2", "02 Two.flac"), listOf("Album", "CD1"))), keepStructure = true)
        assertEquals(listOf("Album", "CD1"), r.files.single().dirSegments)
        assertEquals(0, drive.calls.size)
    }

    @Test fun cannotDownloadIsSkippedWithReason() = runTest {
        drive.pages["A"] = listOf(listOf(file("ok"), file("locked").copy(canDownload = false)))
        val r = expander.expand(listOf(PickedItem(folder("A", "Album"), emptyList())), keepStructure = true)
        assertEquals(listOf("ok"), r.files.map { it.fileId })
        assertEquals(SkipReason.CANNOT_DOWNLOAD, r.skipped.single().reason)
    }

    @Test fun incompleteListingAbortsExpansion() = runTest {
        drive.incompletePages["C1"] = setOf(0)
        try { expander.expand(listOf(PickedItem(folder("A", "Album"), emptyList())), keepStructure = true); fail() } catch (_: ExpansionIncomplete) {}
    }
}
