package io.github.hystericmoon.drivesync.sync.fakes

import androidx.room.Room
import io.github.hystericmoon.drivesync.data.AppDatabase
import io.github.hystericmoon.drivesync.data.BaselineEntry
import io.github.hystericmoon.drivesync.data.FolderPair
import io.github.hystericmoon.drivesync.data.SyncLogEntry
import io.github.hystericmoon.drivesync.sync.SyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var now: Instant = Instant.parse("2026-10-23T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now += d }
}

/**
 * One folder pair wired to a real [SyncEngine], an in-memory Room database, and fake phone and
 * Drive sides. Call [close] when done.
 */
class SyncHarness(excludes: String = "") {
    val context = RuntimeEnvironment.getApplication()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val clock = MutableClock()
    val drive = FakeDrive()
    val phone = FakeLocalFs()
    val engine = SyncEngine(context, db, newDrive = { drive }, openLocal = { phone }, clock = clock)
    val pairId: Long = runBlocking {
        db.pairs().insert(
            FolderPair(
                localTreeUri = "content://test/tree/root",
                localName = "Phone",
                driveFolderId = drive.pairRootId,
                driveFolderName = "Sync",
                excludePatterns = excludes,
            ),
        )
    }

    /** One sync run, a few minutes after the previous one. */
    fun sync() {
        clock.advance(Duration.ofMinutes(15))
        runBlocking { engine.sync(pairId) }
    }

    /** A run that must read Drive through the Changes API. */
    fun syncViaChanges() {
        val fullScans = drive.startPageTokenCalls
        val changes = drive.listChangesCalls
        sync()
        assertEquals("expected the Changes API to be used", changes + 1, drive.listChangesCalls)
        assertEquals("expected no full scan", fullScans, drive.startPageTokenCalls)
    }

    /** A run that must list the whole Drive folder. */
    fun syncViaFullScan() {
        val fullScans = drive.startPageTokenCalls
        sync()
        assertEquals("expected a full scan", fullScans + 1, drive.startPageTokenCalls)
    }

    fun pair(): FolderPair = runBlocking { db.pairs().get(pairId)!! }

    fun updatePair(change: (FolderPair) -> FolderPair) = runBlocking { db.pairs().update(change(pair())) }

    fun baseline(): Map<String, BaselineEntry> = runBlocking { db.baseline().forPair(pairId).associateBy { it.path } }

    /** History entries for the pair, oldest first. */
    fun log(): List<SyncLogEntry> = runBlocking { db.log().observeAll().first().filter { it.pairId == pairId }.reversed() }

    /**
     * Both sides hold the same files and folders, and the baseline describes exactly that state:
     * one row per path, pointing at the right Drive id and phone document, with matching stats.
     */
    fun assertInSync() {
        assertEquals("phone and Drive files differ", phone.files(), drive.files())
        assertEquals("phone and Drive folders differ", phone.folders(), drive.folders())
        val base = baseline()
        assertEquals("baseline paths", (phone.files().keys + phone.folders()).toSortedSet(), base.keys.toSortedSet())
        for ((path, row) in base) {
            val local = phone.find(path)!!
            assertEquals("$path: local doc id", local.id, row.localDocId)
            assertEquals("$path: Drive id", drive.id(path), row.driveFileId)
            if (!row.isDir) {
                assertEquals("$path: local size", local.content.size.toLong(), row.localSize)
                assertEquals("$path: local mtime", local.lastModified, row.localLastModified)
                assertEquals("$path: Drive md5", FakeDrive.md5(drive.item(row.driveFileId!!).content), row.driveMd5)
            }
        }
        val pair = pair()
        assertEquals("lastError", null, pair.lastError)
    }

    /** The engine never deleted anything that held a file, outside the 30-day purge of .sync-trash. */
    fun assertNoPermanentDeletes() {
        val bad = phone.deletes.filter { it.fileCount > 0 && !it.path.startsWith(".sync-trash/") }
        assertTrue("permanent deletes on the phone: $bad", bad.isEmpty())
    }

    fun close() = db.close()
}
