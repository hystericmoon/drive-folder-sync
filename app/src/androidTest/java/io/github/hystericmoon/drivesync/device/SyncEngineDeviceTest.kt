package io.github.hystericmoon.drivesync.device

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.local.LocalFs
import io.github.hystericmoon.drivesync.sync.SyncEngine
import io.github.hystericmoon.drivesync.sync.fakes.FakeDrive
import io.github.hystericmoon.drivesync.sync.fakes.MutableClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * The real engine writing to real phone storage (SAF + ExternalStorageProvider) and a real Room
 * database, with only Drive faked. Catches what the JVM fakes can't: provider naming rules,
 * timestamps, truncation, moves into .sync-trash.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineDeviceTest {
    @get:Rule val folder = SharedStorageFolder()

    private val db = Room.inMemoryDatabaseBuilder(folder.context, AppDatabase::class.java).build()
    private val drive = FakeDrive()
    private val clock = MutableClock()
    private val engine = SyncEngine(
        folder.context, db,
        newDrive = { drive },
        openLocal = { folder.fs },
        clock = clock,
    )
    private val pairId by lazy {
        runBlocking {
            db.pairs().insert(
                FolderPair(
                    localTreeUri = folder.treeUri.toString(), localName = "Test",
                    driveFolderId = drive.pairRootId, driveFolderName = "Sync",
                ),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun sync() {
        clock.advance(Duration.ofMinutes(15))
        runBlocking { engine.sync(pairId) }
        assertNull("lastError", runBlocking { db.pairs().get(pairId)!!.lastError })
    }

    private fun phoneFiles() = folder.allFiles().filterKeys { !it.startsWith(LocalFs.TRASH_DIR + "/") }

    private fun trashFiles() = folder.allFiles()
        .filterKeys { it.startsWith(LocalFs.TRASH_DIR + "/") && !it.endsWith(".nomedia") }
        .mapKeys { it.key.split('/', limit = 3)[2] }

    private fun assertSameOnBothSides() = assertEquals(drive.files(), phoneFiles())

    @Test fun fullLifecycleOnRealStorage() {
        folder.write("phone.txt", "from phone")
        folder.write("docs/nested/deep.md", "deep")
        drive.write("drive.txt", "from drive")
        drive.write("photos/2026/IMG_0001.JPG", "jpeg bytes")
        sync()
        assertSameOnBothSides()
        assertEquals(4, phoneFiles().size)

        // Edits both ways, including a shorter rewrite (truncation on real storage).
        folder.write("phone.txt", "v2")
        drive.write("drive.txt", "drive v2, longer")
        sync()
        assertSameOnBothSides()
        assertEquals("drive v2, longer", folder.read("drive.txt"))
        assertEquals("v2", drive.files()["phone.txt"])

        // Conflict keeps both, with the conflict name on real storage.
        folder.write("docs/nested/deep.md", "phone edit")
        drive.write("docs/nested/deep.md", "drive edit")
        sync()
        assertSameOnBothSides()
        assertEquals("phone edit", folder.read("docs/nested/deep.md"))
        assertEquals("drive edit", folder.read("docs/nested/deep (conflict 2026-10-23).md"))

        // Deletes go to the trash on each side.
        drive.trashPath("photos/2026/IMG_0001.JPG")
        folder.remove("phone.txt")
        sync()
        assertSameOnBothSides()
        assertEquals(mapOf("photos/2026/IMG_0001.JPG" to "jpeg bytes"), trashFiles())
        assertTrue("phone.txt" in drive.trashedNames())

        // Settles.
        val before = folder.allFiles()
        sync()
        sync()
        assertEquals(before, folder.allFiles())
    }

    @Test fun driveNamesThePhoneCantHoldAreSkippedNotFailed() {
        drive.write("Meeting 10:30.txt", "m")
        drive.write("What?.pdf", "w")
        drive.write("no-ext.", "n")
        drive.write("fine.txt", "f")
        clock.advance(Duration.ofMinutes(15))
        runBlocking { engine.sync(pairId) }
        val pair = runBlocking { db.pairs().get(pairId)!! }
        assertTrue(pair.lastError, pair.lastError!!.startsWith("3 skipped"))
        assertTrue("first sync completes", pair.initialSyncDone)
        assertEquals(mapOf("fine.txt" to "f"), phoneFiles())
    }

    @Test fun driveFolderRenameOnRealStorage() {
        drive.write("Projects/a.txt", "A")
        drive.write("Projects/sub/b.txt", "B")
        sync()
        drive.renamePath("Projects", "Work")
        sync()
        assertSameOnBothSides()
        assertEquals("B", folder.read("Work/sub/b.txt"))
        assertNull(folder.docId("Projects/a.txt"))
        assertEquals(setOf("Projects/a.txt", "Projects/sub/b.txt"), trashFiles().keys)
    }
}
