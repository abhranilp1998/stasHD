package com.abhranil.stashd.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhranil.stashd.data.drive.DriveCache
import com.abhranil.stashd.data.drive.DriveItem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduces the M0 cache bug: "a parent-root entry disappeared after caching its nested folder independently".
 * M0 keyed drive_node by fileId but stored a single rootId/parentId on it and inserted with REPLACE, so
 * caching folder B as its own root re-parented (or deleted) B's row, and A's listing lost B.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveCacheIdentityTest {
    private lateinit var db: StashdDatabase
    private lateinit var cache: DriveCache

    private fun folder(id: String, rk: String? = null) = DriveItem(id, "Folder $id", DriveItem.MIME_FOLDER, null, null, rk, 0L, true)
    private fun file(id: String, size: Long = 100) = DriveItem(id, "$id.flac", "audio/flac", size, "md5$id", null, 0L, true)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StashdDatabase::class.java)
            .allowMainThreadQueries().build()
        cache = DriveCache(db.driveNodeDao())
    }

    @After fun tearDown() = db.close()

    @Test fun parentRootKeepsNestedFolderAfterNestedFolderIsCachedAsItsOwnRoot() = runTest {
        // Root A lists album folder B and a file.
        cache.storeListing(folder("A"), "A", null, listOf(folder("B", rk = "0-keyB"), file("a1")))
        // User pastes B's link separately: B is now a root and its children get cached (B itself included as the root node).
        cache.storeListing(folder("B", rk = "0-keyB"), "B", "0-keyB", listOf(file("b1"), file("b2")))

        assertEquals(listOf("B", "a1"), cache.cachedChildren("A")!!.map { it.id })
        assertEquals(listOf("b1", "b2"), cache.cachedChildren("B")!!.map { it.id })
        assertEquals("0-keyB", cache.cachedChildren("A")!!.first { it.id == "B" }.resourceKey)
    }

    @Test fun recachingParentDoesNotWipeNestedFolderCache() = runTest {
        cache.storeListing(folder("B"), "B", null, listOf(file("b1"), file("b2")))
        cache.storeListing(folder("A"), "A", null, listOf(folder("B"), file("a1")))
        // Re-list A (e.g. pull-to-refresh) with a changed set of children.
        cache.storeListing(folder("A"), "A", null, listOf(folder("B"), file("a2")))

        assertEquals(listOf("B", "a2"), cache.cachedChildren("A")!!.map { it.id })
        assertEquals(listOf("b1", "b2"), cache.cachedChildren("B")!!.map { it.id })
    }

    @Test fun nodeWithTwoParentsStaysListedUnderBoth() = runTest {
        cache.storeListing(null, "P1", null, listOf(file("shared")))
        cache.storeListing(null, "P2", null, listOf(file("shared", size = 200)))
        assertEquals(listOf("shared"), cache.cachedChildren("P1")!!.map { it.id })
        assertEquals(listOf("shared"), cache.cachedChildren("P2")!!.map { it.id })
        assertEquals(200L, cache.cachedChildren("P1")!!.single().size) // one identity, latest metadata
        assertEquals(setOf("P1", "P2"), db.driveNodeDao().parentsOf("shared").toSet())
    }

    @Test fun neverListedFolderIsACacheMissNotAnEmptyFolder() = runTest {
        cache.storeListing(null, "Empty", null, emptyList())
        assertEquals(emptyList<DriveItem>(), cache.cachedChildren("Empty"))
        assertEquals(null, cache.cachedChildren("Unknown"))
    }

    @Test fun pruneKeepsRootsAndListedNodes() = runTest {
        db.driveRootDao().upsert(DriveRootEntity("A", null, "A", 0, 0, 0, 0))
        cache.storeListing(folder("A"), "A", null, listOf(folder("B")))
        cache.storeListing(folder("A"), "A", null, emptyList()) // B no longer in A
        db.driveNodeDao().pruneOrphans()
        assertEquals(null, db.driveNodeDao().node("B"))
        assertNotNull(db.driveNodeDao().node("A"))
    }
}
