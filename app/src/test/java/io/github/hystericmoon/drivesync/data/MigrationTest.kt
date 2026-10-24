package io.github.hystericmoon.drivesync.data

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each migration must produce exactly the schema Room exports (app/schemas/) and keep the data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate2To3KeepsPairsAndBaseline() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO folder_pairs (id, localTreeUri, localName, driveFolderId, driveFolderName, wifiOnly, chargingOnly, " +
                    "initialSyncDone, changesPageToken, lastFullScanAt, lastSyncAt, lastSummary, lastError, excludePatterns) " +
                    "VALUES (1, 'content://x', 'Phone', 'd1', 'Sync', 1, 0, 1, '42', 5, 6, 'Up to date', NULL, '*.tmp')",
            )
            db.execSQL(
                "INSERT INTO baseline (pairId, path, isDir, localDocId, localSize, localLastModified, driveFileId, driveParentId, driveMd5, driveModifiedTime) " +
                    "VALUES (1, 'a.txt', 0, 'doc', 3, 7, 'f1', 'd1', 'md5', 't')",
            )
        }
        helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT changesPageToken, excludePatterns, skippedItems FROM folder_pairs WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("42", c.getString(0))
                assertEquals("*.tmp", c.getString(1))
                assertEquals("", c.getString(2))
            }
            db.query("SELECT COUNT(*) FROM baseline").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
