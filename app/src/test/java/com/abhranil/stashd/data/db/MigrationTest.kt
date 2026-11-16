package com.abhranil.stashd.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades keep download state: a paused v4 batch must still be resumable after the update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), StashdDatabase::class.java)

    @Test fun v4ToV5KeepsBatchesTasksAndCache() = runTest {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL("INSERT INTO drive_root (folderId, resourceKey, name, totalBytes, folderCount, fileCount, lastOpenedAt) VALUES ('R', NULL, 'Music', 10, 1, 2, 5)")
            db.execSQL(
                "INSERT INTO download_batch (id, rootFolderId, rootName, destTreeUri, keepStructure, wifiOnly, skipExisting, state, pauseReason, message, totalBytes, totalFiles, skippedFiles, duplicatesRemoved, createdAt, startedAt, finishedAt) " +
                    "VALUES (7, 'R', 'Music', 'content://tree', 1, 0, 0, 'PAUSED', 'USER', NULL, 1000, 1, 0, 0, 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO file_task (id, batchId, sourceKey, fileId, resourceKey, dirSegments, plannedName, finalName, tempName, size, md5, modifiedTime, viaShortcut, bytesDone, state, attempts, errorReason, errorMessage, finalUri, startedAt, finishedAt) " +
                    "VALUES (1, 7, 'R', 'f1', NULL, '[\"Album\"]', 'a.flac', 'a.flac', 'a.flac.stashdpart', 1000, 'abc', 3, 0, 400, 'QUEUED', 1, NULL, NULL, NULL, 2, NULL)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 5, true, *StashdMigrations.ALL).close()

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), StashdDatabase::class.java, dbName)
            .addMigrations(*StashdMigrations.ALL).allowMainThreadQueries().build()
        try {
            val b = room.downloadDao().batchOnce(7)!!
            assertEquals(BatchState.PAUSED, b.state)
            assertEquals(PauseReason.USER, b.pauseReason)
            val t = room.downloadDao().task(1)!!
            assertEquals(400L, t.bytesDone)
            assertEquals("a.flac.stashdpart", t.tempName)
            assertEquals(listOf("Album"), t.dirSegments)
            assertNull(t.md5State) // new column, filled on the next checkpoint
            assertEquals(1, room.driveRootDao().recent().first().size)
        } finally { room.close() }
    }

    @Test fun everyVersionFromFourHasAMigrationPath() {
        val covered = StashdMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        val current = 5
        for (v in 4 until current) assertEquals("missing migration $v -> ${v + 1}", true, (v to v + 1) in covered)
    }
}
