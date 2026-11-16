package com.abhranil.stashd.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written Room migrations. Every schema change from v4 on must add one here, export its schema
 * (app/schemas/, checked in) and get a test in MigrationTest. Downloads in progress and history survive upgrades.
 */
object StashdMigrations {
    /** v5: md5 checkpoint per task (faster resume). Existing tasks simply re-hash once on their next resume. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `file_task` ADD COLUMN `md5State` TEXT")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `file_task` ADD COLUMN `verifiedBytes` INTEGER")
            db.execSQL("ALTER TABLE `file_task` ADD COLUMN `verifiedMd5` TEXT")
            db.execSQL("ALTER TABLE `download_item` ADD COLUMN `parentIds` TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_4_5, MIGRATION_5_6)
}
